package com.example.visualeyes;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.Volley;
import com.bumptech.glide.Glide;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ProfileActivity extends AppCompatActivity {

    private static final String PREFS_NAME               = "VisualEyesPrefs";
    private static final String KEY_TTS_ENABLED          = "tts_enabled";
    private static final String KEY_STT_ENABLED          = "stt_enabled";
    private static final String KEY_IMPAIRMENT_LEVEL     = "impairmentLevel";
    private static final String KEY_RECOMMENDED_TEXT_SIZE= "recommendedTextSize";
    private static final int    CAMERA_PERMISSION_CODE   = 101;
    private static final long   LISTEN_DELAY_NORMAL      = 500L;
    private static final long   LISTEN_DELAY_AFTER_TTS   = 400L;
    private static final long   COMMAND_COOLDOWN         = 900L;

    private static final String BASE_URL          = "http://192.168.1.100/visualed/";
    private static final String UPLOAD_PROFILE_URL= BASE_URL + "upload_profile_image.php";

    private ImageView imgProfile, btnEditProfile;
    private ImageView iconHome, iconMaterials, iconProfile;
    private TextView txtStudentName, txtCourse, txtEmail, txtStudentNumber, txtImpairmentLevel;
    private TextView txtVoiceStatus, txtRecognizedText, txtVoiceHint;
    private TextView textHome, textMaterials, textProfile, txtStudentInfoLabel;
    private LinearLayout optionTts, optionStt, optionHelp, navHome, navMaterials, navProfile;
    private SwitchCompat switchTts, switchStt;
    private Button btnRetakeAssessment, btnLogout;
    private CardView cardProfileInfo, cardImpairmentLevel, cardVoiceStatus, cardOptions, bottomNavCard;
    private View topBarProfile;
    private ImageView btnMenu;

    private DrawerLayout drawerLayout;
    private LinearLayout drawerMaterialsContainer;
    private ImageView btnCloseDrawer;
    private MaterialsDrawerController materialsDrawer;

    private GoogleTtsManager googleTts;
    private GoogleSttManager googleStt;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private static final long CLOUD_STT_SAFETY_TIMEOUT_MS = 11000L;

    private HybridSpeechManager hybridSpeech;
    private static final long VOSK_LISTEN_TIMEOUT_MS = 6000L;

    private AuthManager authManager;
    private SharedPreferences prefs;

    private boolean isTtsEnabled      = true;
    private boolean isSttEnabled      = true;
    private boolean isListening       = false;
    private boolean isTtsSpeaking     = false;
    private boolean commandHandled    = false;
    private String  lastHandledCommand    = "";
    private long    lastHandledCommandTime= 0L;
    private String  currentStudentId      = "";
    private String  lastSpokenInstruction  = "";

    private long lastTapTime = 0L;
    private int  tapCount    = 0;
    private static final long TRIPLE_TAP_WINDOW_MS = 600L;

    private android.view.ScaleGestureDetector scaleGestureDetector;
    private View zoomTarget;
    private float currentZoomScale = 1.0f;
    private static final float MIN_ZOOM_SCALE = 1.0f;
    private static final float MAX_ZOOM_SCALE = 3.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable delayedStartListening = () -> {
        if (isSttEnabled && !isFinishing() && !isDestroyed() && !isTtsSpeaking && !isListening) {
            startVoiceRecognition();
        }
    };

    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                try {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri imageUri = result.getData().getData();
                        if (imageUri != null) { imgProfile.setImageURI(imageUri); uploadImageFromUri(imageUri); }
                        else Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Gallery failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                try {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Bundle extras = result.getData().getExtras();
                        if (extras != null && extras.get("data") != null) {
                            Bitmap bitmap = (Bitmap) extras.get("data");
                            imgProfile.setImageBitmap(bitmap);
                            uploadImageFromBitmap(bitmap);
                        } else Toast.makeText(this, "No image captured", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Camera failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        authManager = new AuthManager(this);
        prefs       = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        googleTts = new GoogleTtsManager(this);
        googleStt = new GoogleSttManager();
        hybridSpeech = new HybridSpeechManager(this);
        hybridSpeech.initVosk(
                () -> Log.d("Profile_STT", "Vosk model ready — now the primary listen engine."),
                () -> Log.e("Profile_STT", "Vosk model failed to load — using raw SpeechRecognizer only."));

        ensureDefaultVoiceOptions();
        bindViews();
        loadSavedOptions();
        applyFontSize();
        loadProfileData();
        setActiveNav("profile");
        setupSwitches();
        setupClickActions();
        initializeVoiceStatus();
        buildSpeechIntent();
        setupSpeechRecognizer();
        setupPressAnimations();
        setupGestures();
        setupMenuButton();
        materialsDrawer = new MaterialsDrawerController(this, drawerLayout, drawerMaterialsContainer,
                btnMenu, btnCloseDrawer, this::stopListeningSafely);
        materialsDrawer.load();
        animateProfileEntrance();
        fetchStudentProfileFromServer();

        handler.postDelayed(() ->
                speak("Profile screen. Your registered details and visual impairment level " +
                        "are displayed. Say a command or say help for available options.", true), 900);
    }

    private void bindViews() {
        topBarProfile      = findViewById(R.id.topBarProfile);
        btnMenu            = findViewById(R.id.btnMenu);
        drawerLayout             = findViewById(R.id.drawerLayout);
        drawerMaterialsContainer = findViewById(R.id.drawerMaterialsContainer);
        btnCloseDrawer           = findViewById(R.id.btnCloseDrawer);
        txtStudentInfoLabel= findViewById(R.id.txtStudentInfoLabel);
        imgProfile         = findViewById(R.id.imgProfile);
        btnEditProfile     = findViewById(R.id.btnEditProfile);
        txtStudentName     = findViewById(R.id.txtStudentName);
        txtCourse          = findViewById(R.id.txtCourse);
        txtEmail           = findViewById(R.id.txtEmail);
        txtStudentNumber   = findViewById(R.id.txtStudentNumber);
        txtImpairmentLevel = findViewById(R.id.txtImpairmentLevel);
        txtVoiceStatus     = findViewById(R.id.txtVoiceStatus);
        txtRecognizedText  = findViewById(R.id.txtRecognizedText);
        txtVoiceHint       = findViewById(R.id.txtVoiceHint);
        optionTts          = findViewById(R.id.optionTts);
        optionStt          = findViewById(R.id.optionStt);
        optionHelp         = findViewById(R.id.optionHelp);
        switchTts          = findViewById(R.id.switchTts);
        switchStt          = findViewById(R.id.switchStt);
        navHome            = findViewById(R.id.navHome);
        navMaterials       = findViewById(R.id.navMaterials);
        navProfile         = findViewById(R.id.navProfile);
        iconHome           = findViewById(R.id.iconHome);
        iconMaterials      = findViewById(R.id.iconMaterials);
        iconProfile        = findViewById(R.id.iconProfile);
        textHome           = findViewById(R.id.textHome);
        textMaterials      = findViewById(R.id.textMaterials);
        textProfile        = findViewById(R.id.textProfile);
        cardProfileInfo    = findViewById(R.id.cardProfileInfo);
        cardImpairmentLevel= findViewById(R.id.cardImpairmentLevel);
        cardVoiceStatus    = findViewById(R.id.cardVoiceStatus);
        cardOptions        = findViewById(R.id.cardOptions);
        bottomNavCard      = findViewById(R.id.bottomNavCard);
        btnRetakeAssessment= findViewById(R.id.btnRetakeAssessment);
        btnLogout          = findViewById(R.id.btnLogout);
    }

    private void buildSpeechIntent() {
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,            "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,     true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,         8);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,      false);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,          1100L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,                   800L);
    }

    private void setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateVoiceStatus("Speech recognition not available.");
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {

            @Override public void onReadyForSpeech(Bundle p) {
                isListening    = true;
                commandHandled = false;
                updateVoiceStatus("Listening...");
            }

            @Override public void onBeginningOfSpeech() { updateVoiceStatus("Hearing your voice..."); }
            @Override public void onRmsChanged(float r)  {}
            @Override public void onBufferReceived(byte[] b) {}

            @Override public void onEndOfSpeech() {
                isListening = false;
                if (!commandHandled) updateVoiceStatus("Processing...");
            }

            @Override public void onError(int error) {
                isListening    = false;
                commandHandled = false;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        updateVoiceStatus("No speech detected.");
                        updateRecognizedText("Waiting for speech...");
                        if (isSttEnabled && !isTtsSpeaking) scheduleListening(LISTEN_DELAY_NORMAL);
                        return;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        updateVoiceStatus("Recognizer busy.");
                        updateRecognizedText("Waiting for speech...");
                        if (isSttEnabled && !isTtsSpeaking) scheduleListening(LISTEN_DELAY_NORMAL);
                        return;
                    default:
                        Log.e("Profile_STT", "Built-in recognizer onError code=" + error);
                        if (SpeechEngineHealth.isRecognizerIncompatible(error)) {
                            Log.e("Profile_STT", "Built-in recognizer is not usable on this device — "
                                    + "skipping it from now on.");
                            SpeechEngineHealth.markBuiltInRecognizerBroken(ProfileActivity.this);
                        }
                        updateVoiceStatus("Voice recognition failed.");
                        updateRecognizedText("Waiting for speech...");
                        if (isSttEnabled && !isTtsSpeaking) cascadeFromBuiltIn();
                        return;
                }
            }

            @Override public void onResults(Bundle results) {
                isListening = false;
                if (commandHandled) return;
                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    processCommand(matches.get(0).trim());
                } else {
                    updateVoiceStatus("No speech detected.");
                    if (isSttEnabled && !isTtsSpeaking) cascadeFromBuiltIn();
                }
            }

            @Override public void onPartialResults(Bundle partial) {
                if (commandHandled) return;
                ArrayList<String> p =
                        partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (p == null || p.isEmpty()) return;
                String spoken     = p.get(0).trim();
                String normalized = normalize(spoken);
                updateRecognizedText("Hearing: " + spoken);

                if (isQuickCommand(normalized)) {
                    processCommand(spoken);
                    try { if (speechRecognizer != null) speechRecognizer.stopListening(); }
                    catch (Exception ignored) {}
                }
            }

            @Override public void onEvent(int e, Bundle p) {}
        });
    }

    private void processCommand(String spokenText) {
        String normalized = normalize(spokenText);
        if (normalized.isEmpty()) {
            scheduleListening(LISTEN_DELAY_AFTER_TTS);
            return;
        }

        if (isDuplicate(normalized)) {
            scheduleListening(LISTEN_DELAY_AFTER_TTS);
            return;
        }

        commandHandled         = true;
        lastHandledCommand     = normalized;
        lastHandledCommandTime = System.currentTimeMillis();

        updateVoiceStatus("Command: " + spokenText);
        updateRecognizedText("Recognized: " + spokenText);
        handleCommand(normalized);
    }

    private void handleCommand(String cmd) {

        if (cmd.contains("help") || cmd.contains("command") || cmd.contains("what can")) {
            speak("Available commands. " +
                    "Say home to go to the home screen. " +
                    "Say materials to view all learning materials. " +
                    "Say profile to stay on this screen. " +
                    "Say retake assessment to redo the visual test. " +
                    "Say log out to sign out. " +
                    "Say turn on or turn off text to speech. " +
                    "Say turn on or turn off speech to text. " +
                    "Say read profile to hear your details.", true);
            return;
        }

        if (cmd.contains("read profile") || cmd.contains("read my profile")
                || cmd.contains("read details") || cmd.contains("describe")) {
            readProfileAloud();
            return;
        }

        if (cmd.contains("text to speech")) {
            boolean turnOn = cmd.contains("turn on") || cmd.contains("enable");
            if (turnOn) {
                if (!switchTts.isChecked()) { switchTts.setChecked(true); }
                else speak("Text to speech is already enabled.", true);
            } else {
                if (switchTts.isChecked()) { switchTts.setChecked(false); }
                else scheduleListening(LISTEN_DELAY_AFTER_TTS);
            }
            return;
        }

        if (cmd.contains("speech to text")) {
            boolean turnOn = cmd.contains("turn on") || cmd.contains("enable");
            if (turnOn) {
                if (!switchStt.isChecked()) { switchStt.setChecked(true); }
                else speak("Speech to text is already enabled.", true);
            } else {
                if (switchStt.isChecked()) { switchStt.setChecked(false); }
            }
            return;
        }

        if (cmd.contains("logout") || cmd.contains("log out") || cmd.contains("sign out")) {
            speak("Logging out.", false);
            handler.postDelayed(this::logoutUser, 600);
            return;
        }

        if (cmd.contains("home")) {
            speak("Opening home.", false);
            handler.postDelayed(this::openHome, 500);
            return;
        }

        if (cmd.contains("material")) {
            speak("Opening materials.", false);
            handler.postDelayed(this::openMaterials, 500);
            return;
        }

        if (cmd.contains("profile")) {
            speak("You are already on the profile screen.", true);
            return;
        }

        if (cmd.contains("retake") || cmd.contains("assessment")
                || cmd.contains("test again") || cmd.contains("take again")) {
            speak("Opening retake assessment.", false);
            handler.postDelayed(this::openRetakeAssessment, 500);
            return;
        }

        speak("Command not recognized. Say help for available commands.", true);
    }

    private void readProfileAloud() {
        String name       = txtStudentName    != null ? txtStudentName.getText().toString()     : "Unknown";
        String email      = txtEmail          != null ? txtEmail.getText().toString()            : "Unknown";
        String schoolNum  = txtStudentNumber  != null ? txtStudentNumber.getText().toString()    : "Unknown";
        String textSize   = txtCourse         != null ? txtCourse.getText().toString()           : "Unknown";
        String impairment = txtImpairmentLevel!= null ? txtImpairmentLevel.getText().toString()  : "Unknown";

        String message = "Your profile details. "
                + "Name: " + name + ". "
                + email + ". "
                + schoolNum + ". "
                + textSize + ". "
                + "Visual impairment level: " + impairment + ".";

        speak(message, true);
    }

    private String normalize(String text) {
        if (text == null) return "";
        String n = text.toLowerCase(Locale.US).trim()
                .replaceAll("[^a-z\\s]", " ")
                .replaceAll("\\s+", " ").trim();
        n = n.replace("go to home",        "home");
        n = n.replace("open home",         "home");
        n = n.replace("go to materials",   "materials");
        n = n.replace("open materials",    "materials");
        n = n.replace("go to profile",     "profile");
        n = n.replace("open profile",      "profile");
        n = n.replace("retake test",       "retake assessment");
        n = n.replace("take the test again","retake assessment");
        n = n.replace("test again",        "retake assessment");
        n = n.replace("redo test",         "retake assessment");
        n = n.replace("log out",           "logout");
        n = n.replace("sign out",          "logout");
        n = n.replace("enable tts",        "turn on text to speech");
        n = n.replace("disable tts",       "turn off text to speech");
        n = n.replace("enable stt",        "turn on speech to text");
        n = n.replace("disable stt",       "turn off speech to text");
        return n;
    }

    private boolean isQuickCommand(String normalized) {
        return normalized.equals("home")
                || normalized.equals("materials")
                || normalized.equals("profile")
                || normalized.equals("logout")
                || normalized.equals("help")
                || normalized.contains("log out")
                || normalized.contains("sign out");
    }

    private boolean isDuplicate(String normalized) {
        long now = System.currentTimeMillis();
        return normalized.equals(lastHandledCommand)
                && (now - lastHandledCommandTime) < COMMAND_COOLDOWN;
    }

    private void speak(String text, boolean listenAfter) {
        lastSpokenInstruction = text;
        if (!isTtsEnabled) {
            if (listenAfter && isSttEnabled) scheduleListening(LISTEN_DELAY_AFTER_TTS);
            return;
        }
        isTtsSpeaking = true;
        stopListeningSafely();
        googleTts.speak(text, () -> {
            isTtsSpeaking = false;
            if (listenAfter && isSttEnabled) scheduleListening(LISTEN_DELAY_AFTER_TTS);
        });
    }

    private void startVoiceRecognition() {
        if (!isSttEnabled || isTtsSpeaking || isListening) return;
        if (SpeechEngineHealth.isBuiltInRecognizerBroken(this)) {
            cascadeFromBuiltIn();
            return;
        }
        startRawAndroidListening();
    }

    private void cascadeFromBuiltIn() {
        if (!isSttEnabled || isTtsSpeaking) return;
        if (NetworkUtils.hasInternet(this)) {
            startCloudSttListening();
        } else if (hybridSpeech != null && hybridSpeech.isReady()) {
            startVoskListening();
        } else {
            scheduleListening(LISTEN_DELAY_NORMAL);
        }
    }

    private void startCloudSttListening() {
        if (!isSttEnabled || isTtsSpeaking || isListening) return;
        commandHandled = false;
        isListening    = true;
        updateVoiceStatus("Listening...");
        updateRecognizedText("Waiting for speech...");

        final boolean[] stopTriggered = {false};
        Runnable stopAndTranscribe = () -> {
            if (stopTriggered[0] || !isListening) return;
            stopTriggered[0] = true;
            isListening = false;
            updateVoiceStatus("Processing...");
            googleStt.stopAndRecognize("command", new GoogleSttManager.SttCallback() {
                @Override public void onResult(String transcript) {
                    if (commandHandled) return;
                    if (transcript != null && !transcript.trim().isEmpty()) {
                        processCommand(transcript.trim());
                    } else {
                        cascadeAfterCloudStt();
                    }
                }

                @Override public void onError(String message) {
                    Log.e("Profile_STT", "Cloud STT failed (" + message + "), falling back to Vosk.");
                    cascadeAfterCloudStt();
                }
            });
        };

        googleStt.startRecording(stopAndTranscribe::run);
        handler.postDelayed(stopAndTranscribe, CLOUD_STT_SAFETY_TIMEOUT_MS);
    }

    private void cascadeAfterCloudStt() {
        if (hybridSpeech != null && hybridSpeech.isReady()) {
            startVoskListening();
        } else if (isSttEnabled && !isTtsSpeaking) {
            scheduleListening(LISTEN_DELAY_NORMAL);
        }
    }

    private void startVoskListening() {
        if (!isSttEnabled || isTtsSpeaking || isListening) return;
        commandHandled = false;
        isListening    = true;
        updateVoiceStatus("Listening...");
        updateRecognizedText("Waiting for speech...");

        boolean useWhisper = NetworkUtils.hasInternet(this);
        hybridSpeech.startListening(new HybridSpeechManager.HybridSpeechCallback() {
            @Override public void onListeningStarted() {  }

            @Override public void onPartialResult(String partial) {
                if (commandHandled) return;
                String normalized = normalize(partial);
                updateRecognizedText("Hearing: " + partial);
                if (isQuickCommand(normalized)) {
                    isListening = false;
                    hybridSpeech.cancel();
                    processCommand(partial.trim());
                }
            }

            @Override public void onFinalResult(String transcript) {
                isListening = false;
                if (commandHandled) return;
                if (transcript != null && !transcript.trim().isEmpty()) {
                    processCommand(transcript.trim());
                } else {
                    updateVoiceStatus("No speech detected.");
                    if (isSttEnabled && !isTtsSpeaking) scheduleListening(LISTEN_DELAY_AFTER_TTS);
                }
            }

            @Override public void onError(String message) {
                isListening    = false;
                commandHandled = false;
                Log.e("Profile_STT", "Vosk failed (" + message + ") — all engines exhausted, retrying from the top.");
                if (isSttEnabled && !isTtsSpeaking) scheduleListening(LISTEN_DELAY_NORMAL);
            }
        }, useWhisper, null);

        handler.postDelayed(() -> {
            if (isListening) hybridSpeech.stopAndTranscribe();
        }, VOSK_LISTEN_TIMEOUT_MS);
    }

    private void startRawAndroidListening() {
        if (!isSttEnabled || isTtsSpeaking || isListening) return;
        if (speechRecognizer == null) {
            Log.e("Profile_STT", "Built-in recognizer unavailable on this device — using Cloud STT.");
            SpeechEngineHealth.markBuiltInRecognizerBroken(this);
            cascadeFromBuiltIn();
            return;
        }
        try {
            commandHandled = false;
            speechRecognizer.cancel();
            updateVoiceStatus("Listening...");
            updateRecognizedText("Waiting for speech...");
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            isListening = false;
            cascadeFromBuiltIn();
        }
    }

    private void stopListeningSafely() {
        isListening = false;
        if (googleStt != null) googleStt.cancel();
        if (hybridSpeech != null) hybridSpeech.cancel();
        try { if (speechRecognizer != null) speechRecognizer.stopListening(); } catch (Exception ignored) {}
        try { if (speechRecognizer != null) speechRecognizer.cancel(); }        catch (Exception ignored) {}
    }

    private void scheduleListening(long delay) {
        handler.removeCallbacks(delayedStartListening);
        if (isSttEnabled && !isTtsSpeaking) handler.postDelayed(delayedStartListening, delay);
    }

    private void setupSwitches() {
        switchTts.setOnCheckedChangeListener((btn, isChecked) -> {
            isTtsEnabled = isChecked;
            prefs.edit().putBoolean(KEY_TTS_ENABLED, isChecked).apply();
            pulseView(optionTts);
            if (isChecked) {
                Toast.makeText(this, "Text-to-Speech enabled", Toast.LENGTH_SHORT).show();
                speak("Text to speech enabled.", true);
            } else {
                Toast.makeText(this, "Text-to-Speech disabled", Toast.LENGTH_SHORT).show();
                if (googleTts != null) googleTts.stopSpeaking();
                isTtsSpeaking = false;
                if (isSttEnabled) scheduleListening(LISTEN_DELAY_AFTER_TTS);
            }
        });

        switchStt.setOnCheckedChangeListener((btn, isChecked) -> {
            isSttEnabled = isChecked;
            prefs.edit().putBoolean(KEY_STT_ENABLED, isChecked).apply();
            pulseView(optionStt);
            if (isChecked) {
                updateVoiceStatus("Speech-to-Text enabled.");
                updateRecognizedText("Waiting for speech...");
                Toast.makeText(this, "Speech-to-Text enabled", Toast.LENGTH_SHORT).show();
                speak("Speech to text enabled.", true);
            } else {
                updateVoiceStatus("Speech-to-Text is OFF.");
                updateRecognizedText("Speech-to-Text is disabled.");
                Toast.makeText(this, "Speech-to-Text disabled", Toast.LENGTH_SHORT).show();
                handler.removeCallbacks(delayedStartListening);
                stopListeningSafely();
                speak("Speech to text disabled.", false);
            }
        });

        optionTts.setOnClickListener(v -> { bounceView(optionTts); switchTts.toggle(); });
        optionStt.setOnClickListener(v -> { bounceView(optionStt); switchStt.toggle(); });
        if (optionHelp != null) optionHelp.setOnClickListener(v -> { bounceView(optionHelp); openHelp(); });
    }

    private void openHelp() {
        startActivity(new Intent(this, HelpActivity.class));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void setupMenuButton() {
        if (btnMenu != null) btnMenu.setOnClickListener(v -> { bounceView(btnMenu); materialsDrawer.open(); });
    }

    private void setupClickActions() {
        btnEditProfile.setOnClickListener(v -> { bounceView(btnEditProfile); showImagePickerDialog(); });
        imgProfile.setOnClickListener(v    -> { bounceView(imgProfile);      showImagePickerDialog(); });

        if (btnRetakeAssessment != null)
            btnRetakeAssessment.setOnClickListener(v -> { bounceView(btnRetakeAssessment); openRetakeAssessment(); });

        if (btnLogout != null)
            btnLogout.setOnClickListener(v -> {
                bounceView(btnLogout);
                speak("Logging out.", false);
                handler.postDelayed(this::logoutUser, 400);
            });

        navHome.setOnClickListener(v -> {
            animateTabPress(navHome);
            speak("Opening home.", false);
            handler.postDelayed(this::openHome, 300);
        });

        navMaterials.setOnClickListener(v -> {
            animateTabPress(navMaterials);
            speak("Opening materials.", false);
            handler.postDelayed(this::openMaterials, 300);
        });

        navProfile.setOnClickListener(v -> {
            animateTabPress(navProfile);
            setActiveNav("profile");
            speak("You are currently on the profile screen.", true);
        });
    }

    private void openHome() {
        startActivity(new Intent(this, HomeActivity.class));
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        finish();
    }

    private void openMaterials() {
        startActivity(new Intent(this, MaterialsActivity.class));
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        finish();
    }

    private void openRetakeAssessment() {
        updateVoiceStatus("Opening retake assessment...");
        stopListeningSafely();
        handler.removeCallbacks(delayedStartListening);
        if (googleTts != null) googleTts.stopSpeaking();
        Intent intent = new Intent(this, TextSizeTestActivity.class);
        intent.putExtra("isRetakeAssessment", true);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    private void logoutUser() {
        handler.removeCallbacksAndMessages(null);
        stopListeningSafely();
        isSttEnabled  = false;
        isTtsSpeaking = false;
        if (googleTts != null) googleTts.stopSpeaking();

        if (authManager != null) authManager.logout();

        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void ensureDefaultVoiceOptions() {
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        if (!prefs.contains(KEY_TTS_ENABLED)) { editor.putBoolean(KEY_TTS_ENABLED, true); changed = true; }
        if (!prefs.contains(KEY_STT_ENABLED)) { editor.putBoolean(KEY_STT_ENABLED, true); changed = true; }
        if (changed) editor.apply();
    }

    private void loadSavedOptions() {
        isTtsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, true);
        isSttEnabled = prefs.getBoolean(KEY_STT_ENABLED, true);
        if (switchTts != null) switchTts.setChecked(isTtsEnabled);
        if (switchStt != null) switchStt.setChecked(isSttEnabled);
    }

    private void initializeVoiceStatus() {
        if (txtVoiceHint != null)
            txtVoiceHint.setText("Voice commands: Help, Read profile, Home, Materials, " +
                    "Retake assessment, Log out, Turn on/off text to speech, Turn on/off speech to text.");
        updateVoiceStatus(isSttEnabled ? "Initializing..." : "Speech-to-Text is OFF.");
        updateRecognizedText(isSttEnabled ? "Waiting for speech..." : "Speech-to-Text is disabled.");
    }

    private void loadProfileData() {
        String fullName   = authManager.getFullName();
        String email      = authManager.getEmail();
        String schoolId   = authManager.getSchoolId();
        String impairment = prefs.getString(KEY_IMPAIRMENT_LEVEL,      "Not Available");
        String textSize   = prefs.getString(KEY_RECOMMENDED_TEXT_SIZE,  "Not Available");

        if (fullName == null || fullName.trim().isEmpty()) fullName = "Student Name";
        else fullName = toProperCase(fullName);
        if (email    == null || email.trim().isEmpty())    email    = "No Email";
        if (schoolId == null || schoolId.trim().isEmpty()) schoolId = "No Student Number";

        if (txtStudentName   != null) txtStudentName.setText(fullName);
        if (txtEmail         != null) txtEmail.setText("Email: " + email);
        if (txtStudentNumber != null) txtStudentNumber.setText("Student Number: " + schoolId);
        if (txtCourse        != null) txtCourse.setText("Recommended Text Size: " + textSize);
        if (txtImpairmentLevel!= null) txtImpairmentLevel.setText(formatImpairmentLevel(impairment));
        applyFontSize();
    }

    private void fetchStudentProfileFromServer() {
        String studentId = authManager.getStudentId();
        String email     = authManager.getEmail();
        String url;

        if (studentId != null && !studentId.trim().isEmpty()) {
            url = ApiConfig.STUDENTS + "?id=eq." + Uri.encode(studentId.trim())
                    + "&select=id,first_name,middle_name,last_name,school_id,email,"
                    + "impairment_level,recommended_text_size&limit=1";
        } else if (email != null && !email.trim().isEmpty()) {
            url = ApiConfig.STUDENTS + "?email=eq."
                    + Uri.encode(email.trim().toLowerCase(Locale.US))
                    + "&select=id,first_name,middle_name,last_name,school_id,email,"
                    + "impairment_level,recommended_text_size&limit=1";
        } else {
            Toast.makeText(this, "No saved student account.", Toast.LENGTH_LONG).show();
            return;
        }

        JsonArrayRequest req = new JsonArrayRequest(Request.Method.GET, url, null,
                response -> {
                    try {
                        if (response == null || response.length() == 0) return;
                        JSONObject s = response.getJSONObject(0);
                        currentStudentId = s.optString("id", "");

                        String fn   = s.optString("first_name",  "");
                        String mn   = s.optString("middle_name", "");
                        String ln   = s.optString("last_name",   "");
                        String sid  = s.optString("school_id",   "");
                        String em   = s.optString("email",       "");
                        String imp  = s.optString("impairment_level", "Not Available");
                        int    ts   = s.optInt("recommended_text_size", 0);
                        String tsStr= ts > 0 ? ts + "sp" : "Not Available";
                        String name = formatProfessionalName(fn, mn, ln);

                        if (txtStudentName   != null) txtStudentName.setText(name);
                        if (txtEmail         != null) txtEmail.setText("Email: " + em);
                        if (txtStudentNumber != null) txtStudentNumber.setText("Student Number: " + sid);
                        if (txtCourse        != null) txtCourse.setText("Recommended Text Size: " + tsStr);
                        if (txtImpairmentLevel!=null) txtImpairmentLevel.setText(formatImpairmentLevel(imp));

                        prefs.edit()
                                .putString(KEY_IMPAIRMENT_LEVEL,      imp)
                                .putString(KEY_RECOMMENDED_TEXT_SIZE, tsStr)
                                .apply();

                        if (imgProfile != null) imgProfile.setImageResource(R.drawable.ic_default_profile);
                        applyFontSize();
                        updateVoiceStatus("Profile loaded.");
                    } catch (Exception e) {
                        Toast.makeText(this, "Profile error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                },
                error -> Toast.makeText(this, "Failed to load profile.", Toast.LENGTH_LONG).show()
        ) {
            @Override public Map<String, String> getHeaders() {
                Map<String, String> h = new HashMap<>();
                h.put("apikey",        ApiConfig.SUPABASE_KEY);
                h.put("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY);
                h.put("Content-Type",  "application/json");
                h.put("Accept",        "application/json");
                return h;
            }
        };
        req.setRetryPolicy(new DefaultRetryPolicy(15000, 1, 1.0f));
        Volley.newRequestQueue(this).add(req);
    }

    private void showImagePickerDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Select Profile Picture")
                .setItems(new String[]{"Take Photo", "Choose from Gallery"}, (d, which) -> {
                    if (which == 0) openCamera(); else openGallery();
                }).show();
    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) cameraLauncher.launch(intent);
        else Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show();
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        galleryLauncher.launch(intent);
    }

    private void uploadImageFromUri(Uri imageUri) {
        if (currentStudentId.isEmpty()) { Toast.makeText(this, "Student ID not loaded.", Toast.LENGTH_SHORT).show(); return; }
        try { uploadBitmap(MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri)); }
        catch (Exception e) { Toast.makeText(this, "Image read failed.", Toast.LENGTH_SHORT).show(); }
    }

    private void uploadImageFromBitmap(Bitmap bitmap) {
        if (currentStudentId.isEmpty()) { Toast.makeText(this, "Student ID not loaded.", Toast.LENGTH_SHORT).show(); return; }
        uploadBitmap(bitmap);
    }

    private void uploadBitmap(Bitmap bitmap) {
        if (bitmap == null) { Toast.makeText(this, "Invalid image.", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "Uploading profile image...", Toast.LENGTH_SHORT).show();

        VolleyMultipartRequest req = new VolleyMultipartRequest(
                Request.Method.POST, UPLOAD_PROFILE_URL,
                response -> {
                    try {
                        JSONObject obj     = new JSONObject(new String(response.data));
                        boolean    success = obj.getBoolean("success");
                        String     message = obj.getString("message");
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                        if (success) loadProfileImage(obj.optString("image_path", ""));
                    } catch (Exception e) { Toast.makeText(this, "Upload parse error.", Toast.LENGTH_SHORT).show(); }
                },
                error -> Toast.makeText(this, "Upload failed.", Toast.LENGTH_LONG).show()
        ) {
            @Override protected Map<String, String>   getParams()   { Map<String, String> p = new HashMap<>(); p.put("student_id", currentStudentId); return p; }
            @Override protected Map<String, DataPart> getByteData() {
                Map<String, DataPart> p = new HashMap<>();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                p.put("profile_image", new DataPart(System.currentTimeMillis() + ".jpg", bos.toByteArray(), "image/jpeg"));
                return p;
            }
        };
        req.setRetryPolicy(new DefaultRetryPolicy(20000, 1, 1.0f));
        Volley.newRequestQueue(this).add(req);
    }

    private void loadProfileImage(String imagePath) {
        if (imagePath == null || imagePath.trim().isEmpty()) { imgProfile.setImageResource(R.drawable.ic_default_profile); return; }
        String url = imagePath.startsWith("http") ? imagePath
                : imagePath.startsWith("profiles/") ? ApiConfig.SUPABASE_URL + "/storage/v1/object/public/" + imagePath
                : BASE_URL + imagePath;
        Glide.with(this).load(url)
                .placeholder(R.drawable.ic_default_profile)
                .error(R.drawable.ic_default_profile)
                .into(imgProfile);
    }

    private void applyFontSize() {
        float b = FontSizeManager.getFontSize(this);

        if (txtStudentInfoLabel != null) txtStudentInfoLabel.setTextSize(b);
        if (txtStudentName      != null) txtStudentName.setTextSize(b + 4);
        if (txtCourse           != null) txtCourse.setTextSize(16f);
        if (txtEmail            != null) txtEmail.setTextSize(16f);
        if (txtStudentNumber    != null) txtStudentNumber.setTextSize(16f);
        if (txtImpairmentLevel  != null) txtImpairmentLevel.setTextSize(b + 8);
        if (btnRetakeAssessment != null) btnRetakeAssessment.setTextSize(16f);
        if (btnLogout           != null) btnLogout.setTextSize(b - 2);
        if (txtVoiceStatus      != null) txtVoiceStatus.setTextSize(b - 2);
        if (txtRecognizedText   != null) txtRecognizedText.setTextSize(b - 2);
        if (txtVoiceHint        != null) txtVoiceHint.setTextSize(16f);
        if (textHome            != null) textHome.setTextSize(b - 4);
        if (textMaterials       != null) textMaterials.setTextSize(b - 4);
        if (textProfile         != null) textProfile.setTextSize(b - 4);
    }

    private void setActiveNav(String tab) {
        int inactive = 0xFF7A2F42, active = 0xFFFFFFFF;
        navHome.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        navMaterials.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        navProfile.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        iconHome.setColorFilter(inactive); iconMaterials.setColorFilter(inactive); iconProfile.setColorFilter(inactive);
        textHome.setTextColor(inactive);   textMaterials.setTextColor(inactive);   textProfile.setTextColor(inactive);
        if ("home".equals(tab))      { navHome.setBackgroundResource(R.drawable.bg_nav_active);      iconHome.setColorFilter(active);      textHome.setTextColor(active); }
        else if ("materials".equals(tab)) { navMaterials.setBackgroundResource(R.drawable.bg_nav_active); iconMaterials.setColorFilter(active); textMaterials.setTextColor(active); }
        else if ("profile".equals(tab))   { navProfile.setBackgroundResource(R.drawable.bg_nav_active);  iconProfile.setColorFilter(active);   textProfile.setTextColor(active); }
    }

    private void updateVoiceStatus(String s)   { runOnUiThread(() -> { if (txtVoiceStatus    != null) txtVoiceStatus.setText("Voice: " + s); }); }
    private void updateRecognizedText(String s) { runOnUiThread(() -> { if (txtRecognizedText != null) txtRecognizedText.setText(s); }); }

    private String toProperCase(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] words = text.trim().toLowerCase(Locale.US).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) sb.append(w.substring(1));
        }
        return sb.toString();
    }

    private String formatProfessionalName(String fn, String mn, String ln) {
        String first = fn == null ? "" : fn.trim();
        String mid   = mn == null ? "" : mn.trim();
        String last  = ln == null ? "" : ln.trim();
        String mi    = mid.isEmpty() ? "" : mid.substring(0, 1).toUpperCase(Locale.US) + ".";
        StringBuilder sb = new StringBuilder();
        if (!last.isEmpty())  sb.append(toProperCase(last));
        if (!first.isEmpty()) { if (sb.length() > 0) sb.append(", "); sb.append(toProperCase(first)); }
        if (!mi.isEmpty())    sb.append(" ").append(mi);
        return sb.toString().isEmpty() ? "Student Name" : sb.toString().trim();
    }

    private String formatImpairmentLevel(String level) {
        if (level == null || level.trim().isEmpty()) return "NOT AVAILABLE";
        String l = level.toLowerCase(Locale.US);
        if (l.contains("low"))      return "LOW";
        if (l.contains("moderate")) return "MODERATE";
        if (l.contains("high"))     return "HIGH";
        return level.toUpperCase(Locale.US);
    }

    private void setupPressAnimations() {
        for (View v : new View[]{btnMenu, optionTts, optionStt, optionHelp, cardProfileInfo, cardImpairmentLevel,
                cardVoiceStatus, cardOptions, navHome, navMaterials, navProfile,
                btnRetakeAssessment, btnLogout, imgProfile, btnEditProfile}) {
            if (v == null) continue;
            v.setOnTouchListener((view, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:   view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start(); break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: view.animate().scaleX(1f).scaleY(1f).setDuration(80).start();       break;
                }
                return false;
            });
        }
    }

    private void bounceView(View v)     { if (v == null) return; v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(90).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start(); }
    private void pulseView(View v)      { if (v == null) return; v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start(); }
    private void animateTabPress(View v){ if (v == null) return; v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(85).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(85).start()).start(); }

    private void animateProfileEntrance() {
        if (topBarProfile != null) { topBarProfile.setAlpha(0f); topBarProfile.setTranslationY(-35f); topBarProfile.animate().alpha(1f).translationY(0f).setDuration(350).start(); }
        if (txtStudentInfoLabel != null) { txtStudentInfoLabel.setAlpha(0f); txtStudentInfoLabel.setTranslationY(20f); txtStudentInfoLabel.animate().alpha(1f).translationY(0f).setStartDelay(80).setDuration(250).start(); }
        animateCard(cardProfileInfo,     120, -40f);
        animateCard(cardImpairmentLevel, 220,  40f);
        animateCard(cardVoiceStatus,     320, -40f);
        animateCard(cardOptions,         420,  40f);
        if (btnLogout     != null) { btnLogout.setAlpha(0f);     btnLogout.setTranslationY(30f);     btnLogout.animate().alpha(1f).translationY(0f).setStartDelay(500).setDuration(280).setInterpolator(new AccelerateDecelerateInterpolator()).start(); }

        if (imgProfile    != null) { imgProfile.setScaleX(0.7f); imgProfile.setScaleY(0.7f); imgProfile.setAlpha(0f); imgProfile.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(250).setDuration(350).start(); }
    }

    private void animateCard(View v, long delay, float fromX) {
        if (v == null) return;
        v.setAlpha(0f); v.setTranslationX(fromX);
        v.animate().alpha(1f).translationX(0f).setStartDelay(delay).setDuration(320).setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    private void setupGestures() {
        View root = findViewById(android.R.id.content);
        if (root == null) return;

        View child = (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 0)
                ? ((ViewGroup) root).getChildAt(0) : root;

        if (child instanceof DrawerLayout && ((ViewGroup) child).getChildCount() > 0) {
            child = ((ViewGroup) child).getChildAt(0);
        }
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
        if (lastSpokenInstruction == null || lastSpokenInstruction.trim().isEmpty()) return;
        vibrateShort();
        if (googleTts != null) googleTts.speak(lastSpokenInstruction, null);
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

    @Override protected void onResume() {
        super.onResume();
        setActiveNav("profile");
        loadSavedOptions();
        applyFontSize();
        loadProfileData();
        fetchStudentProfileFromServer();
        if (isSttEnabled && !isTtsSpeaking) scheduleListening(LISTEN_DELAY_NORMAL);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(delayedStartListening);
        stopListeningSafely();
        commandHandled = false;
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopListeningSafely();
        try { if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); } } catch (Exception ignored) {}
        if (hybridSpeech != null) hybridSpeech.destroy();
        if (googleStt != null) googleStt.destroy();
        if (googleTts != null) googleTts.destroy();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == CAMERA_PERMISSION_CODE && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openCamera();
        else Toast.makeText(this, "Camera permission denied.", Toast.LENGTH_SHORT).show();
    }
}
