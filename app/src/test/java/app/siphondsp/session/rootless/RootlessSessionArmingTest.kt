package app.siphondsp.session.rootless

import android.content.Context
import android.media.AudioAttributes
import android.media.audiofx.AudioEffect
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.model.AudioSessionDumpEntry
import app.siphondsp.session.dump.data.ISessionInfoDump
import app.siphondsp.utils.MutedAudioEffectFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class RootlessSessionArmingTest {
    private lateinit var db: RootlessSessionDatabase
    private lateinit var factory: MutedAudioEffectFactory

    @Before fun setUp() {
        SessionMuteLog.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        factory = mock()
        db = RootlessSessionDatabase(context, factory)
    }

    private fun arm(usage: Int, policy: Int = AudioAttributes.ALLOW_CAPTURE_BY_ALL, allowed: Boolean = true) =
        db.arm(7, 12_345, "com.example.player", SessionMuteLog.Trigger.BROADCAST,
            AudioAttributes.Builder().setUsage(usage).setAllowedCapturePolicy(policy).build(), allowed)

    @Test fun nonRecordableUsagesNeverCreateAMute() {
        listOf(AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_NOTIFICATION).forEach { assertFalse(arm(it)) }
        verify(factory, never()).make(any<Int>(), any<String>())
    }

    @Test fun restrictedOrUnknownAppPolicyNeverCreatesAMute() {
        assertFalse(arm(AudioAttributes.USAGE_MEDIA, allowed = false))
        verify(factory, never()).make(any<Int>(), any<String>())
    }

    @Test fun playerPolicyMustAllowThirdPartyCapture() {
        assertFalse(arm(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM))
        assertFalse(arm(AudioAttributes.USAGE_GAME, AudioAttributes.ALLOW_CAPTURE_BY_NONE))
        verify(factory, never()).make(any<Int>(), any<String>())
    }

    @Test fun exclusionStillTakesPrecedenceOverAllowedCapture() {
        db.setExcludedUids(arrayOf(12_345))
        assertFalse(arm(AudioAttributes.USAGE_MEDIA))
        verify(factory, never()).make(any<Int>(), any<String>())
    }

    @Test fun eligibleMediaReachesTheMuteFactory() {
        arm(AudioAttributes.USAGE_MEDIA)
        verify(factory).make(7, "com.example.player")
    }

    @Test fun failedCreationCanBeRetriedImmediatelyAndEveryFailureIsLogged() {
        // No clock advance: both attempts are inside the former 500 ms throttle window.
        assertFalse(arm(AudioAttributes.USAGE_MEDIA))
        assertFalse(arm(AudioAttributes.USAGE_MEDIA))
        verify(factory, times(2)).make(7, "com.example.player")
        val failures = SessionMuteLog.snapshot()
        assertEquals(2, failures.size)
        failures.forEach {
            assertEquals(SessionMuteLog.Kind.ARM_FAILED, it.kind)
            assertFalse(it.isStart)
        }
        assertFalse(SessionMuteLog.wasSeen(7))
    }

    @Test fun aSuccessfulRetryAttachesAMuteWithoutWaitingForADump() {
        val effect = mock<AudioEffect>()
        whenever(factory.make(7, "com.example.player")).thenReturn(null, effect)
        assertFalse(arm(AudioAttributes.USAGE_MEDIA))
        assertTrue(arm(AudioAttributes.USAGE_MEDIA))
        verify(factory, times(2)).make(7, "com.example.player")
        assertEquals(SessionMuteLog.Kind.ARMED, SessionMuteLog.snapshot().first().kind)
        assertEquals(1, SessionMuteLog.snapshot().first().held)
    }

    private fun dump(includeSession: Boolean) = object : ISessionInfoDump {
        override val sessions = hashMapOf<Int, AudioSessionDumpEntry>().apply {
            if (includeSession) put(7, AudioSessionDumpEntry(12_345, "com.example.player",
                "USAGE_MEDIA", "CONTENT_TYPE_MUSIC"))
        }
        override fun toString() = "TestDump($sessions)"
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun anEarlyMuteExpiresEvenWhenNoDumpEverArrives() {
        val effect = mock<AudioEffect>()
        whenever(factory.make(7, "com.example.player")).thenReturn(effect)
        assertTrue(arm(AudioAttributes.USAGE_MEDIA))
        advance(2_999)
        verify(effect, never()).release()
        advance(1)
        verify(effect).release()
        val expired = SessionMuteLog.snapshot().first()
        assertEquals(SessionMuteLog.Kind.RELEASED, expired.kind)
        assertEquals("early mute not confirmed", expired.detail)
        assertEquals(0, expired.held)
    }

    @Test fun dumpsThatOmitTheSessionDoNotExtendTheConfirmationDeadline() {
        val effect = mock<AudioEffect>()
        whenever(factory.make(7, "com.example.player")).thenReturn(effect)
        assertTrue(arm(AudioAttributes.USAGE_MEDIA))
        repeat(3) {
            advance(1_000)
            db.update(dump(includeSession = false))
        }
        verify(effect).release()
    }

    @Test fun aDumpClaimingTheMuteCancelsItsExpiry() {
        val effect = mock<AudioEffect>()
        whenever(effect.hasControl()).thenReturn(true)
        whenever(factory.make(7, "com.example.player")).thenReturn(effect)
        assertTrue(arm(AudioAttributes.USAGE_MEDIA))
        advance(1_000)
        db.update(dump(includeSession = true))
        advance(5_000)
        verify(effect, never()).release()
        assertTrue(db.sessionList.containsKey(7))
        verify(factory).make(7, "com.example.player")
    }

    @Test fun aMuteHeldAfterConfirmedPlaybackHasNoEarlyArmDeadline() {
        val effect = mock<AudioEffect>()
        whenever(effect.hasControl()).thenReturn(true)
        whenever(factory.make(7, "com.example.player")).thenReturn(effect)
        assertTrue(arm(AudioAttributes.USAGE_MEDIA))
        db.update(dump(includeSession = true))
        db.removeSession(7, gone = true)
        advance(5_000)
        verify(effect, never()).release()
        assertEquals(SessionMuteLog.Kind.HELD, SessionMuteLog.snapshot().first().kind)
    }

    @Test fun clearingAnEarlyMuteDoesNotExpireANewerMuteOnTheSameId() {
        val old = mock<AudioEffect>()
        val replacement = mock<AudioEffect>()
        whenever(factory.make(7, "com.example.player")).thenReturn(old, replacement)
        assertTrue(arm(AudioAttributes.USAGE_MEDIA))
        advance(1_000)
        db.clearSessions()
        verify(old).release()
        assertTrue(arm(AudioAttributes.USAGE_MEDIA))
        advance(2_000) // the original timer's deadline
        verify(old).release()
        verify(replacement, never()).release()
        advance(1_000)
        verify(replacement).release()
    }
}
