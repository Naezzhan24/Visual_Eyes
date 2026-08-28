package com.example.visualeyes;

import android.content.Context;

import com.android.volley.RequestQueue;
import com.android.volley.toolbox.Volley;

// One RequestQueue for the whole app instead of a fresh one per call —
// each Volley.newRequestQueue() spins up its own thread pool and disk
// cache, and several screens (materials drawer, pull-to-refresh, signed
// URL resolution) were doing that on every call.
final class VolleySingleton {

    private static volatile VolleySingleton instance;
    private final RequestQueue requestQueue;

    private VolleySingleton(Context context) {
        requestQueue = Volley.newRequestQueue(context.getApplicationContext());
    }

    static VolleySingleton getInstance(Context context) {
        if (instance == null) {
            synchronized (VolleySingleton.class) {
                if (instance == null) instance = new VolleySingleton(context);
            }
        }
        return instance;
    }

    RequestQueue getRequestQueue() {
        return requestQueue;
    }
}
