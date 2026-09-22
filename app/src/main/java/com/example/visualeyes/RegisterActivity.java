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
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.StringRequest;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class RegisterActivity extends AppCompatActivity {

    private ScrollView registerScrollView;
    private EditText fname, mname, lname, birthdate, yearLevel, schoolid, email, section;
    private Button continueBtn, voiceRegisterBtn, checkSchoolIdBtn;
    private TextView txtVoiceStatus;
    private ImageView logoImage;

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
    // Set while reading back the full details summary at the end of voice
    // registration (either after the School ID auto-check autofills
    // everything, or after all fields were collected one by one) and
    // waiting for a final "is this all correct?" yes/no.
    private boolean isAwaitingFinalReviewConfirm = false;
    // The student said "no" at the final review: is it the School ID itself that's wrong
    // (say it again) or just some detail (edit it manually)?
    private boolean isAwaitingWrongIdConfirm = false;
    // The School ID wasn't on the enrollment list: say it again, or continue by voice anyway?
    private boolean isAwaitingIdRetryConfirm = false;
    // The form fields were autofilled from the enrollment record of the spoken School ID, so if
    // that ID turns out to be wrong those details belong to someone else and must be cleared.
    private boolean detailsFromIdLookup      = false;
    private boolean hasProcessedSpeech      = false;
    private String  pendingValue            = "";
    private String  latestPartialText       = "";
    private int     retryCount              = 0;

    private boolean isAwaitingLetterPosition  = false;
    private boolean isAwaitingLetterValue     = false;
    private int     correctingLetterPosition  = 0;

    private static final int MAX_RETRY      = 4;

    private static final long VOICE_INPUT_TIMEOUT_MS = 11000L;
    // Matches the reinit-settle + retry pacing standardized across every
    // mic-using screen (400ms to tear down/recreate, 600ms before retry).
    private static final long MIC_BUSY_REINIT_DELAY_MS = 400L;
    private static final long MIC_BUSY_RETRY_DELAY_MS  = 600L;

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
        requestQueue = VolleySingleton.getInstance(this).getRequestQueue();
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

        // School ID is asked first — voice registration now confirms it, then
        // auto-checks the official enrollment list and autofills the rest
        // before falling back to asking remaining fields one by one.
        voiceFields = new EditText[]{
                schoolid, fname, mname, lname, birthdate, yearLevel, email, section
        };

        setupAutoScrollForTyping();
        setupBirthdatePicker();
        buildSpeechIntent();
        setupGestures();
        updateVoiceStatus("Ready.");
        animateViews();
        handler.postDelayed(this::startLogoPulse, 550);

        UiAnim.attachPressFeedback(continueBtn);
        UiAnim.attachPressFeedback(voiceRegisterBtn);
        UiAnim.attachPressFeedback(checkSchoolIdBtn);

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

        checkSchoolIdBtn.setOnClickListener(v -> {
            animateClick(v);
            checkSchoolIdDetails();
        });
    }

    private void bindViews() {
        registerScrollView = (ScrollView) ((android.view.ViewGroup)
                findViewById(android.R.id.content)).getChildAt(0);
        fname           = findViewById(R.id.fname);
        mname           = findViewById(R.id.mname);
        lname           = findViewById(R.id.lname);
        birthdate       = findViewById(R.id.birthdate);
        yearLevel       = findViewById(R.id.yearLevel);
        section         = findViewById(R.id.section);
        schoolid        = findViewById(R.id.schoolid);
        email           = findViewById(R.id.email);
        continueBtn     = findViewById(R.id.continueBtn);
        voiceRegisterBtn= findViewById(R.id.voiceRegisterBtn);
        checkSchoolIdBtn= findViewById(R.id.checkSchoolIdBtn);
        txtVoiceStatus  = findViewById(R.id.txtVoiceStatus);
        logoImage       = findViewById(R.id.logoImage);
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
        isAwaitingLetterPosition  = false;
        isAwaitingLetterValue     = false;
        isAwaitingWrongIdConfirm  = false;
        isAwaitingIdRetryConfirm  = false;
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
        isAwaitingLetterPosition = false;
        isAwaitingLetterValue    = false;
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
            case 1:
            case 2:
            case 3: return "name";
            case 6: return "email";
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

                if (!latestPartialText.trim().isEmpty()) {
                    String heard = latestPartialText.trim();
                    latestPartialText = "";
                    handleSpokenText(heard);
                    return;
                }

                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    handler.postDelayed(() -> {
                        if (mySession != voiceSessionId) return;
                        handler.postDelayed(() -> {
                            if (mySession == voiceSessionId) startVoiceInput();
                        }, MIC_BUSY_RETRY_DELAY_MS);
                    }, MIC_BUSY_REINIT_DELAY_MS);
                    return;
                }

                Log.e("Register_STT", "Built-in recognizer onError code=" + error
                        + " (" + speechErrorName(error) + ")");

                if (SpeechEngineHealth.isRecognizerIncompatible(error)) {
                    Log.e("Register_STT", "Built-in recognizer is not usable on this device — "
                            + "skipping it from now on.");
                    SpeechEngineHealth.markBuiltInRecognizerBroken(RegisterActivity.this);
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

        // While correcting a letter, the utterance is a short instruction
        // ("letter 1", "double L") rather than an actual name — using the
        // name field's mode here would keep Cloud STT's name-phrase boost
        // active and bias it away from correctly hearing that instruction.
        String mode = (isAwaitingLetterPosition || isAwaitingLetterValue
                || isAwaitingWrongIdConfirm || isAwaitingIdRetryConfirm)
                ? "command" : sttModeForField(currentFieldIndex);
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
            boolean wasFinalReview = isAwaitingFinalReviewConfirm;
            isVoiceMode = false;
            isAwaitingFinalReviewConfirm = false;
            wasFinalReview = wasFinalReview || isAwaitingWrongIdConfirm;
            isAwaitingWrongIdConfirm = false;
            isAwaitingIdRetryConfirm = false;
            if (wasFinalReview) {
                lastSpokenInstruction = "I am having trouble hearing you. " +
                        "Please review your details above, then press Continue when you're ready.";
                updateVoiceStatus("Please review, then press Continue.");
            } else {
                lastSpokenInstruction = "I am having trouble hearing you. " +
                        "You may type this field manually, then press Voice Register to continue.";
                updateVoiceStatus("Please type manually or press Voice Register again.");
            }
            say(lastSpokenInstruction, null);
        }
    }

    private void handleSpokenText(String spoken) {
        if (!isVoiceMode) return;

        String lower = spoken.toLowerCase(Locale.US).trim();

        if (lower.contains("cancel") || lower.equals("stop")) {
            isVoiceMode = false;
            isAwaitingLetterPosition  = false;
            isAwaitingLetterValue     = false;
            stopListeningSafely();
            lastSpokenInstruction = "Voice registration cancelled.";
            say(lastSpokenInstruction, null);
            updateVoiceStatus("Voice registration cancelled.");
            return;
        }

        if (isAwaitingFinalReviewConfirm) {
            if (isYes(lower)) {
                isAwaitingFinalReviewConfirm = false;
                isVoiceMode = false;
                retryCount  = 0;
                stopListeningSafely();
                lastSpokenInstruction = "Great. Submitting your registration now.";
                say(lastSpokenInstruction, this::validateAndContinue);
            } else if (isNo(lower)) {
                // Something is wrong — first find out whether it's the School ID itself.
                isAwaitingFinalReviewConfirm = false;
                isAwaitingWrongIdConfirm     = true;
                retryCount = 0;
                updateVoiceStatus("Is the School ID wrong?");
                lastSpokenInstruction = "Is your School ID the one that is wrong? " +
                        "Say yes to say your School ID again, or no to fix the other details yourself.";
                say(lastSpokenInstruction, this::startVoiceInput);
            } else {
                retryCount++;
                if (retryCount <= MAX_RETRY) {
                    lastSpokenInstruction = "Please say yes to continue or no to make changes.";
                    say(lastSpokenInstruction, this::startVoiceInput);
                } else {
                    isAwaitingFinalReviewConfirm = false;
                    isVoiceMode = false;
                    retryCount  = 0;
                    lastSpokenInstruction = "Let's continue manually. Please review the fields, then press Continue.";
                    say(lastSpokenInstruction, null);
                    updateVoiceStatus("Please review manually, then press Continue.");
                }
            }
            return;
        }

        if (isAwaitingWrongIdConfirm) {
            if (isYes(lower)) {
                isAwaitingWrongIdConfirm = false;
                retryCount = 0;
                reaskSchoolId("Okay, let's do your School ID again.");
            } else if (isNo(lower)) {
                isAwaitingWrongIdConfirm = false;
                isVoiceMode = false;
                retryCount  = 0;
                stopListeningSafely();
                lastSpokenInstruction = "Okay, please review and edit the fields manually, " +
                        "then press Continue when you're ready.";
                say(lastSpokenInstruction, null);
                updateVoiceStatus("Please edit manually, then press Continue.");
            } else {
                retryCount++;
                if (retryCount <= MAX_RETRY) {
                    lastSpokenInstruction = "Please say yes to say your School ID again, or no to fix the details yourself.";
                    say(lastSpokenInstruction, this::startVoiceInput);
                } else {
                    isAwaitingWrongIdConfirm = false;
                    isVoiceMode = false;
                    retryCount  = 0;
                    lastSpokenInstruction = "Let's continue manually. Please review the fields, then press Continue.";
                    say(lastSpokenInstruction, null);
                    updateVoiceStatus("Please review manually, then press Continue.");
                }
            }
            return;
        }

        if (isAwaitingIdRetryConfirm) {
            if (isYes(lower)) {
                isAwaitingIdRetryConfirm = false;
                retryCount = 0;
                reaskSchoolId("Okay, please say your School ID again.");
            } else if (isNo(lower)) {
                isAwaitingIdRetryConfirm = false;
                retryCount = 0;
                updateVoiceStatus("Continuing by voice...");
                lastSpokenInstruction = "Okay, let's continue by voice for the rest of your details.";
                say(lastSpokenInstruction, () -> handler.postDelayed(this::promptCurrentField, 400));
            } else {
                retryCount++;
                if (retryCount <= MAX_RETRY) {
                    lastSpokenInstruction = "Please say yes to say your School ID again, or no to continue.";
                    say(lastSpokenInstruction, this::startVoiceInput);
                } else {
                    isAwaitingIdRetryConfirm = false;
                    retryCount = 0;
                    lastSpokenInstruction = "Let's continue by voice for the rest of your details.";
                    say(lastSpokenInstruction, () -> handler.postDelayed(this::promptCurrentField, 400));
                }
            }
            return;
        }

        if (isAwaitingLetterPosition) {
            handleLetterPositionResponse(lower);
            return;
        }

        if (isAwaitingLetterValue) {
            handleLetterValueResponse(lower);
            return;
        }

        if (isConfirmingField) {
            if (isYes(lower)) {

                isConfirmingField  = false;
                retryCount         = 0;
                EditText field     = voiceFields[currentFieldIndex];
                String processed   = processVoiceInput(pendingValue);
                boolean wasSchoolIdField = (currentFieldIndex == 0);

                runOnUiThread(() -> {
                    field.requestFocus();
                    scrollToField(field);
                    field.setText(processed);
                    field.setSelection(field.getText().length());
                    updateVoiceStatus(getFieldName(currentFieldIndex) + " saved: " + processed);
                });

                isAdvancingField = true;
                currentFieldIndex++;
                if (wasSchoolIdField) {
                    handler.postDelayed(() -> {
                        isAdvancingField = false;
                        autoCheckSchoolIdForVoice();
                    }, 500);
                } else {
                    handler.postDelayed(() -> {
                        isAdvancingField = false;
                        promptCurrentField();
                    }, 600);
                }

            } else if (isNo(lower)) {

                isConfirmingField = false;
                retryCount        = 0;

                if (isNameField(currentFieldIndex)) {
                    beginLetterCorrection();
                } else {
                    pendingValue = "";
                    lastSpokenInstruction = "Okay, please say it again.";
                    say(lastSpokenInstruction,
                            () -> handler.postDelayed(this::promptCurrentField, 400));
                }

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

        if (isSkipCommand(lower) && (currentFieldIndex == 2 || currentFieldIndex == 7)) {
            String skippedFieldName = currentFieldIndex == 2 ? "Middle name" : "Section";
            isAdvancingField = true;
            currentFieldIndex++;
            lastSpokenInstruction = skippedFieldName + " skipped.";
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
            case 1: type = NameNormalizer.FieldType.FIRST_NAME; break;
            case 2:
            case 3: type = NameNormalizer.FieldType.LAST_NAME; break;
            default: return spoken;
        }
        return nameNormalizer.normalize(spoken, type).getText();
    }

    private void finishVoiceRegistration() {
        isConfirmingField = false;
        Toast.makeText(this, "Voice registration completed.", Toast.LENGTH_SHORT).show();
        presentFinalReviewAndConfirm();
    }

    /**
     * Reads back everything collected so far and waits for an explicit
     * "is this correct?" yes/no before submitting — reached either after the
     * School ID auto-check autofills the form, or after all fields were
     * collected one by one.
     */
    private void presentFinalReviewAndConfirm() {
        isAwaitingFinalReviewConfirm = true;
        isConfirmingField = false;
        retryCount = 0;
        updateVoiceStatus("Reviewing your details...");
        lastSpokenInstruction = buildDetailsSummary();
        say(lastSpokenInstruction, this::startVoiceInput);
    }

    /** Every field here was already confirmed individually as it was collected
     *  (each has its own "is that correct?" step) — this is a final read-back
     *  of everything before asking the single "is this all correct?" gate. */
    private String buildDetailsSummary() {
        return "Here is a summary of your details. " + buildReviewSummaryText()
                + "Is all of this correct? Say yes to continue, or no to fix something.";
    }

    /** Shared field read-back used by both the voice-flow final review and the
     *  manual "Check School ID" button's review (reviewLoadedDetails()). */
    private String buildReviewSummaryText() {
        StringBuilder sb = new StringBuilder();

        sb.append("First name: ").append(fname.getText().toString().trim()).append(". ");

        String middle = mname.getText().toString().trim();
        if (!middle.isEmpty()) sb.append("Middle name: ").append(middle).append(". ");

        sb.append("Last name: ").append(lname.getText().toString().trim()).append(". ");
        sb.append("Birthdate: ").append(birthdate.getText().toString().trim()).append(". ");
        sb.append("Year level: ").append(yearLevel.getText().toString().trim()).append(". ");
        sb.append("School ID: ")
                .append(NumberSpeechFormatter.spellDigits(schoolid.getText().toString().trim()))
                .append(". ");
        sb.append("Email: ").append(email.getText().toString().trim()).append(". ");

        String sectionVal = section.getText().toString().trim();
        if (!sectionVal.isEmpty()) sb.append("Section: ").append(sectionVal).append(". ");

        return sb.toString();
    }

    /** Starts over from the School ID question. Details that were autofilled from the wrong ID's
     *  enrollment record are cleared, since they belong to someone else. */
    private void reaskSchoolId(String intro) {
        runOnUiThread(() -> {
            schoolid.setText("");
            if (detailsFromIdLookup) {
                fname.setText(""); mname.setText(""); lname.setText("");
                birthdate.setText(""); yearLevel.setText(""); email.setText(""); section.setText("");
            }
            detailsFromIdLookup = false;
        });
        currentFieldIndex = 0;
        pendingValue      = "";
        isConfirmingField = false;
        isAdvancingField  = false;
        lastSpokenInstruction = intro;
        say(lastSpokenInstruction, () -> handler.postDelayed(this::promptCurrentField, 400));
    }

    /** Runs right after the user confirms their spoken School ID — checks the
     *  school's official enrollment list and autofills the rest of the form
     *  when a match is found, instead of asking every remaining field by voice. */
    private void autoCheckSchoolIdForVoice() {
        if (!isVoiceMode) return;
        String schoolId = schoolid.getText().toString().trim();
        if (schoolId.isEmpty()) { promptCurrentField(); return; }

        checkSchoolIdBtn.setEnabled(false);
        updateVoiceStatus("Checking your School ID...");
        lastSpokenInstruction = "Thanks. Let me check if you're already on the school's enrollment list.";
        say(lastSpokenInstruction, () -> {
            JSONObject jsonBody = new JSONObject();
            String bodyStr;
            try {
                jsonBody.put("p_school_id", schoolId);
                bodyStr = jsonBody.toString();
            } catch (Exception e) {
                continueVoiceRegistrationAfterCheck(false);
                return;
            }
            final String finalBodyStr = bodyStr;

            String url = ApiConfig.SUPABASE_URL + "/rest/v1/rpc/get_enrolled_student_by_school_id";

            JsonArrayRequest request = new JsonArrayRequest(Request.Method.POST, url, null,
                    response -> {
                        boolean found = false;
                        if (response != null && response.length() > 0) {
                            try {
                                JSONObject s = response.getJSONObject(0);
                                fname.setText(s.optString("first_name", ""));
                                mname.setText(s.optString("middle_name", ""));
                                lname.setText(s.optString("last_name", ""));
                                birthdate.setText(s.optString("birthdate", ""));
                                yearLevel.setText(s.optString("year_level", ""));
                                email.setText(s.optString("email", ""));
                                section.setText(s.optString("section", ""));
                                detailsFromIdLookup = true;
                                found = true;
                            } catch (Exception ignored) {}
                        }
                        continueVoiceRegistrationAfterCheck(found);
                    },
                    error -> continueVoiceRegistrationAfterCheck(false)
            ) {
                @Override public byte[]              getBody()            { return finalBodyStr.getBytes(StandardCharsets.UTF_8); }
                @Override public String              getBodyContentType() { return "application/json; charset=utf-8"; }
                @Override public Map<String, String> getHeaders() {
                    Map<String, String> headers = new HashMap<>();
                    headers.put("apikey",        ApiConfig.SUPABASE_KEY);
                    headers.put("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY);
                    headers.put("Content-Type",  "application/json");
                    headers.put("Accept",        "application/json");
                    return headers;
                }
            };

            request.setRetryPolicy(new DefaultRetryPolicy(15000, 1, 1.0f));
            requestQueue.add(request);
        });
    }

    private void continueVoiceRegistrationAfterCheck(boolean found) {
        checkSchoolIdBtn.setEnabled(true);
        if (!isVoiceMode) return;

        if (found) {
            updateVoiceStatus("Details loaded. Reviewing...");
            presentFinalReviewAndConfirm();
        } else {
            // Could be a misheard or mistyped ID, so offer to say it again before carrying on.
            updateVoiceStatus("No record found for this School ID.");
            isAwaitingIdRetryConfirm = true;
            retryCount = 0;
            lastSpokenInstruction = "I could not find this School ID on the enrollment list. " +
                    "Do you want to say your School ID again? Say yes to try again, or no to continue by voice.";
            say(lastSpokenInstruction, this::startVoiceInput);
        }
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
            case 0: return "Please say your school ID number.";
            case 1: return "Please say your first name.";
            case 2: return "Please say your middle name, or say skip to leave it blank.";
            case 3: return "Please say your last name.";
            case 4: return "Please say your birthdate, including the month, day, and year. " +
                    "For example, January 15, 2005.";
            case 5: return "Please say your year level. For example, first year, second year, third year, or fourth year.";
            case 6: return "Please say your email address. Say at for the at symbol, and dot for the period.";
            case 7: return "Please say your section, or say skip if you're not sure.";
            default: return "Please speak now.";
        }
    }

    private String getFieldName(int index) {
        switch (index) {
            case 0: return "School ID";
            case 1: return "First Name";
            case 2: return "Middle Name";
            case 3: return "Last Name";
            case 4: return "Birthdate";
            case 5: return "Year Level";
            case 6: return "Email";
            case 7: return "Section";
            default: return "Field";
        }
    }

    private String buildConfirmMessage(int index, String value) {
        switch (index) {
            case 0: return "I heard " + NumberSpeechFormatter.spellDigits(value) + " as your school ID. Is that correct? Say yes or no.";
            case 1: return "I heard " + spellOut(value) + ", " + value + ", as your first name. Is the spelling correct? Say yes or no.";
            case 2: return "I heard " + spellOut(value) + ", " + value + ", as your middle name. Is the spelling correct? Say yes or no.";
            case 3: return "I heard " + spellOut(value) + ", " + value + ", as your last name. Is the spelling correct? Say yes or no.";
            case 4: return "I heard " + value + " as your birthdate. Is that correct? Say yes or no.";
            case 5: return "I heard " + value + " as your year level. Is that correct? Say yes or no.";
            case 6: return "I heard your email as " + speakableEmail(value) + ". Is that correct? Say yes or no.";
            default: return "I heard " + value + ". Is that correct? Say yes or no.";
        }
    }

    /** Reads an email back in a speakable form, e.g. "juan dot delacruz at gmail dot com". */
    private String speakableEmail(String email) {
        StringBuilder sb = new StringBuilder();
        for (char c : email.toCharArray()) {
            switch (c) {
                case '@': sb.append(" at ");         break;
                case '.': sb.append(" dot ");         break;
                case '_': sb.append(" underscore ");  break;
                case '-': sb.append(" dash ");        break;
                default:  sb.append(c);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
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

    // ----------------------------------------------------------------------
    // Letter-by-letter correction for first/middle/last name fields. Reached
    // when the user says "no" to a name spelling confirmation instead of
    // making them re-say the entire name — they pick a letter position, then
    // say the correct letter (optionally "double"/"triple" for a repeated
    // letter, e.g. a doubled "L" the recognizer heard as single).
    // ----------------------------------------------------------------------

    private boolean isNameField(int index) {
        return index == 1 || index == 2 || index == 3;
    }

    private boolean isStartOverCommand(String lower) {
        return lower.contains("start over") || lower.contains("whole name")
                || lower.contains("say it again") || lower.contains("from the beginning");
    }

    private void beginLetterCorrection() {
        isAwaitingLetterPosition = true;
        isAwaitingLetterValue    = false;
        retryCount               = 0;
        latestPartialText        = "";

        updateVoiceStatus("Which letter is wrong?");
        lastSpokenInstruction = "Which letter is wrong? Please say its position, like letter 1, letter 2, or letter 3. " +
                "Or say start over to say the whole name again.";
        say(lastSpokenInstruction, this::startVoiceInput);
    }

    private void restartWholeName() {
        isAwaitingLetterPosition = false;
        isAwaitingLetterValue    = false;
        pendingValue             = "";
        retryCount               = 0;
        lastSpokenInstruction = "Okay, please say it again.";
        say(lastSpokenInstruction, () -> handler.postDelayed(this::promptCurrentField, 400));
    }

    private void handleLetterPositionResponse(String lower) {
        if (isStartOverCommand(lower)) {
            restartWholeName();
            return;
        }

        int position = parseLetterPosition(lower, pendingValue.length());
        if (position < 1) {
            retryCount++;
            if (retryCount <= MAX_RETRY) {
                lastSpokenInstruction = "I did not catch a valid letter position. " +
                        "Please say a number from 1 to " + pendingValue.length() + ", like letter 1.";
                say(lastSpokenInstruction, this::startVoiceInput);
            } else {
                restartWholeName();
            }
            return;
        }

        correctingLetterPosition = position;
        retryCount               = 0;
        isAwaitingLetterPosition = false;
        isAwaitingLetterValue    = true;

        char current = Character.toUpperCase(pendingValue.charAt(position - 1));
        updateVoiceStatus("Letter " + position + " is " + current + ". What should it be?");
        lastSpokenInstruction = "Letter " + position + " is " + current + ". " +
                "Please say the correct letter. If it should be a doubled letter, say double, then the letter, like double L.";
        say(lastSpokenInstruction, this::startVoiceInput);
    }

    private int parseLetterPosition(String lower, int maxLen) {
        String normalized = lower
                .replace("first", "1").replace("second", "2").replace("third", "3")
                .replace("fourth", "4").replace("fifth", "5").replace("sixth", "6")
                .replace("seventh", "7").replace("eighth", "8").replace("ninth", "9")
                .replace("tenth", "10")
                // Filipino number words, since the rest of the voice flow is
                // bilingual (e.g. "sige", "oo", "ulit" elsewhere in this file).
                .replace("isa", "1").replace("dalawa", "2").replace("tatlo", "3")
                .replace("apat", "4").replace("lima", "5").replace("anim", "6")
                .replace("pito", "7").replace("walo", "8").replace("siyam", "9")
                .replace("sampu", "10");
        normalized = convertNumberWords(normalized);
        String digits = normalized.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return -1;
        try {
            int value = Integer.parseInt(digits);
            return (value >= 1 && value <= maxLen) ? value : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void handleLetterValueResponse(String lower) {
        if (isStartOverCommand(lower)) {
            restartWholeName();
            return;
        }

        String replacement = parseSpokenLetters(lower);
        if (replacement.isEmpty()) {
            retryCount++;
            if (retryCount <= MAX_RETRY) {
                lastSpokenInstruction = "I did not catch a letter. Please say a single letter, like C. " +
                        "For a doubled letter, say double, then the letter, like double L.";
                say(lastSpokenInstruction, this::startVoiceInput);
            } else {
                restartWholeName();
            }
            return;
        }

        int pos = correctingLetterPosition;
        String updated = pendingValue.substring(0, pos - 1) + replacement + pendingValue.substring(pos);

        isAwaitingLetterValue = false;
        retryCount            = 0;
        updateVoiceStatus("Updated to: " + updated);
        confirmField(updated);
    }

    /** Parses a spoken replacement letter, honoring a "double"/"triple" prefix
     *  for repeated letters (e.g. "double L" -> "LL"). Checked before the
     *  general prefix so the literal letter name "double u" (W) isn't
     *  mistaken for a duplicated "U". */
    private String parseSpokenLetters(String lower) {
        String text = lower.trim();

        if (text.equals("double u") || text.equals("double you")) {
            return "W";
        }

        int repeat = 1;
        if (text.startsWith("double ")) {
            repeat = 2;
            text = text.substring(7).trim();
        } else if (text.startsWith("triple ")) {
            repeat = 3;
            text = text.substring(7).trim();
        }

        char letter = parsePhoneticLetter(text);
        if (letter == 0) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repeat; i++) sb.append(letter);
        return sb.toString();
    }

    private char parsePhoneticLetter(String text) {
        String cleaned = text.replaceAll("[^a-z ]", "").trim();
        if (cleaned.isEmpty()) return 0;

        switch (cleaned) {
            case "a": case "ay": return 'A';
            case "b": case "bee": case "be": return 'B';
            case "c": case "see": case "sea": return 'C';
            case "d": case "dee": case "de": return 'D';
            case "e": case "ee": return 'E';
            case "f": case "eff": return 'F';
            case "g": case "gee": case "jee": return 'G';
            case "h": case "aitch": case "eitch": return 'H';
            case "i": case "eye": return 'I';
            case "j": case "jay": return 'J';
            case "k": case "kay": return 'K';
            case "l": case "el": case "ell": return 'L';
            case "m": case "em": return 'M';
            case "n": case "en": return 'N';
            case "o": case "oh": return 'O';
            case "p": case "pee": case "pe": return 'P';
            case "q": case "cue": case "queue": return 'Q';
            case "r": case "ar": case "are": return 'R';
            case "s": case "ess": return 'S';
            case "t": case "tee": case "te": return 'T';
            case "u": case "you": case "yu": return 'U';
            case "v": case "vee": case "ve": return 'V';
            case "w": return 'W';
            case "x": case "ex": return 'X';
            case "y": case "why": return 'Y';
            case "z": case "zee": case "zed": return 'Z';
            default: {
                char c = Character.toUpperCase(cleaned.charAt(0));
                return (c >= 'A' && c <= 'Z') ? c : 0;
            }
        }
    }

    private String processVoiceInput(String input) {
        if (input == null) return "";
        String lower = input.trim().toLowerCase(Locale.US);
        lower = convertNumberWords(lower);

        switch (currentFieldIndex) {
            case 0: return normalizeSchoolId(lower);
            case 4: return parseSpokenBirthdate(lower);
            case 5: return normalizeYearLevel(lower);
            case 6: return parseSpokenEmail(lower);
            default: return capitalizeName(input);
        }
    }

    private static final String[] MONTH_NAMES = {
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"
    };

    /** Parses a spoken birthdate ("January 15 2005") into ISO "yyyy-MM-dd".
     *  Falls back to the raw trimmed input when month/day/year can't all be
     *  found, so the confirmation read-back still surfaces the mismatch and
     *  lets the user say "no" to retry, same as any other misheard field. */
    private String parseSpokenBirthdate(String input) {
        String cleaned = input.replaceAll("\\b(\\d+)(st|nd|rd|th)\\b", "$1");

        int month = -1;
        for (int i = 0; i < MONTH_NAMES.length; i++) {
            if (cleaned.contains(MONTH_NAMES[i])) { month = i + 1; break; }
        }

        java.util.regex.Matcher yearMatcher =
                java.util.regex.Pattern.compile("\\b(19|20)\\d{2}\\b").matcher(cleaned);
        int year = yearMatcher.find() ? Integer.parseInt(yearMatcher.group()) : -1;

        String withoutMonthYear = month != -1 ? cleaned.replace(MONTH_NAMES[month - 1], "") : cleaned;
        if (year != -1) withoutMonthYear = withoutMonthYear.replaceAll("\\b(19|20)\\d{2}\\b", "");
        java.util.regex.Matcher dayMatcher =
                java.util.regex.Pattern.compile("\\b\\d{1,2}\\b").matcher(withoutMonthYear);
        int day = dayMatcher.find() ? Integer.parseInt(dayMatcher.group()) : -1;

        if (month == -1 || year == -1 || day < 1 || day > 31) {
            return input.trim();
        }
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day);
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
        String userBirthdate = birthdate.getText().toString().trim();
        String userYear    = yearLevel.getText().toString().trim();
        String schoolId    = schoolid.getText().toString().trim();
        String userEmail   = email.getText().toString().trim().toLowerCase(Locale.US);
        String userSection = section.getText().toString().trim();

        if (firstName.isEmpty())     { showError(fname,     "First name required",  "First name is required.");    return; }
        if (lastName.isEmpty())      { showError(lname,     "Last name required",   "Last name is required.");     return; }
        if (userBirthdate.isEmpty()) { showError(birthdate, "Birthdate required",   "Birthdate is required.");     return; }
        if (userYear.isEmpty())      { showError(yearLevel, "Year level required",  "Year level is required.");    return; }
        if (schoolId.isEmpty())      { showError(schoolid,  "School ID required",   "School ID is required.");     return; }
        if (userEmail.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(userEmail).matches()) {
            showError(email, "Invalid email", "Please enter a valid email address."); return;
        }

        updateVoiceStatus("Submitting registration...");
        registerToSupabase(firstName, middleName, lastName, userBirthdate,
                userYear, schoolId, userEmail, userSection);
    }

    private void showError(EditText field, String fieldError, String ttsMessage) {
        field.setError(fieldError);
        field.requestFocus();
        scrollToField(field);
        shakeView(field);
        lastSpokenInstruction = ttsMessage;
        say(lastSpokenInstruction, null);
    }

    /** Looks up an already-enrolled student's details by School ID (from the
     *  school's official_list import) and autofills the form. */
    private void checkSchoolIdDetails() {
        String schoolId = schoolid.getText().toString().trim();
        if (schoolId.isEmpty()) {
            showError(schoolid, "School ID required", "Please enter your School ID first.");
            return;
        }

        checkSchoolIdBtn.setEnabled(false);
        updateVoiceStatus("Checking your details...");
        lastSpokenInstruction = "Please wait while I check your registered details.";
        say(lastSpokenInstruction, null);

        JSONObject jsonBody = new JSONObject();
        String bodyStr;
        try {
            jsonBody.put("p_school_id", schoolId);
            bodyStr = jsonBody.toString();
        } catch (Exception e) {
            checkSchoolIdBtn.setEnabled(true);
            lastSpokenInstruction = "Failed to prepare the School ID check.";
            say(lastSpokenInstruction, null);
            return;
        }

        String url = ApiConfig.SUPABASE_URL + "/rest/v1/rpc/get_enrolled_student_by_school_id";

        JsonArrayRequest request = new JsonArrayRequest(Request.Method.POST, url, null,
                response -> {
                    checkSchoolIdBtn.setEnabled(true);
                    if (response == null || response.length() == 0) {
                        lastSpokenInstruction = "No registered details found for this School ID. " +
                                "Please double check the number, or fill in the fields manually.";
                        say(lastSpokenInstruction, null);
                        updateVoiceStatus("No record found for this School ID.");
                        return;
                    }
                    try {
                        JSONObject s = response.getJSONObject(0);
                        fname.setText(s.optString("first_name", ""));
                        mname.setText(s.optString("middle_name", ""));
                        lname.setText(s.optString("last_name", ""));
                        birthdate.setText(s.optString("birthdate", ""));
                        yearLevel.setText(s.optString("year_level", ""));
                        email.setText(s.optString("email", ""));
                        section.setText(s.optString("section", ""));
                        updateVoiceStatus("Details loaded. Please review.");
                        reviewLoadedDetails();
                    } catch (Exception e) {
                        lastSpokenInstruction = "Found your record, but couldn't read the details. " +
                                "Please fill in the fields manually.";
                        say(lastSpokenInstruction, null);
                        updateVoiceStatus("Failed to read registered details.");
                    }
                },
                error -> {
                    checkSchoolIdBtn.setEnabled(true);
                    lastSpokenInstruction = "Could not check your School ID right now. " +
                            "Please try again, or fill in the fields manually.";
                    say(lastSpokenInstruction, null);
                    updateVoiceStatus("Check failed.");
                }
        ) {
            @Override public byte[]              getBody()            { return bodyStr.getBytes(StandardCharsets.UTF_8); }
            @Override public String              getBodyContentType() { return "application/json; charset=utf-8"; }
            @Override public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("apikey",        ApiConfig.SUPABASE_KEY);
                headers.put("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY);
                headers.put("Content-Type",  "application/json");
                headers.put("Accept",        "application/json");
                return headers;
            }
        };

        request.setRetryPolicy(new DefaultRetryPolicy(15000, 1, 1.0f));
        requestQueue.add(request);
    }

    private void reviewLoadedDetails() {
        lastSpokenInstruction = "I found your registered details. " + buildReviewSummaryText()
                + "Please review the details above, then press Continue if everything looks correct.";
        say(lastSpokenInstruction, null);
    }

    private void registerToSupabase(String firstName, String middleName, String lastName,
                                    String userBirthdate, String yearLevelVal, String schoolId,
                                    String userEmail, String userSection) {
        continueBtn.setEnabled(false);
        voiceRegisterBtn.setEnabled(false);

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("p_first_name",  firstName);
            jsonBody.put("p_middle_name", middleName);
            jsonBody.put("p_last_name",   lastName);
            jsonBody.put("p_birthdate",   userBirthdate);
            jsonBody.put("p_year_level",  yearLevelVal);
            jsonBody.put("p_school_id",   schoolId);
            jsonBody.put("p_email",       userEmail);
            if (!userSection.isEmpty()) jsonBody.put("p_section", userSection);
        } catch (Exception e) {
            continueBtn.setEnabled(true);
            voiceRegisterBtn.setEnabled(true);
            lastSpokenInstruction = "Failed to prepare registration data.";
            say(lastSpokenInstruction, null);
            return;
        }

        // Calls the student_register RPC instead of inserting into the students
        // table directly. A direct INSERT with "Prefer: return=representation"
        // requires Supabase to SELECT the new row back afterward to include it
        // in the response — and that SELECT now has no RLS policy to pass (the
        // wide-open one was intentionally removed as part of the students-table
        // security fix), which was silently failing the whole request and
        // rolling back the insert. The RPC's own RETURNING clause runs inside
        // its SECURITY DEFINER context and doesn't need a separate policy.
        String url = ApiConfig.SUPABASE_URL + "/rest/v1/rpc/student_register";

        StringRequest request = new StringRequest(
                Request.Method.POST,
                url,
                response -> {
                    continueBtn.setEnabled(true);
                    voiceRegisterBtn.setEnabled(true);

                    String approvalStatus = "pending";
                    try {
                        org.json.JSONArray arr = new org.json.JSONArray(response);
                        if (arr.length() > 0) {
                            approvalStatus = arr.getJSONObject(0).optString("approval_status", "pending");
                        }
                    } catch (Exception ignored) {}

                    boolean approved = "approved".equalsIgnoreCase(approvalStatus);
                    String toastMessage = approved
                            ? "Registration successful! You can log in now."
                            : "Registration submitted. Please wait for admin approval.";
                    lastSpokenInstruction = approved
                            ? "Registration successful. You can log in now using your birthdate as your password. " +
                                    "Taking you to the login screen."
                            : "Registration submitted successfully. Please wait for admin approval before logging in. " +
                                    "Taking you to the login screen.";

                    Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show();
                    say(lastSpokenInstruction, () ->
                            handler.postDelayed(() -> {
                                Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
                                intent.putExtra("registered_school_id", schoolId);
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
                        Log.e("Register", "student_register RPC error (status "
                                + error.networkResponse.statusCode + "): " + body);
                        // Check the specific constraint name first — a bare "duplicate"
                        // match is true for ANY unique-constraint violation (email OR
                        // school ID), so checking it before the specific school_id case
                        // was mislabeling school-ID collisions as "email already
                        // registered" even when the email itself was brand new.
                        // Matches only the real constraint names, not a bare "school_id"
                        // substring — that used to also match unrelated errors (e.g. a
                        // PostgREST "could not find function" message that just happens
                        // to list p_school_id among the function's parameter names),
                        // misreporting them as a duplicate School ID.
                        if (body.contains("students_email_key")) {
                            errorMessage = "This email is already registered.";
                            email.setError("Email already registered");
                            email.requestFocus(); scrollToField(email);
                        } else if (body.contains("students_school_id_key")) {
                            errorMessage = "School ID already registered or invalid.";
                            schoolid.setError("Check School ID");
                            schoolid.requestFocus(); scrollToField(schoolid);
                        } else if (body.contains("duplicate")) {
                            errorMessage = "This email or School ID is already registered.";
                            email.setError("Check your details");
                            email.requestFocus(); scrollToField(email);
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
        EditText[] fields = { fname, mname, lname, birthdate, yearLevel, section, schoolid, email };
        for (EditText field : fields) {
            if (field == null) continue;
            field.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) scrollToField(v); });

            field.setOnClickListener(v -> {
                cancelVoiceModeForManualInput();
                scrollToField(v);
            });
        }
    }

    private void setupBirthdatePicker() {
        if (birthdate == null) return;
        birthdate.setOnClickListener(v -> {
            cancelVoiceModeForManualInput();
            scrollToField(v);
            showBirthdatePickerDialog();
        });
    }

    private void showBirthdatePickerDialog() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        String existing = birthdate.getText().toString().trim();
        if (!existing.isEmpty()) {
            try {
                java.util.Date parsed = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .parse(existing);
                if (parsed != null) calendar.setTime(parsed);
            } catch (Exception ignored) {}
        } else {
            calendar.add(java.util.Calendar.YEAR, -18);
        }

        android.app.DatePickerDialog dialog = new android.app.DatePickerDialog(this,
                (view, year, month, dayOfMonth) ->
                        birthdate.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, dayOfMonth)),
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH),
                calendar.get(java.util.Calendar.DAY_OF_MONTH));
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void cancelVoiceModeForManualInput() {
        if (!isVoiceMode) return;
        isVoiceMode       = false;
        isConfirmingField = false;
        isAwaitingWrongIdConfirm = false;
        isAwaitingIdRetryConfirm = false;
        stopListeningSafely();
        updateVoiceStatus("Switched to manual input. Fill in the rest, then press Continue.");
    }

    private void animateViews() {
        if (logoImage != null) UiAnim.popIn(logoImage, 0);
        View[] views = { voiceRegisterBtn, txtVoiceStatus, schoolid, fname, mname, lname,
                birthdate, yearLevel, section, email, continueBtn };
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