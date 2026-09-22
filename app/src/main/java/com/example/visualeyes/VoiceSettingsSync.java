package com.example.visualeyes;

import android.content.Context;
import android.util.Log;

import com.android.volley.Request;
import com.android.volley.toolbox.StringRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the student's assistant voice and reading speed in the database (students.tts_voice,
 * students.speech_rate_scale) so they follow the ACCOUNT rather than the phone. The phone keeps a
 * per-account copy (TtsVoiceManager / SpeechRateManager) so voice calls never wait on the network.
 *
 * Both RPCs are token-based like the rest of the app; they run on the app-wide Volley queue, so a
 * request started from a screen that then finishes still completes.
 */
public final class VoiceSettingsSync {

    private static final String TAG = "VoiceSettingsSync";

    public interface Callback {
        /** ok = saved to the account; detail = a short human-readable reason when it wasn't. */
        void onDone(boolean ok, String detail);
    }

    // The database is fetched once per app launch (plus after every login), not on every screen.
    private static volatile boolean refreshedThisProcess = false;

    private VoiceSettingsSync() {}

    /** Once per app process: pulls the account's saved settings, e.g. when the app opens already logged in. */
    public static void refreshOncePerProcess(Context context) {
        if (refreshedThisProcess) return;
        refreshedThisProcess = true;
        refresh(context);
    }

    /** Pulls the signed-in student's saved voice and speed from the database into this phone's copy. */
    public static void refresh(Context context) {
        final Context app = context.getApplicationContext();
        final String token = new AuthManager(app).getSessionToken();
        if (token == null || token.isEmpty()) return;

        final String bodyStr;
        try {
            bodyStr = new JSONObject().put("p_session_token", token).toString();
        } catch (Exception e) {
            return;
        }
        // The account this answer belongs to: if the student logs out and someone else logs in before
        // the response arrives, it must not be written onto the new account.
        final String accountAtRequest = AccountPrefs.currentAccountId(app);

        StringRequest request = new StringRequest(
                Request.Method.POST,
                ApiConfig.SUPABASE_URL + "/rest/v1/rpc/student_get_voice_settings",
                response -> {
                    try {
                        if (!accountAtRequest.equals(AccountPrefs.currentAccountId(app))) return;
                        JSONArray rows = new JSONArray(response);
                        if (rows.length() == 0) return;
                        JSONObject row = rows.getJSONObject(0);

                        String voice = row.isNull("tts_voice") ? "" : row.optString("tts_voice", "");
                        if (TtsVoiceManager.find(voice) != null) TtsVoiceManager.setVoice(app, voice);

                        if (!row.isNull("speech_rate_scale")) {
                            SpeechRateManager.setScale(app, (float) row.optDouble("speech_rate_scale", 1.0));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Bad voice settings response: " + response, e);
                    }
                },
                error -> Log.e(TAG, "Could not load voice settings: " + errorText(error))
        ) {
            @Override public byte[] getBody() { return bodyStr.getBytes(StandardCharsets.UTF_8); }
            @Override public String getBodyContentType() { return "application/json; charset=utf-8"; }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        VolleySingleton.getInstance(app).getRequestQueue().add(request);
    }

    /**
     * Saves to the database. Pass null for a value to leave it as it is. The caller is expected to
     * have already updated the phone's own copy; this only makes it follow the account.
     */
    public static void save(Context context, String voiceId, Float speechScale, Callback callback) {
        final Context app = context.getApplicationContext();
        final String token = new AuthManager(app).getSessionToken();
        if (token == null || token.isEmpty()) {
            Log.e(TAG, "Not saving voice settings: no session token on this phone.");
            if (callback != null) callback.onDone(false, "you are not logged in on this phone");
            return;
        }

        final String bodyStr;
        try {
            bodyStr = new JSONObject()
                    .put("p_session_token",     token)
                    .put("p_tts_voice",         voiceId == null ? JSONObject.NULL : voiceId)
                    .put("p_speech_rate_scale", speechScale == null ? JSONObject.NULL : (double) speechScale)
                    .toString();
        } catch (Exception e) {
            Log.e(TAG, "Could not build the voice settings request", e);
            if (callback != null) callback.onDone(false, "internal error");
            return;
        }

        StringRequest request = new StringRequest(
                Request.Method.POST,
                ApiConfig.SUPABASE_URL + "/rest/v1/rpc/student_save_voice_settings",
                // true = saved; false (still HTTP 200) = the server refused it (bad token, bad value).
                response -> {
                    boolean ok = "true".equalsIgnoreCase(response.trim());
                    Log.d(TAG, "student_save_voice_settings -> " + response.trim());
                    if (callback != null) callback.onDone(ok, ok ? null : "the server refused it (" + response.trim() + ")");
                },
                error -> {
                    String text = errorText(error);
                    Log.e(TAG, "Could not save voice settings: " + text);
                    String why = SessionManager.isSessionExpiredError(error)
                            ? "your session expired, please log in again"
                            : (error.networkResponse != null
                                    ? "server error " + error.networkResponse.statusCode + ": " + text
                                    : "no connection to the server");
                    if (callback != null) callback.onDone(false, why);
                }
        ) {
            @Override public byte[] getBody() { return bodyStr.getBytes(StandardCharsets.UTF_8); }
            @Override public String getBodyContentType() { return "application/json; charset=utf-8"; }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        VolleySingleton.getInstance(app).getRequestQueue().add(request);
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("apikey",        ApiConfig.SUPABASE_KEY);
        h.put("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY);
        h.put("Content-Type",  "application/json");
        h.put("Accept",        "application/json");
        return h;
    }

    private static String errorText(com.android.volley.VolleyError error) {
        if (error.networkResponse != null && error.networkResponse.data != null) {
            return new String(error.networkResponse.data, StandardCharsets.UTF_8);
        }
        return String.valueOf(error.getMessage());
    }
}
