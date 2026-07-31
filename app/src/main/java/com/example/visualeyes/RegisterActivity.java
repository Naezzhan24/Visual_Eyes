package com.example.visualeyes;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
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
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.visualed.voice.NameNormalizer;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private ScrollView registerScrollView;
    private EditText fname, mname, lname, age, yearLevel, schoolid, email, password, confirmPassword;
    private Button continueBtn, voiceRegisterBtn;
    private TextView txtVoiceStatus;
    private ImageView logoImage;
    private ImageView togglePassword1, togglePassword2;
    private boolean isPassword1Visible = false;
    private boolean isPassword2Visible = false;

    private GoogleTtsManager googleTts;

    private GoogleSttManager googleStt;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;

    private HybridSpeechManager hybridSpeech;
    private SttCascadeSession   cascadeSession;
    private NameNormalizer      nameNormalizer;

    private boolean autoStartVoice = false;

    private AuthManager authManager;
    private RequestQueue requestQueue;

    private EditText[] voiceFields;
    private int currentFieldIndex = 0;

    private boolean isVoiceMode             = false;
    private boolean isListening             = false;
    private boolean isConfirmingField       = false;
    private boolean isAdvancingField        = false;
    private boolean hasProcessedSpeech      = false;
    private String  pendingValue            = "";
    private String  latestPartialText       = "";
    private int     retryCount              = 0;

    private static final int MAX_RETRY      = 4;

    private static final long VOICE_INPUT_TIMEOUT_MS = 11000L;

    private static final float VOICE_SPEAKING_RATE = 1.10f;

    private void say(String text, GoogleTtsManager.TtsCallback callback) {
        googleTts.speak(text, VOICE_SPEAKING_RATE, callback);
    }

    private int voiceSessionId = 0;

    private String lastSpokenInstruction = "";
    private long   lastTapTime = 0L;
    private int    tapCount = 0;
    private static final long TRIPLE_TAP_WINDOW_MS = 600L;

    private android.view.ScaleGestureDetector scaleGestureDetector;
    private View zoomTarget;
    private float currentZoomScale = 1.0f;
    private static final float MIN_ZOOM_SCALE = 1.0f;
    private static final float MAX_ZOOM_SCALE = 3.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean micPermissionRequestInFlight = false;
    private boolean lastKnownMicPermission = false;

    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                micPermissionRequestInFlight = false;
                lastKnownMicPermission = isGranted;
                if (isGranted) {
                    updateVoiceStatus("Microphone enabled.");
                    lastSpokenInstruction = "Microphone permission granted.";
                    say(lastSpokenInstruction, () ->
                            MicReadiness.awaitReady(handler, this::startVoiceRegistration));
                } else {
                    updateVoiceStatus("Microphone permission denied.");
                    lastSpokenInstruction = "Microphone permission is required for voice registration.";
                    say(lastSpokenInstruction, null);
                }
            });

    private boolean hasAudioPermission() {
        return MicPermissionHelper.hasAudioPermission(this);
    }

    private void requestMicPermissionWithRationale() {
        updateVoiceStatus("Requesting microphone access...");
        lastSpokenInstruction = "I need access to your microphone for voice registration. " +
                "A system permission dialog will appear next — please allow it.";
        say(lastSpokenInstruction, () -> {
            MicPermissionHelper.markRequested(this);
            micPermissionRequestInFlight = true;
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        });
    }

    private void explainPermanentDenialAndOpenSettings() {
        updateVoiceStatus("Microphone permission blocked.");
        lastSpokenInstruction = "Microphone access was previously denied and can't be requested again here. " +
                "Opening app settings so you can enable it under Permissions.";
        say(lastSpokenInstruction, () -> MicPermissionHelper.openAppSettings(this));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        authManager  = new AuthManager(this);
        requestQueue = Volley.newRequestQueue(this);
        nameNormalizer = new NameNormalizer(this);

        lastKnownMicPermission = hasAudioPermission();

        googleTts = new GoogleTtsManager(this, false);
        googleStt = new GoogleSttManager(this, false);

        hybridSpeech = new HybridSpeechManager(this, false);
        hybridSpeech.initVosk(
                () -> Log.d("Register_STT", "Vosk model ready — offline fallback available."),
                () -> Log.e("Register_STT", "Vosk model failed to load — raw SpeechRecognizer fallback only."));

        cascadeSession = new SttCascadeSession(googleStt, hybridSpeech, handler, true);

        autoStartVoice = getIntent().getBooleanExtra("autoStartVoice", false);

        bindViews();

        voiceFields = new EditText[]{
                fname, mname, lname, age, yearLevel, schoolid, email, password, confirmPassword
        };

        setupAutoScrollForTyping();
        setupPasswordToggles();
        buildSpeechIntent();
        setupGestures();
        updateVoiceStatus("Ready.");
        animateViews();
        handler.postDelayed(this::startLogoPulse, 550);

        UiAnim.attachPressFeedback(continueBtn);
        UiAnim.attachPressFeedback(voiceRegisterBtn);

        handler.postDelayed(() -> {
            if (autoStartVoice) {

                lastSpokenInstruction = "Student registration. Let's continue by voice. " +
                        "You can switch to typing anytime by tapping a field.";
                say(lastSpokenInstruction, () -> {
                    if (hasAudioPermission()) {
                        startVoiceRegistration();
                    } else if (MicPermissionHelper.isPermanentlyDenied(this)) {
                        explainPermanentDenialAndOpenSettings();
                    } else if (MicPermissionHelper.isScreenReaderActive(this)) {
                        lastSpokenInstruction = "Tap Voice Register when you're ready to enable the microphone.";
                        say(lastSpokenInstruction, null);
                    } else {
                        requestMicPermissionWithRationale();
                    }
                });
            } else {
                lastSpokenInstruction = "Student registration. You may fill in the fields manually, " +
                        "or press the Voice Register button to fill each field by voice.";
                say(lastSpokenInstruction, null);
            }
        }, 800);

        continueBtn.setOnClickListener(v -> {
            animateClick(v);
            isVoiceMode = false;
            stopListeningSafely();
            validateAndContinue();
        });

        voiceRegisterBtn.setOnClickListener(v -> {
            animateClick(v);
            if (hasAudioPermission()) {
                startVoiceRegistration();
            } else if (MicPermissionHelper.isPermanentlyDenied(this)) {
                explainPermanentDenialAndOpenSettings();
            } else {
                requestMicPermissionWithRationale();
            }
        });
    }

    private void bindViews() {
        registerScrollView = (ScrollView) ((android.view.ViewGroup)
                findViewById(android.R.id.content)).getChildAt(0);
        fname           = findViewById(R.id.fname);
        mname           = findViewById(R.id.mname);
        lname           = findViewById(R.id.lname);
        age             = findViewById(R.id.age);
        yearLevel       = findViewById(R.id.yearLevel);
        schoolid        = findViewById(R.id.schoolid);
        email           = findViewById(R.id.email);
        password        = findViewById(R.id.password);
        confirmPassword = findViewById(R.id.confirmPassword);
        continueBtn     = findViewById(R.id.continueBtn);
        voiceRegisterBtn= findViewById(R.id.voiceRegisterBtn);
        txtVoiceStatus  = findViewById(R.id.txtVoiceStatus);
        logoImage       = findViewById(R.id.logoImage);
        togglePassword1 = findViewById(R.id.togglePassword1);
        togglePassword2 = findViewById(R.id.togglePassword2);
    }

    private void setupPasswordToggles() {
        if (togglePassword1 != null) {
            togglePassword1.setOnClickListener(v -> {
                isPassword1Visible = !isPassword1Visible;
                password.setInputType(isPassword1Visible
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                password.setSelection(password.getText().length());
                animateClick(togglePassword1);
            });
            UiAnim.attachPressFeedback(togglePassword1);
        }
        if (togglePassword2 != null) {
            togglePassword2.setOnClickListener(v -> {
                isPassword2Visible = !isPassword2Visible;
                confirmPassword.setInputType(isPassword2Visible
                        ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                confirmPassword.setSelection(confirmPassword.getText().length());
                animateClick(togglePassword2);
            });
            UiAnim.attachPressFeedback(togglePassword2);
        }
    }

    private void buildSpeechIntent() {
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);

        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        speechIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        speechIntent.putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                new ArrayList<>(Arrays.asList(GoogleSttManager.NAME_PHRASE_BOOST)));

        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L);
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

    private void startVoiceRegistration() {
        isVoiceMode       = true;
        isConfirmingField = false;
        isAdvancingField  = false;
        retryCount        = 0;
        latestPartialText = "";
        pendingValue      = "";
        currentFieldIndex = findFirstEmptyFieldIndex();

        if (currentFieldIndex >= voiceFields.length) {
            lastSpokenInstruction = "All fields are already filled. Please review and press Continue.";
            say(lastSpokenInstruction, null);
            updateVoiceStatus("All fields filled.");
            return;
        }

        updateVoiceStatus("Voice registration started.");
        lastSpokenInstruction = "Voice registration started. I will ask you to say each field one by one. " +
                "You'll hear a short beep before each time to start speaking.";
        say(lastSpokenInstruction,
                () -> handler.postDelayed(this::promptCurrentField, 400));
    }

    private void promptCurrentField() {
        if (!isVoiceMode) return;
        skipFilledFields();

        if (currentFieldIndex >= voiceFields.length) {
            finishVoiceRegistration();
            return;
        }

        EditText field = voiceFields[currentFieldIndex];
        field.requestFocus();
        scrollToField(field);
        isConfirmingField = false;
        hasProcessedSpeech = false;
        latestPartialText  = "";

        updateVoiceStatus("Say your " + getFieldName(currentFieldIndex) + "...");
        lastSpokenInstruction = getPromptForField(currentFieldIndex);
        say(lastSpokenInstruction, this::startVoiceInput);
    }

    private void confirmField(String value) {
        if (!isVoiceMode) return;
        isConfirmingField  = true;
        hasProcessedSpeech = false;
        latestPartialText  = "";
        pendingValue       = value;

        updateVoiceStatus("Confirm: " + value);
        lastSpokenInstruction = buildConfirmMessage(currentFieldIndex, value);
        say(lastSpokenInstruction, this::startVoiceInput);
    }

    private String sttModeForField(int index) {
        switch (index) {
            case 0:
            case 1:
            case 2: return "name";
            case 6: return "email";
            case 7:
            case 8: return "password";
            default: return "command";
        }
    }

    private void startVoiceInput() {
        if (!isVoiceMode || isFinishing() || isDestroyed()) return;

        if (SpeechEngineHealth.isBuiltInRecognizerBroken(this)) {
            cascadeFromBuiltIn();
            return;
        }

        stopListeningSafely();

        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("Register_STT", "Built-in recognizer unavailable on this device — using Cloud STT.");
            SpeechEngineHealth.markBuiltInRecognizerBroken(this);
            cascadeFromBuiltIn();
            return;
        }

        final int mySession = voiceSessionId;

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override public void onReadyForSpeech(Bundle p) {
                if (mySession != voiceSessionId) return;
                isListening        = true;
                hasProcessedSpeech = false;
                latestPartialText  = "";
                updateVoiceStatus("Listening...");
            }

            @Override public void onBeginningOfSpeech() {
                if (mySession != voiceSessionId) return;
                updateVoiceStatus("Voice detected...");
            }

            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}

            @Override public void onEndOfSpeech() {
                if (mySession != voiceSessionId) return;
                isListening = false;
                updateVoiceStatus("Processing...");

                handler.postDelayed(() -> {
                    if (mySession != voiceSessionId) return;
                    if (isVoiceMode && !hasProcessedSpeech
                            && !latestPartialText.trim().isEmpty()) {
                        hasProcessedSpeech = true;
                        handleSpokenText(latestPartialText.trim());
                        latestPartialText = "";
                    }
                }, 900);
            }

            @Override public void onError(int error) {
                if (mySession != voiceSessionId) return;
                isListening = false;
                if (!isVoiceMode || isAdvancingField) return;

                Log.e("Register_STT", "Built-in recognizer onError code=" + error
                        + " (" + speechErrorName(error) + ")");

                if (SpeechEngineHealth.isRecognizerIncompatible(error)) {
                    Log.e("Register_STT", "Built-in recognizer is not usable on this device — "
                            + "skipping it from now on.");
                    SpeechEngineHealth.markBuiltInRecognizerBroken(RegisterActivity.this);
                }

                if (!latestPartialText.trim().isEmpty()) {
                    String heard = latestPartialText.trim();
                    latestPartialText = "";
                    handleSpokenText(heard);
                    return;
                }

                cascadeFromBuiltIn();
            }

            @Override public void onResults(Bundle results) {
                if (mySession != voiceSessionId) return;
                isListening = false;
                if (hasProcessedSpeech) return;

                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String spoken = getBestResult(matches);

                if (spoken.isEmpty() && !latestPartialText.trim().isEmpty()) {
                    spoken = latestPartialText.trim();
                }
                latestPartialText = "";

                if (!spoken.isEmpty()) {
                    hasProcessedSpeech = true;
                    handleSpokenText(spoken);
                } else {
                    Log.e("Register_STT", "Built-in recognizer onResults returned no usable transcript.");
                    cascadeFromBuiltIn();
                }
            }

            @Override public void onPartialResults(Bundle partial) {
                if (mySession != voiceSessionId) return;
                ArrayList<String> p =
                        partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (p != null && !p.isEmpty()) {
                    latestPartialText = p.get(0).trim();
                    updateVoiceStatus("Hearing: " + latestPartialText);
                }
            }

            @Override public void onEvent(int e, Bundle p) {}
        });

        updateVoiceStatus("Get ready...");

        AudioCue.playThen(handler, () -> {
            if (mySession != voiceSessionId || !isVoiceMode) return;

            latestPartialText  = "";
            hasProcessedSpeech = false;
            isListening        = true;
            updateVoiceStatus("Listening...");

            try {
                if (speechRecognizer != null && isVoiceMode) {
                    speechRecognizer.startListening(speechIntent);
                }
            } catch (Exception e) {
                isListening = false;
                updateVoiceStatus("Mic failed. Try again.");
                Log.e("Register_STT", "startListening error: " + e.getMessage());

                cascadeFromBuiltIn();
            }

            handler.postDelayed(() -> {
                if (mySession != voiceSessionId || !isVoiceMode || isAdvancingField
                        || hasProcessedSpeech || !isListening) return;

                Log.e("Register_STT", "Recognizer timed out with no callback.");
                isListening = false;
                try { if (speechRecognizer != null) speechRecognizer.cancel(); } catch (Exception ignored) {}

                if (!latestPartialText.trim().isEmpty()) {
                    String heard = latestPartialText.trim();
                    latestPartialText = "";
                    handleSpokenText(heard);
                } else {
                    cascadeFromBuiltIn();
                }
            }, VOICE_INPUT_TIMEOUT_MS);
        });
    }

    private void cascadeFromBuiltIn() {
        if (!isVoiceMode || isFinishing() || isDestroyed()) return;
        stopListeningSafely();

        final int mySession = voiceSessionId;
        updateVoiceStatus("Get ready...");
        latestPartialText  = "";
        hasProcessedSpeech = false;
        isListening         = true;

        String mode = sttModeForField(currentFieldIndex);
        cascadeSession.cascade(this, mode, getFieldName(currentFieldIndex), new SttCascadeSession.Listener() {
            @Override public void onListeningStarted() {
                if (mySession != voiceSessionId) return;
                updateVoiceStatus("Listening...");
            }

            @Override public void onPartialResult(String partial) {
                if (mySession != voiceSessionId) return;
                latestPartialText = partial;
                updateVoiceStatus("Hearing: " + partial);
            }

            @Override public void onTranscript(String transcript) {
                if (mySession != voiceSessionId) return;
                isListening = false;
                if (hasProcessedSpeech) return;
                hasProcessedSpeech = true;
                handleSpokenText(transcript);
            }

            @Override public void onExhausted() {
                if (mySession != voiceSessionId) return;
                isListening = false;
                retryOrFallbackToManual();
            }
        });
    }

    private static String speechErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:                    return "ERROR_AUDIO";
            case SpeechRecognizer.ERROR_CLIENT:                   return "ERROR_CLIENT";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "ERROR_INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_NETWORK:                  return "ERROR_NETWORK";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:          return "ERROR_NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH:                 return "ERROR_NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:          return "ERROR_RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_SERVER:                   return "ERROR_SERVER";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:           return "ERROR_SPEECH_TIMEOUT";
            case 12:                                              return "ERROR_LANGUAGE_NOT_SUPPORTED";
            case 13:                                              return "ERROR_LANGUAGE_UNAVAILABLE";
            default:                                              return "UNKNOWN";
        }
    }

    private void retryOrFallbackToManual() {
        retryCount++;
        if (retryCount <= MAX_RETRY) {
            updateVoiceStatus("Didn't catch that. Retrying...");
            lastSpokenInstruction = "I did not hear you clearly. Please speak closer to the microphone — " +
                    "it's the small hole at the bottom edge of your phone, near the charging port — " +
                    "speak a little louder, or speak more slowly and clearly, and try again.";
            say(lastSpokenInstruction, this::startVoiceInput);
        } else {
            retryCount = 0;
            isVoiceMode = false;
            lastSpokenInstruction = "I am having trouble hearing you. " +
                    "You may type this field manually, then press Voice Register to continue.";
            say(lastSpokenInstruction, null);
            updateVoiceStatus("Please type manually or press Voice Register again.");
        }
    }

    private void handleSpokenText(String spoken) {
        if (!isVoiceMode) return;

        String lower = spoken.toLowerCase(Locale.US).trim();

        if (lower.contains("cancel") || lower.equals("stop")) {
            isVoiceMode = false;
            stopListeningSafely();
            lastSpokenInstruction = "Voice registration cancelled.";
            say(lastSpokenInstruction, null);
            updateVoiceStatus("Voice registration cancelled.");
            return;
        }

        if (isConfirmingField) {
            if (isYes(lower)) {

                isConfirmingField  = false;
                retryCount         = 0;
                EditText field     = voiceFields[currentFieldIndex];
                String processed   = processVoiceInput(pendingValue);

                runOnUiThread(() -> {
                    field.requestFocus();
                    scrollToField(field);
                    field.setText(processed);
                    field.setSelection(field.getText().length());
                    updateVoiceStatus(getFieldName(currentFieldIndex) + " saved: " + processed);
                });

                isAdvancingField = true;
                currentFieldIndex++;
                handler.postDelayed(() -> {
                    isAdvancingField = false;
                    promptCurrentField();
                }, 600);

            } else if (isNo(lower)) {

                isConfirmingField = false;
                retryCount        = 0;
                pendingValue      = "";
                lastSpokenInstruction = "Okay, please say it again.";
                say(lastSpokenInstruction,
                        () -> handler.postDelayed(this::promptCurrentField, 400));

            } else {

                retryCount++;
                if (retryCount <= MAX_RETRY) {
                    lastSpokenInstruction = "Please say yes to confirm or no to try again.";
                    say(lastSpokenInstruction, this::startVoiceInput);
                } else {
                    retryCount = 0;
                    isConfirmingField = false;
                    lastSpokenInstruction = "Moving on. Please say it again.";
                    say(lastSpokenInstruction,
                            () -> handler.postDelayed(this::promptCurrentField, 400));
                }
            }
            return;
        }

        if (isSkipCommand(lower) && currentFieldIndex == 1) {
            isAdvancingField = true;
            currentFieldIndex++;
            lastSpokenInstruction = "Middle name skipped.";
            say(lastSpokenInstruction, () -> handler.postDelayed(() -> {
                isAdvancingField = false;
                promptCurrentField();
            }, 400));
            return;
        }

        updateVoiceStatus("Heard: " + spoken);
        confirmField(applyNameCorrection(spoken));
    }

    /**
     * Fuzzy-corrects first/middle/last name fields against the bundled
     * Philippine name lists (e.g. "dela cruise" -> "dela cruz") — a correction
     * pass on top of whatever the speech engine returned, complementing the
     * phrase-boost hints applied during recognition itself. Other fields are
     * passed through unchanged (email/password can contain digits and symbols
     * the name dictionary would strip).
     */
    private String applyNameCorrection(String spoken) {
        if (nameNormalizer == null) return spoken;
        NameNormalizer.FieldType type;
        switch (currentFieldIndex) {
            case 0: type = NameNormalizer.FieldType.FIRST_NAME; break;
            case 1:
            case 2: type = NameNormalizer.FieldType.LAST_NAME; break;
            default: return spoken;
        }
        return nameNormalizer.normalize(spoken, type).getText();
    }

    private void finishVoiceRegistration() {
        isVoiceMode       = false;
        isConfirmingField = false;
        updateVoiceStatus("Reviewing your details...");
        Toast.makeText(this, "Voice registration completed.", Toast.LENGTH_SHORT).show();

        lastSpokenInstruction = buildDetailsSummary();
        say(lastSpokenInstruction, () -> {
            stopListeningSafely();
            validateAndContinue();
        });
    }

    /**
     * Every field here was already confirmed individually as it was collected
     * (each has its own "is that correct?" step) — this is a final read-back of
     * name through email before auto-continuing, not another confirmation gate.
     * Password fields are intentionally excluded.
     */
    private String buildDetailsSummary() {
        StringBuilder sb = new StringBuilder("Here is a summary of your details before I continue. ");

        sb.append("First name: ").append(fname.getText().toString().trim()).append(". ");

        String middle = mname.getText().toString().trim();
        if (!middle.isEmpty()) sb.append("Middle name: ").append(middle).append(". ");

        sb.append("Last name: ").append(lname.getText().toString().trim()).append(". ");
        sb.append("Age: ").append(age.getText().toString().trim()).append(". ");
        sb.append("Year level: ").append(yearLevel.getText().toString().trim()).append(". ");
        sb.append("School ID: ")
                .append(NumberSpeechFormatter.spellDigits(schoolid.getText().toString().trim()))
                .append(". ");
        sb.append("Email: ").append(email.getText().toString().trim()).append(". ");

        sb.append("Proceeding to submit your registration for approval.");
        return sb.toString();
    }

    private boolean isYes(String text) {
        return text.equals("yes") || text.equals("yeah") || text.equals("yep")
                || text.equals("yup") || text.equals("correct") || text.equals("right")
                || text.contains("yes") || text.contains("correct");
    }

    private boolean isNo(String text) {
        return text.equals("no") || text.equals("nope") || text.equals("nah")
                || text.equals("wrong") || text.equals("incorrect")
                || text.contains("no that") || text.startsWith("no ");
    }

    private boolean isSkipCommand(String text) {
        return text.contains("skip") || text.equals("escape") || text.equals("esc")
                || text.equals("ship") || text.equals("skit");
    }

    private String getBestResult(ArrayList<String> matches) {
        if (matches == null || matches.isEmpty()) return "";

        if (currentFieldIndex == 6) {
            for (String r : matches) {
                if (r != null && (r.contains("@") || r.toLowerCase(Locale.US).contains(" at ")))
                    return r.trim();
            }
        }

        // RESULTS_RECOGNITION is ordered by descending confidence — take the
        // first non-empty candidate instead of the longest one, which could
        // pick a lower-confidence hallucinated alternative over the correct,
        // shorter, top-ranked guess (e.g. a short name).
        for (String r : matches) {
            if (r != null && !r.trim().isEmpty()) return r.trim();
        }
        return "";
    }

    private int findFirstEmptyFieldIndex() {
        for (int i = 0; i < voiceFields.length; i++) {
            if (voiceFields[i].getText().toString().trim().isEmpty()) return i;
        }
        return voiceFields.length;
    }

    private void skipFilledFields() {
        while (currentFieldIndex < voiceFields.length
                && !voiceFields[currentFieldIndex].getText().toString().trim().isEmpty()) {
            currentFieldIndex++;
        }
    }

    private void stopListeningSafely() {
        isListening = false;
        voiceSessionId++;
        if (cascadeSession != null) cascadeSession.cancel();
        try { if (speechRecognizer != null) speechRecognizer.stopListening(); } catch (Exception ignored) {}
        try { if (speechRecognizer != null) speechRecognizer.cancel(); }        catch (Exception ignored) {}
    }

    private String getPromptForField(int index) {
        switch (index) {
            case 0: return "Please say your first name.";
            case 1: return "Please say your middle name, or say skip to leave it blank.";
            case 2: return "Please say your last name.";
            case 3: return "Please say your age as a number.";
            case 4: return "Please say your year level. For example, first year, second year, third year, or fourth year.";
            case 5: return "Please say your school ID number.";
            case 6: return "Please say your email address. Say at for the at symbol, and dot for the period.";
            case 7: return "Please say your password. It must be at least 6 characters.";
            case 8: return "Please confirm your password by saying it again.";
            default: return "Please speak now.";
        }
    }

    private String getFieldName(int index) {
        switch (index) {
            case 0: return "First Name";
            case 1: return "Middle Name";
            case 2: return "Last Name";
            case 3: return "Age";
            case 4: return "Year Level";
            case 5: return "School ID";
            case 6: return "Email";
            case 7: return "Password";
            case 8: return "Confirm Password";
            default: return "Field";
        }
    }

    private String buildConfirmMessage(int index, String value) {
        switch (index) {
            case 0: return "I heard " + spellOut(value) + ", " + value + ", as your first name. Is the spelling correct? Say yes or no.";
            case 1: return "I heard " + spellOut(value) + ", " + value + ", as your middle name. Is the spelling correct? Say yes or no.";
            case 2: return "I heard " + spellOut(value) + ", " + value + ", as your last name. Is the spelling correct? Say yes or no.";
            case 3: return "I heard " + value + " as your age. Is that correct? Say yes or no.";
            case 4: return "I heard " + value + " as your year level. Is that correct? Say yes or no.";
            case 5: return "I heard " + NumberSpeechFormatter.spellDigits(value) + " as your school ID. Is that correct? Say yes or no.";
            case 6: return "I heard your email address. Is that correct? Say yes or no.";
            case 7: return "Password received. Is that correct? Say yes or no.";
            case 8: return "Confirm password received. Is that correct? Say yes or no.";
            default: return "I heard " + value + ". Is that correct? Say yes or no.";
        }
    }

    private String spellOut(String s) {
        StringBuilder sb = new StringBuilder();
        String upper = s.toUpperCase(Locale.US);
        for (int i = 0; i < upper.length(); i++) {
            if (i > 0) sb.append(", ");
            char c = upper.charAt(i);
            sb.append(c == ' ' ? "space" : String.valueOf(c));
        }
        return sb.toString();
    }

    private String processVoiceInput(String input) {
        if (input == null) return "";
        String lower = input.trim().toLowerCase(Locale.US);
        lower = convertNumberWords(lower);

        switch (currentFieldIndex) {
            case 3: return lower.replaceAll("[^0-9]", "");
            case 4: return normalizeYearLevel(lower);
            case 5: return normalizeSchoolId(lower);
            case 6: return parseSpokenEmail(lower);
            case 7:
            case 8: return processPassword(input);
            default: return capitalizeName(input);
        }
    }

    private String parseSpokenEmail(String input) {
        String r = input.trim().toLowerCase(Locale.US);

        r = r.replaceAll("^(my email( address)? is |email( address)? is |the email is )", "");

        r = r.replace("at the rate of ", "@");
        r = r.replace("at the rate ", "@");
        r = r.replace("at sign ", "@");
        r = r.replace(" at ", "@");

        r = r.replace("dot com ph", ".com.ph");
        r = r.replace("dot edu ph", ".edu.ph");
        r = r.replace("dot com",    ".com");
        r = r.replace("dot ph",     ".ph");
        r = r.replace("dot edu",    ".edu");
        r = r.replace("dot net",    ".net");
        r = r.replace("dot org",    ".org");
        r = r.replace("dot io",     ".io");
        r = r.replace("dot ",       ".");
        r = r.replace(" period ",   ".");

        r = r.replace(" underscore ", "_");
        r = r.replace(" under score ", "_");
        r = r.replace(" dash ",    "-");
        r = r.replace(" hyphen ",  "-");
        r = r.replace(" minus ",   "-");
        r = r.replace(" space ",   "");

        r = r.replaceAll("\\s+", "");

        if (!r.contains("@")) r = r.replaceFirst("at(?=[a-z])", "@");
        if (!r.contains(".")) r = r.replaceFirst("dot(?=[a-z])", ".");

        r = r.replace("@@", "@").replace("..", ".");

        return r.toLowerCase(Locale.US);
    }

    private String normalizeYearLevel(String input) {
        if (input.contains("1") || input.contains("first"))  return "1st Year";
        if (input.contains("2") || input.contains("second")) return "2nd Year";
        if (input.contains("3") || input.contains("third"))  return "3rd Year";
        if (input.contains("4") || input.contains("fourth")) return "4th Year";
        return capitalizeName(input);
    }

    private String normalizeSchoolId(String input) {
        String digits = input.replaceAll("[^0-9]", "");
        if (digits.length() >= 3) {
            return digits.substring(0, 2) + "-" + digits.substring(2);
        }
        return digits;
    }

    private String processPassword(String input) {
        if (input == null) return "";
        String pw    = input.trim();
        String lower = pw.toLowerCase(Locale.US);
        String[] prefixes = {
                "my password is ", "the password is ", "password is ", "password ",
                "confirm password is ", "confirm password ", "passcode is ", "passcode "
        };
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                pw = pw.substring(prefix.length()).trim();
                break;
            }
        }
        return pw.replaceAll("\\s+", "");
    }

    private String capitalizeName(String input) {
        if (input == null || input.isEmpty()) return input;
        String[] words = input.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) sb.append(word.substring(1).toLowerCase(Locale.US));
        }
        return sb.toString();
    }

    private String convertNumberWords(String input) {
        return input
                .replace("zero", "0").replace("one", "1").replace("two", "2")
                .replace("three", "3").replace("four", "4").replace("five", "5")
                .replace("six", "6").replace("seven", "7").replace("eight", "8")
                .replace("nine", "9");
    }

    private void validateAndContinue() {
        String firstName   = fname.getText().toString().trim();
        String middleName  = mname.getText().toString().trim();
        String lastName    = lname.getText().toString().trim();
        String userAge     = age.getText().toString().trim();
        String userYear    = yearLevel.getText().toString().trim();
        String schoolId    = schoolid.getText().toString().trim();
        String userEmail   = email.getText().toString().trim().toLowerCase(Locale.US);
        String userPass    = password.getText().toString().trim();
        String confirmPass = confirmPassword.getText().toString().trim();

        if (firstName.isEmpty())  { showError(fname,   "First name required",      "First name is required.");    return; }
        if (lastName.isEmpty())   { showError(lname,   "Last name required",       "Last name is required.");     return; }
        if (userAge.isEmpty())    { showError(age,     "Age required",             "Age is required.");           return; }
        if (userYear.isEmpty())   { showError(yearLevel,"Year level required",     "Year level is required.");    return; }
        if (schoolId.isEmpty())   { showError(schoolid,"School ID required",       "School ID is required.");     return; }
        if (userEmail.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(userEmail).matches()) {
            showError(email, "Invalid email", "Please enter a valid email address."); return;
        }
        if (userPass.isEmpty())   { showError(password,"Password required",        "Password is required.");      return; }
        if (userPass.length() < 6){ showError(password,"Minimum 6 characters",     "Password must be at least 6 characters."); return; }
        if (confirmPass.isEmpty()){ showError(confirmPassword,"Required",          "Please confirm your password."); return; }
        if (!userPass.equals(confirmPass)) {
            showError(confirmPassword, "Passwords do not match", "Passwords do not match. Please try again."); return;
        }

        updateVoiceStatus("Submitting registration...");
        registerToSupabase(firstName, middleName, lastName, userAge,
                userYear, schoolId, userEmail, userPass);
    }

    private void showError(EditText field, String fieldError, String ttsMessage) {
        field.setError(fieldError);
        field.requestFocus();
        scrollToField(field);
        shakeView(field);
        lastSpokenInstruction = ttsMessage;
        say(lastSpokenInstruction, null);
    }

    private void registerToSupabase(String firstName, String middleName, String lastName,
                                    String userAge, String yearLevelVal, String schoolId,
                                    String userEmail, String userPassword) {
        continueBtn.setEnabled(false);
        voiceRegisterBtn.setEnabled(false);

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("first_name",      firstName);
            jsonBody.put("middle_name",     middleName);
            jsonBody.put("last_name",       lastName);
            jsonBody.put("age",             Integer.parseInt(userAge));
            jsonBody.put("year_level",      yearLevelVal);
            jsonBody.put("school_id",       schoolId);
            jsonBody.put("email",           userEmail);
            jsonBody.put("password",        userPassword);
            jsonBody.put("approval_status", "pending");
        } catch (Exception e) {
            continueBtn.setEnabled(true);
            voiceRegisterBtn.setEnabled(true);
            lastSpokenInstruction = "Failed to prepare registration data.";
            say(lastSpokenInstruction, null);
            return;
        }

        StringRequest request = new StringRequest(
                Request.Method.POST,
                ApiConfig.STUDENTS,
                response -> {
                    continueBtn.setEnabled(true);
                    voiceRegisterBtn.setEnabled(true);
                    Toast.makeText(this,
                            "Registration submitted. Please wait for admin approval.",
                            Toast.LENGTH_LONG).show();
                    lastSpokenInstruction = "Registration submitted successfully. " +
                            "Please wait for admin approval before logging in.";
                    say(lastSpokenInstruction, () ->
                            handler.postDelayed(() -> {
                                Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
                                intent.putExtra("registered_email",   userEmail);
                                intent.putExtra("registered_password", userPassword);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                                finish();
                            }, 500));
                    updateVoiceStatus("Registration submitted.");
                },
                error -> {
                    continueBtn.setEnabled(true);
                    voiceRegisterBtn.setEnabled(true);
                    String errorMessage = "Registration failed.";
                    if (error.networkResponse != null && error.networkResponse.data != null) {
                        String body = new String(error.networkResponse.data, StandardCharsets.UTF_8);
                        if (body.contains("duplicate") || body.contains("students_email_key")) {
                            errorMessage = "This email is already registered.";
                            email.setError("Email already registered");
                            email.requestFocus(); scrollToField(email);
                        } else if (body.contains("school_id")) {
                            errorMessage = "School ID already registered or invalid.";
                            schoolid.setError("Check School ID");
                            schoolid.requestFocus(); scrollToField(schoolid);
                        } else {
                            errorMessage = "Registration failed. Please try again.";
                        }
                    }
                    lastSpokenInstruction = errorMessage;
                    say(lastSpokenInstruction, null);
                    updateVoiceStatus(errorMessage);
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
                }
        ) {
            @Override public byte[]              getBody()            { return jsonBody.toString().getBytes(StandardCharsets.UTF_8); }
            @Override public String              getBodyContentType() { return "application/json; charset=utf-8"; }
            @Override public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("apikey",        ApiConfig.SUPABASE_KEY);
                headers.put("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY);
                headers.put("Content-Type",  "application/json");
                headers.put("Accept",        "application/json");
                headers.put("Prefer",        "return=representation");
                return headers;
            }
        };

        request.setRetryPolicy(new DefaultRetryPolicy(15000, 1, 1.0f));
        requestQueue.add(request);
    }

    private void updateVoiceStatus(String msg) {
        runOnUiThread(() -> { if (txtVoiceStatus != null) txtVoiceStatus.setText("Voice Status: " + msg); });
    }

    private void scrollToField(View targetView) {
        if (registerScrollView == null || targetView == null) return;
        targetView.postDelayed(() -> {
            int[] scrollLoc = new int[2];
            int[] targetLoc = new int[2];
            registerScrollView.getLocationOnScreen(scrollLoc);
            targetView.getLocationOnScreen(targetLoc);
            int scrollY = registerScrollView.getScrollY();
            int targetY = targetLoc[1] - scrollLoc[1] + scrollY;
            registerScrollView.smoothScrollTo(0, Math.max(targetY - 130, 0));
        }, 250);
    }

    private void setupAutoScrollForTyping() {
        EditText[] fields = { fname, mname, lname, age, yearLevel, schoolid,
                email, password, confirmPassword };
        for (EditText field : fields) {
            if (field == null) continue;
            field.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) scrollToField(v); });

            field.setOnClickListener(v -> {
                cancelVoiceModeForManualInput();
                scrollToField(v);
            });
        }
    }

    private void cancelVoiceModeForManualInput() {
        if (!isVoiceMode) return;
        isVoiceMode       = false;
        isConfirmingField = false;
        stopListeningSafely();
        updateVoiceStatus("Switched to manual input. Fill in the rest, then press Continue.");
    }

    private void animateViews() {
        if (logoImage != null) UiAnim.popIn(logoImage, 0);
        View[] views = { voiceRegisterBtn, txtVoiceStatus, fname, mname, lname, age, yearLevel,
                schoolid, email, password, confirmPassword, continueBtn };
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            if (v == null) continue;
            v.setAlpha(0f); v.setTranslationY(100f);
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(i * 70L + 70L).setDuration(500)
                    .setInterpolator(new OvershootInterpolator()).start();
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

    private void animateClick(View view) {
        if (view == null) return;
        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(80).start())
                .start();
    }

    private void shakeView(View view) {
        if (view == null) return;
        view.animate().translationX(20f).setDuration(50)
                .withEndAction(() -> view.animate().translationX(-20f).setDuration(50)
                        .withEndAction(() -> view.animate().translationX(12f).setDuration(50)
                                .withEndAction(() -> view.animate().translationX(0f)
                                        .setDuration(50).start()).start()).start()).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean nowGranted = hasAudioPermission();
        // Covers a grant obtained any way other than our own in-app request —
        // returning from Settings, a permission change made elsewhere, or a
        // process restart landing here with permission already present —
        // without depending on a flag we ourselves have to remember to set.
        // micPermissionRequestInFlight excludes the in-app request path, whose
        // own ActivityResultLauncher callback already handles the resume.
        if (nowGranted && !lastKnownMicPermission && !micPermissionRequestInFlight) {
            updateVoiceStatus("Microphone enabled.");
            MicReadiness.awaitReady(handler, this::startVoiceRegistration);
        }
        lastKnownMicPermission = nowGranted;
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListeningSafely();

        if (googleTts != null) googleTts.stopSpeaking();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopListeningSafely();
        try {
            if (speechRecognizer != null) {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            }
        } catch (Exception ignored) {}
        if (hybridSpeech != null) hybridSpeech.destroy();
        if (googleTts != null) googleTts.destroy();
        if (googleStt != null) googleStt.destroy();
        super.onDestroy();
    }
}
