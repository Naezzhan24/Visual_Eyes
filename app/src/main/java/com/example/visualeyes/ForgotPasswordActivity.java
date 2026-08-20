package com.example.visualeyes;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Patterns;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.NoConnectionError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.TimeoutError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

public class ForgotPasswordActivity extends AppCompatActivity {

    private TextView closeButton, resendOtpText, backToLoginText;
    private Button   confirmButton;
    private EditText emailInput, otpInput;

    private GoogleTtsManager googleTts;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RequestQueue requestQueue;

    private static final String REQUEST_CODE_URL =
            "http://10.118.24.232/visualed/request_reset_code.php";

    private boolean codeSent = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        googleTts    = new GoogleTtsManager(this);
        requestQueue = Volley.newRequestQueue(this);

        closeButton     = findViewById(R.id.btnClose);
        resendOtpText   = findViewById(R.id.txtResendOtp);
        backToLoginText = findViewById(R.id.txtBackToLogin);
        confirmButton   = findViewById(R.id.btnConfirm);
        emailInput      = findViewById(R.id.etEmail);
        otpInput        = findViewById(R.id.etOtp);

        animateViews();

        handler.postDelayed(() ->
                googleTts.speak("Forgot password screen. " +
                        "Please enter your registered email, then tap Resend Code to receive " +
                        "a verification code. Enter the code you receive, then tap Confirm.", null), 600);

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
            requestResetCode();
        });

        confirmButton.setOnClickListener(v -> {
            animateClick(v);
            String email = emailInput.getText().toString().trim();
            String code  = otpInput.getText().toString().trim();

            if (email.isEmpty()) {
                emailInput.setError("Required");
                emailInput.requestFocus();
                shakeView(emailInput);
                googleTts.speak("Please enter your email.", null);
                return;
            }
            if (!codeSent) {
                googleTts.speak("Please tap Resend Code first to receive your verification code.", null);
                return;
            }
            if (code.isEmpty()) {
                otpInput.setError("Enter the code");
                otpInput.requestFocus();
                shakeView(otpInput);
                googleTts.speak("Please enter the code sent to your email.", null);
                return;
            }

            googleTts.speak("Opening reset password screen.", () -> {
                Intent intent = new Intent(ForgotPasswordActivity.this, ResetPasswordActivity.class);
                intent.putExtra("email", email);
                intent.putExtra("code", code);
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

    private void requestResetCode() {
        String email = emailInput.getText().toString().trim();

        if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError("Enter a valid email");
            emailInput.requestFocus();
            shakeView(emailInput);
            googleTts.speak("Please enter a valid email address first.", null);
            return;
        }

        resendOtpText.setEnabled(false);
        googleTts.speak("Sending verification code.", null);

        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
        } catch (JSONException e) {
            resendOtpText.setEnabled(true);
            googleTts.speak("Something went wrong. Please try again.", null);
            return;
        }
        final String bodyStr = body.toString();

        StringRequest request = new StringRequest(
                Request.Method.POST,
                REQUEST_CODE_URL,
                response -> {
                    resendOtpText.setEnabled(true);
                    try {
                        JSONObject json    = new JSONObject(response);
                        boolean    success = json.getBoolean("success");
                        String     message = json.getString("message");
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                        if (success) {
                            codeSent = true;
                            otpInput.requestFocus();
                        }
                        googleTts.speak(message, null);
                    } catch (JSONException e) {
                        Toast.makeText(this, "Invalid server response.", Toast.LENGTH_SHORT).show();
                        googleTts.speak("Invalid server response. Please try again.", null);
                    }
                },
                error -> {
                    resendOtpText.setEnabled(true);
                    String msg = "Failed to send verification code.";
                    if (error instanceof TimeoutError)      msg = "Request timed out.";
                    else if (error instanceof NoConnectionError) msg = "No internet connection.";
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    googleTts.speak(msg, null);
                }
        ) {
            @Override public byte[] getBody() { return bodyStr.getBytes(StandardCharsets.UTF_8); }
            @Override public String getBodyContentType() { return "application/json; charset=utf-8"; }
        };

        requestQueue.add(request);
    }

    private void animateViews() {
        View[] views = { closeButton, emailInput, otpInput,
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

    @Override protected void onPause() {
        super.onPause();
        // Backgrounding the app or navigating away leaves this activity
        // paused, not destroyed — its TTS would otherwise keep talking.
        if (googleTts != null) googleTts.stopSpeaking();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (googleTts != null) googleTts.destroy();
        if (requestQueue != null) requestQueue.cancelAll(this);
        super.onDestroy();
    }
}
