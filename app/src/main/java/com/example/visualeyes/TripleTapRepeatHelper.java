package com.example.visualeyes;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

public class TripleTapRepeatHelper {

    public interface OnTripleTapListener {
        void onTripleTap();
    }

    private static final long TAP_WINDOW_MS = 600L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OnTripleTapListener listener;
    private int tapCount = 0;

    private final Runnable resetTaps = () -> tapCount = 0;

    public TripleTapRepeatHelper(OnTripleTapListener listener) {
        this.listener = listener;
    }

    public void attachTo(View view) {
        view.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                tapCount++;
                handler.removeCallbacks(resetTaps);
                if (tapCount >= 3) {
                    tapCount = 0;
                    if (listener != null) listener.onTripleTap();
                } else {
                    handler.postDelayed(resetTaps, TAP_WINDOW_MS);
                }
            }
            return false;
        });
    }

    public void detach(View view) {
        handler.removeCallbacks(resetTaps);
        tapCount = 0;
        view.setOnTouchListener(null);
    }
}
