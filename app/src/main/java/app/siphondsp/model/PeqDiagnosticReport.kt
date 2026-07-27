package app.siphondsp.model

import android.content.Context
import android.os.Build
import app.siphondsp.BuildConfig
import java.util.Date

object PeqDiagnosticReport {
    fun create(
        context: Context,
        state: BmwPeqState,
        sampleRate: Float?,
        serviceActive: Boolean,
        nativeHandleReady: Boolean?,
    ): String {
        val metrics = context.resources.displayMetrics
        val restore = BmwPeqState.diagnosticMetadata(context)
        return buildString {
            appendLine("SiphonDSP three-bank PEQ diagnostic")
            appendLine("Generated: ${Date()}")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Commit: ${BuildConfig.COMMIT_SHA}")
            appendLine("Build type: ${BuildConfig.BUILD_TYPE}; preview=${BuildConfig.PREVIEW}")
            appendLine("Build time: ${BuildConfig.BUILD_TIME}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Architectures: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Screen: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi} dpi")
            appendLine("DSP service active: $serviceActive")
            appendLine("Native DSP handle ready: ${nativeHandleReady ?: "unavailable"}")
            appendLine("Sample rate: ${sampleRate?.let { "${it.toInt()} Hz" } ?: "unavailable"}")
            appendLine("PEQ state format: ${BmwPeqState.VERSION}")
            appendLine("PEQ enabled: ${state.enabled}")
            appendLine("Full Range filters: ${state.fullRangeBands.size}")
            appendLine("Low Band filters: ${state.lowBandBands.size}")
            appendLine("Mid Band filters: ${state.midBandBands.size}")
            appendLine("Last restore: ${restore.result}")
            appendLine("Fallback used: ${restore.fallbackUsed}")
            appendLine("Last restore error: ${restore.error ?: "none"}")
            appendLine("Last backup restore: ${restore.backupRestoreResult}")
            appendLine("Last-known-good timestamp: ${restore.lastKnownGoodTimestamp.takeIf { it > 0 } ?: "none"}")
            appendLine()
            appendLine("Privacy: no audio, filter values, imported file contents, paths, usernames, or account identifiers are included.")
        }
    }
}
