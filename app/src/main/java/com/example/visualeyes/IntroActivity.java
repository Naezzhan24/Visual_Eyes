package com.example.visualeyes;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.FrameLayout;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * App entry point. Plays the VisualED intro once using the real logo
 * artwork (logo_visualed_icon.png / logo_visualed_wordmark.png, both
 * cropped straight from logo_visualed.png so the mark is pixel-exact):
 * the logo pops in with the book hidden behind two cover panels, the
 * panels shrink away to open the book, the icon does a quick double
 * blink, then it hands off to {@link LoginActivity}. Tapping anywhere
 * skips straight to login.
 */
public class IntroActivity extends AppCompatActivity {

    // Fractional bounds of the book within logo_visualed_icon.png,
    // measured directly from the asset so the cover panels line up with
    // the real artwork on any screen density.
    private static final float BOOK_LEFT_FRAC = 0.3245f;
    private static final float BOOK_CENTER_FRAC = 0.5107f;
    private static final float BOOK_RIGHT_FRAC = 0.6969f;
    private static final float BOOK_TOP_FRAC = 0.229f;
    private static final float BOOK_BOTTOM_FRAC = 0.803f;

    private boolean navigated = false;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intro);
        handler = new Handler(Looper.getMainLooper());

        View root = findViewById(R.id.introRoot);
        View stage = findViewById(R.id.introIconStage);
        ImageView icon = findViewById(R.id.introIcon);
        View coverLeft = findViewById(R.id.bookCoverLeft);
        View coverRight = findViewById(R.id.bookCoverRight);
        ImageView wordmark = findViewById(R.id.introWordmark);
        View skipHint = findViewById(R.id.introSkipHint);

        wordmark.setAlpha(0f);
        root.setOnClickListener(v -> goToLogin());

        stage.post(() -> {
            layoutBookCovers(stage, coverLeft, coverRight);
            playIntro(stage, icon, coverLeft, coverRight, wordmark, skipHint);
        });
    }

    /** Positions the two cover panels exactly over the book, using the icon's real pixel size. */
    private void layoutBookCovers(View stage, View coverLeft, View coverRight) {
        int w = stage.getWidth();
        int h = stage.getHeight();
        int top = Math.round(h * BOOK_TOP_FRAC);
        int bottom = Math.round(h * BOOK_BOTTOM_FRAC);
        int left = Math.round(w * BOOK_LEFT_FRAC);
        int center = Math.round(w * BOOK_CENTER_FRAC);
        int right = Math.round(w * BOOK_RIGHT_FRAC);

        setBounds(coverLeft, left, top, center - left, bottom - top);
        coverLeft.setPivotX(0f);

        setBounds(coverRight, center, top, right - center, bottom - top);
        coverRight.setPivotX(right - center);
    }

    private void setBounds(View v, int left, int top, int width, int height) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        lp.width = width;
        lp.height = height;
        lp.leftMargin = left;
        lp.topMargin = top;
        v.setLayoutParams(lp);
    }

    private void playIntro(View stage, ImageView icon, View coverLeft, View coverRight,
                            ImageView wordmark, View skipHint) {
        stage.setAlpha(0f);
        stage.setScaleX(0.8f);
        stage.setScaleY(0.8f);
        coverLeft.setScaleX(1f);
        coverRight.setScaleX(1f);

        // The logo pops in already "closed" (book hidden by the cover panels).
        ValueAnimator entrance = ValueAnimator.ofFloat(0f, 1f);
        entrance.setDuration(420);
        entrance.setInterpolator(new OvershootInterpolator(1.3f));
        entrance.addUpdateListener(a -> {
            stage.setAlpha(a.getAnimatedFraction());
            float s = 0.8f + 0.2f * (float) a.getAnimatedValue();
            stage.setScaleX(s);
            stage.setScaleY(s);
        });
        entrance.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startBookOpen(icon, coverLeft, coverRight, wordmark, skipHint);
            }
        });
        entrance.start();
    }

    private void startBookOpen(ImageView icon, View coverLeft, View coverRight,
                                ImageView wordmark, View skipHint) {
        ValueAnimator open = ValueAnimator.ofFloat(1f, 0f);
        open.setDuration(650);
        open.setStartDelay(120);
        open.setInterpolator(new DecelerateInterpolator(1.4f));
        open.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            coverLeft.setScaleX(v);
            coverRight.setScaleX(v);
        });
        open.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                handler.postDelayed(() -> startBlink(icon, wordmark, skipHint), 200);
            }
        });
        open.start();
    }

    private void startBlink(ImageView icon, ImageView wordmark, View skipHint) {
        // Two quick blinks: open -> closed -> open -> (hold) -> closed -> open.
        Keyframe kf0 = Keyframe.ofFloat(0f, 1f);
        Keyframe kf1 = Keyframe.ofFloat(0.24f, 0.05f);
        Keyframe kf2 = Keyframe.ofFloat(0.42f, 1f);
        Keyframe kf3 = Keyframe.ofFloat(0.58f, 1f);
        Keyframe kf4 = Keyframe.ofFloat(0.80f, 0.05f);
        Keyframe kf5 = Keyframe.ofFloat(1f, 1f);
        PropertyValuesHolder pvh = PropertyValuesHolder.ofKeyframe("blink", kf0, kf1, kf2, kf3, kf4, kf5);
        ValueAnimator blink = ValueAnimator.ofPropertyValuesHolder(pvh);
        blink.setDuration(950);
        blink.setInterpolator(new AccelerateDecelerateInterpolator());
        blink.addUpdateListener(a -> icon.setScaleY((float) a.getAnimatedValue("blink")));
        blink.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                UiAnim.fadeSlideIn(wordmark, 0);
                skipHint.animate().alpha(0f).setStartDelay(200).setDuration(200).start();
                handler.postDelayed(IntroActivity.this::goToLogin, 650);
            }
        });
        blink.start();
    }

    private void goToLogin() {
        if (navigated || isFinishing()) return;
        navigated = true;
        startActivity(new Intent(this, LoginActivity.class));
        overridePendingTransition(R.anim.fade_in_fast, R.anim.fade_out_soft);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }
}
