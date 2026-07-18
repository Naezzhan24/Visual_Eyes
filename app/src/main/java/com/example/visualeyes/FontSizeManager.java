package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;

public class FontSizeManager {

    private static final String PREF_NAME = "VisualEyesPrefs";
    private static final String KEY_USE_RECOMMENDED = "use_recommended_font";
    private static final String KEY_RECOMMENDED_SIZE = "recommended_text_size";

    public static float getFontSize(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        boolean useRecommended = prefs.getBoolean(KEY_USE_RECOMMENDED, true);
        float recommendedSize = prefs.getFloat(KEY_RECOMMENDED_SIZE, 14f);

        return useRecommended ? recommendedSize : 14f;
    }

    public static void saveRecommendedSize(Context context, float size) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        prefs.edit()
                .putFloat(KEY_RECOMMENDED_SIZE, size)
                .putBoolean(KEY_USE_RECOMMENDED, true)
                .apply();
    }

    public static void useRecommended(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_USE_RECOMMENDED, true)
                .apply();
    }

    public static void useDefault(Context context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_USE_RECOMMENDED, false)
                .apply();
    }
}
