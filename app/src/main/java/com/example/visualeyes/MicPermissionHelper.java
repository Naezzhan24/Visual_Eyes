package com.example.visualeyes;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public final class MicPermissionHelper {
    private static final String PREFS_NAME = "VisualEyesPrefs";
    private static final String KEY_MIC_PERMISSION_EVER_REQUESTED = "mic_permission_ever_requested";

    private MicPermissionHelper() {}

    public static boolean hasAudioPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** True when a screen reader like TalkBack is active — system permission dialogs
     *  must not be auto-triggered in this state, only from an explicit user gesture. */
    public static boolean isScreenReaderActive(Context context) {
        AccessibilityManager am =
                (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
    }

    public static boolean hasRequestedBefore(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MIC_PERMISSION_EVER_REQUESTED, false);
    }

    public static void markRequested(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MIC_PERMISSION_EVER_REQUESTED, true)
                .apply();
    }

    /** Distinguishes "permanently denied" from "never asked yet" — both make
     *  shouldShowRequestPermissionRationale() return false, so our own flag is required. */
    public static boolean isPermanentlyDenied(Activity activity) {
        return !hasAudioPermission(activity)
                && hasRequestedBefore(activity)
                && !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.RECORD_AUDIO);
    }

    public static void openAppSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }
}
