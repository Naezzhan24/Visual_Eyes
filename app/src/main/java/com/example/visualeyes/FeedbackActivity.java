package com.example.visualeyes;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RatingBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.cardview.widget.CardView;

import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class FeedbackActivity extends AppCompatActivity {

    private static final int STEP_RATING     = 0;
    private static final int STEP_MATERIAL   = 1;
    private static final int STEP_INSTRUCTOR = 2;
    private static final int TOTAL_STEPS     = 3;

    private static final int  MAX_RETRY            = 4;
    private static final float VOICE_SPEAKING_RATE = 1.10f;

    private ScrollView feedbackScrollView;
    private ImageView btnBack;
    private RatingBar ratingBarFeedback;
    private TextView txtRatingLabel;
    private EditText txtMaterialFeedback, txtInstructorFeedback;
    private Button btnVoiceMaterial, btnVoiceInstructor, btnSubmit, btnVoiceFeedback;
    private TextView txtVoiceStatus;
    private CardView cardRatingView, cardMaterialView, cardInstructorView;

    private String materialId = "";
    private String studentEmail = "";
    private String studentPassword = "";
    private EditText activeVoiceField;

    private GoogleTtsManager    googleTts;
    private GoogleSttManager    googleStt;
    private HybridSpeechManager hybridSpeech;
    private SttCascadeSession   cascadeSession;
    private SpeechRecognizer    speechRecognizer;
    private Intent              speechIntent;
    private boolean             isListening = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RequestQueue requestQueue;

    private int voiceSessionId = 0;
    private boolean isVoiceMode        = false;
    private boolean isConfirmingField  = false;
    private boolean isAdvancingField   = false;
    private String  pendingValue       = "";
    private String  latestPartialText  = "";
    private int     retryCount         = 0;
    private int     currentStepIndex   = 0;
    private String  lastSpokenInstruction = "";

    private boolean micPermissionRequestInFlight = false;
    private boolean lastKnownMicPermission       = false;
    private Runnable pendingVoiceAction          = null;

    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                micPermissionRequestInFlight = false;
                lastKnownMicPermission = isGranted;
                if (isGranted) {
                    updateVoiceStatus("Microphone enabled.");
                    Runnable action = pendingVoiceAction != null ? pendingVoiceAction : this::startVoiceFeedbackFlow;
                    pendingVoiceAction = null;
                    lastSpokenInstruction = "Microphone permission granted.";
                    say(lastSpokenInstruction, () -> MicReadiness.awaitReady(handler, action));
                } else {
                    pendingVoiceAction = null;
                    updateVoiceStatus("Microphone permission denied.");
                    lastSpokenInstruction = "Microphone permission is required for voice input.";
                    say(lastSpokenInstruction, null);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);

        requestQueue = Volley.newRequestQueue(getApplicationContext());
        lastKnownMicPermission = hasAudioPermission();

        btnBack               = findViewById(R.id.btnBack);
        ratingBarFeedback     = findViewById(R.id.ratingBarFeedback);
        txtRatingLabel        = findViewById(R.id.txtRatingLabel);
        txtMaterialFeedback   = findViewById(R.id.txtMaterialFeedback);
        txtInstructorFeedback = findViewById(R.id.txtInstructorFeedback);
        btnVoiceMaterial      = findViewById(R.id.btnVoiceMaterial);
        btnVoiceInstructor    = findViewById(R.id.btnVoiceInstructor);
        btnSubmit             = findViewById(R.id.btnSubmitFeedback);
        btnVoiceFeedback      = findViewById(R.id.btnVoiceFeedback);
        txtVoiceStatus        = findViewById(R.id.txtVoiceStatus);
        feedbackScrollView    = findViewById(R.id.feedbackScrollView);

        cardRatingView     = findViewById(R.id.cardRating);
        cardMaterialView   = findViewById(R.id.cardMaterial);
        cardInstructorView = findViewById(R.id.cardInstructor);
        if (cardRatingView     != null) UiAnim.popIn(cardRatingView, 60);
        if (cardMaterialView   != null) UiAnim.fadeSlideIn(cardMaterialView, 140);
        if (cardInstructorView != null) UiAnim.rotateFadeIn(cardInstructorView, 220);
        if (btnSubmit           != null) UiAnim.popIn(btnSubmit, 300);

        if (btnBack           != null) UiAnim.attachPressFeedback(btnBack);
        if (btnVoiceMaterial  != null) UiAnim.attachPressFeedback(btnVoiceMaterial);
        if (btnVoiceInstructor != null) UiAnim.attachPressFeedback(btnVoiceInstructor);
        if (btnVoiceFeedback   != null) UiAnim.attachPressFeedback(btnVoiceFeedback);
        if (btnSubmit          != null) UiAnim.attachPressFeedback(btnSubmit);

        materialId = getIntent().getStringExtra("material_id");

        AuthManager authManager = new AuthManager(this);
        studentEmail    = authManager.getEmail();
        studentPassword = authManager.getPassword();

        buildSpeechIntent();

        googleTts = new GoogleTtsManager(this, false);
        googleStt = new GoogleSttManager(this, false);

        hybridSpeech = new HybridSpeechManager(this, false);
        hybridSpeech.initVosk(
                () -> Log.d("Feedback_STT", "Vosk model ready — offline fallback available."),
                () -> Log.e("Feedback_STT", "Vosk model failed to load — cloud/raw recognizer fallback only."));

        cascadeSession = new SttCascadeSession(googleStt, hybridSpeech, handler, true);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        if (btnVoiceMaterial != null) btnVoiceMaterial.setOnClickListener(v -> {
            activeVoiceField = txtMaterialFeedback;
            startFieldDictation();
        });

        if (btnVoiceInstructor != null) btnVoiceInstructor.setOnClickListener(v -> {
            activeVoiceField = txtInstructorFeedback;
            startFieldDictation();
        });

        if (btnVoiceFeedback != null) btnVoiceFeedback.setOnClickListener(v -> {
            animateClick(v);
            if (hasAudioPermission()) {
                startVoiceFeedbackFlow();
            } else if (MicPermissionHelper.isPermanentlyDenied(this)) {
                explainPermanentDenialAndOpenSettings();
            } else {
                requestMicPermissionWithRationale(this::startVoiceFeedbackFlow);
            }
        });

        ratingBarFeedback.setOnRatingBarChangeListener((ratingBar, rating, fromUser) -> {
            int stars = (int) rating;
            if      (stars == 1) txtRatingLabel.setText("1 Star - Not Satisfied");
            else if (stars == 2) txtRatingLabel.setText("2 Stars - Slightly Satisfied");
            else if (stars == 3) txtRatingLabel.setText("3 Stars - Neutral");
            else if (stars == 4) txtRatingLabel.setText("4 Stars - Satisfied");
            else if (stars == 5) txtRatingLabel.setText("5 Stars - Very Satisfied");
            else                 txtRatingLabel.setText("Please select a rating");

            if (fromUser) {
                ratingBar.animate().cancel();
                ratingBar.setScaleX(1f);
                ratingBar.setScaleY(1f);
                ratingBar.animate()
                        .scaleX(1.12f).scaleY(1.12f)
                        .setDuration(120)
                        .withEndAction(() -> ratingBar.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(150)
                                .setInterpolator(new DecelerateInterpolator())
                                .start())
                        .start();
            }
        });

        if (btnSubmit != null) btnSubmit.setOnClickListener(v -> {
            isVoiceMode = false;
            stopListeningSafely();
            submitFeedback();
        });

        handler.postDelayed(() -> {
            lastSpokenInstruction = "Feedback. I will guide you through your rating and feedback by voice. " +
                    "You can also tap a field to type instead.";
            say(lastSpokenInstruction, () -> {
                if (hasAudioPermission()) {
                    startVoiceFeedbackFlow();
                } else if (MicPermissionHelper.isPermanentlyDenied(this)) {
                    explainPermanentDenialAndOpenSettings();
                } else if (MicPermissionHelper.isScreenReaderActive(this)) {
                    lastSpokenInstruction = "Tap Voice Feedback when you're ready to enable the microphone.";
                    say(lastSpokenInstruction, null);
                } else {
                    requestMicPermissionWithRationale(this::startVoiceFeedbackFlow);
                }
            });
        }, 800);
    }

    private void say(String text, GoogleTtsManager.TtsCallback callback) {
        googleTts.speak(text, VOICE_SPEAKING_RATE, callback);
    }

    private boolean hasAudioPermission() {
        return MicPermissionHelper.hasAudioPermission(this);
    }

    private void requestMicPermissionWithRationale(Runnable afterGranted) {
        updateVoiceStatus("Requesting microphone access...");
        lastSpokenInstruction = "I need access to your microphone for voice input. " +
                "A system permission dialog will appear next — please allow it.";
        pendingVoiceAction = afterGranted;
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

    private void buildSpeechIntent() {
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,            "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,     true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,         5);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,      false);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,          1500L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,                   800L);
    }

    // ----------------------------------------------------------------------
    // Shared, session-guarded listening primitive (built-in recognizer, with
    // a Cloud STT -> Vosk fallback via SttCascadeSession) — used both by the
    // manual per-field "Use Voice" buttons and by the guided flow below, so
    // both get the same recognition accuracy.
    // ----------------------------------------------------------------------

    private interface TranscriptHandler {
        void onTranscript(String transcript);
    }

    private void listenAndHandle(String mode, String fieldDescription, TranscriptHandler resultHandler) {
        if (isFinishing() || isDestroyed()) return;
        stopListeningSafely();

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            cascadeListen(mode, fieldDescription, resultHandler);
            return;
        }

        try { if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); } } catch (Exception ignored) {}
        speechRecognizer = null;

        final int mySession = voiceSessionId;
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {
                if (mySession != voiceSessionId) return;
                isListening = true;
                latestPartialText = "";
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
            }

            @Override public void onError(int error) {
                if (mySession != voiceSessionId) return;
                isListening = false;
                Log.e("Feedback_STT", "Built-in recognizer onError code=" + error);
                if (!latestPartialText.trim().isEmpty()) {
                    String heard = latestPartialText.trim();
                    latestPartialText = "";
                    resultHandler.onTranscript(heard);
                    return;
                }
                cascadeListen(mode, fieldDescription, resultHandler);
            }

            @Override public void onResults(Bundle results) {
                if (mySession != voiceSessionId) return;
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String spoken = (matches != null && !matches.isEmpty() && matches.get(0) != null)
                        ? matches.get(0).trim() : "";
                if (spoken.isEmpty() && !latestPartialText.trim().isEmpty()) {
                    spoken = latestPartialText.trim();
                }
                latestPartialText = "";
                if (!spoken.isEmpty()) {
                    resultHandler.onTranscript(spoken);
                } else {
                    cascadeListen(mode, fieldDescription, resultHandler);
                }
            }

            @Override public void onPartialResults(Bundle partial) {
                if (mySession != voiceSessionId) return;
                ArrayList<String> p = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (p != null && !p.isEmpty()) {
                    latestPartialText = p.get(0).trim();
                    updateVoiceStatus("Hearing: " + latestPartialText);
                }
            }

            @Override public void onEvent(int e, Bundle p) {}
        });

        updateVoiceStatus("Get ready...");
        AudioCue.playThen(handler, () -> {
            if (mySession != voiceSessionId) return;
            latestPartialText = "";
            isListening       = true;
            updateVoiceStatus("Listening...");
            try {
                Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show();
                speechRecognizer.startListening(speechIntent);
            } catch (Exception e) {
                isListening = false;
                Log.e("Feedback_STT", "startListening error: " + e.getMessage());
                cascadeListen(mode, fieldDescription, resultHandler);
            }
        });
    }

    private void cascadeListen(String mode, String fieldDescription, TranscriptHandler resultHandler) {
        if (isFinishing() || isDestroyed()) return;
        final int mySession = voiceSessionId;
        updateVoiceStatus("Get ready...");
        isListening = true;

        cascadeSession.cascade(this, mode, fieldDescription, new SttCascadeSession.Listener() {
            @Override public void onListeningStarted() {
                if (mySession != voiceSessionId) return;
                updateVoiceStatus("Listening...");
            }

            @Override public void onPartialResult(String partial) {
                if (mySession != voiceSessionId) return;
                updateVoiceStatus("Hearing: " + partial);
            }

            @Override public void onTranscript(String transcript) {
                if (mySession != voiceSessionId) return;
                isListening = false;
                resultHandler.onTranscript(transcript);
            }

            @Override public void onExhausted() {
                if (mySession != voiceSessionId) return;
                isListening = false;
                if (isVoiceMode) {
                    retryOrFallbackToManual();
                } else {
                    updateVoiceStatus("Voice not detected. Try again.");
                    Toast.makeText(FeedbackActivity.this, "Voice not detected. Try again.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void stopListeningSafely() {
        isListening = false;
        voiceSessionId++;
        if (cascadeSession != null) cascadeSession.cancel();
        try { if (speechRecognizer != null) speechRecognizer.stopListening(); } catch (Exception ignored) {}
        try { if (speechRecognizer != null) speechRecognizer.cancel(); }        catch (Exception ignored) {}
    }

    // ----------------------------------------------------------------------
    // Manual per-field dictation (existing "Use Voice" buttons) — appends
    // the transcript to whichever field triggered it, no confirmation step.
    // ----------------------------------------------------------------------

    private void startFieldDictation() {
        if (!hasAudioPermission()) {
            if (MicPermissionHelper.isPermanentlyDenied(this)) {
                explainPermanentDenialAndOpenSettings();
                return;
            }
            requestMicPermissionWithRationale(this::startFieldDictation);
            return;
        }
        if (isListening) return;

        final EditText targetField = activeVoiceField;
        if (targetField == null) return;

        listenAndHandle("command", "feedback", transcript -> appendSpokenText(targetField, transcript));
    }

    private void appendSpokenText(EditText field, String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) return;
        String currentText = field.getText().toString().trim();
        field.setText(currentText.isEmpty() ? spoken : currentText + " " + spoken);
        field.setSelection(field.getText().length());
    }

    // ----------------------------------------------------------------------
    // Guided voice flow: rating -> material feedback -> instructor feedback,
    // each prompted, confirmed ("is that correct? yes/no"), then a summary
    // read-back before auto-submitting. Mirrors RegisterActivity's voice flow.
    // ----------------------------------------------------------------------

    private void startVoiceFeedbackFlow() {
        isVoiceMode        = true;
        isConfirmingField  = false;
        isAdvancingField   = false;
        retryCount         = 0;
        latestPartialText  = "";
        pendingValue       = "";
        currentStepIndex   = STEP_RATING;

        updateVoiceStatus("Voice feedback started.");
        lastSpokenInstruction = "I will ask for your rating, then your feedback, one at a time. " +
                "You'll hear a short beep before each time to speak.";
        say(lastSpokenInstruction, () -> handler.postDelayed(this::promptCurrentStep, 400));
    }

    private void promptCurrentStep() {
        if (!isVoiceMode) return;

        if (currentStepIndex >= TOTAL_STEPS) {
            finishVoiceFeedbackFlow();
            return;
        }

        isConfirmingField  = false;
        latestPartialText  = "";

        scrollToView(cardForStep(currentStepIndex));
        updateVoiceStatus("Say your " + getStepName(currentStepIndex) + "...");
        lastSpokenInstruction = getPromptForStep(currentStepIndex);
        say(lastSpokenInstruction, this::listenForStep);
    }

    private void listenForStep() {
        if (!isVoiceMode) return;
        listenAndHandle("command", getStepName(currentStepIndex), this::handleSpokenText);
    }

    private void confirmStep(String value) {
        if (!isVoiceMode) return;
        isConfirmingField  = true;
        latestPartialText  = "";
        pendingValue       = value;

        updateVoiceStatus("Confirm: " + value);
        lastSpokenInstruction = buildConfirmMessage(currentStepIndex, value);
        say(lastSpokenInstruction, this::listenForStep);
    }

    private void handleSpokenText(String spoken) {
        if (!isVoiceMode) return;
        String lower = spoken.toLowerCase(Locale.US).trim();

        if (lower.equals("cancel") || lower.equals("stop")) {
            isVoiceMode = false;
            stopListeningSafely();
            lastSpokenInstruction = "Voice feedback cancelled.";
            say(lastSpokenInstruction, null);
            updateVoiceStatus("Voice feedback cancelled.");
            return;
        }

        if (isConfirmingField) {
            handleConfirmationResponse(lower);
            return;
        }

        if (currentStepIndex != STEP_RATING && isSkipCommand(lower)) {
            isAdvancingField = true;
            String skippedStep = getStepName(currentStepIndex);
            currentStepIndex++;
            lastSpokenInstruction = skippedStep + " skipped.";
            say(lastSpokenInstruction, () -> handler.postDelayed(() -> {
                isAdvancingField = false;
                promptCurrentStep();
            }, 400));
            return;
        }

        if (currentStepIndex == STEP_RATING) {
            int rating = parseRating(lower);
            if (rating == 0) {
                lastSpokenInstruction = "I didn't catch a number from 1 to 5. Please say a rating from 1 to 5 stars.";
                say(lastSpokenInstruction, this::listenForStep);
                return;
            }
            updateVoiceStatus("Heard: " + rating + " stars");
            confirmStep(String.valueOf(rating));
            return;
        }

        updateVoiceStatus("Heard: " + spoken);
        confirmStep(spoken.trim());
    }

    private void handleConfirmationResponse(String lower) {
        if (isYes(lower)) {

            isConfirmingField = false;
            retryCount        = 0;
            commitStepValue(currentStepIndex, pendingValue);

            isAdvancingField = true;
            currentStepIndex++;
            handler.postDelayed(() -> {
                isAdvancingField = false;
                promptCurrentStep();
            }, 600);

        } else if (isNo(lower)) {

            isConfirmingField = false;
            retryCount        = 0;
            pendingValue      = "";
            lastSpokenInstruction = "Okay, please say it again.";
            say(lastSpokenInstruction, () -> handler.postDelayed(this::promptCurrentStep, 400));

        } else {

            retryCount++;
            if (retryCount <= MAX_RETRY) {
                lastSpokenInstruction = "Please say yes to confirm or no to try again.";
                say(lastSpokenInstruction, this::listenForStep);
            } else {
                retryCount = 0;
                isConfirmingField = false;
                lastSpokenInstruction = "Moving on. Please say it again.";
                say(lastSpokenInstruction, () -> handler.postDelayed(this::promptCurrentStep, 400));
            }
        }
    }

    private void commitStepValue(int index, String value) {
        runOnUiThread(() -> {
            switch (index) {
                case STEP_RATING:
                    int stars = Integer.parseInt(value);
                    ratingBarFeedback.setRating(stars);
                    updateVoiceStatus("Rating saved: " + stars + (stars == 1 ? " star" : " stars"));
                    break;
                case STEP_MATERIAL:
                    txtMaterialFeedback.setText(value);
                    txtMaterialFeedback.setSelection(txtMaterialFeedback.getText().length());
                    updateVoiceStatus("Material feedback saved.");
                    break;
                case STEP_INSTRUCTOR:
                    txtInstructorFeedback.setText(value);
                    txtInstructorFeedback.setSelection(txtInstructorFeedback.getText().length());
                    updateVoiceStatus("Instructor feedback saved.");
                    break;
                default: break;
            }
        });
    }

    private void retryOrFallbackToManual() {
        retryCount++;
        if (retryCount <= MAX_RETRY) {
            updateVoiceStatus("Didn't catch that. Retrying...");
            lastSpokenInstruction = "I did not hear you clearly. Please speak closer to the microphone, " +
                    "speak a little louder, or speak more slowly and clearly, and try again.";
            say(lastSpokenInstruction, this::listenForStep);
        } else {
            retryCount = 0;
            isVoiceMode = false;
            lastSpokenInstruction = "I am having trouble hearing you. " +
                    "You may fill this in manually, then press Voice Feedback to continue.";
            say(lastSpokenInstruction, null);
            updateVoiceStatus("Please type manually or press Voice Feedback again.");
        }
    }

    private void finishVoiceFeedbackFlow() {
        isVoiceMode        = false;
        isConfirmingField  = false;
        updateVoiceStatus("Reviewing your feedback...");
        Toast.makeText(this, "Voice feedback completed.", Toast.LENGTH_SHORT).show();

        lastSpokenInstruction = buildFeedbackSummary();
        say(lastSpokenInstruction, () -> {
            stopListeningSafely();
            submitFeedback();
        });
    }

    private String buildFeedbackSummary() {
        StringBuilder sb = new StringBuilder("Here is a summary of your feedback. ");

        int stars = (int) ratingBarFeedback.getRating();
        sb.append("Rating: ").append(stars).append(stars == 1 ? " star. " : " stars. ");

        String material = txtMaterialFeedback.getText().toString().trim();
        sb.append("Material feedback: ").append(material.isEmpty() ? "none provided" : material).append(". ");

        String instructor = txtInstructorFeedback.getText().toString().trim();
        sb.append("Instructor feedback: ").append(instructor.isEmpty() ? "none provided" : instructor).append(". ");

        sb.append("Submitting your feedback now.");
        return sb.toString();
    }

    private String getStepName(int index) {
        switch (index) {
            case STEP_RATING:     return "Rating";
            case STEP_MATERIAL:   return "Material Feedback";
            case STEP_INSTRUCTOR: return "Instructor Feedback";
            default:              return "Field";
        }
    }

    private String getPromptForStep(int index) {
        switch (index) {
            case STEP_RATING:     return "Please say your satisfaction rating, from 1 to 5 stars.";
            case STEP_MATERIAL:   return "Please say your feedback about the learning material, or say skip to leave it blank.";
            case STEP_INSTRUCTOR: return "Please say your feedback for the instructor, or say skip to leave it blank.";
            default:              return "Please speak now.";
        }
    }

    private String buildConfirmMessage(int index, String value) {
        if (index == STEP_RATING) {
            return "I heard " + value + " stars. Is that correct? Say yes or no.";
        }
        return "I heard: " + value + ". Is that correct? Say yes or no.";
    }

    private View cardForStep(int index) {
        switch (index) {
            case STEP_RATING:     return cardRatingView;
            case STEP_MATERIAL:   return cardMaterialView;
            case STEP_INSTRUCTOR: return cardInstructorView;
            default:              return null;
        }
    }

    private int parseRating(String spoken) {
        String lower = convertNumberWords(spoken.toLowerCase(Locale.US));
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= '1' && c <= '5') return c - '0';
        }
        return 0;
    }

    private String convertNumberWords(String input) {
        return input
                .replace("one", "1").replace("two", "2").replace("three", "3")
                .replace("four", "4").replace("five", "5");
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

    private void updateVoiceStatus(String msg) {
        runOnUiThread(() -> { if (txtVoiceStatus != null) txtVoiceStatus.setText("Voice Status: " + msg); });
    }

    private void scrollToView(View targetView) {
        if (feedbackScrollView == null || targetView == null) return;
        targetView.postDelayed(() -> {
            int[] scrollLoc = new int[2];
            int[] targetLoc = new int[2];
            feedbackScrollView.getLocationOnScreen(scrollLoc);
            targetView.getLocationOnScreen(targetLoc);
            int scrollY = feedbackScrollView.getScrollY();
            int targetY = targetLoc[1] - scrollLoc[1] + scrollY;
            feedbackScrollView.smoothScrollTo(0, Math.max(targetY - 130, 0));
        }, 250);
    }

    private void animateClick(View view) {
        if (view == null) return;
        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(80).start())
                .start();
    }

    // ----------------------------------------------------------------------
    // Feedback submission
    // ----------------------------------------------------------------------

    private void submitFeedback() {
        int rating = (int) ratingBarFeedback.getRating();

        if (rating == 0) {
            Toast.makeText(this, "Please select a star rating.", Toast.LENGTH_SHORT).show();
            UiAnim.shake(ratingBarFeedback);
            return;
        }
        if (materialId == null || materialId.trim().isEmpty()) {
            Toast.makeText(this, "Material ID not found.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (studentEmail == null || studentEmail.trim().isEmpty()) {
            Toast.makeText(this, "Student account not found. Please log in again.", Toast.LENGTH_SHORT).show();
            return;
        }

        int materialIdInt;
        try {
            materialIdInt = Integer.parseInt(materialId.trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Material ID is invalid.", Toast.LENGTH_SHORT).show();
            return;
        }

        String satisfactionLabel;
        if      (rating == 1) satisfactionLabel = "Not Satisfied";
        else if (rating == 2) satisfactionLabel = "Slightly Satisfied";
        else if (rating == 3) satisfactionLabel = "Neutral";
        else if (rating == 4) satisfactionLabel = "Satisfied";
        else                  satisfactionLabel = "Very Satisfied";

        String materialFeedback   = txtMaterialFeedback.getText().toString().trim();
        String instructorFeedback = txtInstructorFeedback.getText().toString().trim();

        if (materialFeedback.isEmpty())   materialFeedback   = "No material feedback provided.";
        if (instructorFeedback.isEmpty()) instructorFeedback = "No instructor feedback provided.";

        String combinedFeedback =
                "Satisfaction: " + satisfactionLabel +
                        "\nMaterial Feedback: " + materialFeedback +
                        "\nInstructor Feedback: " + instructorFeedback;

        JSONObject body = new JSONObject();
        try {
            body.put("p_email",         studentEmail);
            body.put("p_password",      studentPassword);
            body.put("p_material_id",   materialIdInt);
            body.put("p_rating",        rating);
            body.put("p_feedback_text", combinedFeedback);
        } catch (Exception e) {
            Toast.makeText(this, "Something went wrong.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSubmit.setEnabled(false);
        btnSubmit.setText("Sending...");
        final String bodyStr = body.toString();

        // Calls the student_submit_feedback RPC instead of inserting into
        // material_feedbacks directly. The direct insert let the client choose
        // its own student_id, so anyone with the anon key could post feedback
        // as another student, on any material. The RPC verifies the account
        // server-side and derives student_id from it.
        String url = ApiConfig.SUPABASE_URL + "/rest/v1/rpc/student_submit_feedback";

        StringRequest request = new StringRequest(
                Request.Method.POST,
                url,
                response -> {
                    // The RPC returns false (with HTTP 200) when the account
                    // couldn't be verified or the student has no access to this
                    // material — so a 200 alone doesn't mean the row was saved.
                    if (response != null && response.trim().equalsIgnoreCase("false")) {
                        btnSubmit.setEnabled(true);
                        btnSubmit.setText("Submit Feedback");
                        Toast.makeText(this,
                                "Could not submit feedback. Please log in again, or open this material from your materials list first.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    btnSubmit.setText("Sent!");
                    Toast.makeText(this, "Feedback submitted successfully!", Toast.LENGTH_LONG).show();
                    btnSubmit.postDelayed(() -> {
                        finish();
                        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                    }, 400);
                },
                error -> {
                    btnSubmit.setEnabled(true);
                    btnSubmit.setText("Submit Feedback");
                    String message = "Failed to submit feedback.";
                    if (error.networkResponse != null && error.networkResponse.data != null) {
                        message += "\n" + new String(error.networkResponse.data, StandardCharsets.UTF_8);
                    } else if (error.getMessage() != null) {
                        message += "\n" + error.getMessage();
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
        ) {
            @Override
            public byte[] getBody() {
                return bodyStr.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String getBodyContentType() {
                return "application/json; charset=utf-8";
            }

            @Override
            public Map<String, String> getHeaders() {
                Map<String, String> headers = new HashMap<>();
                headers.put("apikey",        ApiConfig.SUPABASE_KEY);
                headers.put("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY);
                headers.put("Content-Type",  "application/json");
                headers.put("Accept",        "application/json");
                return headers;
            }
        };

        request.setTag(this);
        requestQueue.add(request);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean nowGranted = hasAudioPermission();
        if (nowGranted && !lastKnownMicPermission && !micPermissionRequestInFlight) {
            updateVoiceStatus("Microphone enabled.");
            MicReadiness.awaitReady(handler, this::startVoiceFeedbackFlow);
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
        try { if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); } } catch (Exception ignored) {}
        if (hybridSpeech != null) hybridSpeech.destroy();
        if (googleTts    != null) googleTts.destroy();
        if (googleStt    != null) googleStt.destroy();
        if (requestQueue != null) requestQueue.cancelAll(this);
        super.onDestroy();
    }
}