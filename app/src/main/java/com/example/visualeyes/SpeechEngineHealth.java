package com.example.visualeyes;

import android.content.Context;

public final class SpeechEngineHealth {
    private static final String PREFS_NAME = "VisualEyesPrefs";
    private static final String KEY_BUILTIN_RECOGNIZER_BROKEN = "builtin_recognizer_broken";

    private SpeechEngineHealth() {}

    public static boolean isBuiltInRecognizerBroken(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_BUILTIN_RECOGNIZER_BROKEN, false);
    }

    public static void markBuiltInRecognizerBroken(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BUILTIN_RECOGNIZER_BROKEN, true)
                .apply();
    }

    public static boolean isRecognizerIncompatible(int error) {
        return error == android.speech.SpeechRecognizer.ERROR_CLIENT
                || error == 12
                || error == 13;
    }
}
