package com.example.visualeyes;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;

public final class AudioCue {
    private static final int BEEP_DURATION_MS   = 220;
    private static final int GAP_AFTER_BEEP_MS  = 200;
    private static final int BEEP_VOLUME         = 100;

    private AudioCue() {}

    public static void playThen(Handler handler, Runnable afterBeep) {
        ToneGenerator toneGen = null;
        try {
            toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME);
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_DURATION_MS);
            final ToneGenerator finalToneGen = toneGen;
            handler.postDelayed(() -> {
                finalToneGen.release();
                afterBeep.run();
            }, BEEP_DURATION_MS + GAP_AFTER_BEEP_MS);
        } catch (Exception e) {
            if (toneGen != null) {
                try { toneGen.release(); } catch (Exception ignored) {}
            }
            afterBeep.run();
        }
    }
}
