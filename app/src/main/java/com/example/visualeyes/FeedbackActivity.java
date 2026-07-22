package com.example.visualeyes;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RatingBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

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

    private ImageView btnBack;
    private RatingBar ratingBarFeedback;
    private TextView txtRatingLabel;
    private EditText txtMaterialFeedback, txtInstructorFeedback;
    private Button btnVoiceMaterial, btnVoiceInstructor, btnSubmit;

    private String materialId = "";
    private String studentId = "";
    private EditText activeVoiceField;

    private HybridSpeechManager hybridSpeech;
    private SpeechRecognizer    speechRecognizer;
    private Intent              speechIntent;
    private boolean             isListening = false;
    private static final long VOSK_LISTEN_TIMEOUT_MS = 6000L;
    private static final int  REQUEST_RECORD_AUDIO    = 100;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private RequestQueue requestQueue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feedback);

        requestQueue = Volley.newRequestQueue(getApplicationContext());

        btnBack               = findViewById(R.id.btnBack);
        ratingBarFeedback     = findViewById(R.id.ratingBarFeedback);
        txtRatingLabel        = findViewById(R.id.txtRatingLabel);
        txtMaterialFeedback   = findViewById(R.id.txtMaterialFeedback);
        txtInstructorFeedback = findViewById(R.id.txtInstructorFeedback);
        btnVoiceMaterial      = findViewById(R.id.btnVoiceMaterial);
        btnVoiceInstructor    = findViewById(R.id.btnVoiceInstructor);
        btnSubmit             = findViewById(R.id.btnSubmitFeedback);

        CardView cardRating     = findViewById(R.id.cardRating);
        CardView cardMaterial   = findViewById(R.id.cardMaterial);
        CardView cardInstructor = findViewById(R.id.cardInstructor);
        if (cardRating     != null) UiAnim.popIn(cardRating, 60);
        if (cardMaterial   != null) UiAnim.fadeSlideIn(cardMaterial, 140);
        if (cardInstructor != null) UiAnim.rotateFadeIn(cardInstructor, 220);
        if (btnSubmit       != null) UiAnim.popIn(btnSubmit, 300);

        if (btnBack           != null) UiAnim.attachPressFeedback(btnBack);
        if (btnVoiceMaterial  != null) UiAnim.attachPressFeedback(btnVoiceMaterial);
        if (btnVoiceInstructor != null) UiAnim.attachPressFeedback(btnVoiceInstructor);
        if (btnSubmit          != null) UiAnim.attachPressFeedback(btnSubmit);

        materialId = getIntent().getStringExtra("material_id");

        AuthManager authManager = new AuthManager(this);
        studentId = authManager.getStudentId();

        buildSpeechIntent();
        hybridSpeech = new HybridSpeechManager(this);
        hybridSpeech.initVosk(
                () -> Log.d("Feedback_STT", "Vosk model ready — now the primary listen engine."),
                () -> Log.e("Feedback_STT", "Vosk model failed to load — using raw SpeechRecognizer only."));

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        if (btnVoiceMaterial != null) btnVoiceMaterial.setOnClickListener(v -> {
            activeVoiceField = txtMaterialFeedback;
            startVoiceInput();
        });

        if (btnVoiceInstructor != null) btnVoiceInstructor.setOnClickListener(v -> {
            activeVoiceField = txtInstructorFeedback;
            startVoiceInput();
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

        if (btnSubmit != null) btnSubmit.setOnClickListener(v -> submitFeedback());
    }

    private void buildSpeechIntent() {
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString());
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
    }

    private void startVoiceInput() {
        if (!MicPermissionHelper.hasAudioPermission(this)) {
            if (MicPermissionHelper.isPermanentlyDenied(this)) {
                Toast.makeText(this, "Microphone access is blocked. Enable it in Settings.", Toast.LENGTH_LONG).show();
                return;
            }
            // No screen-reader gate here: startVoiceInput() only ever runs from an
            // explicit tap on a voice button, never automatically, so it's safe to
            // prompt even with TalkBack active (see isScreenReaderActive() doc).
            MicPermissionHelper.markRequested(this);
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        if (isListening) return;

        if (hybridSpeech != null && hybridSpeech.isReady()) {
            startVoskVoiceInput();
        } else {
            startRawVoiceInput();
        }
    }

    private void startVoskVoiceInput() {
        final EditText targetField = activeVoiceField;
        if (targetField == null) return;
        isListening = true;

        AudioCue.playThen(handler, () -> {
            if (!isListening) return;
            Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show();

            boolean useWhisper = NetworkUtils.hasInternet(this);
            hybridSpeech.startListening(new HybridSpeechManager.HybridSpeechCallback() {
                @Override public void onListeningStarted() {}
                @Override public void onPartialResult(String partial) {}

                @Override public void onFinalResult(String transcript) {
                    isListening = false;
                    appendSpokenText(targetField, transcript);
                }

                @Override public void onError(String message) {
                    isListening = false;
                    Log.e("Feedback_STT", "Vosk failed (" + message + "), falling back to raw recognizer.");
                    startRawVoiceInput();
                }
            }, useWhisper, "feedback");

            handler.postDelayed(() -> {
                if (isListening) hybridSpeech.stopAndTranscribe();
            }, VOSK_LISTEN_TIMEOUT_MS);
        });
    }

    private void startRawVoiceInput() {
        final EditText targetField = activeVoiceField;
        if (targetField == null) return;

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            isListening = false;
            Toast.makeText(this, "Speech recognition is not available.", Toast.LENGTH_SHORT).show();
            return;
        }

        try { if (speechRecognizer != null) speechRecognizer.destroy(); } catch (Exception ignored) {}
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { isListening = true; }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() { isListening = false; }

            @Override public void onError(int error) {
                isListening = false;
                Toast.makeText(FeedbackActivity.this, "Voice not detected. Try again.", Toast.LENGTH_SHORT).show();
            }

            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) appendSpokenText(targetField, matches.get(0));
            }

            @Override public void onPartialResults(Bundle partial) {}
            @Override public void onEvent(int e, Bundle p) {}
        });

        try {
            isListening = true;
            AudioCue.playThen(handler, () -> {
                if (!isListening) return;
                try {
                    Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show();
                    speechRecognizer.startListening(speechIntent);
                } catch (Exception e) {
                    isListening = false;
                    Toast.makeText(this, "Speech recognition is not available.", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            isListening = false;
            Toast.makeText(this, "Speech recognition is not available.", Toast.LENGTH_SHORT).show();
        }
    }

    private void appendSpokenText(EditText field, String spoken) {
        if (spoken == null || spoken.trim().isEmpty()) return;
        String currentText = field.getText().toString().trim();
        field.setText(currentText.isEmpty() ? spoken : currentText + " " + spoken);
        field.setSelection(field.getText().length());
    }

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
        if (studentId == null || studentId.trim().isEmpty()) {
            Toast.makeText(this, "Student ID not found.", Toast.LENGTH_SHORT).show();
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
            body.put("student_id",    studentId);
            body.put("material_id",   materialId);
            body.put("rating",        rating);
            body.put("feedback_text", combinedFeedback);
        } catch (Exception e) {
            Toast.makeText(this, "Something went wrong.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSubmit.setEnabled(false);
        btnSubmit.setText("Sending...");
        final String bodyStr = body.toString();

        StringRequest request = new StringRequest(
                Request.Method.POST,
                ApiConfig.MATERIAL_FEEDBACKS,
                response -> {

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
                headers.put("Prefer",        "return=minimal");
                return headers;
            }
        };

        request.setTag(this);
        requestQueue.add(request);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (hybridSpeech != null) hybridSpeech.destroy();
        try { if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); } } catch (Exception ignored) {}
        if (requestQueue != null) requestQueue.cancelAll(this);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                            @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceInput();
            } else {
                Toast.makeText(this, "Microphone permission is required for voice input.", Toast.LENGTH_LONG).show();
            }
        }
    }
}
