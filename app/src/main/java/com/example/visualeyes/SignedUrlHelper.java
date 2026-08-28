package com.example.visualeyes;

import android.content.Context;

import com.android.volley.Request;
import com.android.volley.toolbox.StringRequest;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns a stored materials file URL into a short-lived signed URL (valid for
 * 1 hour) via Supabase Storage's /object/sign/ endpoint, instead of using the
 * permanent public URL directly. Needed because the "materials" bucket is
 * being switched from public to private — a signed URL is the replacement
 * for the old permanent link, generated on demand right before a file is
 * actually opened, and it stops working automatically once it expires.
 */
final class SignedUrlHelper {

    interface Callback {
        void onSignedUrl(String signedUrl);
        void onError();
    }

    private static final String PUBLIC_URL_PREFIX =
            ApiConfig.SUPABASE_URL + "/storage/v1/object/public/materials/";
    private static final int EXPIRES_IN_SECONDS = 3600;

    private SignedUrlHelper() {}

    static void resolve(Context context, String storedFileUrl, Callback callback) {
        String storagePath = extractStoragePath(storedFileUrl);
        if (storagePath == null || storagePath.trim().isEmpty()) {
            callback.onError();
            return;
        }

        String signUrl = ApiConfig.SUPABASE_URL + "/storage/v1/object/sign/materials/" + storagePath;

        JSONObject body = new JSONObject();
        String bodyStr;
        try {
            body.put("expiresIn", EXPIRES_IN_SECONDS);
            bodyStr = body.toString();
        } catch (Exception e) {
            callback.onError();
            return;
        }
        final String finalBodyStr = bodyStr;

        StringRequest req = new StringRequest(Request.Method.POST, signUrl,
                response -> {
                    try {
                        JSONObject obj = new JSONObject(response);
                        String signedPath = obj.optString("signedURL", "");
                        if (signedPath.isEmpty()) {
                            callback.onError();
                            return;
                        }
                        callback.onSignedUrl(ApiConfig.SUPABASE_URL + "/storage/v1" + signedPath);
                    } catch (Exception e) {
                        callback.onError();
                    }
                },
                error -> callback.onError()
        ) {
            @Override
            public byte[] getBody() {
                return finalBodyStr.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String getBodyContentType() {
                return "application/json; charset=utf-8";
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> h = new HashMap<>();
                h.put("apikey",        ApiConfig.SUPABASE_KEY);
                h.put("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY);
                h.put("Content-Type",  "application/json");
                h.put("Accept",        "application/json");
                return h;
            }
        };

        VolleySingleton.getInstance(context).getRequestQueue().add(req);
    }

    /**
     * Strips the known public-URL prefix to recover the raw storage path
     * (e.g. "instructor_5/abcd1234.pdf") that the sign endpoint needs.
     * Falls back to the input unchanged if it doesn't match the expected
     * prefix, so a URL that's already a raw path still works.
     */
    private static String extractStoragePath(String storedFileUrl) {
        if (storedFileUrl == null) return null;
        String trimmed = storedFileUrl.trim();
        if (trimmed.startsWith(PUBLIC_URL_PREFIX)) {
            return trimmed.substring(PUBLIC_URL_PREFIX.length());
        }
        return trimmed;
    }
}