package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class AuthManager {

    private static final String TAG = "AuthManager";

    // Renamed from the old plaintext "VisualEyesPrefs" file so switching to
    // EncryptedSharedPreferences doesn't try to read old plaintext values
    // through the encrypted codec (which would throw). Existing sessions are
    // simply logged out once; nothing else reads the old file.
    private static final String PREF_NAME = "VisualEyesPrefsEncrypted";

    private static final String KEY_STUDENT_ID = "student_id";
    private static final String KEY_FIRST_NAME = "first_name";
    private static final String KEY_MIDDLE_NAME = "middle_name";
    private static final String KEY_LAST_NAME = "last_name";
    private static final String KEY_AGE = "age";
    private static final String KEY_SCHOOL_ID = "school_id";
    private static final String KEY_EMAIL = "email";
    // Replaces KEY_PASSWORD — the raw password used to be stored here and
    // resent on nearly every authenticated call. Now the password only ever
    // lives as a local variable during the login request itself; this holds
    // the opaque, server-issued, revocable session token instead.
    private static final String KEY_SESSION_TOKEN = "session_token";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_PROFILE_COMPLETED = "profile_completed";

    private static final String KEY_REMEMBERED_EMAIL = "remembered_email";
    // Survives logout() on purpose: this tracks whether the app has ever been
    // opened to the Home screen on this install, not whether an account is
    // currently logged in. Fresh install -> false -> "Welcome"; every open
    // after that -> true -> "Welcome back", even across different accounts.
    private static final String KEY_HAS_SEEN_HOME = "has_seen_home";

    private final SharedPreferences sharedPreferences;

    public AuthManager(Context context) {
        sharedPreferences = createEncryptedPrefs(context);
    }

    private static SharedPreferences createEncryptedPrefs(Context context) {
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    context,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            // Falls back to a plain (unencrypted) prefs file so login still
            // works if the device keystore is unavailable, rather than
            // crashing the app on every screen that touches AuthManager.
            // Recorded as a non-fatal so this is actually visible in the
            // field — it previously only went to Logcat, which nobody reads
            // for a device that's already shipped.
            Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to plain prefs", e);
            FirebaseCrashlytics.getInstance().setCustomKey("auth_encrypted_prefs_fallback", true);
            FirebaseCrashlytics.getInstance().recordException(e);
            return context.getSharedPreferences(PREF_NAME + "Fallback", Context.MODE_PRIVATE);
        }
    }

    public void saveLoggedInStudent(String studentId,
                                    String firstName,
                                    String middleName,
                                    String lastName,
                                    String age,
                                    String schoolId,
                                    String email,
                                    String sessionToken) {
        sharedPreferences.edit()
                .putString(KEY_STUDENT_ID, studentId)
                .putString(KEY_FIRST_NAME, firstName)
                .putString(KEY_MIDDLE_NAME, middleName)
                .putString(KEY_LAST_NAME, lastName)
                .putString(KEY_AGE, age)
                .putString(KEY_SCHOOL_ID, schoolId)
                .putString(KEY_EMAIL, email)
                .putString(KEY_SESSION_TOKEN, sessionToken)
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .putString(KEY_REMEMBERED_EMAIL, email)
                .apply();
    }

    public String getRememberedEmail() {
        return sharedPreferences.getString(KEY_REMEMBERED_EMAIL, "");
    }

    public String getStudentId() {
        return sharedPreferences.getString(KEY_STUDENT_ID, "");
    }

    public void setLoggedIn(boolean loggedIn) {
        sharedPreferences.edit().putBoolean(KEY_IS_LOGGED_IN, loggedIn).apply();
    }

    public boolean isLoggedIn() {
        return sharedPreferences.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    public boolean hasSeenHome() {
        return sharedPreferences.getBoolean(KEY_HAS_SEEN_HOME, false);
    }

    public void markHomeSeen() {
        sharedPreferences.edit().putBoolean(KEY_HAS_SEEN_HOME, true).apply();
    }

    public void logout() {
        sharedPreferences.edit()
                .putBoolean(KEY_IS_LOGGED_IN, false)
                .remove(KEY_STUDENT_ID)
                .remove(KEY_FIRST_NAME)
                .remove(KEY_MIDDLE_NAME)
                .remove(KEY_LAST_NAME)
                .remove(KEY_AGE)
                .remove(KEY_SCHOOL_ID)
                .remove(KEY_EMAIL)
                .remove(KEY_SESSION_TOKEN)
                .remove(KEY_PROFILE_COMPLETED)
                .apply();
    }

    public String getFirstName() {
        return sharedPreferences.getString(KEY_FIRST_NAME, "");
    }

    public String getMiddleName() {
        return sharedPreferences.getString(KEY_MIDDLE_NAME, "");
    }

    public String getLastName() {
        return sharedPreferences.getString(KEY_LAST_NAME, "");
    }

    public String getFullName() {
        String firstName = getFirstName();
        String middleName = getMiddleName();
        String lastName = getLastName();

        StringBuilder fullName = new StringBuilder();

        if (!firstName.isEmpty()) fullName.append(firstName);
        if (!middleName.isEmpty()) {
            if (fullName.length() > 0) fullName.append(" ");
            fullName.append(middleName);
        }
        if (!lastName.isEmpty()) {
            if (fullName.length() > 0) fullName.append(" ");
            fullName.append(lastName);
        }

        return fullName.toString().trim();
    }

    public String getAge() {
        return sharedPreferences.getString(KEY_AGE, "");
    }

    public String getEmail() {
        return sharedPreferences.getString(KEY_EMAIL, "");
    }

    public String getSessionToken() {
        return sharedPreferences.getString(KEY_SESSION_TOKEN, "");
    }

    public String getSchoolId() {
        return sharedPreferences.getString(KEY_SCHOOL_ID, "");
    }

    public boolean isProfileCompleted() {
        return sharedPreferences.getBoolean(KEY_PROFILE_COMPLETED, false);
    }

    public void setProfileCompleted(boolean value) {
        sharedPreferences.edit().putBoolean(KEY_PROFILE_COMPLETED, value).apply();
    }

    public void clearAll() {
        sharedPreferences.edit().clear().apply();
    }
}