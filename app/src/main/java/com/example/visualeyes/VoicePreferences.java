package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;

final class VoicePreferences {

    private static final String PREFS_NAME      = "VisualEyesPrefs";
    private static final String KEY_TTS_ENABLED  = "tts_enabled";
    private static final String KEY_STT_ENABLED  = "stt_enabled";

    private VoicePreferences() {}

    static boolean isTtsEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TTS_ENABLED, true);
    }

    static boolean isSttEnabled(Context context) {
        return prefs(context).getBoolean(KEY_STT_ENABLED, true);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
