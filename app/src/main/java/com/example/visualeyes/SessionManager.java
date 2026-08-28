package com.example.visualeyes;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.android.volley.VolleyError;

import java.nio.charset.StandardCharsets;

/**
 * Detects the invalid/expired-session signal the token-based RPCs raise
 * (SQLSTATE 28000, see _student_id_from_token in Supabase) and forces a
 * clean logout + redirect back to LoginActivity, instead of each of the 6
 * token-protected call sites silently failing or showing a generic
 * "something went wrong" error when the real cause is just an expired
 * session token.
 */
final class SessionManager {
    private SessionManager() {}

    static boolean isSessionExpiredError(VolleyError error) {
        if (error == null || error.networkResponse == null || error.networkResponse.data == null) {
            return false;
        }
        try {
            String body = new String(error.networkResponse.data, StandardCharsets.UTF_8);
            return body.contains("28000") || body.contains("invalid_session");
        } catch (Exception e) {
            return false;
        }
    }

    static void forceLogoutAndRedirect(Activity activity) {
        new AuthManager(activity).logout();
        Toast.makeText(activity, "Your session has expired. Please log in again.", Toast.LENGTH_LONG).show();
        Intent intent = new Intent(activity, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        // The current activity's own TTS engine is about to be torn down by
        // finish() below, so the expiry can't be spoken from here — instead
        // LoginActivity speaks it on arrival, for users relying on the
        // in-app voice UI rather than a screen reader that would announce
        // the Toast above on its own.
        intent.putExtra("session_expired_message", "Your session has expired. Please log in again.");
        activity.startActivity(intent);
        activity.finish();
    }
}
