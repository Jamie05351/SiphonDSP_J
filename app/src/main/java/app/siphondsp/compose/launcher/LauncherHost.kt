package app.siphondsp.compose.launcher

/**
 * MainActivity's line to the launcher home screen (head unit): the iDrive knob and the Home button
 * arrive at the activity, and reach the home screen through this. HomeRoute fills the two handlers
 * in while it is the resumed screen and clears them when it isn't, so neither does anything with a
 * workspace, Settings or another app in front.
 */
class LauncherHost {
    internal var knobFocus: (() -> Boolean)? = null
    // Set by HomeRoute on resume, cleared on pause. A Home press that lands while it is null (home
    // paused behind another app or a workspace being popped) is queued and delivered as soon as the
    // handler is registered, so Home always ends on the plain home screen.
    internal var homePressed: (() -> Unit)? = null
        set(value) {
            field = value
            if (value != null && homePending) {
                homePending = false
                value()
            }
        }
    private var homePending = false

    /**
     * A knob (D-pad) key went down: true when the home screen took it to bring focus onto itself,
     * so the key isn't also used for anything else.
     */
    fun enterKnobFocus(): Boolean = knobFocus?.invoke() ?: false

    /**
     * The Home button: back to the plain home screen -- now if home is the resumed screen, else as
     * soon as it resumes.
     */
    fun onHomePressed() {
        val handler = homePressed
        if (handler != null) handler() else homePending = true
    }
}
