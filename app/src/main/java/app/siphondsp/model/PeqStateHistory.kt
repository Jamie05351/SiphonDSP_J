package app.siphondsp.model

/**
 * Bounded history of complete, successfully committed PEQ states.
 * Drafts never enter this class and the caller advances the cursor only after
 * the native full-state apply succeeds.
 */
class PeqStateHistory(private val capacity: Int = 20) {
    private val states = mutableListOf<BmwPeqState>()
    private var cursor = -1

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor >= 0 && cursor < states.lastIndex
    val size: Int get() = states.size

    fun reset(initial: BmwPeqState) {
        states.clear()
        states.add(initial.deepCopy())
        cursor = 0
    }

    fun commit(state: BmwPeqState) {
        if (cursor < states.lastIndex) states.subList(cursor + 1, states.size).clear()
        states.add(state.deepCopy())
        while (states.size > capacity) states.removeAt(0)
        cursor = states.lastIndex
    }

    fun peekUndo(): BmwPeqState? = states.getOrNull(cursor - 1)?.deepCopy()
    fun peekRedo(): BmwPeqState? = states.getOrNull(cursor + 1)?.deepCopy()

    fun confirmUndo() {
        check(canUndo)
        cursor--
    }

    fun confirmRedo() {
        check(canRedo)
        cursor++
    }
}
