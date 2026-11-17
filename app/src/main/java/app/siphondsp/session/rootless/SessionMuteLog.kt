package app.siphondsp.session.rootless

/**
 * What happened to each app session's mute effect, for Native DSP Truth.
 *
 * The rootless DSP never stops an app's own audio reaching the speakers: it records a copy and
 * mutes the original with an effect on the app's audio session. Until that effect is attached,
 * the app plays straight out, unprocessed, at the head unit's own (full) volume -- a burst at
 * the start of playback that the audio loop never sees, so the stream-start log can't show it.
 * This log shows it instead: each time an app's session starts, whether its mute was still
 * attached from before (no burst), or had to be made new, and how long after the system's
 * playback change that took (about how long the burst lasted).
 *
 * Written from the main thread and, for an exclusions change, the audio worker; read by the
 * screen. Synchronized: none of it is on the audio path.
 */
object SessionMuteLog {
    const val EVENTS = 16
    private const val SEEN_IDS = 64

    enum class Kind {
        /** A session started and its mute was still attached from before: muted from the first sample. */
        KEPT,
        /** A session started and a new mute was attached. */
        NEW,
        /** A session id came back for a different app: the old mute was released, a new one attached. */
        REPLACED,
        /** A session started and no mute could be attached. */
        FAILED,
        /** A session went quiet; its mute stays attached for when it plays again. */
        HELD,
        /** A held mute was released (see [Event.detail]). */
        RELEASED,
    }

    data class Event(
        val atMs: Long,
        val sessionId: Int,
        val packageName: String,
        val kind: Kind,
        /** From the first playback change not yet answered by a session dump to this event; null if unknown. */
        val afterPlaybackChangeMs: Long?,
        /** Whether this session id had already started playing before (since the app was started). */
        val seenBefore: Boolean,
        /** Mutes held for quiet sessions, after this event. */
        val held: Int,
        val detail: String = "",
    ) {
        /** A session that started playing (rather than going quiet or being released). */
        val isStart: Boolean get() = kind == Kind.KEPT || kind == Kind.NEW || kind == Kind.REPLACED || kind == Kind.FAILED
    }

    private val events = ArrayDeque<Event>(EVENTS)
    private val seen = LinkedHashSet<Int>()
    private var pendingSinceMs = 0L

    /** The system reported a playback change at [nowMs]: a session may be about to start. */
    @Synchronized
    fun notePlaybackChange(nowMs: Long) {
        if (pendingSinceMs == 0L) pendingSinceMs = nowMs
    }

    /**
     * A session dump that began at [dumpStartedAtMs] has been applied. A playback change from
     * before then is answered by it; one that came in during the dump waits for the next.
     */
    @Synchronized
    fun noteDumpApplied(dumpStartedAtMs: Long) {
        if (pendingSinceMs != 0L && pendingSinceMs <= dumpStartedAtMs) pendingSinceMs = 0L
    }

    @Synchronized
    fun record(nowMs: Long, sessionId: Int, packageName: String, kind: Kind, held: Int, detail: String = "") {
        val seenBefore = seen.contains(sessionId)
        val event = Event(
            atMs = nowMs,
            sessionId = sessionId,
            packageName = packageName,
            kind = kind,
            afterPlaybackChangeMs = if (kind == Kind.HELD || kind == Kind.RELEASED || pendingSinceMs == 0L) null
                else (nowMs - pendingSinceMs).coerceAtLeast(0L),
            seenBefore = seenBefore,
            held = held,
            detail = detail,
        )
        if (events.size >= EVENTS) events.removeFirst()
        events.addLast(event)
        if (event.isStart) {
            seen.remove(sessionId)
            seen.add(sessionId)
            while (seen.size > SEEN_IDS) seen.remove(seen.first())
        }
    }

    /** Newest first. */
    @Synchronized
    fun snapshot(): List<Event> = events.reversed()

    @Synchronized
    fun clear() {
        events.clear()
        seen.clear()
        pendingSinceMs = 0L
    }
}
