package app.siphondsp.session.dump.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioServiceDumpProviderPlaybackStateTest {

    @Test fun startedPlayerIsActive() {
        assertTrue(
            AudioServiceDumpProvider.isPlaybackConfigurationActive(
                "AudioPlaybackConfiguration piid:1 -- u/pid:1000/2000 -- state:started -- usage=USAGE_MEDIA -- content=CONTENT_TYPE_MUSIC -- sessionId:7"
            )
        )
    }

    @Test fun pausedPlayerIsNotActive() {
        assertFalse(
            AudioServiceDumpProvider.isPlaybackConfigurationActive(
                "AudioPlaybackConfiguration piid:1 -- u/pid:1000/2000 -- state:paused -- usage=USAGE_MEDIA -- content=CONTENT_TYPE_MUSIC -- sessionId:7"
            )
        )
    }

    @Test fun stoppedPlayerIsNotActive() {
        assertFalse(
            AudioServiceDumpProvider.isPlaybackConfigurationActive(
                "AudioPlaybackConfiguration piid:1 -- u/pid:1000/2000 -- state:stopped -- usage=USAGE_MEDIA -- content=CONTENT_TYPE_MUSIC -- sessionId:7"
            )
        )
    }

    @Test fun idleAndReleasedPlayersAreNotActive() {
        assertFalse(AudioServiceDumpProvider.isPlaybackConfigurationActive("ID:1 -- state:idle -- u/pid:1000/2000"))
        assertFalse(AudioServiceDumpProvider.isPlaybackConfigurationActive("ID:1 -- state:released -- u/pid:1000/2000"))
    }

    @Test fun unknownStateIsNotActive() {
        assertFalse(AudioServiceDumpProvider.isPlaybackConfigurationActive("ID:1 -- state:unknown -- u/pid:1000/2000"))
    }

    @Test fun missingStatePreservesLegacyCompatibility() {
        assertTrue(
            AudioServiceDumpProvider.isPlaybackConfigurationActive(
                "AudioPlaybackConfiguration u/pid:1000/2000 usage=USAGE_MEDIA content=CONTENT_TYPE_MUSIC sessionId:7"
            )
        )
    }
}
