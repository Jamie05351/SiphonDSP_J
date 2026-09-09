package app.siphondsp.model

import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.core.content.getSystemService
import app.siphondsp.BuildConfig
import app.siphondsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import app.siphondsp.utils.isRootless
import java.util.Date
import java.util.TimeZone

/**
 * A structural, privacy-safe snapshot of the DSP setup for a bug report. Deliberately carries
 * **no** audio, no filter/crossover/compressor *values*, no file contents, paths, usernames or
 * account identifiers — only shapes, counts, flags, health and timestamps (roadmap Phase 10g §8).
 */
object PeqDiagnosticReport {
    fun create(
        context: Context,
        state: BmwPeqState,
        systemValues: FloatArray,
        sampleRate: Float?,
        serviceActive: Boolean,
        nativeHandleReady: Boolean?,
        channelDisplay: String,
        showIndividualFilters: Boolean,
    ): String {
        val metrics = context.resources.displayMetrics
        val restore = BmwPeqState.diagnosticMetadata(context)
        val hasConfig = systemValues.size == NativeBmwDspValues.SIZE
        fun flag(index: Int): String =
            if (!hasConfig) "?" else if (systemValues[index] >= 0.5f) "on" else "off"
        fun bypassFlag(index: Int): String =
            if (!hasConfig) "?" else if (systemValues[index] >= 0.5f) "bypassed" else "active"

        return buildString {
            appendLine("SiphonDSP DSP diagnostic")
            appendLine("Generated: ${Date()}")
            appendLine("Timezone offset: ${tzOffset()}")
            appendLine()

            appendLine("== Build ==")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Commit: ${BuildConfig.COMMIT_SHA}")
            appendLine("Build type: ${BuildConfig.BUILD_TYPE}; preview=${BuildConfig.PREVIEW}")
            appendLine("Build time: ${BuildConfig.BUILD_TIME}")
            appendLine()

            appendLine("== Device ==")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Architectures: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Screen: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi} dpi")
            appendDeviceHealth(context)
            appendLine()

            appendLine("== Engine ==")
            appendLine("Setup mode: ${if (isRootless()) "rootless" else "root"}")
            appendLine("PROJECT_MEDIA app-op: ${if (context.hasProjectMediaAppOp()) "granted" else "not granted"}")
            appendLine("DSP service active: $serviceActive")
            appendLine("Native DSP handle ready: ${nativeHandleReady ?: "unavailable"}")
            appendLine("Sample rate: ${sampleRate?.let { "${it.toInt()} Hz" } ?: "unavailable"}")
            appendLine("Master enable: ${flag(NativeBmwDspValues.INDEX_ENABLED)}")
            appendLine("Crossover LPF/HPF: ${bypassFlag(NativeBmwDspValues.INDEX_LPF_PASS)} / ${bypassFlag(NativeBmwDspValues.INDEX_HPF_PASS)}")
            appendLine("Subsonic: ${flag(NativeBmwDspValues.INDEX_SUBSONIC_ENABLED)}")
            appendLine("Tilt: ${flag(NativeBmwDspValues.INDEX_TILT_ENABLED)}")
            appendLine("Mono bass: ${flag(NativeBmwDspValues.INDEX_MONO_BASS_ENABLED)}")
            appendLine("All-pass: ${flag(NativeBmwDspValues.INDEX_ALL_PASS)}")
            appendLine("MBC: ${flag(NativeBmwDspValues.INDEX_MBC_ENABLED)}")
            appendLine(
                "Bus limiters low/mid: ${flag(NativeBmwDspValues.INDEX_BUS_LIMITER_LOW_ENABLED)} / " +
                    "${flag(NativeBmwDspValues.INDEX_BUS_LIMITER_MID_ENABLED)}",
            )
            appendLine("Master limiter: ${flag(NativeBmwDspValues.INDEX_MASTER_LIMITER_ENABLED)}")
            appendLine(
                "Legacy per-output compressors low/mid: ${flag(NativeBmwDspValues.INDEX_LOW_COMPRESSOR_ENABLED)} / " +
                    "${flag(NativeBmwDspValues.INDEX_MID_COMPRESSOR_ENABLED)}",
            )
            appendLine()

            appendLine("== PEQ ==")
            appendLine("State format: ${BmwPeqState.VERSION}")
            appendLine("PEQ enabled: ${state.enabled}")
            appendBankSummary("Full Range", state.fullRangeBands)
            appendBankSummary("Low Band", state.lowBandBands)
            appendBankSummary("Mid Band", state.midBandBands)
            val validation = state.validate(sampleRate ?: 48_000f)
            appendLine("Current-state validation: ${validation ?: "passes"}")
            appendLine("Graph display: channel=$channelDisplay, individual filters=$showIndividualFilters")
            appendLine()

            appendLine("== Persistence ==")
            appendLine("Last restore: ${restore.result}")
            appendLine("Fallback used: ${restore.fallbackUsed}")
            appendLine("Last restore error: ${restore.error ?: "none"}")
            appendLine("Last backup restore: ${restore.backupRestoreResult}")
            appendLine("Last-known-good timestamp: ${restore.lastKnownGoodTimestamp.takeIf { it > 0 } ?: "none"}")
            appendLine()

            appendLine(
                "Privacy: no audio, filter / crossover / compressor values, imported file contents, " +
                    "paths, usernames, or account identifiers are included.",
            )
        }
    }

    private fun StringBuilder.appendBankSummary(name: String, bands: ParametricEqBandList) {
        val atCapacity = if (bands.size >= BmwPeqState.MAX_BANDS) " (AT CAPACITY)" else ""
        appendLine("$name: ${bands.size}/${BmwPeqState.MAX_BANDS} filters$atCapacity")
        if (bands.isEmpty()) return
        val types = bands.groupingBy { it.filterType }.eachCount()
        val channels = bands.groupingBy { it.channel }.eachCount()
        appendLine("  types: " + ParametricEqFilterType.entries.mapNotNull { t ->
            types[t]?.let { "${t.name.lowercase()}=$it" }
        }.joinToString(", "))
        appendLine("  channels: " + ParametricEqChannel.entries.mapNotNull { c ->
            channels[c]?.let { "${c.name.lowercase()}=$it" }
        }.joinToString(", "))
    }

    private fun StringBuilder.appendDeviceHealth(context: Context) {
        val am = context.getSystemService<android.app.ActivityManager>()
        val lowRam = am?.isLowRamDevice ?: false
        val memInfo = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val mb = 1024L * 1024L
        appendLine("Low-RAM device: $lowRam; system low-memory: ${memInfo.lowMemory}")
        appendLine("App heap: ${(rt.totalMemory() - rt.freeMemory()) / mb} / ${rt.maxMemory() / mb} MB used")
        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        if (stat != null) {
            appendLine("Internal storage free: ${stat.availableBytes / mb} / ${stat.totalBytes / mb} MB")
        }
    }

    private fun tzOffset(): String {
        val offsetMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (offsetMinutes >= 0) "+" else "-"
        val abs = kotlin.math.abs(offsetMinutes)
        return "UTC%s%02d:%02d".format(sign, abs / 60, abs % 60)
    }
}
