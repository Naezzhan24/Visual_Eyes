package com.example.visualeyes;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * App entry point: plays the VisualED intro animation (book opens, eye
 * blinks) once, then hands off to {@link LoginActivity}. Tapping anywhere
 * skips straight to login.
 */
public class IntroActivity extends AppCompatActivity {

    private static final int LETTER_STAGGER_MS = 45;
    private static final int LETTER_POP_DURATION_MS = 340;
    private static final float BASE_TEXT_SP = 30f;
    private static final float ACCENT_TEXT_SP = BASE_TEXT_SP * 1.18f; // the "ED" in the wordmark

    private boolean navigated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intro);

        View root = findViewById(R.id.introRoot);
        IntroLogoView introView = findViewById(R.id.introLogoView);
        LinearLayout appNameContainer = findViewById(R.id.introAppNameContainer);
        TextView skipHint = findViewById(R.id.introSkipHint);

        buildAppNameLetters(appNameContainer);
        root.setOnClickListener(v -> goToLogin());

        introView.playIntro(() -> {
            animateAppNameLetters(appNameContainer);
            skipHint.animate().alpha(0f).setStartDelay(200).setDuration(200).start();
            new Handler(Looper.getMainLooper()).postDelayed(this::goToLogin, 650 + lettersDurationMs(appNameContainer));
        });
    }

    /**
     * Builds one TextView per character of the app name (last two — "ED" —
     * a touch larger/bolder, matching the wordmark in logo_visualed.png),
     * starting hidden and shrunk so {@link #animateAppNameLetters} can pop
     * them in one at a time like the name is being written.
     */
    private void buildAppNameLetters(LinearLayout container) {
        String name = getString(R.string.app_name);
        int accentStart = Math.max(0, name.length() - 2);
        int color = ContextCompat.getColor(this, R.color.color_text_on_primary);

        // TalkBack should hear "VisualED" once, not each letter individually.
        container.setContentDescription(name);
        container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        for (int i = 0; i < name.length(); i++) {
            TextView letter = new TextView(this);
            letter.setText(String.valueOf(name.charAt(i)));
            letter.setTextColor(color);
            letter.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, i >= accentStart ? ACCENT_TEXT_SP : BASE_TEXT_SP);
            letter.setAlpha(0f);
            letter.setScaleX(0.3f);
            letter.setScaleY(0.3f);
            letter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            container.addView(letter);
        }
    }

    /** Pops each letter in, one after another, growing past full size then settling — like it's being written. */
    private void animateAppNameLetters(ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View letter = container.getChildAt(i);
            letter.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay((long) i * LETTER_STAGGER_MS)
                    .setDuration(LETTER_POP_DURATION_MS)
                    .setInterpolator(new OvershootInterpolator(2.4f))
                    .start();
        }
    }

    private long lettersDurationMs(ViewGroup container) {
        return (long) container.getChildCount() * LETTER_STAGGER_MS + LETTER_POP_DURATION_MS;
    }

    private void goToLogin() {
        if (navigated || isFinishing()) return;
        navigated = true;
        startActivity(new Intent(this, LoginActivity.class));
        overridePendingTransition(R.anim.fade_in_fast, R.anim.fade_out_soft);
        finish();
    }
}
