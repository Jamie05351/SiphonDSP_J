package app.siphondsp.compose.state

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import app.siphondsp.fragment.PeqGraphPreferences
import app.siphondsp.view.GraphDbRange

/**
 * The one dB range every frequency-response graph draws with (see [GraphDbRange]): picked by the
 * user from any graph, saved in [PeqGraphPreferences], and observed by every graph on screen, so
 * a change on one screen holds on the others and after a restart.
 */
object GraphDbRangeState {
    // Created holding the saved range on first use, so the first read in composition is never a
    // state write.
    @Volatile private var state: MutableState<GraphDbRange>? = null

    /** The current range. */
    @Composable
    fun current(): GraphDbRange = stateFor(LocalContext.current).value

    /**
     * The current range, from anywhere. Read inside a draw lambda it is observed by that draw
     * phase, so a change redraws the canvas; read in a gesture handler it is the window on screen.
     */
    fun read(context: Context): GraphDbRange = stateFor(context).value

    fun set(context: Context, range: GraphDbRange) {
        stateFor(context).value = range
        PeqGraphPreferences(context).dbRange = range
    }

    private fun stateFor(context: Context): MutableState<GraphDbRange> =
        state ?: synchronized(this) {
            state ?: mutableStateOf(PeqGraphPreferences(context).dbRange).also { state = it }
        }
}
