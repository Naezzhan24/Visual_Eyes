package com.example.visualeyes;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.util.Log;
import android.util.Patterns;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LoginActivity extends AppCompatActivity {

    private EditText emailInput, passwordInput;
    private Button loginButton, voiceLoginButton;
    private TextView registerText, forgotPasswordText, txtVoiceStatus, txtTitle;
    private ImageView logoImage, togglePassword;
    private View mainPanel;

    private GoogleTtsManager googleTts;

    private GoogleSttManager googleStt;

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;

    private HybridSpeechManager hybridSpeech;

    private AuthManager authManager;
    private RequestQueue requestQueue;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isListening       = false;
    private boolean isVoiceLoginMode  = false;
    private boolean isPasswordVisible = false;
    private String  latestPartialText = "";
    private int     voiceRetryCount   = 0;

    private static final int MAX_VOICE_RETRY = 4;

    private boolean isAwaitingEntryChoice = false;
    private int     entryChoiceRetryCount = 0;
    private static final int MAX_ENTRY_CHOICE_RETRY = 2;

    private String lastSpokenInstruction = "";
    private long   lastTapTime = 0L;
    private int    tapCount = 0;
    private static final long TRIPLE_TAP_WINDOW_MS = 600L;

    private android.view.ScaleGestureDetector scaleGestureDetector;
    private View zoomTarget;
    private float currentZoomScale = 1.0f;
    private static final float MIN_ZOOM_SCALE = 1.0f;
    private static final float MAX_ZOOM_SCALE = 3.0f;

    private String emailUsername  = "";
    private String emailProvider  = "";
    private String emailExtension = "";

    private enum VoiceStep {
        USERNAME, CONFIRM_USERNAME,
        PROVIDER, CONFIRM_PROVIDER,
        EXTENSION, CONFIRM_EXTENSION,
        PASSWORD
    }
    private VoiceStep currentStep = VoiceStep.USERNAME;

    private static final long PROMPT_RETRY_DELAY  = 1600L;

    private long recordTimeoutForMode(String mode) {
        return "password".equals(mode) ? 4000L : 3200L;
    }
    private static final long ANDROID_ASR_TIMEOUT = 8500L;

    private static final float VOICE_SPEAKING_RATE = 1.15f;

    private void say(String text, GoogleTtsManager.TtsCallback callback) {
        googleTts.speak(text, VOICE_SPEAKING_RATE, callback);
    }

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    setVoiceStatus("Microphone enabled.");
                    handler.postDelayed(this::startVoiceLogin, 800);
                } else {
                    setVoiceStatus("Microphone permission denied.");
                    Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        authManager  = new AuthManager(this);
        requestQueue = Volley.newRequestQueue(this);

        bindViews();
        buildSpeechIntent();
        setupPasswordToggle();
        setupClickActions();
        setupPressAnimations();
        setupGestures();
        animateLoginEntrance();
        handler.postDelayed(this::startLogoPulse, 550);

        setVoiceStatus("Ready.");

        googleTts = new GoogleTtsManager(this);

        googleStt = new GoogleSttManager();

        buildAndAttachRecognizer();

        hybridSpeech = new HybridSpeechManager(this);
        hybridSpeech.initVosk(
                () -> Log.d("Login_STT", "Vosk model ready — offline fallback available."),
                () -> Log.e("Login_STT", "Vosk model failed to load — raw SpeechRecognizer fallback only."));

        setVoiceStatus("Voice engine ready.");

        String regEmail = getIntent().getStringExtra("registered_email");
        String regPass  = getIntent().getStringExtra("registered_password");
        if (regEmail != null) emailInput.setText(regEmail);
        if (regPass  != null) passwordInput.setText(regPass);

        boolean emailRemembered = false;
        if (regEmail == null) {
            String rememberedEmail = authManager.getRememberedEmail();
            if (!rememberedEmail.isEmpty()) {
                emailInput.setText(rememberedEmail);
                emailRemembered = true;
            }
        }

        boolean finalEmailRemembered = emailRemembered;
        handler.postDelayed(() -> {
            String tips = finalEmailRemembered
                    ? "Welcome back to Visual E D. Your email has been filled in for you — " +
                      "just enter or say your password to continue."
                    : "Welcome to Visual E D. Quick tip: triple tap anywhere on the screen " +
                      "to repeat the last instruction, or pinch with two fingers to zoom in.";
            if (hasAudioPermission()) {
                lastSpokenInstruction = tips + " You'll hear a short beep each time it's your turn to speak.";
                say(lastSpokenInstruction, this::promptEntryChoice);
            } else {
                lastSpokenInstruction = tips + " You can log in manually, or tap Register if you " +
                        "don't have an account yet. Enable microphone access anytime to use voice login.";
                say(lastSpokenInstruction, null);
            }
        }, 800);
    }

    private void bindViews() {
        mainPanel          = findViewById(R.id.mainPanel);
        logoImage          = findViewById(R.id.logoImage);
        txtTitle           = findViewById(R.id.txtTitle);
        emailInput         = findViewById(R.id.etEmail);
        passwordInput      = findViewById(R.id.etPassword);
        loginButton        = findViewById(R.id.btnLogin);
        voiceLoginButton   = findViewById(R.id.btnVoiceLogin);
        registerText       = findViewById(R.id.txtRegister);
        forgotPasswordText = findViewById(R.id.txtForgotPassword);
        txtVoiceStatus     = findViewById(R.id.txtVoiceStatus);
        togglePassword     = findViewById(R.id.togglePassword);
    }

    private void buildSpeechIntent() {
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);

        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3200L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L);
    }

    private void setupClickActions() {
        loginButton.setOnClickListener(v -> {
            bounceClick(loginButton);
            stopListeningSafely();
            isVoiceLoginMode = false;
            isAwaitingEntryChoice = false;
            loginUser();
        });

        voiceLoginButton.setOnClickListener(v -> {
            bounceClick(voiceLoginButton);
            isAwaitingEntryChoice = false;
            if (hasAudioPermission()) {
                startVoiceLogin();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
        });

        registerText.setOnClickListener(v -> {
            stopListeningSafely();
            isVoiceLoginMode = false;
            isAwaitingEntryChoice = false;
            startActivity(new Intent(LoginActivity.this, RegisterActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });

        forgotPasswordText.setOnClickListener(v -> {
            stopListeningSafely();
            isVoiceLoginMode = false;
            isAwaitingEntryChoice = false;
            startActivity(new Intent(LoginActivity.this, ForgotPasswordActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
    }

    private void setupPasswordToggle() {
        if (togglePassword == null) return;
        togglePassword.setOnClickListener(v -> {
            isPasswordVisible = !isPasswordVisible;
            passwordInput.setInputType(isPasswordVisible
                    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passwordInput.setSelection(passwordInput.getText().length());
            bounceClick(togglePassword);
        });
    }

    private boolean isVoiceInteractionActive() {
        return isVoiceLoginMode || isAwaitingEntryChoice;
    }

    private void promptEntryChoice() {
        isAwaitingEntryChoice = true;
        setVoiceStatus("New or existing user?");
        lastSpokenInstruction = "Are you a new user or an existing user? " +
                "Say new to create an account, or say existing to log in.";
        say(lastSpokenInstruction, () -> startGoogleListening("command"));
    }

    private void retryEntryChoice() {
        entryChoiceRetryCount++;
        if (entryChoiceRetryCount <= MAX_ENTRY_CHOICE_RETRY) {
            setVoiceStatus("Please say new or existing.");
            lastSpokenInstruction = "Sorry, I didn't catch that. Please say new, or existing.";
            say(lastSpokenInstruction, () -> startGoogleListening("command"));
        } else {
            isAwaitingEntryChoice = false;
            entryChoiceRetryCount = 0;
            stopListeningSafely();
            setVoiceStatus("Ready.");
            lastSpokenInstruction = "No problem. You can tap Register to create an account, " +
                    "or log in manually or by voice whenever you're ready. " +
                    "Triple tap the screen anytime to hear this again.";
            say(lastSpokenInstruction, null);
        }
    }

    private void setupGestures() {
        View root = findViewById(android.R.id.content);
        if (root == null) return;

        View child = (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 0)
                ? ((ViewGroup) root).getChildAt(0) : root;
        zoomTarget = child;

        scaleGestureDetector = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector detector) {
                        currentZoomScale *= detector.getScaleFactor();
                        currentZoomScale = Math.max(MIN_ZOOM_SCALE, Math.min(currentZoomScale, MAX_ZOOM_SCALE));
                        if (currentZoomScale < 1.05f) currentZoomScale = 1.0f;
                        zoomTarget.setPivotX(detector.getFocusX());
                        zoomTarget.setPivotY(detector.getFocusY());
                        zoomTarget.setScaleX(currentZoomScale);
                        zoomTarget.setScaleY(currentZoomScale);
                        return true;
                    }
                });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (scaleGestureDetector != null) {
            scaleGestureDetector.onTouchEvent(ev);
        }
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            long now = System.currentTimeMillis();
            if (now - lastTapTime < TRIPLE_TAP_WINDOW_MS) {
                tapCount++;
            } else {
                tapCount = 1;
            }
            lastTapTime = now;
            if (tapCount >= 3) {
                tapCount = 0;
                repeatLastInstruction();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void repeatLastInstruction() {
        if (lastSpokenInstruction == null || lastSpokenInstruction.trim().isEmpty()) {
            return;
        }
        vibrateShort();
        if (googleTts != null) {
            say(lastSpokenInstruction, null);
        }
    }

    private void vibrateShort() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(40);
        }
    }

    private void startVoiceLogin() {
        isVoiceLoginMode  = true;
        isAwaitingEntryChoice = false;
        voiceRetryCount   = 0;
        latestPartialText = "";
        emailUsername     = "";
        emailProvider     = "";
        emailExtension    = "";
        currentStep       = VoiceStep.USERNAME;

        emailInput.setText("");
        passwordInput.setText("");

        setVoiceStatus("Voice login started.");
        pulseVoiceStatus();

        lastSpokenInstruction = "Voice login started. You'll hear a short beep before each time to speak.";
        say(lastSpokenInstruction, this::promptCurrentStep);
    }

    private void promptCurrentStep() {
        if (!isVoiceLoginMode) return;
        stopListeningSafely();
        latestPartialText = "";

        switch (currentStep) {
            case USERNAME:
                setVoiceStatus("Say your username...");
                lastSpokenInstruction = "Please say the first part of your email, before the at sign. " +
                        "For example, johnsmith123.";
                say(lastSpokenInstruction, () -> startGoogleListening("email"));
                break;

            case CONFIRM_USERNAME:
                setVoiceStatus("Confirm: " + emailUsername);
                lastSpokenInstruction = "I heard your username as " + emailUsername + ". Is that correct? Say yes or no.";
                say(lastSpokenInstruction, () -> startGoogleListening("command"));
                break;

            case PROVIDER:
                setVoiceStatus("Say email provider...");
                lastSpokenInstruction = "Please say your email provider. For example, gmail, yahoo, or outlook.";
                say(lastSpokenInstruction, () -> startGoogleListening("command"));
                break;

            case CONFIRM_PROVIDER:
                setVoiceStatus("Confirm: " + emailProvider);
                lastSpokenInstruction = "I heard your provider as " + emailProvider + ". Is that correct? Say yes or no.";
                say(lastSpokenInstruction, () -> startGoogleListening("command"));
                break;

            case EXTENSION:
                setVoiceStatus("Say domain extension...");
                lastSpokenInstruction = "Please say the ending of your email address, like com, ph, or edu.";
                say(lastSpokenInstruction, () -> startGoogleListening("command"));
                break;

            case CONFIRM_EXTENSION:
                String fullEmail = emailUsername + "@" + emailProvider + "." + emailExtension;
                setVoiceStatus("Confirm email: " + fullEmail);
                lastSpokenInstruction = "Your email is " + emailUsername + " at " + emailProvider + " dot " + emailExtension + ". Is that correct? Say yes or no.";
                say(lastSpokenInstruction, () -> startGoogleListening("command"));
                break;

            case PASSWORD:
                setVoiceStatus("Say your password...");
                lastSpokenInstruction = "Please say your password now. If you're somewhere public, " +
                        "you may want to switch to manual login instead.";
                say(lastSpokenInstruction, () -> startGoogleListening("password"));
                break;
        }
    }

    private void startGoogleListening(String mode) {
        if (!isVoiceInteractionActive() || isFinishing() || isDestroyed()) return;

        if (!NetworkUtils.hasInternet(this)) {
            Log.e("Login_STT", "No internet detected — skipping Cloud STT, using on-device recognizer.");
            startAndroidListening();
            return;
        }

        setVoiceStatus("Get ready...");

        AudioCue.playThen(handler, () -> {
            if (!isVoiceInteractionActive() || isFinishing() || isDestroyed()) return;

            setVoiceStatus("Listening...");
            isListening = true;
            googleStt.startRecording();

            handler.removeCallbacksAndMessages("stt_stop");

            Runnable stopRunnable = () -> {
                if (isListening && isVoiceInteractionActive()) {
                    isListening = false;
                    setVoiceStatus("Processing...");
                    googleStt.stopAndRecognize(mode, new GoogleSttManager.SttCallback() {
                        @Override
                        public void onResult(String transcript) {
                            setVoiceStatus("Heard: " + transcript);
                            handleVoiceResult(transcript);
                        }

                        @Override
                        public void onError(String message) {

                            setVoiceStatus("Switching to fallback...");
                            startAndroidListening();
                        }
                    });
                }
            };

            handler.postDelayed(stopRunnable, recordTimeoutForMode(mode));
        });
    }

    private void startAndroidListening() {
        if (hybridSpeech != null && hybridSpeech.isReady()) {
            startVoskListening();
        } else {
            startRawAndroidListening();
        }
    }

    private void startVoskListening() {
        if (!isVoiceInteractionActive() || isFinishing() || isDestroyed()) return;

        stopListeningSafely();
        latestPartialText = "";
        setVoiceStatus("Get ready...");

        AudioCue.playThen(handler, () -> {
            if (!isVoiceInteractionActive() || isFinishing() || isDestroyed()) return;

            isListening = true;

            boolean useWhisper = NetworkUtils.hasInternet(this);
            setVoiceStatus(useWhisper ? "Listening (offline + refining)..." : "Listening (offline)...");

            hybridSpeech.startListening(new HybridSpeechManager.HybridSpeechCallback() {
                @Override public void onListeningStarted() {  }

                @Override public void onPartialResult(String partial) {
                    if (!isVoiceInteractionActive()) return;
                    setVoiceStatus("Hearing: " + partial);
                }

                @Override public void onFinalResult(String transcript) {
                    isListening = false;
                    if (!isVoiceInteractionActive()) return;
                    handleVoiceResult(transcript);
                }

                @Override public void onError(String message) {
                    isListening = false;
                    Log.e("Login_STT", "Vosk fallback failed (" + message + "), using raw SpeechRecognizer.");
                    if (!isVoiceInteractionActive()) return;
                    startRawAndroidListening();
                }
            }, useWhisper, currentFieldDescriptionForVosk());

            handler.postDelayed(() -> {
                if (isListening) hybridSpeech.stopAndTranscribe();
            }, ANDROID_ASR_TIMEOUT);
        });
    }

    private String currentFieldDescriptionForVosk() {
        if (isAwaitingEntryChoice) return "new or existing user";
        switch (currentStep) {
            case USERNAME:
            case CONFIRM_USERNAME:  return "email username";
            case PROVIDER:
            case CONFIRM_PROVIDER:  return "email provider";
            case EXTENSION:
            case CONFIRM_EXTENSION: return "email domain extension";
            case PASSWORD:          return "password";
            default:                return null;
        }
    }

    private void startRawAndroidListening() {
        if (speechRecognizer == null) buildAndAttachRecognizer();
        stopListeningSafely();
        latestPartialText = "";
        setVoiceStatus("Get ready...");

        AudioCue.playThen(handler, () -> {
            if (!isVoiceInteractionActive() || isFinishing() || isDestroyed()) return;

            try {
                setVoiceStatus("Listening (device fallback)...");
                speechRecognizer.startListening(speechIntent);
                isListening = true;

                handler.postDelayed(() -> {
                    if (isListening) {
                        isListening = false;
                        try {
                            if (speechRecognizer != null) speechRecognizer.stopListening();
                        } catch (Exception ignored) {}

                    }
                }, ANDROID_ASR_TIMEOUT);

            } catch (Exception e) {
                isListening = false;
                if (isAwaitingEntryChoice) {
                    retryEntryChoice();
                    return;
                }
                voiceRetryCount++;
                if (voiceRetryCount <= MAX_VOICE_RETRY) {
                    handler.postDelayed(this::promptCurrentStep, 900);
                } else {
                    abortVoiceLogin("I could not hear you. Please use manual login.");
                }
            }
        });
    }

    private void handleVoiceResult(String spokenText) {
        if (!isVoiceInteractionActive()) return;

        if (isAwaitingEntryChoice) {
            if (spokenText == null || spokenText.trim().isEmpty()) {
                retryEntryChoice();
                return;
            }
            String entryLower = spokenText.toLowerCase(Locale.US).trim();

            if (entryLower.contains("new") || entryLower.contains("register")) {
                isAwaitingEntryChoice = false;
                entryChoiceRetryCount = 0;
                stopListeningSafely();
                lastSpokenInstruction = "Opening registration.";
                say(lastSpokenInstruction, () -> {

                    Intent intent = new Intent(this, RegisterActivity.class);
                    intent.putExtra("autoStartVoice", true);
                    startActivity(intent);
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                });
            } else if (entryLower.contains("existing") || entryLower.contains("log")) {
                isAwaitingEntryChoice = false;
                entryChoiceRetryCount = 0;
                stopListeningSafely();
                setVoiceStatus("Ready.");
                lastSpokenInstruction = "Great. You can now say your username to log in by voice, " +
                        "or use the manual fields. Triple tap the screen anytime to hear instructions again.";
                say(lastSpokenInstruction, null);
            } else {
                retryEntryChoice();
            }
            return;
        }

        if (spokenText == null || spokenText.trim().isEmpty()) {
            retryOrStop("I did not catch that. Please try again.");
            return;
        }

        String lower = spokenText.toLowerCase(Locale.US).trim();

        if (lower.contains("cancel") || lower.contains("stop")) {
            abortVoiceLogin("Voice login cancelled.");
            return;
        }
        if (lower.contains("register")) {
            isVoiceLoginMode = false;
            stopListeningSafely();
            lastSpokenInstruction = "Opening registration.";
            say(lastSpokenInstruction, () -> {

                Intent intent = new Intent(this, RegisterActivity.class);
                intent.putExtra("autoStartVoice", true);
                startActivity(intent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            });
            return;
        }
        if (lower.contains("forgot")) {
            isVoiceLoginMode = false;
            stopListeningSafely();
            lastSpokenInstruction = "Opening forgot password.";
            say(lastSpokenInstruction, () -> {
                startActivity(new Intent(this, ForgotPasswordActivity.class));
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            });
            return;
        }

        switch (currentStep) {
            case USERNAME:
                emailUsername = lower.replaceAll("\\s+", "");
                if (emailUsername.isEmpty()) { retryOrStop("I did not catch your username."); return; }
                currentStep = VoiceStep.CONFIRM_USERNAME;
                promptCurrentStep();
                break;

            case CONFIRM_USERNAME:
                if (isYes(lower)) {
                    voiceRetryCount = 0;
                    currentStep = VoiceStep.PROVIDER;
                    promptCurrentStep();
                } else {
                    emailUsername = "";
                    currentStep = VoiceStep.USERNAME;
                    lastSpokenInstruction = "Okay, please say your username again.";
                    say(lastSpokenInstruction, () -> promptCurrentStep());
                }
                break;

            case PROVIDER:
                emailProvider = normalizeProvider(lower);
                if (emailProvider.isEmpty()) { retryOrStop("I did not catch your provider."); return; }
                currentStep = VoiceStep.CONFIRM_PROVIDER;
                promptCurrentStep();
                break;

            case CONFIRM_PROVIDER:
                if (isYes(lower)) {
                    voiceRetryCount = 0;
                    currentStep = VoiceStep.EXTENSION;
                    promptCurrentStep();
                } else {
                    emailProvider = "";
                    currentStep = VoiceStep.PROVIDER;
                    lastSpokenInstruction = "Okay, please say your provider again.";
                    say(lastSpokenInstruction, () -> promptCurrentStep());
                }
                break;

            case EXTENSION:
                emailExtension = normalizeExtension(lower);
                if (emailExtension.isEmpty()) { retryOrStop("I did not catch your extension."); return; }
                currentStep = VoiceStep.CONFIRM_EXTENSION;
                promptCurrentStep();
                break;

            case CONFIRM_EXTENSION:
                if (isYes(lower)) {
                    voiceRetryCount = 0;
                    String fullEmail = emailUsername + "@" + emailProvider + "." + emailExtension;
                    emailInput.setText(fullEmail);
                    emailInput.setSelection(fullEmail.length());
                    currentStep = VoiceStep.PASSWORD;
                    promptCurrentStep();
                } else {
                    emailExtension = "";
                    currentStep = VoiceStep.EXTENSION;
                    lastSpokenInstruction = "Okay, please say your extension again.";
                    say(lastSpokenInstruction, () -> promptCurrentStep());
                }
                break;

            case PASSWORD:
                String password = processSpokenPassword(spokenText);
                if (password.isEmpty()) { retryOrStop("I did not catch your password."); return; }
                passwordInput.setText(password);
                passwordInput.setSelection(password.length());
                isVoiceLoginMode = false;
                stopListeningSafely();
                setVoiceStatus("Password received. Logging in...");
                lastSpokenInstruction = "Password received. Logging you in now.";
                say(lastSpokenInstruction, () ->
                        handler.postDelayed(this::loginUser, 500));
                break;
        }
    }

    private boolean isYes(String text) {
        return text.contains("yes") || text.contains("correct")
                || text.contains("yep") || text.contains("yeah");
    }

    private String normalizeProvider(String text) {
        if (text.contains("gmail"))   return "gmail";
        if (text.contains("yahoo"))   return "yahoo";
        if (text.contains("outlook")) return "outlook";
        if (text.contains("hotmail")) return "hotmail";
        if (text.contains("icloud"))  return "icloud";
        return text.replaceAll("\\s+", "").toLowerCase();
    }

    private String normalizeExtension(String text) {
        if (text.contains("com"))    return "com";
        if (text.contains("ph"))     return "ph";
        if (text.contains("edu"))    return "edu";
        if (text.contains("net"))    return "net";
        if (text.contains("org"))    return "org";
        if (text.contains("gov"))    return "gov";
        return text.replaceAll("\\s+", "").toLowerCase();
    }

    private void retryOrStop(String message) {
        voiceRetryCount++;
        if (voiceRetryCount <= MAX_VOICE_RETRY) {
            setVoiceStatus(message);
            lastSpokenInstruction = message + " Please try again.";
            say(lastSpokenInstruction, () ->
                    handler.postDelayed(this::promptCurrentStep, PROMPT_RETRY_DELAY));
        } else {
            abortVoiceLogin("I am having trouble hearing you. Please use manual login.");
        }
    }

    private void abortVoiceLogin(String message) {
        voiceRetryCount  = 0;
        isVoiceLoginMode = false;
        stopListeningSafely();
        setVoiceStatus(message);
        lastSpokenInstruction = message;
        say(lastSpokenInstruction, null);
        shakeView(txtVoiceStatus);
    }

    private void buildAndAttachRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle p) {
                isListening = true;
                latestPartialText = "";
                setVoiceStatus("Listening...");
            }

            @Override public void onBeginningOfSpeech() { setVoiceStatus("Voice detected..."); }
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
                setVoiceStatus("Processing...");
            }

            @Override public void onEvent(int e, Bundle p) {}

            @Override
            public void onError(int error) {
                isListening = false;
                if (!isVoiceInteractionActive()) return;

                if (!latestPartialText.trim().isEmpty()) {
                    handleVoiceResult(latestPartialText);
                    return;
                }

                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    try {
                        if (speechRecognizer != null) {
                            speechRecognizer.cancel();
                            speechRecognizer.destroy();
                        }
                    } catch (Exception ignored) {}
                    speechRecognizer = null;
                    handler.postDelayed(() -> {
                        buildAndAttachRecognizer();
                        startRawAndroidListening();
                    }, 800);
                    return;
                }

                if (isAwaitingEntryChoice) {
                    retryEntryChoice();
                    return;
                }
                retryOrStop("I did not catch that.");
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;
                if (!isVoiceInteractionActive()) return;
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                String best = getBestResult(matches);
                if (!best.isEmpty()) handleVoiceResult(best);
                else if (!latestPartialText.trim().isEmpty()) handleVoiceResult(latestPartialText);
                else if (isAwaitingEntryChoice) retryEntryChoice();
                else retryOrStop("I did not catch that.");
            }

            @Override
            public void onPartialResults(Bundle partial) {
                if (!isListening || !isVoiceInteractionActive()) return;

                ArrayList<String> p = partial.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                if (p != null && !p.isEmpty()) {
                    latestPartialText = p.get(0).trim();
                    setVoiceStatus("Hearing: " + latestPartialText);
                }
            }
        });
    }

    private String getBestResult(ArrayList<String> matches) {
        if (matches == null || matches.isEmpty()) return "";
        String best = "";
        for (String s : matches) {
            if (s != null && s.trim().length() > best.length()) best = s.trim();
        }
        return best;
    }

    private void loginUser() {
        String email    = emailInput.getText().toString().trim().toLowerCase(Locale.US);
        String password = passwordInput.getText().toString().trim();

        if (email.isEmpty()) { emailInput.setError("Email required"); emailInput.requestFocus(); return; }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) { emailInput.setError("Invalid email"); emailInput.requestFocus(); return; }
        if (password.isEmpty()) { passwordInput.setError("Password required"); passwordInput.requestFocus(); return; }

        loginButton.setEnabled(false);
        voiceLoginButton.setEnabled(false);
        setVoiceStatus("Logging in...");

        String url = ApiConfig.STUDENTS
                + "?email=eq." + android.net.Uri.encode(email)
                + "&password=eq." + android.net.Uri.encode(password)
                + "&select=id,first_name,middle_name,last_name,age,school_id,email,password,"
                +          "approval_status,impairment_level,recommended_text_size"
                + "&limit=1";

        JsonArrayRequest request = new JsonArrayRequest(Request.Method.GET, url, null,
                response -> {
                    loginButton.setEnabled(true);
                    voiceLoginButton.setEnabled(true);
                    handleLoginResponse(response, email, password);
                },
                error -> {
                    loginButton.setEnabled(true);
                    voiceLoginButton.setEnabled(true);
                    String msg = "Login failed.";
                    if (error.networkResponse != null) {
                        try {
                            msg = "Login failed: " + new String(
                                    error.networkResponse.data, StandardCharsets.UTF_8);
                        } catch (Exception ignored) {}
                    }
                    setVoiceStatus("Login failed.");
                    lastSpokenInstruction = "Login failed. Please check your connection or account details.";
                    say(lastSpokenInstruction, null);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    shakeView(mainPanel);
                }
        ) {
            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> h = new HashMap<>();
                h.put("apikey", ApiConfig.SUPABASE_KEY);
                h.put("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY);
                h.put("Content-Type", "application/json");
                h.put("Accept", "application/json");
                return h;
            }
        };

        request.setRetryPolicy(new DefaultRetryPolicy(15000, 1, 1.0f));
        requestQueue.add(request);
    }

    private void handleLoginResponse(org.json.JSONArray response, String email, String password) {
        try {
            if (response == null || response.length() == 0) {
                setVoiceStatus("Invalid account.");
                lastSpokenInstruction = "Incorrect email or password. Please try again.";
                say(lastSpokenInstruction, null);
                Toast.makeText(this, "Invalid email or password.", Toast.LENGTH_LONG).show();
                shakeView(mainPanel);
                return;
            }

            JSONObject student = response.getJSONObject(0);
            String approvalStatus = student.optString("approval_status", "pending");

            if (!approvalStatus.equalsIgnoreCase("approved")) {
                setVoiceStatus("Account not yet approved.");
                lastSpokenInstruction = "Your account has not been approved yet. Please wait for admin approval.";
                say(lastSpokenInstruction, null);
                Toast.makeText(this, "Account not yet approved.", Toast.LENGTH_LONG).show();
                shakeView(mainPanel);
                return;
            }

            String studentId    = student.optString("id", "");
            String firstName    = student.optString("first_name", "");
            String middleName   = student.optString("middle_name", "");
            String lastName     = student.optString("last_name", "");
            String age          = student.optString("age", "");
            String schoolId     = student.optString("school_id", "");
            String studentEmail = student.optString("email", email);
            String impairment   = student.optString("impairment_level", "");
            String textSize     = student.optString("recommended_text_size", "");

            authManager.saveLoggedInStudent(studentId, firstName, middleName, lastName,
                    age, schoolId, studentEmail, password);

            boolean hasAssessment = isValidAssessment(impairment) && isValidAssessment(textSize);

            if (hasAssessment) {
                authManager.setProfileCompleted(true);
                getSharedPreferences("VisualEyesPrefs", MODE_PRIVATE).edit()
                        .putString("impairmentLevel", impairment)
                        .putString("recommendedTextSize", textSize + "sp")
                        .apply();
                setVoiceStatus("Login successful.");
                lastSpokenInstruction = "Login successful. Opening home.";
                say(lastSpokenInstruction, () ->
                        openNextScreen(HomeActivity.class));
            } else {
                authManager.setProfileCompleted(false);
                setVoiceStatus("Login successful.");
                lastSpokenInstruction = "Login successful. Continuing to the impairment test.";
                say(lastSpokenInstruction, () ->
                        openNextScreen(TextSizeTestActivity.class));
            }

        } catch (Exception e) {
            setVoiceStatus("Login error.");
            Toast.makeText(this, "Login parsing error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openNextScreen(Class<?> target) {
        handler.postDelayed(() -> {
            if (mainPanel != null) {
                mainPanel.animate().alpha(0f).setDuration(280).withEndAction(() -> {
                    startActivity(new Intent(LoginActivity.this, target));
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    finish();
                }).start();
            } else {
                startActivity(new Intent(LoginActivity.this, target));
                finish();
            }
        }, 500);
    }

    private String processSpokenPassword(String spoken) {
        String p     = spoken.trim();
        String lower = p.toLowerCase(Locale.US);
        String[] prefixes = {
                "my password is ", "the password is ", "password is ", "password ",
                "my password is ", "the password is "
        };
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                p = p.substring(prefix.length()).trim();
                break;
            }
        }
        p = p.replace(" space ", "")
                .replace(" underscore ", "_")
                .replace(" dash ", "-")
                .replace(" hyphen ", "-")
                .replace(" at ", "@")
                .replace(" dot ", ".");
        return p.replaceAll("\\s+", "");
    }

    private boolean isValidAssessment(String value) {
        return value != null && !value.trim().isEmpty()
                && !value.equalsIgnoreCase("null")
                && !value.equalsIgnoreCase("not available")
                && !value.equalsIgnoreCase("0");
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void stopListeningSafely() {
        isListening = false;
        if (googleStt != null) googleStt.cancel();
        if (hybridSpeech != null) hybridSpeech.cancel();
        try { if (speechRecognizer != null) speechRecognizer.stopListening(); } catch (Exception ignored) {}
        try { if (speechRecognizer != null) speechRecognizer.cancel(); } catch (Exception ignored) {}
    }

    private void setVoiceStatus(String msg) {
        runOnUiThread(() -> {
            if (txtVoiceStatus != null) txtVoiceStatus.setText("Voice Status: " + msg);
        });
    }

    private void setupPressAnimations() {
        for (View v : new View[]{loginButton, voiceLoginButton, registerText,
                forgotPasswordText, togglePassword}) {
            if (v == null) continue;
            v.setOnTouchListener((view, event) -> {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                        break;
                }
                return false;
            });
        }
    }

    private void bounceClick(View view) {
        if (view == null) return;
        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(120)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .start())
                .start();
    }

    private void animateLoginEntrance() {
        if (logoImage != null) UiAnim.popIn(logoImage, 0);
        View[] views = { txtTitle, mainPanel, emailInput, passwordInput,
                loginButton, voiceLoginButton, registerText, forgotPasswordText, txtVoiceStatus };
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null) continue;
            v.setAlpha(0f);
            v.setTranslationY(70f);
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * 70L + 70L).setDuration(450)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .start();
        }
    }

    private void startLogoPulse() {
        if (logoImage == null) return;
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(logoImage, "scaleX", 1f, 1.06f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(logoImage, "scaleY", 1f, 1.06f);
        scaleX.setRepeatMode(ValueAnimator.REVERSE);
        scaleX.setRepeatCount(ValueAnimator.INFINITE);
        scaleY.setRepeatMode(ValueAnimator.REVERSE);
        scaleY.setRepeatCount(ValueAnimator.INFINITE);

        AnimatorSet pulse = new AnimatorSet();
        pulse.playTogether(scaleX, scaleY);
        pulse.setDuration(1400);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.start();
    }

    private void pulseVoiceStatus() {
        if (txtVoiceStatus == null) return;
        txtVoiceStatus.animate().scaleX(1.05f).scaleY(1.05f).setDuration(160)
                .withEndAction(() -> txtVoiceStatus.animate()
                        .scaleX(1f).scaleY(1f).setDuration(160).start())
                .start();
    }

    private void shakeView(View view) {
        if (view == null) return;
        view.animate().translationX(18f).setDuration(70)
                .withEndAction(() -> view.animate().translationX(-18f).setDuration(70)
                        .withEndAction(() -> view.animate().translationX(0f)
                                .setDuration(70).start()).start()).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListeningSafely();

        if (googleTts != null) googleTts.stopSpeaking();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        stopListeningSafely();
        if (hybridSpeech != null) hybridSpeech.destroy();
        if (googleStt != null) googleStt.destroy();
        if (googleTts != null) googleTts.destroy();
        try {
            if (speechRecognizer != null) {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            }
        } catch (Exception ignored) {}
    }
}
