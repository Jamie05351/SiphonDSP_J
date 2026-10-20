package app.siphondsp.fragment

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import app.siphondsp.R
import app.siphondsp.model.ApoImportResult
import app.siphondsp.model.BmwPeqState
import app.siphondsp.model.ParametricEqBandList
import app.siphondsp.model.ParametricEqChannel
import app.siphondsp.utils.extensions.ContextExtensions.toast
import app.siphondsp.utils.storage.StorageUtils
import timber.log.Timber

/**
 * The REW/APO plain-text import/export flow, lifted verbatim out of [ParametricEqualizerFragment]:
 * read one or more picked files, route each by filename to a (bank, channel), preview the plan in
 * a dialog, and merge on confirm via [PeqBandEditor]; plus the single-bank text export.
 *
 * The two `registerForActivityResult` launchers stay on the fragment (they must be registered
 * before it is created); their callbacks now just forward the Uri(s) here.
 */
class PeqApoImport(private val host: Host) {

    interface Host {
        val context: Context
        val activeScope: PeqScope
        val state: BmwPeqState
        fun applyCandidate(candidate: BmwPeqState, source: String): Boolean
    }

    private data class ParsedApoFile(
        val name: String,
        val bands: ParametricEqBandList,
        val result: ApoImportResult,
    )

    fun handleApoImport(uris: List<Uri>) {
        val parsed = try {
            uris.mapNotNull { uri ->
                val name = StorageUtils.queryName(host.context, uri) ?: "file"
                val text = host.context.contentResolver.openInputStream(uri)
                    ?.use(ApoImportRouter::readImportText) ?: return@mapNotNull null
                val bands = ParametricEqBandList()
                ParsedApoFile(name, bands, bands.fromApoString(text))
            }
        } catch (error: Exception) {
            Timber.e(error, "Failed to read import files")
            host.context.toast(R.string.peq_import_error)
            return
        }
        if (parsed.isEmpty()) return

        val routed = LinkedHashMap<Pair<PeqScope, ParametricEqChannel>, RoutedApoImport>()
        val unmatched = mutableListOf<String>()
        parsed.forEach { file ->
            val route = ApoImportRouter.routeApoFileName(file.name)
            if (route == null) {
                unmatched += file.name
            } else {
                // last file wins if two map to the same bank+channel
                routed[route] = RoutedApoImport(
                    file.name, route.first, route.second, file.bands,
                    file.result.preampDb, file.result.skippedFilters, file.result.preampInFileDb,
                )
            }
        }

        if (routed.isEmpty()) {
            if (parsed.size == 1) {
                promptSingleScopeImport(parsed[0].bands, parsed[0].result)
            } else {
                host.context.toast(
                    "No filenames recognised. Name them like input.txt / low_left.txt / mid_right.txt"
                )
            }
            return
        }

        val channelSuffix = { c: ParametricEqChannel ->
            when (c) {
                ParametricEqChannel.LEFT -> " · L"
                ParametricEqChannel.RIGHT -> " · R"
                ParametricEqChannel.LEFT_RIGHT -> ""
            }
        }
        val lines = routed.values.joinToString("\n") {
            "${it.name} → ${it.scope.label}${channelSuffix(it.channel)}: ${it.bands.size}"
        }
        val skippedNote = if (unmatched.isEmpty()) "" else
            "\n\nNot recognised, ignored: ${unmatched.joinToString(", ")}"
        // Only the Input Correction file's preamp is used (PeqBandEditor.importRoutedApo).
        val preampNote = routed.values.firstOrNull { it.scope == PeqScope.FULL }?.let {
            val summary = ApoImportRouter.preampSummary(it.preampDb, it.preampInFileDb)
            "\n\n" + if (summary != null) "$summary, applied on Replace, ignored on Append." else
                "${ApoImportRouter.NO_PREAMP_ON_REPLACE}."
        } ?: ""

        AlertDialog.Builder(host.context)
            .setTitle("Import REW filter set")
            .setMessage("$lines$skippedNote$preampNote\n\nReplace clears each affected bank first; Append adds to it.")
            .setPositiveButton("Replace") { _, _ -> applyApoImport(routed.values.toList(), append = false) }
            .setNeutralButton("Append") { _, _ -> applyApoImport(routed.values.toList(), append = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptSingleScopeImport(imported: ParametricEqBandList, result: ApoImportResult) {
        val detail = if (result.skippedFilters > 0) {
            "${result.skippedFilters} malformed or unsupported lines will be skipped."
        } else {
            "All ${imported.size} parsed filters are supported."
        }
        // The preamp belongs to Pre EQ, so only a Full-bank import touches it.
        val preampNote = if (host.activeScope != PeqScope.FULL) "" else {
            val summary = ApoImportRouter.preampSummary(result.preampDb, result.preampInFileDb)
            "\n\n" + if (summary != null) "$summary, applied on Replace, ignored on Append." else
                "${ApoImportRouter.NO_PREAMP_ON_REPLACE}."
        }
        AlertDialog.Builder(host.context)
            .setTitle("Import into ${host.activeScope.label}")
            .setMessage("$detail Choose how to apply the previewed filters.$preampNote")
            .setPositiveButton("Replace") { _, _ ->
                applyScopeImport(imported, result.preampDb.toFloat(), append = false, result.skippedFilters)
            }
            .setNeutralButton("Append") { _, _ ->
                applyScopeImport(imported, result.preampDb.toFloat(), append = true, result.skippedFilters)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyApoImport(routed: List<RoutedApoImport>, append: Boolean) {
        when (val result = PeqBandEditor.importRoutedApo(host.state, routed, append)) {
            is PeqBandEditResult.Overflow -> host.context.toast(PeqBandEditor.overflowToast(result))
            is PeqBandEditResult.Changed ->
                if (host.applyCandidate(result.candidate, result.undoSource)) {
                    val total = routed.sumOf { it.bands.size }
                    val skipped = routed.sumOf { it.skipped }
                    val bankCount = routed.map { it.scope }.distinct().size
                    val base = "Imported $total filters into $bankCount bank(s)"
                    val withSkipped = if (skipped > 0) "$base ($skipped lines skipped)" else base
                    host.context.toast(
                        if (routed.any { it.scope == PeqScope.FULL }) withPreamp(withSkipped, result.candidate, append)
                        else withSkipped
                    )
                }
            else -> Unit
        }
    }

    private fun applyScopeImport(
        imported: ParametricEqBandList,
        importedPreamp: Float,
        append: Boolean,
        skippedFilters: Int,
    ) {
        when (
            val result = PeqBandEditor.importIntoScope(
                host.state, host.activeScope, imported, importedPreamp, append,
            )
        ) {
            is PeqBandEditResult.Overflow -> host.context.toast(PeqBandEditor.overflowToast(result))
            is PeqBandEditResult.Changed ->
                if (host.applyCandidate(result.candidate, result.undoSource)) {
                    val message = host.context.getString(R.string.peq_import_success, imported.size)
                    val withSkipped =
                        if (skippedFilters > 0) "$message ($skippedFilters malformed or unsupported lines skipped)"
                        else message
                    host.context.toast(
                        if (host.activeScope == PeqScope.FULL) withPreamp(withSkipped, result.candidate, append)
                        else withSkipped
                    )
                }
            else -> Unit
        }
    }

    /** [message] plus the preamp now running, when a Full-bank Replace may have changed it. */
    private fun withPreamp(message: String, applied: BmwPeqState, append: Boolean): String =
        if (append) message else "$message. Preamp now ${ApoImportRouter.formatDb(applied.preampDb.toDouble())}"

    /** Body of the fragment's single-bank text-export launcher. */
    fun exportTo(uri: Uri) {
        try {
            val preamp = if (host.activeScope == PeqScope.FULL) host.state.preampDb.toDouble() else 0.0
            val apoString = PeqBandEditor.bandsFor(host.state, host.activeScope).toApoString(preamp)
            host.context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(apoString) }
            host.context.toast(R.string.peq_export_success)
        } catch (error: Exception) {
            Timber.e(error, "Failed to export PEQ file")
            host.context.toast("Export failed: ${error.message}")
        }
    }
}
