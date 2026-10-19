#include "NativeBmwDspProcessor.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include "NativeBmwDspMath.h"
#include "NativeBmwDspSchema.h"

namespace {
using NativeBmwDsp::clampf;
using NativeBmwDsp::changed;
using NativeBmwDsp::dbToLin;
using NativeBmwDsp::measurementMuteIsolates;
using OutputId = NativeBmwRouting::OutputId;

// Any generator param change restarts the run, not just a type flip -- editing the sweep
// range/duration/level while it's already playing is expected to retrigger it.
bool measGenChanged(const NativeBmwDsp::Params& a, const NativeBmwDsp::Params& b) {
    return a.measGenType != b.measGenType ||
           changed(a.measGenSweepStartHz, b.measGenSweepStartHz) ||
           changed(a.measGenSweepEndHz, b.measGenSweepEndHz) ||
           changed(a.measGenSweepDurationS, b.measGenSweepDurationS) ||
           changed(a.measGenSweepLevelDb, b.measGenSweepLevelDb) ||
           changed(a.measGenPinkPeriodS, b.measGenPinkPeriodS) ||
           changed(a.measGenPinkLevelDb, b.measGenPinkLevelDb) ||
           a.measGenTimingRefEnabled != b.measGenTimingRefEnabled ||
           a.measGenTimingRefSplitChannels != b.measGenTimingRefSplitChannels ||
           changed(a.measGenTimingRefMidStartHz, b.measGenTimingRefMidStartHz) ||
           changed(a.measGenTimingRefMidEndHz, b.measGenTimingRefMidEndHz) ||
           changed(a.measGenTimingRefLowStartHz, b.measGenTimingRefLowStartHz) ||
           changed(a.measGenTimingRefLowEndHz, b.measGenTimingRefLowEndHz);
}
}  // namespace

bool NativeBmwDspProcessor::configure(const float* v, std::size_t n) {
    if (!v || n != kConfigSize) {
        return false;
    }
    // Never stateMutex_: see the threading comment in NativeBmwDspProcessor.h.
    std::lock_guard<std::mutex> lock(configMutex_);
    ParsedConfig& update = configHandoff_.back();
    if (!parseConfig(v, update)) {
        return false;
    }
    configShadow_.params = update.params;
    configShadow_.outputConfigs = update.outputConfigs;
    configHandoff_.publish();
    return true;
}

bool NativeBmwDspProcessor::parseConfig(const float* v, ParsedConfig& parsed) const {
    const float sampleRate = configSampleRate_.load(std::memory_order_relaxed);
    parsed.sampleRate = sampleRate;
    Params& next = parsed.params;
    next = configShadow_.params;
    // clampf() maps NaN to its upper bound (std::min(hi, NaN) == hi), and `NaN >= .5f` is
    // false, so a non-finite slot would silently land on max gain/delay or switch a control
    // off (e.g. un-silence High via highXoPass, or drop the master limiter). Every clamped slot
    // goes through clampIn() and every on/off slot through flagIn(); the whole update is
    // rejected below (before any member state is touched) if one wasn't finite -- same policy
    // as the routing/all-pass/MBC-crossover checks.
    bool allFinite = true;
    auto clampIn = [&allFinite](float x, float lo, float hi) {
        allFinite = allFinite && std::isfinite(x);
        return clampf(x, lo, hi);
    };
    auto flagIn = [&allFinite](float x) {
        allFinite = allFinite && std::isfinite(x);
        return x >= .5f;
    };
    next.enabled = flagIn(v[0]);
    next.lpfPass = flagIn(v[1]);
    next.hpfPass = flagIn(v[2]);
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
    // v[141] / v[142] (the retired stage-centering delay) are not read: see kConfigSize's comment.
    next.tilt = flagIn(v[25]);
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
    next.measGenTimingRefEnabled = flagIn(v[199]);
    next.measGenTimingRefSplitChannels = flagIn(v[200]);
    next.measGenTimingRefMidStartHz = clampIn(v[201], 10, 24000);
    next.measGenTimingRefMidEndHz = clampIn(v[202], 10, 24000);
    next.measGenTimingRefLowStartHz = clampIn(v[203], 10, 24000);
    next.measGenTimingRefLowEndHz = clampIn(v[204], 10, 24000);

    // High band scalars (v[210..214], added in the 210 -> 262 growth). Routing/all-pass/
    // output-config for High are read further down, alongside the equivalent Low/Mid blocks.
    next.highXoPass = flagIn(v[210]);
    next.highPass = flagIn(v[nbschema::kHighPass]);
    next.masterVolumeDb = clampIn(v[nbschema::kMasterVolumeDb], -80, 0);
    next.masterMute = flagIn(v[nbschema::kMasterMute]);
    next.loudnessEnabled = flagIn(v[nbschema::kLoudnessEnabled]);
    next.loudnessStrength = clampIn(v[nbschema::kLoudnessStrength], 0, 100) * .01f;
    next.highGainL = clampIn(v[211], -6, 6);
    next.highGainR = clampIn(v[212], -6, 6);
    next.highDelayL = clampIn(v[213], 0, 2.8f);
    next.highDelayR = clampIn(v[214], 0, 2.8f);

    // Pre-crossover multiband compressor (v[144..180]) + per-bus limiter (v[182..187]). v[181]
    // is the Kotlin-only migration marker and v[188..192) are reserved -- none are read here.
    next.mbcEnabled = flagIn(v[144]);
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
        mb.enabled = flagIn(v[base]);
        mb.threshold = clampIn(v[base + 1], -48, 0);
        mb.ratio = clampIn(v[base + 2], 1, 20);
        mb.knee = clampIn(v[base + 3], 0, 24);
        mb.attack = clampIn(v[base + 4], 1, 200);
        mb.release = clampIn(v[base + 5], 20, 1000);
        mb.makeup = clampIn(v[base + 6], 0, 12);
        mb.stereoLink = flagIn(v[base + 7]);
    }
    next.busLimLowEnabled = flagIn(v[182]);
    next.busLimLowThreshDb = clampIn(v[183], -24, 0);
    next.busLimLowReleaseMs = clampIn(v[184], 20, 800);
    next.busLimMidEnabled = flagIn(v[185]);
    next.busLimMidThreshDb = clampIn(v[186], -24, 0);
    next.busLimMidReleaseMs = clampIn(v[187], 20, 800);
    // High-bus limiter (v[262..264], added in the 262 -> 266 growth); v[265] is the
    // Kotlin-only migration marker.
    next.busLimHighEnabled = flagIn(v[262]);
    next.busLimHighThreshDb = clampIn(v[263], -24, 0);
    next.busLimHighReleaseMs = clampIn(v[264], 20, 800);
    // v[189/190]: master limiter enable + threshold dBFS (v[191] is a Kotlin-only migration
    // marker). Slots reclaimed from the 188..191 "reserved" run -- SIZE stays 192.
    next.limiterEnabled = flagIn(v[189]);
    next.limiterThreshDb = clampIn(v[190], -12, 0);

    // Virtual-source stage (v[266..286], added in the 266 -> 288 growth); v[287] is the
    // Kotlin-only migration marker. See docs/NATIVE_BMW_VIRTUAL_CHANNELS.md.
    {
        namespace sch = nbschema;
        auto& vs = next.virtualStage;
        vs.enabled = flagIn(v[sch::kVirtualEnabled]);
        vs.detectHpfHz = clampIn(v[sch::kVirtualDetectHpf], 20, 1000);
        vs.detectLpfHz = clampIn(v[sch::kVirtualDetectLpf], 1000, 20000);
        vs.attackMs = clampIn(v[sch::kVirtualAttack], 1, 500);
        vs.releaseMs = clampIn(v[sch::kVirtualRelease], 10, 2000);
        vs.centreLevelDb = clampIn(v[sch::kVirtualCentreLevel], -24, 6);
        vs.sideLevelDb = clampIn(v[sch::kVirtualSideLevel], -24, 6);
        for (std::size_t side = 0; side < vs.centreFeed.size(); ++side) {
            const std::size_t base = sch::kVirtualFeedBase + side * sch::kVirtualFeedWidth;
            auto& feed = vs.centreFeed[side];
            feed.gainDb = clampIn(v[base + sch::kVirtualFeedGain], -24, 6);
            feed.delayMs = clampIn(v[base + sch::kVirtualFeedDelay], 0, kStageDelayMaxMs);
            feed.polarityInverted = flagIn(v[base + sch::kVirtualFeedPolarity]);
            feed.allPass.enabled = flagIn(v[base + sch::kVirtualFeedApEnabled]);
            feed.allPass.frequencyHz = clampIn(v[base + sch::kVirtualFeedApFreq], 20, 20000);
            feed.allPass.q = clampIn(v[base + sch::kVirtualFeedApQ], 0.1f, 30);
            // Same encoding as the per-output all-pass order slot: 1 = 1st order, 2 = 2nd.
            feed.allPass.secondOrder = clampIn(v[base + sch::kVirtualFeedApOrder], 1, 2) >= 1.5f;
        }
        // The detector band must be a real band; an inverted pair is a caller bug, not
        // something to silently reorder. Rejected in full like any other invalid update.
        if (vs.detectHpfHz >= vs.detectLpfHz) {
            return false;
        }
    }

    // These four loops (routing, all-pass, output-config x2) are deliberately scoped to
    // kLegacyOutputCount (4), not the in-memory kOutputCount (6): High has no persisted schema
    // slots yet, so reading v[] at the offsets a 6-output loop would visit for outputs 4/5 would
    // read into the *next* block (all-pass would misread output-config bytes as its own, etc).
    // High's runtime entries (outputs_[4]/[5], outputConfigs_[4]/[5]) are left at their
    // constructor defaults -- muted, identity routing -- untouched by configure() until a later
    // phase adds its own tail block. See docs/NATIVE_BMW_3WAY_OUTPUT_CROSSOVER.md.
    NativeBmwRouting::RoutingMatrix& nextRouting = parsed.routing;
    nextRouting = {};
    for (std::size_t out = 0; out < NativeBmwRouting::kLegacyOutputCount; ++out) {
        const float fromLeft = v[kRoutingBase + out * 2], fromRight = v[kRoutingBase + out * 2 + 1];
        if (!std::isfinite(fromLeft) || !std::isfinite(fromRight) || std::fabs(fromLeft) > 2.f ||
            std::fabs(fromRight) > 2.f) {
            return false;
        }
        nextRouting.outputs[out] = {fromLeft, fromRight};
    }

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
            (void)candidate.rebuild(sampleRate);
            parsed.allPass[out][section] = candidate;
        }
    }

    auto& nextOutputConfigs = parsed.outputConfigs;
    nextOutputConfigs = configShadow_.outputConfigs;
    // Fields 6..12 of each output-config block were the per-output compressor, removed from
    // native (force-disabled by a Kotlin migration long before). The slots stay in the schema so
    // no later index moves, and are not read.
    for (std::size_t out = 0; out < NativeBmwRouting::kLegacyOutputCount; ++out) {
        const std::size_t base = kOutputConfigBase + out * kOutputConfigWidth;
        auto& cfg = nextOutputConfigs[out];
        cfg.crossoverFreq = clampIn(v[base], 60, 320);
        // v[base+1]: 0 = BW2, 1 = BW3, 2 = LR4, 3 = BW1, 4 = BW4. Keep the old threshold
        // decoding for existing saves; only the explicit new IDs select first-order / BW4.
        const float typeVal = v[base + 1];
        allFinite = allFinite && std::isfinite(typeVal);  // NaN would silently decode as LR4
        cfg.crossoverType = typeVal == 3.f ? OutputConfig::CrossoverType::Butterworth1
                            : typeVal == 4.f ? OutputConfig::CrossoverType::Butterworth4
                            : typeVal < .5f ? OutputConfig::CrossoverType::Butterworth2
                            : typeVal < 1.5f ? OutputConfig::CrossoverType::Butterworth3
                                             : OutputConfig::CrossoverType::LinkwitzRiley4;
        cfg.subsonicEnabled = flagIn(v[base + 2]);
        cfg.subsonicFreq = clampIn(v[base + 3], 20, 60);
        cfg.muted = flagIn(v[base + 4]);
        cfg.polarityInverted = flagIn(v[base + 5]);
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

        // The upper corner's own slope, v[288] (Mid Left) / v[289] (Mid Right), added in the
        // 288 -> 291 growth; same 0 BW2 / 1 BW3 / 2 LR4 / 3 BW1 / 4 BW4 encoding as
        // FIELD_CROSSOVER_TYPE. Kotlin seeds both from Mid's own type on first load (marker
        // v[290]), so an existing save's lowpass keeps its old slope.
        const float midLeftType = v[nbschema::kMidUpperXoTypeLeft];
        const float midRightType = v[nbschema::kMidUpperXoTypeRight];
        if (!std::isfinite(midLeftType) || !std::isfinite(midRightType)) {
            return false;
        }
        auto decodeType = [](float t) {
            return t == 3.f ? OutputConfig::CrossoverType::Butterworth1
                   : t == 4.f ? OutputConfig::CrossoverType::Butterworth4
                   : t < .5f ? OutputConfig::CrossoverType::Butterworth2
                   : t < 1.5f ? OutputConfig::CrossoverType::Butterworth3
                              : OutputConfig::CrossoverType::LinkwitzRiley4;
        };
        midLeftCfg.upperCrossoverType = decodeType(midLeftType);
        midRightCfg.upperCrossoverType = decodeType(midRightType);
    }

    // High band: routing/all-pass/output-config, v[215..260] (added in the 210 -> 262 growth).
    // A genuinely new third output -- unlike Mid's tail block above, this populates real
    // nextRouting/allPass/nextOutputConfigs entries for OutputId::HighLeft/HighRight, the
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
            (void)candidate.rebuild(sampleRate);
            parsed.allPass[out][section] = candidate;
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
        cfg.subsonicEnabled = flagIn(v[cfgBase + 2]);  // carried but ignored, same as Mid
        cfg.subsonicFreq = clampIn(v[cfgBase + 3], 20, 60);
        cfg.muted = flagIn(v[cfgBase + 4]);
        cfg.polarityInverted = flagIn(v[cfgBase + 5]);
    }

    if (!allFinite) {
        return false;
    }
    // Make this slot's generator a fresh one for exactly these settings (see
    // ParsedConfig::generator). Kept as it is only if it is unused and was built for the same
    // settings at the same rate; otherwise rebuilt here, on this thread. Only reached on
    // success: a rejected update leaves the slot as it was.
    const bool reusable = parsed.generator && !parsed.generatorInstalled &&
                          parsed.generatorSampleRate == sampleRate &&
                          !measGenChanged(parsed.generatorParams, next);
    if (!reusable) {
        auto fresh = std::make_unique<NativeBmwMeasurementGenerator>();
        NativeBmwDsp::configureMeasurementGenerator(*fresh, next, sampleRate);
        parsed.generator = std::move(fresh);  // frees a retired or stale one, on this thread
        parsed.generatorParams = next;
        parsed.generatorSampleRate = sampleRate;
        parsed.generatorInstalled = false;
    }
    return true;
}

void NativeBmwDspProcessor::installConfig(ParsedConfig& parsed) {
    const Params& next = parsed.params;
    const auto& nextOutputConfigs = parsed.outputConfigs;
    // All-pass sections, retuned in place. Built at the config thread's rate; a rate change
    // since then means rebuilding them here at the one now running.
    const bool rateMatches = parsed.sampleRate == sampleRate_;
    for (std::size_t out = 0; out < NativeBmwRouting::kOutputCount; ++out) {
        for (std::size_t section = 0; section < NativeBmwRouting::kAllPassSectionsPerOutput;
             ++section) {
            auto candidate = parsed.allPass[out][section];
            if (!rateMatches) {
                (void)candidate.rebuild(sampleRate_);
            }
            auto& current = outputs_[out].allPass[section];
            const bool sectionChanged = current.enabled != candidate.enabled ||
                                        current.secondOrder != candidate.secondOrder ||
                                        changed(current.frequencyHz, candidate.frequencyHz) ||
                                        changed(current.q, candidate.q) || !rateMatches;
            const bool switchedOn = !current.enabled && candidate.enabled;
            current = candidate;
            // Keep the delay history when an unrelated setting is configured. Clearing an
            // active all-pass section here creates a phase discontinuity even though its
            // coefficients did not change.
            if (sectionChanged) {
                // loadAllPass() keeps the running state across a retune (no click while a
                // frequency/Q is dragged). A section that is only now switching on hasn't run
                // since it was last on, so its state is stale -- that one starts clean.
                auto& state = outputs_[out].allPassState[section];
                state.loadAllPass(candidate.coefficients);
                if (switchedOn) {
                    state.clear();
                }
            }
        }
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
        changed(next.highDelayL, p_.highDelayL) || changed(next.highDelayR, p_.highDelayR)) {
        dirty |= DirtyDelays;
    }
    if (changed(next.tiltAmount, p_.tiltAmount) || changed(next.tiltFreq, p_.tiltFreq)) {
        dirty |= DirtyTilt;
    }
    // Tilt doesn't run while off, so its filters still hold whatever played when it was switched
    // off; clear them when it comes back on, or that stale audio replays as a short burst.
    if (!p_.tilt && next.tilt) {
        dirty |= DirtyTiltResume;
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
             old.upperCrossoverEnabled != now.upperCrossoverEnabled ||
             old.upperCrossoverType != now.upperCrossoverType)) {
            dirty |= DirtyMidXo;
            // Isolate-Mid's upper bus LPF follows Mid's upper corner (and its enable).
            if (next.measurementMute == 1) {
                dirty |= DirtyMeasBus;
            }
        }
        if (low && (old.subsonicEnabled != now.subsonicEnabled ||
                    changed(old.subsonicFreq, now.subsonicFreq))) {
            dirty |= DirtySubsonic;
            // The subsonic stage doesn't run while off; one switching back on starts clean
            // rather than replaying the state it froze with (the rebuild keeps state).
            if (!old.subsonicEnabled && now.subsonicEnabled) {
                outputs_[out].subsonic1.clear();
                outputs_[out].subsonic2.clear();
            }
        }
        if (old.muted != now.muted || old.polarityInverted != now.polarityInverted) {
            dirty |= DirtyPolarity;
        }
    }
    // High's filters, all-pass, delay and PEQ don't run while highXoPass silences it or highPass
    // passes it through raw, so they still hold whatever was playing when its chain stopped.
    // Clear them whenever the chain starts running again (either flag, or both, turning off),
    // or that stale audio replays through the tweeter as a burst for the first few milliseconds.
    const bool highChainWasFrozen = p_.highXoPass || p_.highPass;
    const bool highChainRunsNow = !next.highXoPass && !next.highPass;
    if (highChainWasFrozen && highChainRunsNow) {
        dirty |= DirtyHighResume;
    }
    // Same for Low and Mid: lpfPass / hpfPass skip their chains just the same.
    if (p_.lpfPass && !next.lpfPass) {
        dirty |= DirtyLowResume;
    }
    if (p_.hpfPass && !next.hpfPass) {
        dirty |= DirtyMidResume;
    }
    if (next.measurementMute != p_.measurementMute) {
        dirty |= DirtyPolarity | DirtyMeasBus;
    }
    if (changed(next.measBusStopbandOctaves, p_.measBusStopbandOctaves)) {
        dirty |= DirtyMeasBus;
    }
    if (measGenChanged(next, p_)) {
        // Swap in the fresh generator the config thread built for these settings. The fallback
        // (configuring it in place, in applyDirty()) is only for one built at a sample rate
        // that has changed since -- the same rare race the all-pass and PEQ paths rebuild for.
        if (parsed.generator && !parsed.generatorInstalled &&
            parsed.generatorSampleRate == sampleRate_) {
            std::swap(measGen_, *parsed.generator);
            parsed.generatorInstalled = true;
        } else {
            dirty |= DirtyMeasGen;
        }
    }

    // MBC: split-freq changes -> DirtyMbc (rebuild filter coeffs, keeps tree state, same cost as
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
    // Virtual stage: any parameter change rebuilds its coefficients (state kept, so a slider
    // drag doesn't click); switching it on or off also clears its state, so stale detector
    // and delay-line contents can't replay when it comes back on.
    {
        const auto& cur = p_.virtualStage;
        const auto& nxt = next.virtualStage;
        bool feedChanged = false;
        for (std::size_t side = 0; side < cur.centreFeed.size(); ++side) {
            const auto& a = cur.centreFeed[side];
            const auto& b = nxt.centreFeed[side];
            feedChanged = feedChanged || changed(a.gainDb, b.gainDb) ||
                          changed(a.delayMs, b.delayMs) ||
                          a.polarityInverted != b.polarityInverted ||
                          a.allPass.enabled != b.allPass.enabled ||
                          a.allPass.secondOrder != b.allPass.secondOrder ||
                          changed(a.allPass.frequencyHz, b.allPass.frequencyHz) ||
                          changed(a.allPass.q, b.allPass.q);
        }
        if (feedChanged || changed(cur.detectHpfHz, nxt.detectHpfHz) ||
            changed(cur.detectLpfHz, nxt.detectLpfHz) || changed(cur.attackMs, nxt.attackMs) ||
            changed(cur.releaseMs, nxt.releaseMs) || changed(cur.centreLevelDb, nxt.centreLevelDb) ||
            changed(cur.sideLevelDb, nxt.sideLevelDb)) {
            dirty |= DirtyVirtual;
        }
        if (cur.enabled != nxt.enabled) {
            dirty |= DirtyVirtual | DirtyVirtualState;
        }
    }
    // Master limiter: enable is checked live in processFrame; only the threshold -> ceiling
    // scalar needs a (cheap, state-free) recompute.
    if (changed(next.limiterThreshDb, p_.limiterThreshDb)) {
        dirty |= DirtyLimiter;
    }
    if (changed(next.masterVolumeDb, p_.masterVolumeDb) ||
        changed(next.loudnessStrength, p_.loudnessStrength) ||
        next.loudnessEnabled != p_.loudnessEnabled) {
        dirty |= DirtyLoudness;
    }
    // Loudness is skipped once fully faded out; switching it back on then starts its shelves
    // clean (its lift starts from 0, so the clean state is inaudible while it fades in).
    if (!p_.loudnessEnabled && next.loudnessEnabled && loudnessStrength_ == 0.f) {
        dirty |= DirtyLoudnessResume;
    }

    p_ = next;
    masterGainTarget_ = p_.masterMute ? 0.f : std::pow(10.f, p_.masterVolumeDb / 20.f);
    if (!masterGainPrimed_) {
        masterGain_ = masterGainTarget_;
        masterGainPrimed_ = true;
    }
    routing_ = parsed.routing;
    outputConfigs_ = nextOutputConfigs;
    // See mbcEnabledMeterFlag_ etc's declaration: the read*Meter() functions are lock-free and
    // read these atomics instead of the plain bools inside p_ to avoid racing this assignment.
    mbcEnabledMeterFlag_.store(p_.mbcEnabled, std::memory_order_relaxed);
    busLimLowEnabledMeterFlag_.store(p_.busLimLowEnabled, std::memory_order_relaxed);
    busLimMidEnabledMeterFlag_.store(p_.busLimMidEnabled, std::memory_order_relaxed);
    busLimHighEnabledMeterFlag_.store(p_.busLimHighEnabled, std::memory_order_relaxed);
    masterLimiterEnabledMeterFlag_.store(p_.limiterEnabled, std::memory_order_relaxed);
    virtualEnabledMeterFlag_.store(p_.virtualStage.enabled, std::memory_order_relaxed);
    applyDirty(dirty);
    // The first configure() lands the real settings: snap the polarity/mute ramps to them rather
    // than fading from the constructor's defaults at start-up.
    if (!outputGainsPrimed_) {
        rebuildPolarityAndMute(/*snap=*/true);
        for (auto& out : outputs_) {
            out.delay.snap();  // nor crossfade the delays in from their defaults
        }
        // ...nor fade loudness in at start-up.
        loudnessStrength_ = p_.loudnessEnabled ? p_.loudnessStrength : 0.f;
        retuneLoudness();
        outputGainsPrimed_ = true;
    }
}

bool NativeBmwDspProcessor::configurePeq(bool enabled, float preampDb, const double* full,
                                         std::size_t fullCount, const double* low,
                                         std::size_t lowCount, const double* mid,
                                         std::size_t midCount, const double* high,
                                         std::size_t highCount) {
    // Never stateMutex_: see the threading comment in NativeBmwDspProcessor.h.
    std::lock_guard<std::mutex> lock(configMutex_);
    if (!parsePeq(enabled, preampDb, full, fullCount, low, lowCount, mid, midCount, high,
                  highCount, configSampleRate_.load(std::memory_order_relaxed),
                  peqHandoff_.back())) {
        return false;
    }
    peqHandoff_.publish();
    return true;
}
bool NativeBmwDspProcessor::parsePeq(bool enabled, float preampDb, const double* full,
                                     std::size_t fullCount, const double* low,
                                     std::size_t lowCount, const double* mid,
                                     std::size_t midCount, const double* high,
                                     std::size_t highCount, float sampleRate, ParsedPeq& out,
                                     bool dropUnbuildable) {
    if (!std::isfinite(preampDb) || preampDb < -30 || preampDb > 12) {
        return false;
    }
    auto build = [sampleRate, dropUnbuildable](const double* v, std::size_t n, PeqBank& b) {
        if (n % 5 || n / 5 > 16 || (n && v == nullptr)) {
            return false;
        }
        b = PeqBank{};
        b.rawBandCount = n / 5;
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
            int ch = static_cast<int>(v[i + 4]);
            if (ch < 0 || ch > 2) {
                return false;
            }
            Biquad q;
            if (!NativeBmwDsp::makePeq(q, v[i], v[i + 1], v[i + 2], type, sampleRate)) {
                if (dropUnbuildable) {
                    continue;
                }
                return false;
            }
            if (NativeBmwDsp::peqBandSkipped(type, v[i + 1])) {
                continue;
            }
            const PeqBank::BandKey key{static_cast<int>(i / 5), type, v[i], v[i + 1], v[i + 2]};
            if (ch != 2) {
                b.append(PeqBank::kLeftLane, q, key);
            }
            if (ch != 1) {
                b.append(PeqBank::kRightLane, q, key);
            }
        }
        return true;
    };
    const double* sources[4] = {full, low, mid, high};
    const std::size_t counts[4] = {fullCount, lowCount, midCount, highCount};
    for (std::size_t bank = 0; bank < 4; ++bank) {
        if (!build(sources[bank], counts[bank], out.banks[bank])) {
            return false;
        }
    }
    for (std::size_t bank = 0; bank < 4; ++bank) {
        out.values[bank].fill(0);
        if (counts[bank] > 0) {
            std::copy_n(sources[bank], counts[bank], out.values[bank].begin());
        }
        out.counts[bank] = counts[bank];
    }
    out.enabled = enabled;
    out.preampDb = preampDb;
    out.sampleRate = sampleRate;
    return true;
}
void NativeBmwDspProcessor::installPeq(ParsedPeq& peq) {
    ParsedPeq* update = &peq;
    if (peq.sampleRate != sampleRate_) {
        // Built at a rate that has changed since: rebuild it at the one now running. It was
        // accepted (and acknowledged) already, so a band the new rate can't hold is left out
        // rather than dropping the whole update. Can't fail: everything else was validated.
        if (&peq == &peqScratch_ ||
            !parsePeq(peq.enabled, peq.preampDb, peq.values[0].data(), peq.counts[0],
                      peq.values[1].data(), peq.counts[1], peq.values[2].data(), peq.counts[2],
                      peq.values[3].data(), peq.counts[3], sampleRate_, peqScratch_,
                      /*dropUnbuildable=*/true)) {
            return;
        }
        update = &peqScratch_;
    }
    // An edit retunes the running banks: each surviving band carries its filter state over, so
    // dragging a band doesn't drop every band's memory at once (a click per drag event, worst on
    // bass). The banks don't run while the PEQ is off, so switching it back on starts clean.
    PeqBank* running[4] = {&inputPeq_, &lowPeq_, &midPeq_, &highPeq_};
    std::array<double, kMaxPeqSectionsPerChannel * kPeqBandWidth>* values[4] = {
        &inputPeqValues_, &lowPeqValues_, &midPeqValues_, &highPeqValues_};
    std::size_t* counts[4] = {&inputPeqValueCount_, &lowPeqValueCount_, &midPeqValueCount_,
                              &highPeqValueCount_};
    for (std::size_t bank = 0; bank < 4; ++bank) {
        if (update->enabled && peqEnabled_) {
            update->banks[bank].inheritState(*running[bank]);
        }
        *running[bank] = update->banks[bank];
        *values[bank] = update->values[bank];
        *counts[bank] = update->counts[bank];
    }
    peqEnabled_ = update->enabled;
    peqPreampDb_ = update->preampDb;
    peqPreamp_ = dbToLin(update->preampDb);
}
void NativeBmwDspProcessor::rebuildPeqLocked() {
    // The saved values were accepted once already; a band that can't be built at the new rate
    // is left out rather than dropping the whole PEQ.
    if (parsePeq(peqEnabled_, peqPreampDb_, inputPeqValues_.data(), inputPeqValueCount_,
                 lowPeqValues_.data(), lowPeqValueCount_, midPeqValues_.data(),
                 midPeqValueCount_, highPeqValues_.data(), highPeqValueCount_, sampleRate_,
                 peqScratch_, /*dropUnbuildable=*/true)) {
        installPeq(peqScratch_);
    }
}
void NativeBmwDspProcessor::installPending() {
    if (ParsedConfig* config = configHandoff_.take()) {
        installConfig(*config);
    }
    if (ParsedPeq* peq = peqHandoff_.take()) {
        installPeq(*peq);
    }
}

// ---- Rebuilds: derived state from p_ / outputConfigs_ / sampleRate_ ----------------------------
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
void NativeBmwDspProcessor::retuneLoudness() {
    auto cutDb = [](float gain) { return -20.f * std::log10(std::max(gain, 1e-6f)); };
    const float cut = std::min(cutDb(masterGain_), cutDb(masterGainTarget_));
    loudness_.rebuild(-cut, loudnessStrength_, sampleRate_);
}
void NativeBmwDspProcessor::updateDelays() {
    using NativeBmwDsp::delaySamples;
    // A delay edit crossfades between the old and new read points instead of jumping.
    const auto fade = static_cast<unsigned>(std::lround(kDelayFadeSeconds * sampleRate_));
    for (auto& out : outputs_) {
        out.delay.fadeLength = fade;
        out.delay.fadeRemaining = std::min(out.delay.fadeRemaining, fade);
    }
    output(OutputId::LowLeft).delay.setDelay(delaySamples(p_.lowDelayL, sampleRate_, kDelayLineCapacity));
    output(OutputId::LowRight).delay.setDelay(delaySamples(p_.lowDelayR, sampleRate_, kDelayLineCapacity));
    output(OutputId::MidLeft).delay.setDelay(delaySamples(p_.midDelayL, sampleRate_, kDelayLineCapacity));
    output(OutputId::MidRight).delay.setDelay(delaySamples(p_.midDelayR, sampleRate_, kDelayLineCapacity));
    output(OutputId::HighLeft).delay.setDelay(delaySamples(p_.highDelayL, sampleRate_, kDelayLineCapacity));
    output(OutputId::HighRight).delay.setDelay(delaySamples(p_.highDelayR, sampleRate_, kDelayLineCapacity));
}
void NativeBmwDspProcessor::rebuildBusLimiter() {
    busLimLow_.zeroMeter();
    busLimMid_.zeroMeter();
    busLimHigh_.zeroMeter();
    busLimLow_.releaseMix = NativeBmwDsp::busLimiterReleaseMix(p_.busLimLowReleaseMs, sampleRate_);
    busLimMid_.releaseMix = NativeBmwDsp::busLimiterReleaseMix(p_.busLimMidReleaseMs, sampleRate_);
    busLimHigh_.releaseMix = NativeBmwDsp::busLimiterReleaseMix(p_.busLimHighReleaseMs, sampleRate_);
    // Hold windows keep their contents unless their length changes (a sample-rate change), and
    // the follower's gain is left alone, so a release/enable edit mid-limiting just releases from
    // where it is -- peaks already in the lookahead delay keep their hold.
    busLimLow_.setHold(NativeBmwDsp::kBusLimLowHoldMs, sampleRate_);
    busLimMid_.setHold(NativeBmwDsp::kBusLimMidHoldMs, sampleRate_);
    busLimHigh_.setHold(NativeBmwDsp::kBusLimHighHoldMs, sampleRate_);
    for (BusLimiter* b : {&busLimLow_, &busLimMid_, &busLimHigh_}) {
        b->setLookahead(sampleRate_);
    }
    // Bypassed bands skip both the alignment kernel and the bus limiter, so they carry both.
    for (auto& line : bypassLatency_) {
        line.delay = static_cast<float>(NativeBmwDsp::kAlignmentLatency + busLimLow_.lookahead());
    }
}
void NativeBmwDspProcessor::rebuildPolarityAndMute(bool snap) {
    for (std::size_t i = 0; i < outputs_.size(); ++i) {
        auto& out = outputs_[i];
        const auto& cfg = outputConfigs_[i];
        // Every band except the isolated one is muted while measurement mute is on.
        const bool measurementMuted =
            p_.measurementMute != 0 &&
            !measurementMuteIsolates(p_.measurementMute, NativeBmwRouting::band(i));
        out.muted = cfg.muted || measurementMuted;
        out.polarityInverted = cfg.polarityInverted;
        out.outputGainTarget = out.muted ? 0.f : out.polarityInverted ? -1.f : 1.f;
        if (snap) {
            out.outputGain = out.outputGainTarget;
        }
    }
    if (snap) {
        channelGainL_ = p_.channelMute == 1 ? 0.f : 1.f;
        channelGainR_ = p_.channelMute == 2 ? 0.f : 1.f;
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
        busLimHigh_.clear();
    }
    if (d & DirtyLowResume) {
        output(OutputId::LowLeft).clearState();
        output(OutputId::LowRight).clearState();
        lowPeq_.clear();
        busLimLow_.clear();
    }
    if (d & DirtyMidResume) {
        output(OutputId::MidLeft).clearState();
        output(OutputId::MidRight).clearState();
        midPeq_.clear();
        busLimMid_.clear();
    }
    if (d & DirtyDelays) {
        updateDelays();
    }
    if (d & DirtyTilt) {
        tilt_.rebuild(p_.tiltAmount, p_.tiltFreq, sampleRate_);
    }
    if (d & DirtyTiltResume) {
        tilt_.clear();
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
    if (d & DirtyLoudness) {
        retuneLoudness();
    }
    if (d & DirtyLoudnessResume) {
        loudness_.clear();
    }
    if (d & DirtyVirtual) {
        virtual_.rebuild(p_.virtualStage, sampleRate_, (d & DirtyVirtualState) != 0);
    }
    if ((d & DirtyVirtualState) && !p_.virtualStage.enabled) {
        virtual_.zeroMeter();
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
    tilt_.clear();
    retuneLoudness();
    loudness_.clear();
    detector_.rebuild(sampleRate_);
    limiter_.rebuild(sampleRate_, p_.limiterThreshDb);
    mbc_.rebuild(p_.mbcXo, p_.mbcMix, p_.mbcBand, sampleRate_);
    rebuildBusLimiter();
    rebuildPolarityAndMute(/*snap=*/true);
    measBus_.rebuild(p_.measurementMute, p_.measBusStopbandOctaves, outputConfigs_, sampleRate_);
    NativeBmwDsp::configureMeasurementGenerator(measGen_, p_, sampleRate_);
    rebuildAllPass();
    virtual_.rebuild(p_.virtualStage, sampleRate_, true);
    inputDcL_.clear();
    inputDcR_.clear();
    for (auto& out : outputs_) {
        out.clearState();
    }
    limiter_.clear();
    for (auto& line : bypassLatency_) {
        line.clear();
    }
    mbc_.resetState();
    for (BusLimiter* b : {&busLimLow_, &busLimMid_, &busLimHigh_}) {
        b->clear();
        b->zeroMeter();
    }
    updateDelays();
    for (auto& out : outputs_) {
        out.delay.snap();  // a full rebuild applies delays at once, no crossfade
    }
    // A full rebuild starts every filter clean; the PEQ rebuild below would otherwise carry the
    // banks' state over (see installPeq).
    inputPeq_.clear();
    lowPeq_.clear();
    midPeq_.clear();
    highPeq_.clear();
    // Runs from the constructor or from setSampleRate() (already holding stateMutex_).
    rebuildPeqLocked();
}
