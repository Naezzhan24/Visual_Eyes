package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The student's own reading-speed preference, as ONE multiplier for the whole app
 * (1.00 = the app's normal pace). Every voice in the app already has its own base rate
 * (reader 0.85, Login/Register/Feedback 1.10, the shared Google voice 0.95, ...); this scale
 * is multiplied onto whichever base a screen uses, so changing it once changes them all
 * together while keeping their relative pacing.
 *
 * It is set from the Feedback screen in small steps (see FeedbackActivity) and kept inside
 * a narrow range, so repeated feedback can never push the voice to an unusable speed.
 *
 * Stored PER STUDENT ACCOUNT (see {@link AccountPrefs}): another account on the same phone
 * starts at 100% until it sets its own.
 */
public final class SpeechRateManager {

    private static final String PREF_NAME = "VisualEyesPrefs";
    private static final String KEY_SCALE = "speech_rate_scale";

    public static final float MIN_SCALE = 0.70f;
    public static final float MAX_SCALE = 1.30f;

    private SpeechRateManager() {}

    /** Current multiplier, 1.00 if the student never changed it. */
    public static float getScale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return clamp(prefs.getFloat(AccountPrefs.key(context, KEY_SCALE), 1.0f));
    }

    /** Saves a new multiplier (clamped, rounded to 1%) and returns what was actually saved. */
    public static float setScale(Context context, float scale) {
        float saved = clamp(scale);
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putFloat(AccountPrefs.key(context, KEY_SCALE), saved)
                .apply();
        return saved;
    }

    /** Adds delta (e.g. -0.05f for 5% slower) to the current multiplier; returns the new value. */
    public static float adjust(Context context, float delta) {
        return setScale(context, getScale(context) + delta);
    }

    /** 0.95f -> 95, for showing and speaking the speed as a percentage. */
    public static int percent(float scale) {
        return Math.round(scale * 100f);
    }

    private static float clamp(float scale) {
        float rounded = Math.round(scale * 100f) / 100f;
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, rounded));
    }
}
