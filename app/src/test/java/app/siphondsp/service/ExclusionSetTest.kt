package app.siphondsp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusionSetTest {

    private val self = 10_100
    private val dabble = 10_200
    private val other = 10_300
    private val blockedApp = 10_400

    private fun set(restricted: Set<Int> = emptySet(), blocked: Set<Int> = emptySet(), excludeRestricted: Boolean = true) =
        ExclusionSet(if (excludeRestricted) restricted else emptySet(), blocked, self, excludeRestricted)

    @Test
    fun theSameSetNeedsNoRebuild() {
        val built = set(restricted = setOf(dabble), blocked = setOf(blockedApp))
        val c = ExclusionSet.compare(built, built.copy(), policyUids = setOf(dabble))
        assertFalse(c.needsRebuild)
    }

    @Test
    fun aRestrictedAppThatStoppedStaysExcludedWithoutARebuild() {
        // Dabble blocks capture; its session ends and its entry leaves the log.
        val built = set(restricted = setOf(dabble))
        val c = ExclusionSet.compare(built, set(), policyUids = emptySet())
        assertFalse(c.needsRebuild)
        assertEquals(setOf(dabble), c.stopped)
        // ...and starts again: the live AudioRecord already excludes it.
        assertFalse(ExclusionSet.compare(built, set(restricted = setOf(dabble)), setOf(dabble)).needsRebuild)
    }

    @Test
    fun aNewRestrictedAppRebuilds() {
        val c = ExclusionSet.compare(set(restricted = setOf(dabble)), set(restricted = setOf(dabble, other)), setOf(dabble, other))
        assertTrue(c.needsRebuild)
        assertEquals(setOf(other), c.added)
    }

    @Test
    fun anAppThatNowAllowsCaptureRebuilds() {
        // Still in the log, no longer restricted: kept excluded, its audio would go unprocessed.
        val c = ExclusionSet.compare(set(restricted = setOf(dabble)), set(), policyUids = setOf(dabble))
        assertTrue(c.needsRebuild)
        assertEquals(setOf(dabble), c.released)
    }

    @Test
    fun leavingTheExcludedAppsListRebuilds() {
        val c = ExclusionSet.compare(set(blocked = setOf(blockedApp)), set(), policyUids = emptySet())
        assertTrue(c.needsRebuild)
        assertEquals(setOf(blockedApp), c.released)
    }

    @Test
    fun aBlockedAndRestrictedAppLeavingTheListRebuildsEvenWhenQuiet() {
        val built = set(restricted = setOf(dabble), blocked = setOf(dabble))
        assertTrue(ExclusionSet.compare(built, set(), policyUids = emptySet()).needsRebuild)
    }

    @Test
    fun joiningTheExcludedAppsListRebuilds() {
        assertTrue(ExclusionSet.compare(set(), set(blocked = setOf(blockedApp)), emptySet()).needsRebuild)
    }

    @Test
    fun switchingExcludeRestrictedOffReleasesEvenQuietApps() {
        val built = set(restricted = setOf(dabble))
        val c = ExclusionSet.compare(built, set(excludeRestricted = false), policyUids = emptySet())
        assertTrue(c.needsRebuild)
        assertEquals(setOf(dabble), c.released)
    }

    @Test
    fun switchingExcludeRestrictedOnWithNothingRestrictedNeedsNoRebuild() {
        assertFalse(ExclusionSet.compare(set(excludeRestricted = false), set(), emptySet()).needsRebuild)
    }

    @Test
    fun thisAppIsAlwaysExcluded() {
        assertTrue(self in set().all)
        assertTrue(self in set(excludeRestricted = false).all)
    }
}
