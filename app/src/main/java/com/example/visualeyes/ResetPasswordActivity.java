package com.example.visualeyes;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
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

import java.util.HashMap;
import java.util.Map;

public class ResetPasswordActivity extends AppCompatActivity {

    private TextView  closeButton, backToLoginText;
    private EditText  newPasswordInput, confirmPasswordInput;
    private Button    resetPasswordButton;
    private CheckBox  cbShowPassword;

    private GoogleTtsManager googleTts;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String       phoneNumber  = "";
    private RequestQueue requestQueue;
    private static final String RESET_PASSWORD_URL =
            "http://192.168.1.100/VisualED/reset_password.php";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reset_password);

        googleTts    = new GoogleTtsManager(this);
        requestQueue = Volley.newRequestQueue(this);

        closeButton          = findViewById(R.id.btnClose);
        backToLoginText      = findViewById(R.id.txtBackToLogin);
        newPasswordInput     = findViewById(R.id.etNewPassword);
        confirmPasswordInput = findViewById(R.id.etConfirmPassword);
        resetPasswordButton  = findViewById(R.id.btnResetPassword);
        cbShowPassword       = findViewById(R.id.cbShowPassword);

        if (getIntent() != null && getIntent().hasExtra("phone_number")) {
            phoneNumber = getIntent().getStringExtra("phone_number");
        }

        setupShowPassword();
        animateViews();

        handler.postDelayed(() ->
                googleTts.speak("Reset password screen. " +
                        "Please enter your new password, confirm it, " +
                        "then tap Reset Password.", null), 600);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finishWithAnimation(); }
        });

        closeButton.setOnClickListener(v -> {
            animateClick(v);
            googleTts.speak("Going back.", () ->
                    handler.postDelayed(this::finishWithAnimation, 200));
        });

        backToLoginText.setOnClickListener(v -> {
            animateClick(v);
            googleTts.speak("Going back to login.", () ->
                    handler.postDelayed(this::finishWithAnimation, 200));
        });

        resetPasswordButton.setOnClickListener(v -> {
            animateClick(v);
            validateAndResetPassword();
        });
    }

    private void setupShowPassword() {
        if (cbShowPassword == null) return;
        cbShowPassword.setOnCheckedChangeListener((btn, isChecked) -> {
            newPasswordInput.setTransformationMethod(isChecked
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            confirmPasswordInput.setTransformationMethod(isChecked
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
            newPasswordInput.setSelection(newPasswordInput.getText().length());
            confirmPasswordInput.setSelection(confirmPasswordInput.getText().length());
        });
    }

    private void validateAndResetPassword() {
        String newPass     = newPasswordInput.getText().toString().trim();
        String confirmPass = confirmPasswordInput.getText().toString().trim();

        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            Toast.makeText(this, "Phone number not found.", Toast.LENGTH_SHORT).show();
            googleTts.speak("Phone number not found. Please go back and try again.", null);
            return;
        }
        if (newPass.isEmpty()) {
            newPasswordInput.setError("Enter new password");
            newPasswordInput.requestFocus();
            shakeView(newPasswordInput);
            googleTts.speak("Please enter your new password.", null);
            return;
        }
        if (newPass.length() < 6) {
            newPasswordInput.setError("Password must be at least 6 characters");
            newPasswordInput.requestFocus();
            shakeView(newPasswordInput);
            googleTts.speak("Password must be at least 6 characters.", null);
            return;
        }
        if (confirmPass.isEmpty()) {
            confirmPasswordInput.setError("Confirm your password");
            confirmPasswordInput.requestFocus();
            shakeView(confirmPasswordInput);
            googleTts.speak("Please confirm your password.", null);
            return;
        }
        if (!newPass.equals(confirmPass)) {
            confirmPasswordInput.setError("Passwords do not match");
            confirmPasswordInput.requestFocus();
            shakeView(confirmPasswordInput);
            googleTts.speak("Passwords do not match. Please try again.", null);
            return;
        }

        googleTts.speak("Resetting your password. Please wait.", null);
        resetPasswordInDatabase(phoneNumber, newPass);
    }

    private void resetPasswordInDatabase(String phone, String newPassword) {
        resetPasswordButton.setEnabled(false);

        StringRequest req = new StringRequest(Request.Method.POST, RESET_PASSWORD_URL,
                response -> {
                    resetPasswordButton.setEnabled(true);
                    try {
                        JSONObject json    = new JSONObject(response);
                        boolean    success = json.getBoolean("success");
                        String     message = json.getString("message");
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                        if (success) {
                            googleTts.speak("Password reset successful. Returning to login.", () -> {
                                Intent intent = new Intent(ResetPasswordActivity.this, LoginActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                                finish();
                                overridePendingTransition(R.anim.fade_in_fast, R.anim.slide_out_right);
                            });
                        } else {
                            googleTts.speak(message, null);
                        }
                    } catch (JSONException e) {
                        Toast.makeText(this, "Invalid server response.", Toast.LENGTH_SHORT).show();
                        googleTts.speak("Invalid server response. Please try again.", null);
                    }
                },
                error -> {
                    resetPasswordButton.setEnabled(true);
                    String msg = "Password reset failed.";
                    if (error instanceof TimeoutError)      msg = "Request timed out.";
                    else if (error instanceof NoConnectionError) msg = "No internet connection.";
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    googleTts.speak(msg, null);
                }
        ) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("phone_number", phone);
                params.put("new_password", newPassword);
                return params;
            }
        };
        requestQueue.add(req);
    }

    private void animateViews() {
        View[] views = { closeButton, newPasswordInput, confirmPasswordInput,
                cbShowPassword, resetPasswordButton, backToLoginText };
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null) continue;
            v.setAlpha(0f); v.setTranslationY(80f);
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
