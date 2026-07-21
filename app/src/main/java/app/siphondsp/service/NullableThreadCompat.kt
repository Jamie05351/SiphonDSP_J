package app.siphondsp.service

/**
 * Nullable receiver helpers for the worker reference captured from the
 * synchronized recorder lifecycle block.
 */
internal val Thread?.isAlive: Boolean
    get() = this?.let { thread -> thread.isAlive } ?: false

internal fun Thread?.interrupt() {
    this?.let { thread -> thread.interrupt() }
}

@Throws(InterruptedException::class)
internal fun Thread?.join(millis: Long) {
    this?.let { thread -> thread.join(millis) }
}
