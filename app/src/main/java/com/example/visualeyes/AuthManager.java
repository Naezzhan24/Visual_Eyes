package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;

public class AuthManager {

    private static final String PREF_NAME = "VisualEyesPrefs";

    private static final String KEY_STUDENT_ID = "student_id";
    private static final String KEY_FIRST_NAME = "first_name";
    private static final String KEY_MIDDLE_NAME = "middle_name";
    private static final String KEY_LAST_NAME = "last_name";
    private static final String KEY_AGE = "age";
    private static final String KEY_SCHOOL_ID = "school_id";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_PROFILE_COMPLETED = "profile_completed";

    private static final String KEY_REMEMBERED_EMAIL = "remembered_email";

    private final SharedPreferences sharedPreferences;

    public AuthManager(Context context) {
        sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveLoggedInStudent(String studentId,
                                    String firstName,
                                    String middleName,
                                    String lastName,
                                    String age,
                                    String schoolId,
                                    String email,
                                    String password) {
        sharedPreferences.edit()
                .putString(KEY_STUDENT_ID, studentId)
                .putString(KEY_FIRST_NAME, firstName)
                .putString(KEY_MIDDLE_NAME, middleName)
                .putString(KEY_LAST_NAME, lastName)
                .putString(KEY_AGE, age)
                .putString(KEY_SCHOOL_ID, schoolId)
                .putString(KEY_EMAIL, email)
                .putString(KEY_PASSWORD, password)
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
                .remove(KEY_PASSWORD)
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

    public String getPassword() {
        return sharedPreferences.getString(KEY_PASSWORD, "");
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