#include "NativeBmwDspProcessor.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include "NativeBmwDspMath.h"

namespace {
using NativeBmwDsp::clampf;
using NativeBmwDsp::changed;
using NativeBmwDsp::dbToLin;
using NativeBmwDsp::measurementMuteIsolates;
using OutputId = NativeBmwRouting::OutputId;
}  // namespace

bool NativeBmwDspProcessor::configure(const float* v, std::size_t n) {
    if (!v || n != kConfigSize) {
        return false;
    }
    std::lock_guard<std::mutex> lock(stateMutex_);
    Params next = p_;
    // clampf() maps NaN to its upper bound (std::min(hi, NaN) == hi), so a non-finite scalar
    // would silently land on max gain/delay/etc. Every clamped slot goes through clampIn(), and
    // the whole update is rejected below (before any member state is touched) if one wasn't
    // finite -- same policy as the routing/all-pass/MBC-crossover checks.
    bool allFinite = true;
    auto clampIn = [&allFinite](float x, float lo, float hi) {
        allFinite = allFinite && std::isfinite(x);
        return clampf(x, lo, hi);
    };
    next.enabled = v[0] >= .5f;
    next.lpfPass = v[1] >= .5f;
    next.hpfPass = v[2] >= .5f;
    next.channelMute = static_cast<int>(clampIn(v[3], 0, 2));
    // 0 off, 1 isolate Mid, 2 isolate Low, 3 isolate High -- see MeasurementBus::rebuild().
    next.measurementMute = static_cast<int>(clampIn(v[4], 0, 3));
    next.headroom = clampIn(v[5], -12, 0);
    next.lowGainL = clampIn(v[6], -6, 6);
    next.lowGainR = clampIn(v[7], -6, 6);
    next.midGainL = clampIn(v[8], -6, 6);
    next.midGainR = clampIn(v[9], -6, 6);
    next.postGainL = clampIn(v[10], -6, 6);
    next.postGainR = clampIn(v[11], -6, 6);
    next.midDelayL = clampIn(v[21], 0, 2.8f);
    next.midDelayR = clampIn(v[22], 0, 2.8f);
    next.lowDelayL = clampIn(v[23], 0, 2.8f);
    next.lowDelayR = clampIn(v[24], 0, 2.8f);
    // v[141] / v[142] -- reclaimed from the removed Mid-band LPF -- are the stage-centering L/R
    // alignment delay (ms). See Params::stageDelay* and processFrame's tail.
    next.stageDelayL = clampIn(v[141], 0, kStageDelayMaxMs);
    next.stageDelayR = clampIn(v[142], 0, kStageDelayMaxMs);
    next.tilt = v[25] >= .5f;
    next.tiltAmount = clampIn(v[26], -6, 6);
    next.tiltFreq = clampIn(v[27], 200, 2000);
    // v[139]: measurement-mute bus brick-wall stopband offset in octaves (see MeasurementBus::rebuild()).
    // v[140] is a Kotlin-only migration marker; v[141..142] (formerly the removed Pultec bass
    // stage) now carry the Mid-band independent LPF -- read further down with the per-output
    // config. See NativeBmwDspProcessor.h's kConfigSize comment.
    next.measBusStopbandOctaves = clampIn(v[139], 0, 4);

    // v[192..199]: measurement signal generator. type: 0 off, 1 sweep, 2 pink periodic noise.
    next.measGenType = static_cast<int>(clampIn(v[192], 0, 2));
    next.measGenSweepStartHz = clampIn(v[193], 10, 24000);
    next.measGenSweepEndHz = clampIn(v[194], 10, 24000);
    next.measGenSweepDurationS = clampIn(v[195], 0.5f, 60);
    next.measGenSweepLevelDb = clampIn(v[196], -60, 0);
    next.measGenPinkPeriodS = clampIn(v[197], 0.1f, 10);
    next.measGenPinkLevelDb = clampIn(v[198], -60, 0);
    next.measGenTimingRefEnabled = v[199] >= .5f;
    next.measGenTimingRefSplitChannels = v[200] >= .5f;
    next.measGenTimingRefMidStartHz = clampIn(v[201], 10, 24000);
    next.measGenTimingRefMidEndHz = clampIn(v[202], 10, 24000);
    next.measGenTimingRefLowStartHz = clampIn(v[203], 10, 24000);
    next.measGenTimingRefLowEndHz = clampIn(v[204], 10, 24000);

    // High band scalars (v[210..214], added in the 210 -> 262 growth). Routing/all-pass/
    // output-config for High are read further down, alongside the equivalent Low/Mid blocks.
    next.highXoPass = v[210] >= .5f;
    next.highGainL = clampIn(v[211], -6, 6);
    next.highGainR = clampIn(v[212], -6, 6);
    next.highDelayL = clampIn(v[213], 0, 2.8f);
    next.highDelayR = clampIn(v[214], 0, 2.8f);

    // Pre-crossover multiband compressor (v[144..180]) + per-bus limiter (v[182..187]). v[181]
    // is the Kotlin-only migration marker and v[188..192) are reserved -- none are read here.
    next.mbcEnabled = v[144] >= .5f;
    next.mbcMix = clampIn(v[145], 0, 100) * .01f;
    // Out-of-order input (e.g. v[147] < v[146]) previously reached MultibandCompressor::rebuild() as-is, where its
    // sequential spacing-clamp chain silently pushed the later value up to fit rather than the
    // update being rejected or the values being reordered predictably -- the resulting split
    // frequencies depended on which slot the clamp chain happened to touch, not on the values the
    // caller actually sent. Sorting the raw magnitudes first (before the per-slot clamp below)
    // means the same three magnitudes always normalize the same way regardless of which slot the
    // caller put them in. std::sort requires a strict weak ordering that NaN violates, so reject
    // a non-finite triple up front rather than letting it reach the sort.
    if (!std::isfinite(v[146]) || !std::isfinite(v[147]) || !std::isfinite(v[148])) {
        return false;
    }
    float mbcXoSorted[3] = {v[146], v[147], v[148]};
    std::sort(std::begin(mbcXoSorted), std::end(mbcXoSorted));
    next.mbcXo[0] = clampf(mbcXoSorted[0], 20, 2000);
    next.mbcXo[1] = clampf(mbcXoSorted[1], 40, 8000);
    next.mbcXo[2] = clampf(mbcXoSorted[2], 80, 20000);
    for (int b = 0; b < 4; ++b) {
        const std::size_t base = 149 + b * 8;
        auto& mb = next.mbcBand[b];
        mb.enabled = v[base] >= .5f;
        mb.threshold = clampIn(v[base + 1], -48, 0);
        mb.ratio = clampIn(v[base + 2], 1, 20);
        mb.knee = clampIn(v[base + 3], 0, 24);
        mb.attack = clampIn(v[base + 4], 1, 200);
        mb.release = clampIn(v[base + 5], 20, 1000);
        mb.makeup = clampIn(v[base + 6], 0, 12);
        mb.stereoLink = v[base + 7] >= .5f;
    }
    next.busLimLowEnabled = v[182] >= .5f;
    next.busLimLowThreshDb = clampIn(v[183], -24, 0);
    next.busLimLowReleaseMs = clampIn(v[184], 20, 800);
    next.busLimMidEnabled = v[185] >= .5f;
    next.busLimMidThreshDb = clampIn(v[186], -24, 0);
    next.busLimMidReleaseMs = clampIn(v[187], 20, 800);
    // High-bus limiter (v[262..264], added in the 262 -> 266 growth); v[265] is the
    // Kotlin-only migration marker.
    next.busLimHighEnabled = v[262] >= .5f;
    next.busLimHighThreshDb = clampIn(v[263], -24, 0);
    next.busLimHighReleaseMs = clampIn(v[264], 20, 800);
    // v[189/190]: master limiter enable + threshold dBFS (v[191] is a Kotlin-only migration
    // marker). Slots reclaimed from the 188..191 "reserved" run -- SIZE stays 192.
    next.limiterEnabled = v[189] >= .5f;
    next.limiterThreshDb = clampIn(v[190], -12, 0);

    // These four loops (routing, all-pass, output-config x2) are deliberately scoped to
    // kLegacyOutputCount (4), not the in-memory kOutputCount (6): High has no persisted schema
    // slots yet, so reading v[] at the offsets a 6-output loop would visit for outputs 4/5 would
    // read into the *next* block (all-pass would misread output-config bytes as its own, etc).
    // High's runtime entries (outputs_[4]/[5], outputConfigs_[4]/[5]) are left at their
    // constructor defaults -- muted, identity routing -- untouched by configure() until a later
    // phase adds its own tail block. See docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
    NativeBmwRouting::RoutingMatrix nextRouting;
    for (std::size_t out = 0; out < NativeBmwRouting::kLegacyOutputCount; ++out) {
        const float fromLeft = v[kRoutingBase + out * 2], fromRight = v[kRoutingBase + out * 2 + 1];
        if (!std::isfinite(fromLeft) || !std::isfinite(fromRight) || std::fabs(fromLeft) > 2.f ||
            std::fabs(fromRight) > 2.f) {
            return false;
        }
        nextRouting.outputs[out] = {fromLeft, fromRight};
    }

    auto nextOutputs = outputs_;
    for (std::size_t out = 0; out < NativeBmwRouting::kLegacyOutputCount; ++out) {
        for (std::size_t section = 0; section < NativeBmwRouting::kAllPassSectionsPerOutput;
             ++section) {
            const std::size_t base =
                kAllPassBase +
                (out * NativeBmwRouting::kAllPassSectionsPerOutput + section) * kAllPassValueWidth;
            const float enabledValue = v[base], orderValue = v[base + 1], freqValue = v[base + 2],
                        qValue = v[base + 3];
            if (!std::isfinite(enabledValue) || !std::isfinite(orderValue) ||
                !std::isfinite(freqValue) || !std::isfinite(qValue)) {
                return false;
            }
            NativeBmwRouting::AllPassSection candidate;
            candidate.enabled = enabledValue >= .5f;
            candidate.secondOrder = orderValue >= 1.5f;
            candidate.frequencyHz = freqValue;
            candidate.q = qValue;
            // Do not reject the whole 139-value config over one out-of-range all-pass
            // section (eg. a frequency at/above this output's current Nyquist limit).
            // rebuild() already degrades an invalid section to an identity
            // pass-through on its own and leaves every other pending change intact.
            (void)candidate.rebuild(sampleRate_);
            const auto& current = outputs_[out].allPass[section];
            const bool sectionChanged = current.enabled != candidate.enabled ||
                                        current.secondOrder != candidate.secondOrder ||
                                        changed(current.frequencyHz, candidate.frequencyHz) ||
                                        changed(current.q, candidate.q);
            nextOutputs[out].allPass[section] = candidate;
            // Keep the delay history when an unrelated setting is configured. Clearing an
            // active all-pass section here creates a phase discontinuity even though its
            // coefficients did not change.
            if (sectionChanged) {
                nextOutputs[out].allPassState[section].loadAllPass(candidate.coefficients);
            }
        }
    }

    auto nextOutputConfigs = outputConfigs_;
    auto readComp = [&](CompressorParams& c, std::size_t base) {
        c.enabled = v[base] >= .5f;
        c.threshold = clampIn(v[base + 1], -24, 0);
        c.ratio = clampIn(v[base + 2], 1, 10);
        c.knee = clampIn(v[base + 3], 0, 12);
        c.attack = clampIn(v[base + 4], 1, 100);
        c.release = clampIn(v[base + 5], 20, 800);
        c.makeup = clampIn(v[base + 6], 0, 6);
    };
    for (std::size_t out = 0; out < NativeBmwRouting::kLegacyOutputCount; ++out) {
        const std::size_t base = kOutputConfigBase + out * kOutputConfigWidth;
        auto& cfg = nextOutputConfigs[out];
        cfg.crossoverFreq = clampIn(v[base], 80, 320);
        // v[base+1]: 0 = BW2, 1 = BW3, 2 = LR4, 3 = BW1, 4 = BW4. Keep the old threshold
        // decoding for existing saves; only the explicit new IDs select first-order / BW4.
        const float typeVal = v[base + 1];
        allFinite = allFinite && std::isfinite(typeVal);  // NaN would silently decode as LR4
        cfg.crossoverType = typeVal == 3.f ? OutputConfig::CrossoverType::Butterworth1
                            : typeVal == 4.f ? OutputConfig::CrossoverType::Butterworth4
                            : typeVal < .5f ? OutputConfig::CrossoverType::Butterworth2
                            : typeVal < 1.5f ? OutputConfig::CrossoverType::Butterworth3
                                             : OutputConfig::CrossoverType::LinkwitzRiley4;
        cfg.subsonicEnabled = v[base + 2] >= .5f;
        cfg.subsonicFreq = clampIn(v[base + 3], 20, 60);
        cfg.muted = v[base + 4] >= .5f;
        cfg.polarityInverted = v[base + 5] >= .5f;
        readComp(cfg.compressor, base + 6);
    }

    // Mid's optional upper (Mid/High) bandpass corner -- tail block v[205..208], added in the
    // 205 -> 210 growth (see docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md). Read directly into
    // Mid Left/Right rather than folding into the per-output loop above, since this field only
    // exists for Mid (Low/High don't have it) and lives in the schema's tail, not the per-output
    // block. Disabled (the DEFAULTS/migrated state) leaves rebuildMidCrossover() building only
    // the existing HPF pair, byte-identical to before this growth.
    {
        auto& midLeftCfg = nextOutputConfigs[static_cast<std::size_t>(OutputId::MidLeft)];
        auto& midRightCfg = nextOutputConfigs[static_cast<std::size_t>(OutputId::MidRight)];
        const float midLeftFreq = v[205], midLeftEnabled = v[206];
        const float midRightFreq = v[207], midRightEnabled = v[208];
        if (!std::isfinite(midLeftFreq) || !std::isfinite(midLeftEnabled) ||
            !std::isfinite(midRightFreq) || !std::isfinite(midRightEnabled)) {
            return false;
        }
        midLeftCfg.upperCrossoverFreq = clampf(midLeftFreq, 300, 8000);
        midLeftCfg.upperCrossoverEnabled = midLeftEnabled >= .5f;
        midRightCfg.upperCrossoverFreq = clampf(midRightFreq, 300, 8000);
        midRightCfg.upperCrossoverEnabled = midRightEnabled >= .5f;
    }

    // High band: routing/all-pass/output-config, v[215..260] (added in the 210 -> 262 growth).
    // A genuinely new third output -- unlike Mid's tail block above, this populates real
    // nextRouting/nextOutputs/nextOutputConfigs entries for OutputId::HighLeft/HighRight, the
    // same shape as the Low/Mid loops earlier in this function, just at the schema's tail
    // instead of the legacy per-output block (kOutputConfigBase etc are frozen at their
    // kLegacyOutputCount-derived offsets -- see NativeBmwDspProcessor.h's kRoutingBase comment).
    for (std::size_t slot = 0; slot < 2; ++slot) {
        const std::size_t out = static_cast<std::size_t>(OutputId::HighLeft) + slot;
        const std::size_t routingBase = 215 + slot * 2;
        const float fromLeft = v[routingBase], fromRight = v[routingBase + 1];
        if (!std::isfinite(fromLeft) || !std::isfinite(fromRight) || std::fabs(fromLeft) > 2.f ||
            std::fabs(fromRight) > 2.f) {
            return false;
        }
        nextRouting.outputs[out] = {fromLeft, fromRight};

        for (std::size_t section = 0; section < NativeBmwRouting::kAllPassSectionsPerOutput;
             ++section) {
            const std::size_t base =
                219 + (slot * NativeBmwRouting::kAllPassSectionsPerOutput + section) * kAllPassValueWidth;
            const float enabledValue = v[base], orderValue = v[base + 1], freqValue = v[base + 2],
                        qValue = v[base + 3];
            if (!std::isfinite(enabledValue) || !std::isfinite(orderValue) ||
                !std::isfinite(freqValue) || !std::isfinite(qValue)) {
                return false;
            }
            NativeBmwRouting::AllPassSection candidate;
            candidate.enabled = enabledValue >= .5f;
            candidate.secondOrder = orderValue >= 1.5f;
            candidate.frequencyHz = freqValue;
            candidate.q = qValue;
            (void)candidate.rebuild(sampleRate_);
            const auto& current = outputs_[out].allPass[section];
            const bool sectionChanged = current.enabled != candidate.enabled ||
                                        current.secondOrder != candidate.secondOrder ||
                                        changed(current.frequencyHz, candidate.frequencyHz) ||
                                        changed(current.q, candidate.q);
            nextOutputs[out].allPass[section] = candidate;
            if (sectionChanged) {
                nextOutputs[out].allPassState[section].loadAllPass(candidate.coefficients);
            }
        }

        const std::size_t cfgBase = 235 + slot * kOutputConfigWidth;
        auto& cfg = nextOutputConfigs[out];
        // Same 300-8000 Hz range as Mid's upper corner (v[205]/v[207]): High's single corner is
        // the same Mid/High boundary, just approached from above instead of below.
        cfg.crossoverFreq = clampIn(v[cfgBase], 300, 8000);
        const float typeVal = v[cfgBase + 1];
        allFinite = allFinite && std::isfinite(typeVal);
        cfg.crossoverType = typeVal == 3.f ? OutputConfig::CrossoverType::Butterworth1
                            : typeVal == 4.f ? OutputConfig::CrossoverType::Butterworth4
                            : typeVal < .5f ? OutputConfig::CrossoverType::Butterworth2
                            : typeVal < 1.5f ? OutputConfig::CrossoverType::Butterworth3
                                             : OutputConfig::CrossoverType::LinkwitzRiley4;
        cfg.subsonicEnabled = v[cfgBase + 2] >= .5f;  // carried but ignored, same as Mid
        cfg.subsonicFreq = clampIn(v[cfgBase + 3], 20, 60);
        cfg.muted = v[cfgBase + 4] >= .5f;
        cfg.polarityInverted = v[cfgBase + 5] >= .5f;
        readComp(cfg.compressor, cfgBase + 6);
    }

    if (!allFinite) {
        return false;
    }

    uint32_t dirty = DirtyNone;
    if (changed(next.headroom, p_.headroom) || changed(next.lowGainL, p_.lowGainL) ||
        changed(next.lowGainR, p_.lowGainR) || changed(next.midGainL, p_.midGainL) ||
        changed(next.midGainR, p_.midGainR) || changed(next.highGainL, p_.highGainL) ||
        changed(next.highGainR, p_.highGainR) || changed(next.postGainL, p_.postGainL) ||
        changed(next.postGainR, p_.postGainR)) {
        dirty |= DirtyGains;
    }
    if (changed(next.lowDelayL, p_.lowDelayL) || changed(next.lowDelayR, p_.lowDelayR) ||
        changed(next.midDelayL, p_.midDelayL) || changed(next.midDelayR, p_.midDelayR) ||
        changed(next.highDelayL, p_.highDelayL) || changed(next.highDelayR, p_.highDelayR) ||
        changed(next.stageDelayL, p_.stageDelayL) || changed(next.stageDelayR, p_.stageDelayR)) {
        dirty |= DirtyDelays;
    }
    if (changed(next.tiltAmount, p_.tiltAmount) || changed(next.tiltFreq, p_.tiltFreq)) {
        dirty |= DirtyTilt;
    }

    for (std::size_t out = 0; out < NativeBmwRouting::kOutputCount; ++out) {
        const auto& old = outputConfigs_[out];
        const auto& now = nextOutputConfigs[out];
        const auto band = NativeBmwRouting::band(out);
        const bool low = band == NativeBmwRouting::Band::Low;
        if (changed(old.crossoverFreq, now.crossoverFreq) || old.crossoverType != now.crossoverType) {
            dirty |= band == NativeBmwRouting::Band::Low  ? DirtyLowXo
                    : band == NativeBmwRouting::Band::Mid ? DirtyMidXo
                                                          : DirtyHighXo;
            // The meas-bus corner tracks the isolated band's own crossover: rebuild it only
            // if the crossover that just moved belongs to the band the active mode keeps.
            if (measurementMuteIsolates(next.measurementMute, band)) {
                dirty |= DirtyMeasBus;
            }
        }
        // Mid's upper corner (v[205..208], read above) only exists for Mid; explicitly Mid-only
        // now that band() gives a real three-way classifier (was "!low", back when High wasn't
        // in this loop yet and that was equivalent to Mid-only by construction).
        if (band == NativeBmwRouting::Band::Mid &&
            (changed(old.upperCrossoverFreq, now.upperCrossoverFreq) ||
             old.upperCrossoverEnabled != now.upperCrossoverEnabled)) {
            dirty |= DirtyMidXo;
            // Isolate-Mid's upper bus LPF follows Mid's upper corner (and its enable).
            if (next.measurementMute == 1) {
                dirty |= DirtyMeasBus;
            }
        }
        if (low && (old.subsonicEnabled != now.subsonicEnabled ||
                    changed(old.subsonicFreq, now.subsonicFreq))) {
            dirty |= DirtySubsonic;
        }
        if (old.muted != now.muted || old.polarityInverted != now.polarityInverted) {
            dirty |= DirtyPolarity;
        }
        if (changed(old.compressor.attack, now.compressor.attack) ||
            changed(old.compressor.release, now.compressor.release)) {
            dirty |= DirtyCompTiming;
        }
        if (old.compressor.enabled != now.compressor.enabled) {
            dirty |= DirtyCompState;
        }
        if (changed(old.compressor.makeup, now.compressor.makeup)) {
            dirty |= DirtyGains;
        }
    }
    // High's filters, all-pass, delay and PEQ don't run while highXoPass silences it, so they
    // still hold whatever was playing when 3-way was switched off. Clear them on the way back
    // on, or that stale audio replays as a burst for the first few milliseconds.
    if (p_.highXoPass && !next.highXoPass) {
        dirty |= DirtyHighResume;
    }
    if (next.measurementMute != p_.measurementMute) {
        dirty |= DirtyPolarity | DirtyMeasBus;
    }
    if (changed(next.measBusStopbandOctaves, p_.measBusStopbandOctaves)) {
        dirty |= DirtyMeasBus;
    }
    // Any generator param change restarts the run, not just a type flip -- editing the sweep
    // range/duration/level while it's already playing is expected to retrigger it.
    if (next.measGenType != p_.measGenType ||
        changed(next.measGenSweepStartHz, p_.measGenSweepStartHz) ||
        changed(next.measGenSweepEndHz, p_.measGenSweepEndHz) ||
        changed(next.measGenSweepDurationS, p_.measGenSweepDurationS) ||
        changed(next.measGenSweepLevelDb, p_.measGenSweepLevelDb) ||
        changed(next.measGenPinkPeriodS, p_.measGenPinkPeriodS) ||
        changed(next.measGenPinkLevelDb, p_.measGenPinkLevelDb) ||
        next.measGenTimingRefEnabled != p_.measGenTimingRefEnabled ||
        next.measGenTimingRefSplitChannels != p_.measGenTimingRefSplitChannels ||
        changed(next.measGenTimingRefMidStartHz, p_.measGenTimingRefMidStartHz) ||
        changed(next.measGenTimingRefMidEndHz, p_.measGenTimingRefMidEndHz) ||
        changed(next.measGenTimingRefLowStartHz, p_.measGenTimingRefLowStartHz) ||
        changed(next.measGenTimingRefLowEndHz, p_.measGenTimingRefLowEndHz)) {
        dirty |= DirtyMeasGen;
    }

    // MBC: split-freq changes -> DirtyMbc (rebuild filter coeffs, clears tree state, same cost as
    // the band crossovers). mix/makeup/attack/release -> DirtyMbcTiming (scalars only, no click).
    // threshold/ratio/knee are read live in MultibandCompressor::bandGain() and need no rebuild. enable or
    // stereo-link flips -> DirtyMbcState (reset cells + tree so a re-enabled band starts clean
    // and stale unlinked-[1] state can't leak in).
    if (changed(next.mbcMix, p_.mbcMix)) {
        dirty |= DirtyMbcTiming;
    }
    for (int i = 0; i < 3; ++i) {
        if (changed(next.mbcXo[i], p_.mbcXo[i])) {
            dirty |= DirtyMbc;
        }
    }
    if (next.mbcEnabled != p_.mbcEnabled) {
        dirty |= DirtyMbcState;
    }
    for (int b = 0; b < 4; ++b) {
        const auto& cur = p_.mbcBand[b];
        const auto& nxt = next.mbcBand[b];
        if (changed(cur.attack, nxt.attack) || changed(cur.release, nxt.release) ||
            changed(cur.makeup, nxt.makeup)) {
            dirty |= DirtyMbcTiming;
        }
        if (cur.enabled != nxt.enabled || cur.stereoLink != nxt.stereoLink) {
            dirty |= DirtyMbcState;
        }
    }
    // Bus limiter: threshold is read live per sample, so only enable/release need a rebuild.
    if (next.busLimLowEnabled != p_.busLimLowEnabled ||
        next.busLimMidEnabled != p_.busLimMidEnabled ||
        next.busLimHighEnabled != p_.busLimHighEnabled ||
        changed(next.busLimLowReleaseMs, p_.busLimLowReleaseMs) ||
        changed(next.busLimMidReleaseMs, p_.busLimMidReleaseMs) ||
        changed(next.busLimHighReleaseMs, p_.busLimHighReleaseMs)) {
        dirty |= DirtyBusLimiter;
    }
    // Master limiter: enable is checked live in processFrame; only the threshold -> ceiling
    // scalar needs a (cheap, state-free) recompute.
    if (changed(next.limiterThreshDb, p_.limiterThreshDb)) {
        dirty |= DirtyLimiter;
    }

    p_ = next;
    routing_ = nextRouting;
    outputs_ = nextOutputs;
    outputConfigs_ = nextOutputConfigs;
    // See mbcEnabledMeterFlag_ etc's declaration: the read*Meter() functions are lock-free and
    // read these atomics instead of the plain bools inside p_ to avoid racing this assignment.
    mbcEnabledMeterFlag_.store(p_.mbcEnabled, std::memory_order_relaxed);
    busLimLowEnabledMeterFlag_.store(p_.busLimLowEnabled, std::memory_order_relaxed);
    busLimMidEnabledMeterFlag_.store(p_.busLimMidEnabled, std::memory_order_relaxed);
    busLimHighEnabledMeterFlag_.store(p_.busLimHighEnabled, std::memory_order_relaxed);
    masterLimiterEnabledMeterFlag_.store(p_.limiterEnabled, std::memory_order_relaxed);
    applyDirty(dirty);
    return true;
}

bool NativeBmwDspProcessor::configurePeq(bool enabled, float preampDb, const double* full,
                                         std::size_t fullCount, const double* low,
                                         std::size_t lowCount, const double* mid,
                                         std::size_t midCount, const double* high,
                                         std::size_t highCount) {
    std::lock_guard<std::mutex> lock(stateMutex_);
    return configurePeqLocked(enabled, preampDb, full, fullCount, low, lowCount, mid, midCount,
                              high, highCount);
}
bool NativeBmwDspProcessor::configurePeqLocked(bool enabled, float preampDb, const double* full,
                                               std::size_t fullCount, const double* low,
                                               std::size_t lowCount, const double* mid,
                                               std::size_t midCount, const double* high,
                                               std::size_t highCount) {
    if (!std::isfinite(preampDb) || preampDb < -30 || preampDb > 12) {
        return false;
    }
    auto build = [this](const double* v, std::size_t n, PeqBank& b) {
        if (n % 5 || n / 5 > 16 || (n && v == nullptr)) {
            return false;
        }
        PeqBank x;
        for (std::size_t i = 0; i < n; i += 5) {
            // v[i+3]/v[i+4] (type, channel) are cast to int below; casting a value outside int's
            // representable range (including NaN/Infinity, but also a finite-but-huge double from
            // the raw JNI array) is UB ([conv.fpint]), so both have to be ruled out before either
            // cast runs, not deferred to makePeq()'s own isfinite checks (which only cover
            // f/gainDb/Q) or to the type/channel range checks below the casts.
            constexpr double kIntMin = static_cast<double>(std::numeric_limits<int>::min()),
                             kIntMax = static_cast<double>(std::numeric_limits<int>::max());
            if (!std::isfinite(v[i + 3]) || !std::isfinite(v[i + 4]) || v[i + 3] < kIntMin ||
                v[i + 3] > kIntMax || v[i + 4] < kIntMin || v[i + 4] > kIntMax) {
                return false;
            }
            int type = static_cast<int>(v[i + 3]);
            Biquad q;
            if (!NativeBmwDsp::makePeq(q, v[i], v[i + 1], v[i + 2], type, sampleRate_)) {
                return false;
            }
            int ch = static_cast<int>(v[i + 4]);
            if (ch < 0 || ch > 2) {
                return false;
            }
            if (NativeBmwDsp::peqBandSkipped(type, v[i + 1])) {
                continue;
            }
            if (ch != 2) {
                x.append(PeqBank::kLeftLane, q);
            }
            if (ch != 1) {
                x.append(PeqBank::kRightLane, q);
            }
        }
        b = x;
        return true;
    };
    PeqBank f, l, m, h;
    if (!build(full, fullCount, f) || !build(low, lowCount, l) || !build(mid, midCount, m) ||
        !build(high, highCount, h)) {
        return false;
    }
    auto save = [](auto& target, std::size_t& count, const double* source,
                   std::size_t sourceCount) {
        target.fill(0);
        if (sourceCount > 0) {
            std::copy_n(source, sourceCount, target.begin());
        }
        count = sourceCount;
    };
    save(inputPeqValues_, inputPeqValueCount_, full, fullCount);
    save(lowPeqValues_, lowPeqValueCount_, low, lowCount);
    save(midPeqValues_, midPeqValueCount_, mid, midCount);
    save(highPeqValues_, highPeqValueCount_, high, highCount);
    inputPeq_ = f;
    lowPeq_ = l;
    midPeq_ = m;
    highPeq_ = h;
    peqEnabled_ = enabled;
    peqPreampDb_ = preampDb;
    peqPreamp_ = dbToLin(preampDb);
    return true;
}

// ---- Rebuilds: derived state from p_ / outputConfigs_ / sampleRate_ ----------------------------
void NativeBmwDspProcessor::resetDynamics() {
    for (auto& s : outputDynamics_) {
        NativeBmwDsp::resetCompressorState(s);
    }
}
void NativeBmwDspProcessor::rebuildGains() {
    headroom_ = dbToLin(p_.headroom);
    postGainL_ = dbToLin(p_.postGainL);
    postGainR_ = dbToLin(p_.postGainR);
    output(OutputId::LowLeft).gain = dbToLin(p_.lowGainL);
    output(OutputId::LowRight).gain = dbToLin(p_.lowGainR);
    output(OutputId::MidLeft).gain = dbToLin(p_.midGainL);
    output(OutputId::MidRight).gain = dbToLin(p_.midGainR);
    output(OutputId::HighLeft).gain = dbToLin(p_.highGainL);
    output(OutputId::HighRight).gain = dbToLin(p_.highGainR);
    for (std::size_t i = 0; i < outputDynamics_.size(); ++i) {
        outputDynamics_[i].makeupLin = dbToLin(outputConfigs_[i].compressor.makeup);
    }
}
void NativeBmwDspProcessor::rebuildSubsonic() {
    for (OutputId id : {OutputId::LowLeft, OutputId::LowRight}) {
        NativeBmwDsp::buildSubsonic(output(id), outputConfig(id), sampleRate_);
    }
}
void NativeBmwDspProcessor::rebuildLowCrossover() {
    for (OutputId id : {OutputId::LowLeft, OutputId::LowRight}) {
        NativeBmwDsp::buildLowCrossover(output(id), outputConfig(id), sampleRate_);
    }
}
void NativeBmwDspProcessor::rebuildMidCrossover() {
    for (OutputId id : {OutputId::MidLeft, OutputId::MidRight}) {
        NativeBmwDsp::buildMidCrossover(output(id), outputConfig(id), sampleRate_);
    }
}
void NativeBmwDspProcessor::rebuildHighCrossover() {
    for (OutputId id : {OutputId::HighLeft, OutputId::HighRight}) {
        NativeBmwDsp::buildHighCrossover(output(id), outputConfig(id), sampleRate_);
    }
}
void NativeBmwDspProcessor::updateDelays() {
    using NativeBmwDsp::delaySamples;
    output(OutputId::LowLeft).delay.delay = delaySamples(p_.lowDelayL, sampleRate_, kDelayLineCapacity);
    output(OutputId::LowRight).delay.delay = delaySamples(p_.lowDelayR, sampleRate_, kDelayLineCapacity);
    output(OutputId::MidLeft).delay.delay = delaySamples(p_.midDelayL, sampleRate_, kDelayLineCapacity);
    output(OutputId::MidRight).delay.delay = delaySamples(p_.midDelayR, sampleRate_, kDelayLineCapacity);
    output(OutputId::HighLeft).delay.delay = delaySamples(p_.highDelayL, sampleRate_, kDelayLineCapacity);
    output(OutputId::HighRight).delay.delay = delaySamples(p_.highDelayR, sampleRate_, kDelayLineCapacity);
    // Stage-centering delay -- its own (larger) ring buffer, so clamp to kStageDelayCapacity.
    stageDelayL_.delay = delaySamples(p_.stageDelayL, sampleRate_, kStageDelayCapacity);
    stageDelayR_.delay = delaySamples(p_.stageDelayR, sampleRate_, kStageDelayCapacity);
}
void NativeBmwDspProcessor::rebuildCompressorTiming() {
    detector_.rebuild(sampleRate_);
    for (std::size_t i = 0; i < outputDynamics_.size(); ++i) {
        NativeBmwDsp::rebuildCompressorTiming(outputConfigs_[i].compressor, outputDynamics_[i],
                                              sampleRate_);
    }
}
void NativeBmwDspProcessor::rebuildBusLimiter() {
    busLimAttackMix_ = NativeBmwDsp::busLimiterAttackMix(sampleRate_);
    busLimLow_.zeroMeter();
    busLimMid_.zeroMeter();
    busLimHigh_.zeroMeter();
    busLimLow_.releaseMix = NativeBmwDsp::busLimiterReleaseMix(p_.busLimLowReleaseMs, sampleRate_);
    busLimMid_.releaseMix = NativeBmwDsp::busLimiterReleaseMix(p_.busLimMidReleaseMs, sampleRate_);
    busLimHigh_.releaseMix = NativeBmwDsp::busLimiterReleaseMix(p_.busLimHighReleaseMs, sampleRate_);
}
void NativeBmwDspProcessor::rebuildPolarityAndMute() {
    for (std::size_t i = 0; i < outputs_.size(); ++i) {
        auto& out = outputs_[i];
        const auto& cfg = outputConfigs_[i];
        // Every band except the isolated one is muted while measurement mute is on.
        const bool measurementMuted =
            p_.measurementMute != 0 &&
            !measurementMuteIsolates(p_.measurementMute, NativeBmwRouting::band(i));
        out.muted = cfg.muted || measurementMuted;
        out.polarityInverted = cfg.polarityInverted;
    }
}
void NativeBmwDspProcessor::rebuildAllPass() {
    for (auto& out : outputs_) {
        NativeBmwDsp::rebuildAllPass(out, sampleRate_);
    }
}
void NativeBmwDspProcessor::applyDirty(uint32_t d) {
    if (d & DirtyGains) {
        rebuildGains();
    }
    if (d & DirtySubsonic) {
        rebuildSubsonic();
    }
    if (d & DirtyLowXo) {
        rebuildLowCrossover();
    }
    if (d & DirtyMidXo) {
        rebuildMidCrossover();
    }
    if (d & DirtyHighXo) {
        rebuildHighCrossover();
    }
    if (d & DirtyHighResume) {
        output(OutputId::HighLeft).clearState();
        output(OutputId::HighRight).clearState();
        highPeq_.clear();
    }
    if (d & DirtyDelays) {
        updateDelays();
    }
    if (d & DirtyTilt) {
        tilt_.rebuild(p_.tiltAmount, p_.tiltFreq, sampleRate_);
    }
    if (d & DirtyCompTiming) {
        rebuildCompressorTiming();
    }
    if (d & DirtyCompState) {
        resetDynamics();
    }
    if (d & DirtyPolarity) {
        rebuildPolarityAndMute();
    }
    if (d & DirtyMeasBus) {
        measBus_.rebuild(p_.measurementMute, p_.measBusStopbandOctaves, outputConfigs_, sampleRate_);
    }
    if (d & DirtyMeasGen) {
        NativeBmwDsp::configureMeasurementGenerator(measGen_, p_, sampleRate_);
    }
    if (d & DirtyMbc) {
        mbc_.rebuild(p_.mbcXo, p_.mbcMix, p_.mbcBand, sampleRate_);
    }
    if (d & DirtyMbcTiming) {
        mbc_.rebuildTiming(p_.mbcMix, p_.mbcBand, sampleRate_);
    }
    if (d & DirtyMbcState) {
        mbc_.resetState();
    }
    if (d & DirtyBusLimiter) {
        rebuildBusLimiter();
    }
    if (d & DirtyLimiter) {
        limiter_.rebuild(sampleRate_, p_.limiterThreshDb);
    }
}
void NativeBmwDspProcessor::rebuildAll() {
    dcR_ = NativeBmwDsp::DcBlocker::coefficient(sampleRate_);
    rebuildGains();
    rebuildSubsonic();
    rebuildLowCrossover();
    rebuildMidCrossover();
    rebuildHighCrossover();
    tilt_.rebuild(p_.tiltAmount, p_.tiltFreq, sampleRate_);
    rebuildCompressorTiming();
    limiter_.rebuild(sampleRate_, p_.limiterThreshDb);
    mbc_.rebuild(p_.mbcXo, p_.mbcMix, p_.mbcBand, sampleRate_);
    rebuildBusLimiter();
    rebuildPolarityAndMute();
    measBus_.rebuild(p_.measurementMute, p_.measBusStopbandOctaves, outputConfigs_, sampleRate_);
    NativeBmwDsp::configureMeasurementGenerator(measGen_, p_, sampleRate_);
    rebuildAllPass();
    inputDcL_.clear();
    inputDcR_.clear();
    for (auto& out : outputs_) {
        out.clearState();
    }
    limiter_.clear();
    stageDelayL_.clear();
    stageDelayR_.clear();
    mbc_.resetState();
    for (BusLimiter* b : {&busLimLow_, &busLimMid_, &busLimHigh_}) {
        b->gain = 1.f;
        b->zeroMeter();
    }
    updateDelays();
    resetDynamics();
    // Locked variant: rebuildAll() runs either from the constructor (no lock needed, object not
    // yet published) or from setSampleRate() (already holding stateMutex_) -- never call
    // configurePeq() itself here, it would deadlock re-taking the same non-recursive mutex.
    configurePeqLocked(peqEnabled_, peqPreampDb_, inputPeqValues_.data(), inputPeqValueCount_,
                       lowPeqValues_.data(), lowPeqValueCount_, midPeqValues_.data(),
                       midPeqValueCount_, highPeqValues_.data(), highPeqValueCount_);
}
