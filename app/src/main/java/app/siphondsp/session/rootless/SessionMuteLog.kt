package app.siphondsp.session.rootless

/**
 * What happened to each app session's mute, for Native DSP Truth.
 *
 * The rootless DSP never stops an app's own audio reaching the speakers: it records a copy and
 * mutes the original with an effect on the app's audio session. Until that effect is attached,
 * the app plays straight out, unprocessed, at the head unit's own (full) volume -- a burst at
 * the start of playback that the audio loop never sees, so the stream-start log can't show it.
 * This log shows it instead: for every attach, what triggered it and the unmuted window -- ms
 * from the player starting (the first unanswered "player started" from the playback callback)
 * to the mute being attached. A mute attached before the player started reads 0.
 *
 * Written from the main thread and, for an exclusions change, the audio worker; read by the
 * screen. Synchronized: none of it is on the audio path.
 */
object SessionMuteLog {
    const val EVENTS = 16
    private const val SEEN_IDS = 64
    private const val MAX_PENDING_START_AGE_MS = 3_000L
    private const val MAX_PENDING_STARTS = 16

    /** The unmuted window this aims for. */
    const val TARGET_UNMUTED_MS = 20L
    /** Above this the Truth screen shows the window in yellow. */
    const val WARN_UNMUTED_MS = 50L

    enum class Kind {
        /** A session started and its mute was already attached (held from before, or armed early). */
        KEPT,
        /** A session started and a new mute was attached only once a session dump found it. */
        NEW,
        /** A session id came back for a different app: the old mute was released, a new one attached. */
        REPLACED,
        /** A session started and no mute could be attached. */
        FAILED,
        /**
         * A mute was attached ahead of any dump: on the session-open broadcast, or on the playback
         * callback reporting a player start (see [Trigger]). Not a start itself; the dump that
         * confirms the session logs it as [KEPT].
         */
        ARMED,
        /** Early mute creation failed; no mute was attached and no playback start is inferred. */
        ARM_FAILED,
        /** A session went quiet; its mute stays attached for when it plays again. */
        HELD,
        /** A held mute was released (see [Event.detail]). */
        RELEASED,
    }

    /** What attached the mute a start used. */
    enum class Trigger(val label: String) {
        /** Still attached from the session's last play. */
        HELD("held"),
        /** Attached on the playback callback reporting a player start. */
        CALLBACK("playback callback"),
        /** Attached on the app's audio-effect session-open broadcast. */
        BROADCAST("session-open broadcast"),
        /** Attached only once a session dump (polling) found the session. */
        DUMP("dump"),
    }

    data class Event(
        val atMs: Long,
        val sessionId: Int,
        val packageName: String,
        val kind: Kind,
        /**
         * For a start or an arm: ms from the player starting to its mute being attached, 0 when
         * the mute was there first; null if no player start was seen to time it from.
         */
        val unmutedMs: Long?,
        /** For a start or an arm: what attached the mute. */
        val trigger: Trigger?,
        /** Whether this session id had already started playing before (since the app was started). */
        val seenBefore: Boolean,
        /** Mutes held for quiet sessions, after this event. */
        val held: Int,
        val detail: String = "",
        /** For a mute this attached (or failed to): where the time went. */
        val timing: Timing? = null,
    ) {
        /** A session that started playing (rather than going quiet or being released). */
        val isStart: Boolean get() = kind == Kind.KEPT || kind == Kind.NEW || kind == Kind.REPLACED || kind == Kind.FAILED
    }

    /** A lookup in progress: what found the sessions being applied, and when it ran. */
    data class Lookup(
        /** "playback config" (read straight off the callback's configurations) or "dump". */
        val path: String,
        val startedAtMs: Long,
        val endedAtMs: Long,
        /** e.g. which dump method answered and how long each dump took. */
        val detail: String = "",
    )

    /** Where one new session's unmuted window went. */
    data class Timing(
        val path: String,
        /**
         * Playback-start callback to the lookup starting (waiting on a dump in flight, a
         * main-thread hop); null with no start to time from. Never negative: 0 with
         * [lookupInFlight] when the lookup had already begun before the start.
         */
        val callbackToLookupMs: Long?,
        /** The lookup was already running when the player started (a dump in flight caught it). */
        val lookupInFlight: Boolean = false,
        /** The lookup itself (the dump, or reading the configurations). */
        val lookupMs: Long,
        /** Lookup done to the mute attached (applying it, making the effect); null if none was. */
        val lookupToMuteMs: Long?,
        val detail: String,
    )

    /** What the fast path (reading session ids off the playback callback's configurations) saw last. */
    data class ConfigLookup(
        val atMs: Long,
        /** Players that had just started. */
        val started: Int,
        /** Of those, how many had a session id and uid visible to us (not anonymized). */
        val visible: Int,
        val micros: Long,
        val detail: String,
    )

    /** The last capture-allowlist check: run in the background, after the mutes it verifies. */
    data class AllowlistCheck(
        val atMs: Long,
        val reason: String,
        /** Queued behind another check, and the hop back to the main thread. */
        val waitedMs: Long,
        val tookMs: Long,
        val ok: Boolean,
    )

    @Volatile var lastAllowlistCheck: AllowlistCheck? = null
        private set

    fun noteAllowlistCheck(check: AllowlistCheck) { lastAllowlistCheck = check }

    private val events = ArrayDeque<Event>(EVENTS)
    private var lookup: Lookup? = null
    @Volatile var lastConfigLookup: ConfigLookup? = null
        private set
    private val seen = LinkedHashSet<Int>()
    // Player starts not yet answered by a dump begun after them, oldest first. Every one is kept,
    // not just the oldest: a dump begun between two starts answers only the first, and the
    // second still needs a dump (and its own window timed from its own start).
    private val pendingStarts = ArrayDeque<Long>()
    private val pendingSinceMs: Long get() = pendingStarts.firstOrNull() ?: 0L

    /**
     * The playback callback reported a player starting at [nowMs] (or a change it couldn't
     * read): a session may be playing unmuted from now until its mute is attached.
     */
    @Synchronized
    fun notePlaybackChange(nowMs: Long) {
        while (pendingStarts.isNotEmpty() && nowMs - pendingStarts.first() > MAX_PENDING_START_AGE_MS) {
            pendingStarts.removeFirst()
        }
        pendingStarts.addLast(nowMs)
        while (pendingStarts.size > MAX_PENDING_STARTS) pendingStarts.removeFirst()
    }

    /**
     * A session dump that began at [dumpStartedAtMs] has been applied. A start from before then
     * is answered by it; one that came in during the dump waits for the next.
     */
    @Synchronized
    fun noteDumpApplied(dumpStartedAtMs: Long) {
        while (pendingStarts.isNotEmpty() && pendingStarts.first() <= dumpStartedAtMs) pendingStarts.removeFirst()
    }

    /** A player start not yet answered by a dump begun after it, as of [nowMs]. */
    @Synchronized
    fun hasPendingStart(nowMs: Long): Boolean =
        pendingStarts.any { nowMs - it <= MAX_PENDING_START_AGE_MS }

    /** Mutes recorded until [endLookup] were attached by [lookup]. */
    @Synchronized
    fun beginLookup(lookup: Lookup) { this.lookup = lookup }

    @Synchronized
    fun endLookup() { lookup = null }

    fun noteConfigLookup(result: ConfigLookup) { lastConfigLookup = result }

    /** Whether [sessionId] has started playing before (since the app was started). */
    @Synchronized
    fun wasSeen(sessionId: Int): Boolean = seen.contains(sessionId)

    /**
     * [attachedAtMs]: when the mute this event is about was attached; [nowMs] if omitted. For a
     * start that found its mute already there (KEPT), the time that mute was made or armed.
     */
    @Synchronized
    fun record(
        nowMs: Long,
        sessionId: Int,
        packageName: String,
        kind: Kind,
        held: Int,
        detail: String = "",
        trigger: Trigger? = null,
        attachedAtMs: Long? = null,
    ) {
        val seenBefore = seen.contains(sessionId)
        val timed = kind != Kind.HELD && kind != Kind.RELEASED
        val staleStart = pendingSinceMs != 0L && nowMs - pendingSinceMs > MAX_PENDING_START_AGE_MS
        val event = Event(
            atMs = nowMs,
            sessionId = sessionId,
            packageName = packageName,
            kind = kind,
            unmutedMs = when {
                !timed || kind == Kind.FAILED || kind == Kind.ARM_FAILED -> null
                trigger == Trigger.HELD -> 0L
                staleStart -> null
                pendingSinceMs == 0L -> if (attachedAtMs != null) 0L else null
                else -> ((attachedAtMs ?: nowMs) - pendingSinceMs).coerceAtLeast(0L)
            },
            trigger = if (timed && kind != Kind.FAILED) trigger else null,
            seenBefore = seenBefore,
            held = held,
            detail = detail,
            timing = lookup?.takeIf { timed && kind != Kind.KEPT }?.let { l ->
                val failed = kind == Kind.FAILED || kind == Kind.ARM_FAILED
                val sinceStart = if (pendingSinceMs != 0L && !staleStart) l.startedAtMs - pendingSinceMs else null
                Timing(
                    path = l.path,
                    callbackToLookupMs = sinceStart?.coerceAtLeast(0L),
                    lookupInFlight = sinceStart != null && sinceStart < 0L,
                    lookupMs = l.endedAtMs - l.startedAtMs,
                    lookupToMuteMs = if (failed) null else ((attachedAtMs ?: nowMs) - l.endedAtMs).coerceAtLeast(0L),
                    detail = l.detail,
                )
            },
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
        pendingStarts.clear()
        lookup = null
        lastConfigLookup = null
        lastAllowlistCheck = null
    }
}
