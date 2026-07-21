package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;

public final class SpeechEngineHealth {
    private static final String PREFS_NAME = "VisualEyesPrefs";
    private static final String KEY_BUILTIN_RECOGNIZER_BROKEN   = "builtin_recognizer_broken";
    private static final String KEY_BUILTIN_RECOGNIZER_SINCE_MS = "builtin_recognizer_broken_since_ms";

    // The built-in recognizer is re-probed after this long instead of staying
    // latched off forever — a device/network hiccup shouldn't permanently
    // disable the free on-device engine for the life of the install.
    private static final long RETRY_AFTER_MS = 24L * 60L * 60L * 1000L; // 24 hours

    private SpeechEngineHealth() {}

    public static boolean isBuiltInRecognizerBroken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_BUILTIN_RECOGNIZER_BROKEN, false)) return false;

        // Missing timestamp (e.g. flag set by a build before this mechanism
        // existed) reads as 0, which is always "expired" — so stale latches
        // from earlier installs clear themselves the first time this runs.
        long brokenSinceMs = prefs.getLong(KEY_BUILTIN_RECOGNIZER_SINCE_MS, 0L);
        if (System.currentTimeMillis() - brokenSinceMs >= RETRY_AFTER_MS) {
            prefs.edit()
                    .putBoolean(KEY_BUILTIN_RECOGNIZER_BROKEN, false)
                    .remove(KEY_BUILTIN_RECOGNIZER_SINCE_MS)
                    .apply();
            return false;
        }
        return true;
    }

    public static void markBuiltInRecognizerBroken(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_BUILTIN_RECOGNIZER_BROKEN, true)
                .putLong(KEY_BUILTIN_RECOGNIZER_SINCE_MS, System.currentTimeMillis())
                .apply();
    }

    // Only a genuine client-side failure counts as "incompatible" — codes 12/13
    // (ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE) mean a missing
    // offline language pack, not a broken recognizer, and must never latch it off.
    public static boolean isRecognizerIncompatible(int error) {
        return error == android.speech.SpeechRecognizer.ERROR_CLIENT;
    }
}
