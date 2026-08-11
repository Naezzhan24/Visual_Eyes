package com.example.visualeyes;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * App entry point: plays the VisualED intro animation (book opens, eye
 * blinks) once, then hands off to {@link LoginActivity}. Tapping anywhere
 * skips straight to login.
 */
public class IntroActivity extends AppCompatActivity {

    private boolean navigated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intro);

        View root = findViewById(R.id.introRoot);
        IntroLogoView introView = findViewById(R.id.introLogoView);
        TextView appName = findViewById(R.id.introAppName);
        TextView skipHint = findViewById(R.id.introSkipHint);

        appName.setAlpha(0f);
        appName.setText(stylizedAppName());
        root.setOnClickListener(v -> goToLogin());

        introView.playIntro(() -> {
            UiAnim.fadeSlideIn(appName, 0);
            skipHint.animate().alpha(0f).setStartDelay(200).setDuration(200).start();
            new Handler(Looper.getMainLooper()).postDelayed(this::goToLogin, 650);
        });
    }

    /** "Visual" + a slightly larger, bolder "ED", matching the wordmark in logo_visualed.png. */
    private CharSequence stylizedAppName() {
        String name = getString(R.string.app_name);
        SpannableString styled = new SpannableString(name);
        int split = Math.max(0, name.length() - 2);
        styled.setSpan(new RelativeSizeSpan(1.18f), split, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), split, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return styled;
    }

    private void goToLogin() {
        if (navigated || isFinishing()) return;
        navigated = true;
        startActivity(new Intent(this, LoginActivity.class));
        overridePendingTransition(R.anim.fade_in_fast, R.anim.fade_out_soft);
        finish();
    }
}
