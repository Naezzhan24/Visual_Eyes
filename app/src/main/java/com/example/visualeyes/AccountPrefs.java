package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which student account is currently signed in, kept in plain (non-encrypted) preferences so it
 * can be read cheaply from anywhere — including every text-to-speech call. Per-account settings
 * (assistant voice, reading speed) use {@link #key} to store one value PER STUDENT, so when a
 * different student logs in on the same phone they start from the defaults instead of inheriting
 * the previous student's choices.
 *
 * AuthManager sets this on login and clears it on logout.
 */
public final class AccountPrefs {

    private static final String PREF_NAME   = "VisualEyesPrefs";
    private static final String KEY_ACCOUNT = "current_account_id";

    // A session that was already logged in before this existed has no stored account id yet;
    // it is looked up from AuthManager once, then cached here.
    private static volatile boolean triedExistingSession = false;

    private AccountPrefs() {}

    /** The signed-in student's id, or "" when nobody is signed in (Login / Register screens). */
    public static String currentAccountId(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_ACCOUNT, "");
        if (id.isEmpty() && !triedExistingSession) {
            triedExistingSession = true;
            try {
                AuthManager auth = new AuthManager(context.getApplicationContext());
                if (auth.isLoggedIn() && !auth.getStudentId().isEmpty()) {
                    id = auth.getStudentId();
                    prefs.edit().putString(KEY_ACCOUNT, id).apply();
                }
            } catch (Exception ignored) {
                // Fall back to "no account" — the defaults — rather than failing a voice call.
            }
        }
        return id;
    }

    public static void setCurrentAccount(Context context, String studentId) {
        triedExistingSession = true;
        context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACCOUNT, studentId == null ? "" : studentId)
                .apply();
    }

    public static void clearCurrentAccount(Context context) {
        setCurrentAccount(context, "");
    }

    /** A preference key for the current student: "tts_voice_42". With nobody signed in: "tts_voice". */
    public static String key(Context context, String base) {
        String id = currentAccountId(context);
        return id.isEmpty() ? base : base + "_" + id;
    }
}
