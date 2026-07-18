package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

final class MaterialReadTracker {

    private static final String PREFS_NAME    = "VisualEyesPrefs";
    private static final String KEY_OPENED_IDS = "opened_material_ids";

    private MaterialReadTracker() {}

    static boolean isOpened(Context context, String materialId) {
        if (materialId == null) return false;
        Set<String> opened = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(scopedKey(context), null);
        return opened != null && opened.contains(materialId);
    }

    static void markOpened(Context context, String materialId) {
        if (materialId == null || materialId.trim().isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = scopedKey(context);

        Set<String> existing = prefs.getStringSet(key, null);
        Set<String> updated = new HashSet<>(existing != null ? existing : new HashSet<>());
        updated.add(materialId);
        prefs.edit().putStringSet(key, updated).apply();
    }

    private static String scopedKey(Context context) {
        String studentId = new AuthManager(context).getStudentId();
        return (studentId == null || studentId.trim().isEmpty())
                ? KEY_OPENED_IDS
                : KEY_OPENED_IDS + "_" + studentId;
    }
}
