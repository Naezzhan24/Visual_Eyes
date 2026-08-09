package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

final class MaterialReadTracker {

    private static final String PREFS_NAME    = "VisualEyesPrefs";
    private static final String KEY_OPENED_IDS = "opened_material_ids";
    private static final String KEY_CHUNK_INDEX_PREFIX = "chunk_index_";

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

    /** Last chunk index the student had reached in this material, or 0 if none saved. */
    static int getLastChunkIndex(Context context, String materialId) {
        if (materialId == null || materialId.trim().isEmpty()) return 0;
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(chunkIndexKey(context, materialId), 0);
    }

    static void saveChunkIndex(Context context, String materialId, int index) {
        if (materialId == null || materialId.trim().isEmpty()) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(chunkIndexKey(context, materialId), index).apply();
    }

    static void clearChunkIndex(Context context, String materialId) {
        if (materialId == null || materialId.trim().isEmpty()) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(chunkIndexKey(context, materialId)).apply();
    }

    private static String chunkIndexKey(Context context, String materialId) {
        String studentId = new AuthManager(context).getStudentId();
        String suffix = (studentId == null || studentId.trim().isEmpty()) ? "" : "_" + studentId;
        return KEY_CHUNK_INDEX_PREFIX + materialId + suffix;
    }

    private static String scopedKey(Context context) {
        String studentId = new AuthManager(context).getStudentId();
        return (studentId == null || studentId.trim().isEmpty())
                ? KEY_OPENED_IDS
                : KEY_OPENED_IDS + "_" + studentId;
    }
}
