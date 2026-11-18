package app.siphondsp.session.rootless

import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import app.siphondsp.utils.MutedAudioEffectFactory
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootlessSessionArmingTest {
    private lateinit var db: RootlessSessionDatabase
    private lateinit var factory: MutedAudioEffectFactory

    @Before fun setUp() {
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
}
