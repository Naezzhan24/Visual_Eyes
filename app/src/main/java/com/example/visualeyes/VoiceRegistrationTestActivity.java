package com.example.visualeyes;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.visualed.voice.NameNormalizer;
import com.visualed.voice.SttResult;
import com.visualed.voice.VoiceRegistrationManager;

public class VoiceRegistrationTestActivity extends AppCompatActivity {

    private static final int RECORD_AUDIO_CODE = 201;
    private static final long VOSK_LISTEN_TIMEOUT_MS = 6000L;
    private static final float FASTER_SPEAKING_RATE = 1.15f;

    private TextView txtStatus, txtTranscript, txtCollected;
    private Button btnStart;

    private HybridSpeechManager hybridSpeech;
    private GoogleTtsManager googleTts;
    private NameNormalizer nameNormalizer;
    private VoiceRegistrationManager registrationManager;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private VoiceRegistrationManager.State currentState = VoiceRegistrationManager.State.WELCOME;
    private boolean isTtsBusy = false;
    private boolean listenRequested = false;
    private boolean isListening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_registration_test);

        txtStatus     = findViewById(R.id.txtStatus);
        txtTranscript = findViewById(R.id.txtTranscript);
        txtCollected  = findViewById(R.id.txtCollected);
        btnStart      = findViewById(R.id.btnStart);

        googleTts      = new GoogleTtsManager(this);
        nameNormalizer = new NameNormalizer(this);
        hybridSpeech   = new HybridSpeechManager(this);

        registrationManager = new VoiceRegistrationManager(
                new VoiceRegistrationManager.RegistrationListener() {
                    @Override public void speak(String prompt) { handleSpeak(prompt); }

                    @Override public void onStateChanged(VoiceRegistrationManager.State state) {
                        currentState = state;
                    }

                    @Override public void onNeedsListening() { handleNeedsListening(); }

                    @Override public void onRegistrationComplete(
                            VoiceRegistrationManager.RegistrationData data) {
                        String summary = "First: " + data.getFirstName()
                                + "\nLast: " + data.getLastName()
                                + "\nEmail: " + data.getEmail()
                                + "\nPassword: " + data.getPassword();
                        runOnUiThread(() -> txtCollected.setText(summary));
                        Toast.makeText(VoiceRegistrationTestActivity.this,
                                "Test complete — no account was actually created.",
                                Toast.LENGTH_LONG).show();
                    }
                });

        btnStart.setOnClickListener(v -> onStartPressed());

        checkMicPermission();
    }

    private void onStartPressed() {
        btnStart.setEnabled(false);
        txtStatus.setText("Loading Vosk model...");
        hybridSpeech.initVosk(
                () -> { txtStatus.setText("Vosk ready. Starting..."); registrationManager.start(); },
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
                        "\"" + transcript + "\" -> \"" + normalized.getText() + "\""
                                + (normalized.getWasCorrected() ? "  (corrected)" : "")));

                SttResult result = new SttResult(
                        transcript, normalized.getText(), normalized.getMatchScore(),
                        false, "vosk");
                registrationManager.onSttResult(result);
            }

            @Override public void onError(String message) {
                isListening = false;
                runOnUiThread(() -> txtStatus.setText("Mic error: " + message));
            }
        }, false, null);

        handler.postDelayed(() -> { if (isListening) hybridSpeech.stopAndTranscribe(); },
                VOSK_LISTEN_TIMEOUT_MS);
    }

    private NameNormalizer.FieldType fieldTypeForState(VoiceRegistrationManager.State state) {
        if (state == VoiceRegistrationManager.State.FIRST_NAME) return NameNormalizer.FieldType.FIRST_NAME;
        if (state == VoiceRegistrationManager.State.LAST_NAME) return NameNormalizer.FieldType.LAST_NAME;
        if (state == VoiceRegistrationManager.State.EMAIL_USERNAME) return NameNormalizer.FieldType.EMAIL_USER;
        return NameNormalizer.FieldType.FREE_TEXT;
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
