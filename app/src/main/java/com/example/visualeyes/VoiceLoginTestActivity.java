package com.example.visualeyes;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.Volley;
import com.visualed.voice.NameNormalizer;
import com.visualed.voice.SttResult;
import com.visualed.voice.VoiceLoginManager;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class VoiceLoginTestActivity extends AppCompatActivity {

    private static final int RECORD_AUDIO_CODE = 202;
    private static final long VOSK_LISTEN_TIMEOUT_MS = 6000L;
    private static final int MAX_PASSWORD_RETRIES = 2;
    private static final float FASTER_SPEAKING_RATE = 1.15f;

    private TextView txtStatus, txtTranscript, txtCollected;
    private Button btnStart;

    private HybridSpeechManager hybridSpeech;
    private GoogleTtsManager googleTts;
    private NameNormalizer nameNormalizer;
    private VoiceLoginManager loginManager;
    private RequestQueue requestQueue;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private VoiceLoginManager.State currentState = VoiceLoginManager.State.WELCOME;
    private boolean isTtsBusy = false;
    private boolean listenRequested = false;
    private boolean isListening = false;
    private int passwordRetries = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_login_test);

        txtStatus     = findViewById(R.id.txtStatus);
        txtTranscript = findViewById(R.id.txtTranscript);
        txtCollected  = findViewById(R.id.txtCollected);
        btnStart      = findViewById(R.id.btnStart);

        googleTts      = new GoogleTtsManager(this);
        nameNormalizer = new NameNormalizer(this);
        hybridSpeech   = new HybridSpeechManager(this);
        requestQueue   = Volley.newRequestQueue(this);

        loginManager = new VoiceLoginManager(new VoiceLoginManager.LoginListener() {
            @Override public void speak(String prompt) { handleSpeak(prompt); }

            @Override public void onStateChanged(VoiceLoginManager.State state) {
                currentState = state;
            }

            @Override public void onNeedsListening() { handleNeedsListening(); }

            @Override public void onAttemptLogin(String email, String password) {
                attemptLogin(email, password);
            }
        });

        btnStart.setOnClickListener(v -> onStartPressed());

        checkMicPermission();
    }

    private void onStartPressed() {
        btnStart.setEnabled(false);
        passwordRetries = 0;
        txtStatus.setText("Loading Vosk model...");
        hybridSpeech.initVosk(
                () -> { txtStatus.setText("Vosk ready. Starting..."); loginManager.start(); },
                () -> { txtStatus.setText("Vosk failed to load."); btnStart.setEnabled(true); }
        );
    }

    private void handleSpeak(String prompt) {
        isTtsBusy = true;
        runOnUiThread(() -> txtStatus.setText(prompt));
        googleTts.speak(prompt, FASTER_SPEAKING_RATE, () -> {
            isTtsBusy = false;
            if (listenRequested) {
                listenRequested = false;
                beginListening();
            }
        });
    }

    private void handleNeedsListening() {
        if (isTtsBusy) {
            listenRequested = true;
        } else {
            beginListening();
        }
    }

    private void beginListening() {
        if (isListening || !hybridSpeech.isReady()) return;
        isListening = true;
        runOnUiThread(() -> txtStatus.setText(txtStatus.getText() + "  [listening...]"));

        hybridSpeech.startListening(new HybridSpeechManager.HybridSpeechCallback() {
            @Override public void onListeningStarted() {}

            @Override public void onPartialResult(String partial) {
                runOnUiThread(() -> txtTranscript.setText("Hearing: " + partial));
            }

            @Override public void onFinalResult(String transcript) {
                isListening = false;
                NameNormalizer.FieldType type = fieldTypeForState(currentState);
                NameNormalizer.NormalizedResult normalized = nameNormalizer.normalize(transcript, type);

                runOnUiThread(() -> txtTranscript.setText(
                        "\"" + transcript + "\" -> \"" + normalized.getText() + "\""));

                SttResult result = new SttResult(
                        transcript, normalized.getText(), normalized.getMatchScore(), false, "vosk");
                loginManager.onSttResult(result);
            }

            @Override public void onError(String message) {
                isListening = false;
                runOnUiThread(() -> txtStatus.setText("Mic error: " + message));
            }
        }, false, null);

        handler.postDelayed(() -> { if (isListening) hybridSpeech.stopAndTranscribe(); },
                VOSK_LISTEN_TIMEOUT_MS);
    }

    private NameNormalizer.FieldType fieldTypeForState(VoiceLoginManager.State state) {
        return NameNormalizer.FieldType.FREE_TEXT;
    }

    private void attemptLogin(String email, String password) {
        runOnUiThread(() -> txtCollected.setText("Trying: " + email + " / " + password));

        String url = ApiConfig.STUDENTS
                + "?email=eq." + android.net.Uri.encode(email)
                + "&password=eq." + android.net.Uri.encode(password)
                + "&select=id,first_name,approval_status"
                + "&limit=1";

        JsonArrayRequest request = new JsonArrayRequest(Request.Method.GET, url, null,
                response -> {
                    if (response != null && response.length() > 0) {
                        try {
                            JSONObject student = response.getJSONObject(0);
                            String firstName = student.optString("first_name", "");
                            runOnUiThread(() -> txtCollected.setText(
                                    "SUCCESS (test only, no real session started): " + email));
                            handleSpeak("Welcome back, " + firstName + "! Login test successful.");
                            btnStart.post(() -> btnStart.setEnabled(true));
                        } catch (Exception e) {
                            handleLoginFailure(email);
                        }
                    } else {
                        handleLoginFailure(email);
                    }
                },
                error -> handleLoginFailure(email)
        ) {
            @Override public Map<String, String> getHeaders() {
                Map<String, String> h = new HashMap<>();
                h.put("apikey",        ApiConfig.SUPABASE_KEY);
                h.put("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY);
                h.put("Accept",        "application/json");
                return h;
            }
        };
        requestQueue.add(request);
    }

    private void handleLoginFailure(String email) {
        runOnUiThread(() -> txtCollected.setText("FAILED: " + email));
        passwordRetries++;
        if (passwordRetries <= MAX_PASSWORD_RETRIES) {
            loginManager.retryPassword();
        } else {
            passwordRetries = 0;
            loginManager.restart();
        }
    }

    private void checkMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_CODE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (hybridSpeech != null) hybridSpeech.destroy();
        if (googleTts != null) googleTts.stopSpeaking();
    }
}
