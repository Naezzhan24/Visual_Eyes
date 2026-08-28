package com.example.visualeyes;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

/**
 * Centralizes audio-focus request/release for mic capture and TTS narration,
 * so this app coordinates with the rest of the system instead of silently
 * grabbing the mic/speaker underneath a phone call, another app's playback,
 * or a voice assistant. minSdk is 29, so only the modern AudioFocusRequest
 * API is needed — no legacy int-based requestAudioFocus() fallback.
 */
final class AudioFocusHelper {
    private AudioFocusHelper() {}

    /**
     * Exclusive, transient focus for a single voice-capture session — mutes
     * other apps' playback for the duration, the same way a phone call or
     * voice assistant would, and is returned as soon as capture stops.
     */
    static AudioFocusRequest requestForRecording(Context context, AudioManager.OnAudioFocusChangeListener listener) {
        return request(context, listener, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
    }

    /**
     * Transient focus for TTS narration — ducks rather than silences other
     * audio, since reading a lesson aloud isn't as exclusive a need as
     * actively capturing the user's voice.
     */
    static AudioFocusRequest requestForPlayback(Context context, AudioManager.OnAudioFocusChangeListener listener) {
        return request(context, listener, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
    }

    private static AudioFocusRequest request(Context context, AudioManager.OnAudioFocusChangeListener listener,
                                               int focusGain) {
        if (context == null || listener == null) return null;
        try {
            AudioManager am = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return null;

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(focusGain)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(listener)
                    .build();

            int result = am.requestAudioFocus(focusRequest);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? focusRequest : null;
        } catch (Exception e) {
            return null;
        }
    }

    static void abandon(Context context, AudioFocusRequest request) {
        if (context == null || request == null) return;
        try {
            AudioManager am = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.abandonAudioFocusRequest(request);
        } catch (Exception ignored) {}
    }
}
