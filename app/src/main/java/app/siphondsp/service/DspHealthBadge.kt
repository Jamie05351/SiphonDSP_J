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
