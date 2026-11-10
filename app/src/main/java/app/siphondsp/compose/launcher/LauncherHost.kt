package app.siphondsp.compose.launcher

/**
 * MainActivity's line to the launcher home screen (head unit): the iDrive knob and the Home button
 * arrive at the activity, and reach the home screen through this. HomeRoute fills the two handlers
 * in while it is the resumed screen and clears them when it isn't, so neither does anything with a
 * workspace, Settings or another app in front.
 */
class LauncherHost {
    internal var knobFocus: (() -> Boolean)? = null
    internal var homePressed: (() -> Unit)? = null

    /**
     * A knob (D-pad) key went down: true when the home screen took it to bring focus onto itself,
     * so the key isn't also used for anything else.
     */
    fun enterKnobFocus(): Boolean = knobFocus?.invoke() ?: false

    /** The Home button while home is already showing: back to the plain home screen. */
    fun onHomePressed() {
        homePressed?.invoke()
    }
}
