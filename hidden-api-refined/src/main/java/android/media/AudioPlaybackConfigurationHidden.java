package android.media;

import dev.rikka.tools.refine.RefineAs;

@RefineAs(AudioPlaybackConfiguration.class)
public class AudioPlaybackConfigurationHidden {
    /**
     * Return the uid of the client application that created this player.
     * @return the uid of the client
     */
    public int getClientUid() {
        throw new RuntimeException("Stub!");
    }

    /**
     * Return the pid of the client application that created this player.
     * @return the pid of the client
     */
    public int getClientPid() {
        throw new RuntimeException("Stub!");
    }

    /**
     * Return the audio session ID associated with this player.
     * See {@link AudioManager#generateAudioSessionId()}.
     * @return an audio session ID
     */
    public int getSessionId() {
        throw new RuntimeException("Stub!");
    }

    /** The player's interface id, unique per player (kept in the anonymized copies apps get). */
    public int getPlayerInterfaceId() {
        throw new RuntimeException("Stub!");
    }

    /** The player's state (kept in the anonymized copies apps get). */
    public int getPlayerState() {
        throw new RuntimeException("Stub!");
    }
}
