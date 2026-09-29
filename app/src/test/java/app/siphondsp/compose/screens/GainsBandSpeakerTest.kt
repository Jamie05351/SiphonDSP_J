package app.siphondsp.compose.screens

import app.siphondsp.compose.controls.SpeakerKind
import org.junit.Assert.assertEquals
import org.junit.Test

class GainsBandSpeakerTest {
    @Test
    fun eachSpeakerKindMapsToItsBand() {
        assertEquals(GainsBand.HIGH, GainsBand.forSpeaker(SpeakerKind.TWEETER))
        assertEquals(GainsBand.MID, GainsBand.forSpeaker(SpeakerKind.MID))
        assertEquals(GainsBand.LOW, GainsBand.forSpeaker(SpeakerKind.WOOFER))
    }

    @Test
    fun everyBandRoundTripsThroughItsSpeaker() {
        for (band in GainsBand.entries) {
            assertEquals(band, GainsBand.forSpeaker(band.speaker))
        }
    }
}
