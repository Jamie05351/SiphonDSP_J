package app.siphondsp.service

import app.siphondsp.model.debug.RootlessPipelineRuntimeSnapshot

/**
 * Turns the rootless pipeline's runtime snapshot into the one-word badge shown in the DSP
 * toolbar's status strip. Pure function -- the colours and dialog live in the view.
 *
 * The important distinction is *silent* versus *unprocessed*: [Level.BAD] ("NO AUDIO") means the
 * pipeline is stuck and captured audio is not coming back out, while [Level.WARN] "DSP off" means
 * the engine isn't running so audio simply isn't being processed.
 */
internal object DspHealthBadge {
    enum class Level { OK, IDLE, WARN, BAD }

    data class Badge(val level: Level, val label: String, val detail: String)

    fun evaluate(snapshot: RootlessPipelineRuntimeSnapshot?): Badge {
        if (snapshot == null) {
            return Badge(Level.WARN, "DSP off", "The audio engine is not running, so audio is not being processed.")
        }
        val excludedNote = if (snapshot.excludedPlaying.isEmpty()) null else
            "${snapshot.excludedPlaying.joinToString()} is playing but is excluded, so J_DSP isn't " +
                "processing it. Remove it from ⋮ → Excluded apps to process it."
        // With only an excluded app playing there is no session to process, so the pipeline reads
        // as idle (or healthy) while music plays -- the case that looked like "nothing is playing".
        // Then the exclusion is the headline. A real fault, or other audio being processed, keeps
        // its own badge, with the exclusion added to the detail.
        val state = snapshot.pipelineHealthState
        val quiet = state == AudioPipelineHealth.State.IDLE_EXPECTED.name ||
            state == AudioPipelineHealth.State.HEALTHY.name
        if (excludedNote != null && snapshot.playbackSessions == 0 && quiet) {
            return Badge(Level.WARN, "Excluded app", excludedNote)
        }
        val badge = health(snapshot)
        return if (excludedNote == null) badge else badge.copy(detail = "${badge.detail} $excludedNote")
    }

    private fun health(snapshot: RootlessPipelineRuntimeSnapshot): Badge {
        val reason = snapshot.pipelineHealthReason.orEmpty()
        return when (snapshot.pipelineHealthState) {
            AudioPipelineHealth.State.HEALTHY.name ->
                Badge(Level.OK, "DSP ok", "Audio is flowing through the DSP.")
            AudioPipelineHealth.State.IDLE_EXPECTED.name ->
                Badge(Level.IDLE, "DSP idle", "Nothing is playing right now ($reason).")
            AudioPipelineHealth.State.STARTING.name ->
                Badge(Level.WARN, "DSP starting", "Waiting for the first audio ($reason).")
            AudioPipelineHealth.State.RECREATING.name ->
                Badge(Level.WARN, "DSP recovering", "Rebuilding the audio path ($reason).")
            AudioPipelineHealth.State.STOPPING.name ->
                Badge(Level.WARN, "DSP stopping", "The audio engine is shutting down.")
            AudioPipelineHealth.State.STALLED.name, AudioPipelineHealth.State.FAILED.name ->
                Badge(Level.BAD, "NO AUDIO", "The audio path is stuck ($reason).")
            // No evaluation yet: the watchdog's first pass runs a couple of seconds after start.
            null -> Badge(Level.WARN, "DSP starting", "The audio engine is starting.")
            else -> Badge(Level.WARN, "DSP ${snapshot.pipelineHealthState}", reason)
        }
    }
}
