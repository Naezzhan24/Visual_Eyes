package com.example.visualeyes;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

public class ForgotPasswordActivity extends AppCompatActivity {

    private TextView closeButton, resendOtpText, backToLoginText;
    private Button   confirmButton;
    private EditText phoneInput, otpInput;

    private GoogleTtsManager googleTts;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        googleTts = new GoogleTtsManager(this);

        closeButton     = findViewById(R.id.btnClose);
        resendOtpText   = findViewById(R.id.txtResendOtp);
        backToLoginText = findViewById(R.id.txtBackToLogin);
        confirmButton   = findViewById(R.id.btnConfirm);
        phoneInput      = findViewById(R.id.etPhoneNumber);
        otpInput        = findViewById(R.id.etOtp);

        animateViews();

        handler.postDelayed(() ->
                googleTts.speak("Forgot password screen. " +
                        "Please enter your phone number, then tap Send OTP. " +
                        "Enter the OTP you receive, then tap Confirm.", null), 600);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finishWithAnimation(); }
        });

        closeButton.setOnClickListener(v -> {
            animateClick(v);
            googleTts.speak("Going back to login.", () ->
                    handler.postDelayed(this::finishWithAnimation, 200));
        });

        resendOtpText.setOnClickListener(v -> {
            animateClick(v);
            String phone = phoneInput.getText().toString().trim();
            if (phone.isEmpty()) {
                phoneInput.setError("Enter phone number first");
                phoneInput.requestFocus();
                shakeView(phoneInput);
                googleTts.speak("Please enter your phone number first.", null);
            } else {
                Toast.makeText(this, "OTP sent to " + phone, Toast.LENGTH_SHORT).show();
                googleTts.speak("OTP sent to " + phone + ". Please check your messages.", null);
            }
        });

        confirmButton.setOnClickListener(v -> {
            animateClick(v);
            String phone = phoneInput.getText().toString().trim();
            String otp   = otpInput.getText().toString().trim();

            if (phone.isEmpty()) {
                phoneInput.setError("Required");
                phoneInput.requestFocus();
                shakeView(phoneInput);
                googleTts.speak("Please enter your phone number.", null);
                return;
            }
            if (otp.isEmpty()) {
                otpInput.setError("Enter OTP");
                otpInput.requestFocus();
                shakeView(otpInput);
                googleTts.speak("Please enter the OTP sent to your phone.", null);
                return;
            }

            googleTts.speak("OTP verified. Opening reset password screen.", () -> {
                Toast.makeText(this, "OTP Verified!", Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(ForgotPasswordActivity.this, ResetPasswordActivity.class);
                intent.putExtra("phone_number", phone);
                startActivity(intent);
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            });
        });

        backToLoginText.setOnClickListener(v -> {
            animateClick(v);
            googleTts.speak("Going back to login.", () ->
                    handler.postDelayed(this::finishWithAnimation, 200));
        });
    }

    private void animateViews() {
        View[] views = { closeButton, phoneInput, otpInput,
                resendOtpText, confirmButton, backToLoginText };
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null) continue;
            v.setAlpha(0f);
            v.setTranslationY(80f);
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * 90L).setDuration(500)
                    .setInterpolator(new OvershootInterpolator()).start();
        }
    }

    private void animateClick(View view) {
        if (view == null) return;
        view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(80).start())
                .start();
    }

    private void shakeView(View view) {
        if (view == null) return;
        view.animate().translationX(18f).setDuration(50)
                .withEndAction(() -> view.animate().translationX(-18f).setDuration(50)
                        .withEndAction(() -> view.animate().translationX(12f).setDuration(50)
                                .withEndAction(() -> view.animate().translationX(0f)
                                        .setDuration(50).start()).start()).start()).start();
    }

    private void finishWithAnimation() {
        finish();
        overridePendingTransition(R.anim.fade_in_fast, R.anim.slide_out_right);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (googleTts != null) googleTts.destroy();
        super.onDestroy();
    }
}
