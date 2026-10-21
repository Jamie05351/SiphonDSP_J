package app.siphondsp.service

/**
 * The UIDs a capture AudioRecord excludes, by why: apps that block capture of their own audio
 * ([restricted], only while [excludeRestricted] is on), the user's Excluded apps list
 * ([blocked]), and this app ([self]).
 */
data class ExclusionSet(
    val restricted: Set<Int>,
    val blocked: Set<Int>,
    val self: Int,
    val excludeRestricted: Boolean,
) {
    val all: Set<Int> = restricted + blocked + self

    /**
     * Whether any of [allowedUids] -- apps the capture-permission log now lists as allowing
     * capture -- is one this set excluded for blocking it. A "stopped" app kept excluded (see
     * [compare]) can come back allowing capture; the log reports that as a minor update, so the
     * caller has to ask for a re-check itself, or the app's audio stays unprocessed.
     */
    fun excludesAnyOf(allowedUids: Collection<Int>): Boolean = allowedUids.any { it in restricted }

    /** How a freshly computed set compares with the one the live AudioRecord was built with. */
    data class Comparison(
        /** Excluded now but not by the live AudioRecord: their audio is being processed. */
        val added: Set<Int>,
        /** Excluded by the live AudioRecord but no longer wanted out: their audio is missing. */
        val released: Set<Int>,
        /** Excluded by the live AudioRecord for blocking capture, and since gone quiet. */
        val stopped: Set<Int>,
    ) {
        /** Only [added] and [released] change what's heard; [stopped] apps aren't playing. */
        val needsRebuild: Boolean get() = added.isNotEmpty() || released.isNotEmpty()
    }

    companion object {
        /**
         * Compares [now] with [built], the set the live AudioRecord excludes. [policyUids] are the
         * apps the capture-permission log still lists, allowed or not.
         *
         * A UID [built] excluded for blocking capture that has since left the log entirely is
         * [Comparison.stopped]: its session ended, so keeping it excluded changes nothing
         * audible, and if it starts again the live AudioRecord already excludes it. Anything else
         * [built] has and [now] doesn't is [Comparison.released] -- the app now allows capture,
         * left the Excluded apps list, or "exclude restricted" went off -- and needs a rebuild,
         * or its audio stays unprocessed.
         */
        fun compare(built: ExclusionSet, now: ExclusionSet, policyUids: Set<Int>): Comparison {
            val added = now.all - built.all
            val stopped = mutableSetOf<Int>()
            val released = mutableSetOf<Int>()
            for (uid in built.all - now.all) {
                val quiet = now.excludeRestricted && uid in built.restricted && uid !in built.blocked &&
                    uid != built.self && uid !in policyUids
                if (quiet) stopped += uid else released += uid
            }
            return Comparison(added, released, stopped)
        }
    }
}
