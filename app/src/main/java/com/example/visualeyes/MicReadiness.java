package com.example.visualeyes;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;

/** Probes whether the mic hardware is actually ready to record right after
 *  RECORD_AUDIO is granted, instead of guessing with a fixed delay. */
public final class MicReadiness {
    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_INTERVAL_MS = 250L;

    private MicReadiness() {}

    public static void awaitReady(Handler handler, Runnable onReady) {
        probe(handler, onReady, 0);
    }

    private static void probe(Handler handler, Runnable onReady, int attempt) {
        if (isMicReady() || attempt >= MAX_ATTEMPTS) {
            onReady.run();
            return;
        }
        handler.postDelayed(() -> probe(handler, onReady, attempt + 1), RETRY_INTERVAL_MS);
    }

    private static boolean isMicReady() {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) return false;

        AudioRecord probe = null;
        try {
            probe = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer);
            return probe.getState() == AudioRecord.STATE_INITIALIZED;
        } catch (Exception e) {
            return false;
        } finally {
            if (probe != null) probe.release();
        }
    }
}
