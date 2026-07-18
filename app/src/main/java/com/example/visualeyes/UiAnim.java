package com.example.visualeyes;

import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

final class UiAnim {

    private UiAnim() {}

    static void attachPressFeedback(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(100).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
            }
            return false;
        });
    }

    static void fadeSlideIn(View view, long delayMs) {
        view.setAlpha(0f);
        view.setTranslationY(60f);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(420)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    static void popIn(View view, long delayMs) {
        view.setAlpha(0f);
        view.setScaleX(0.8f);
        view.setScaleY(0.8f);
        view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delayMs)
                .setDuration(420)
                .setInterpolator(new OvershootInterpolator(1.6f))
                .start();
    }

    static void rotateFadeIn(View view, long delayMs) {
        view.setAlpha(0f);
        view.setRotation(-10f);
        view.setScaleX(0.9f);
        view.setScaleY(0.9f);
        view.animate()
                .alpha(1f)
                .rotation(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(delayMs)
                .setDuration(400)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    static void shake(View view) {
        if (view == null) return;
        view.animate().cancel();
        view.setTranslationX(0f);
        view.animate().translationX(18f).setDuration(60)
                .withEndAction(() -> view.animate().translationX(-18f).setDuration(60)
                        .withEndAction(() -> view.animate().translationX(10f).setDuration(60)
                                .withEndAction(() -> view.animate().translationX(0f)
                                        .setDuration(60).start()).start()).start()).start();
    }

    static void crossfadeText(android.widget.TextView view, CharSequence newText) {
        view.animate().alpha(0f).setDuration(120).withEndAction(() -> {
            view.setText(newText);
            view.animate().alpha(1f).setDuration(220).start();
        }).start();
    }
}
