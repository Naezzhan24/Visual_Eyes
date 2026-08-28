package com.example.visualeyes;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.toolbox.JsonArrayRequest;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
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
    private static final String KEY_YEAR_LEVEL           = "yearLevel";
    private static final long   LISTEN_DELAY_NORMAL      = 500L;
    private static final long   LISTEN_DELAY_AFTER_TTS   = 400L;
    private static final long   COMMAND_COOLDOWN         = 900L;

    private ImageView iconHome, iconMaterials, iconProfile;
    private TextView txtStudentName, txtCourse, txtEmail, txtStudentNumber, txtAge, txtYearLevel, txtImpairmentLevel;
    private TextView txtVoiceStatus, txtRecognizedText, txtVoiceHint;
    private TextView textHome, textMaterials, textProfile, txtStudentInfoLabel;
    private LinearLayout optionTts, optionStt, optionHelp, optionPrivacyPolicy, navHome, navMaterials, navProfile;
    private TextView txtAppVersion;
    private SwitchCompat switchTts, switchStt;
    private Button btnRetakeAssessment, btnLogout;
    private CardView cardProfileInfo, cardImpairmentLevel, cardVoiceStatus, cardOptions;
    private SwipeRefreshLayout swipeRefreshProfile;
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

    private HybridSpeechManager hybridSpeech;
    private SttCascadeSession   cascadeSession;

    private AuthManager authManager;
    private SharedPreferences prefs;

    private boolean isTtsEnabled      = true;
    private boolean isSttEnabled      = true;
    private boolean isListening       = false;
    private boolean isTtsSpeaking     = false;
    private boolean commandHandled    = false;
    private String  lastHandledCommand    = "";
    private long    lastHandledCommandTime= 0L;
    private String  lastSpokenInstruction  = "";

    private int voiceSessionId = 0;

    private boolean micPermissionRequestInFlight = false;
    private boolean lastKnownMicPermission       = false;
    private boolean isNavPending = false;

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

    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                micPermissionRequestInFlight = false;
                lastKnownMicPermission = granted;
                if (granted) {
                    updateVoiceStatus("Microphone enabled.");
                    if (isSttEnabled && !isTtsSpeaking) speak("Microphone permission granted.", true);
                } else {
                    updateVoiceStatus("Microphone permission denied.");
                }
            });

    private void checkMicPermission() {
        if (MicPermissionHelper.hasAudioPermission(this)) return;
        if (MicPermissionHelper.isPermanentlyDenied(this)) {
            explainPermanentDenialAndOpenSettings();
            return;
        }
        if (MicPermissionHelper.isScreenReaderActive(this)) {
            updateVoiceStatus("Microphone permission needed for voice commands.");
            return;
        }
        requestMicPermissionWithRationale();
    }

    private void requestMicPermissionWithRationale() {
        updateVoiceStatus("Requesting microphone access...");
        isTtsSpeaking = true;
        stopListeningSafely();
        googleTts.speak("I need access to your microphone for voice commands. " +
                "A system permission dialog will appear next — please allow it.", () -> {
            isTtsSpeaking = false;
            MicPermissionHelper.markRequested(this);
            micPermissionRequestInFlight = true;
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        });
    }

    private void explainPermanentDenialAndOpenSettings() {
        updateVoiceStatus("Microphone permission blocked.");
        isTtsSpeaking = true;
        stopListeningSafely();
        googleTts.speak("Microphone access was previously denied and can't be requested again here. " +
                "Opening app settings so you can enable it under Permissions.", () -> {
            isTtsSpeaking = false;
            MicPermissionHelper.openAppSettings(this);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        authManager = new AuthManager(this);
        prefs       = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        googleTts = new GoogleTtsManager(this);
        googleStt = new GoogleSttManager(this);
        lastKnownMicPermission = MicPermissionHelper.hasAudioPermission(this);
        hybridSpeech = new HybridSpeechManager(this);
        hybridSpeech.initVosk(
                () -> Log.d("Profile_STT", "Vosk model ready — now the primary listen engine."),
                () -> Log.e("Profile_STT", "Vosk model failed to load — using raw SpeechRecognizer only."));

        cascadeSession = new SttCascadeSession(googleStt, hybridSpeech, handler, false);

        ensureDefaultVoiceOptions();
        bindViews();
        loadSavedOptions();
        applyFontSize();
        loadProfileData();
        showAppVersion();
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
                btnMenu, btnCloseDrawer, this::stopListeningSafely, text -> speak(text, false));
        materialsDrawer.load(authManager.getSessionToken());
        animateProfileEntrance();
        fetchStudentProfileFromServer();

        if (swipeRefreshProfile != null) {
            swipeRefreshProfile.setColorSchemeColors(0xFF8C4356);
            swipeRefreshProfile.setOnRefreshListener(() -> {
                loadProfileData();
                fetchStudentProfileFromServer();
                materialsDrawer.load(authManager.getSessionToken());
            });
        }

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
        txtStudentName     = findViewById(R.id.txtStudentName);
        txtCourse          = findViewById(R.id.txtCourse);
        txtEmail           = findViewById(R.id.txtEmail);
        txtStudentNumber   = findViewById(R.id.txtStudentNumber);
        txtAge             = findViewById(R.id.txtAge);
        txtYearLevel       = findViewById(R.id.txtYearLevel);
        txtImpairmentLevel = findViewById(R.id.txtImpairmentLevel);
        txtVoiceStatus     = findViewById(R.id.txtVoiceStatus);
        txtRecognizedText  = findViewById(R.id.txtRecognizedText);
        txtVoiceHint       = findViewById(R.id.txtVoiceHint);
        optionTts          = findViewById(R.id.optionTts);
        optionStt          = findViewById(R.id.optionStt);
        optionHelp         = findViewById(R.id.optionHelp);
        optionPrivacyPolicy = findViewById(R.id.optionPrivacyPolicy);
        txtAppVersion      = findViewById(R.id.txtAppVersion);
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
        swipeRefreshProfile= findViewById(R.id.swipeRefreshProfile);
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
            boolean turnOn  = cmd.contains("turn on")  || cmd.contains("enable");
            boolean turnOff = cmd.contains("turn off") || cmd.contains("disable");
            if (turnOn) {
                if (!switchTts.isChecked()) { switchTts.setChecked(true); }
                else speak("Text to speech is already enabled.", true);
            } else if (turnOff) {
                if (switchTts.isChecked()) { switchTts.setChecked(false); }
                else scheduleListening(LISTEN_DELAY_AFTER_TTS);
            } else {
                speak("Say turn on or turn off text to speech.", true);
            }
            return;
        }

        if (cmd.contains("speech to text")) {
            boolean turnOn  = cmd.contains("turn on")  || cmd.contains("enable");
            boolean turnOff = cmd.contains("turn off") || cmd.contains("disable");
            if (turnOn) {
                if (!switchStt.isChecked()) { switchStt.setChecked(true); }
                else speak("Speech to text is already enabled.", true);
            } else if (turnOff) {
                if (switchStt.isChecked()) { switchStt.setChecked(false); }
            } else {
                speak("Say turn on or turn off speech to text.", true);
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
        String age        = txtAge            != null ? txtAge.getText().toString()              : "Unknown";
        String yearLevel  = txtYearLevel       != null ? txtYearLevel.getText().toString()        : "Unknown";
        String textSize   = txtCourse         != null ? txtCourse.getText().toString()           : "Unknown";
        String impairment = txtImpairmentLevel!= null ? txtImpairmentLevel.getText().toString()  : "Unknown";

        String message = "Your profile details. "
                + "Name: " + name + ". "
                + email + ". "
                + schoolNum + ". "
                + age + ". "
                + yearLevel + ". "
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
        if (!MicPermissionHelper.hasAudioPermission(this)) {
            checkMicPermission();
            return;
        }
        if (SpeechEngineHealth.isBuiltInRecognizerBroken(this)) {
            cascadeFromBuiltIn();
            return;
        }
        startRawAndroidListening();
    }

    private void cascadeFromBuiltIn() {
        if (!isSttEnabled || isTtsSpeaking || isListening) return;
        commandHandled = false;
        isListening    = true;
        final int mySession = voiceSessionId;

        cascadeSession.cascade(this, "command", null, new SttCascadeSession.Listener() {
            @Override public void onListeningStarted() {
                if (mySession != voiceSessionId) return;
                updateVoiceStatus("Listening...");
                updateRecognizedText("Waiting for speech...");
            }

            @Override public void onPartialResult(String partial) {
                if (mySession != voiceSessionId || commandHandled) return;
                String normalized = normalize(partial);
                updateRecognizedText("Hearing: " + partial);
                if (isQuickCommand(normalized)) {
                    isListening = false;
                    hybridSpeech.cancel();
                    processCommand(partial.trim());
                }
            }

            @Override public void onTranscript(String transcript) {
                if (mySession != voiceSessionId) return;
                isListening = false;
                if (commandHandled) return;
                processCommand(transcript);
            }

            @Override public void onExhausted() {
                if (mySession != voiceSessionId) return;
                isListening    = false;
                commandHandled = false;
                updateVoiceStatus("No speech detected.");
                if (isSttEnabled && !isTtsSpeaking) scheduleListening(LISTEN_DELAY_NORMAL);
            }
        });
    }

    private void startRawAndroidListening() {
        if (!isSttEnabled || isTtsSpeaking || isListening) return;
        if (speechRecognizer == null) {
            Log.e("Profile_STT", "Built-in recognizer unavailable on this device — using Cloud STT.");
            SpeechEngineHealth.markBuiltInRecognizerBroken(this);
            cascadeFromBuiltIn();
            return;
        }
        // Set synchronously here, not in onReadyForSpeech — that callback
        // fires asynchronously, leaving a window right after this call where
        // isListening is still false and a rapid second tap would bypass
        // the guard above and start a second recognizer on top of the first.
        isListening = true;
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
        voiceSessionId++;
        if (cascadeSession != null) cascadeSession.cancel();
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
        if (optionPrivacyPolicy != null) optionPrivacyPolicy.setOnClickListener(v -> { bounceView(optionPrivacyPolicy); openPrivacyPolicy(); });
    }

    private void openPrivacyPolicy() {
        startActivity(new Intent(this, PrivacyPolicyActivity.class));
    }

    private void showAppVersion() {
        if (txtAppVersion == null) return;
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            txtAppVersion.setText("VisualED v" + versionName);
        } catch (Exception ignored) {}
    }

    private void openHelp() {
        startActivity(new Intent(this, HelpActivity.class));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void setupMenuButton() {
        if (btnMenu != null) btnMenu.setOnClickListener(v -> { bounceView(btnMenu); materialsDrawer.open(); });
    }

    private void setupClickActions() {
        if (btnRetakeAssessment != null)
            btnRetakeAssessment.setOnClickListener(v -> { bounceView(btnRetakeAssessment); openRetakeAssessment(); });

        if (btnLogout != null)
            btnLogout.setOnClickListener(v -> {
                if (isNavPending) return;
                isNavPending = true;
                bounceView(btnLogout);
                speak("Logging out.", false);
                handler.postDelayed(this::logoutUser, 400);
            });

        navHome.setOnClickListener(v -> {
            if (isNavPending) return;
            isNavPending = true;
            speak("Opening home.", false);
            handler.postDelayed(this::openHome, 300);
        });

        navMaterials.setOnClickListener(v -> {
            if (isNavPending) return;
            isNavPending = true;
            speak("Opening materials.", false);
            handler.postDelayed(this::openMaterials, 300);
        });

        navProfile.setOnClickListener(v -> {
            setActiveNav("profile");
            speak("You are currently on the profile screen.", true);
        });
    }

    private void openHome() {
        startNavTransition(new Intent(this, HomeActivity.class), false);
    }

    private void openMaterials() {
        startNavTransition(new Intent(this, MaterialsActivity.class), false);
    }

    // Simple slide in/out — Profile is the rightmost tab, so both Home and
    // Materials are always a backward (leftward) move from here.
    private void startNavTransition(Intent intent, boolean forward) {
        startActivity(intent);
        overridePendingTransition(forward ? R.anim.slide_in_right : R.anim.slide_in_left,
                                   forward ? R.anim.slide_out_left : R.anim.slide_out_right);
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
        String age        = authManager.getAge();
        String impairment = prefs.getString(KEY_IMPAIRMENT_LEVEL,      "Not Available");
        String textSize   = prefs.getString(KEY_RECOMMENDED_TEXT_SIZE,  "Not Available");
        String yearLevel  = prefs.getString(KEY_YEAR_LEVEL,             "Not Available");

        if (fullName == null || fullName.trim().isEmpty()) fullName = "Student Name";
        else fullName = toProperCase(fullName);
        if (email    == null || email.trim().isEmpty())    email    = "No Email";
        if (schoolId == null || schoolId.trim().isEmpty()) schoolId = "No Student Number";
        if (age      == null || age.trim().isEmpty())      age      = "Not Available";

        if (txtStudentName   != null) txtStudentName.setText(fullName);
        if (txtEmail         != null) txtEmail.setText("Email: " + email);
        if (txtStudentNumber != null) txtStudentNumber.setText("Student Number: " + schoolId);
        if (txtAge           != null) txtAge.setText("Age: " + age);
        if (txtYearLevel     != null) txtYearLevel.setText("Year Level: " + yearLevel);
        if (txtCourse        != null) txtCourse.setText("Recommended Text Size: " + textSize);
        if (txtImpairmentLevel!= null) txtImpairmentLevel.setText(formatImpairmentLevel(impairment));
        applyFontSize();
    }

    private void fetchStudentProfileFromServer() {
        String sessionToken = authManager.getSessionToken();

        if (sessionToken == null || sessionToken.trim().isEmpty()) {
            Toast.makeText(this, "No saved student account.", Toast.LENGTH_LONG).show();
            if (swipeRefreshProfile != null) swipeRefreshProfile.setRefreshing(false);
            return;
        }

        // Dedicated, lightweight profile fetch — resolves the student from
        // their session token server-side instead of re-verifying a password.
        String url = ApiConfig.SUPABASE_URL + "/rest/v1/rpc/get_student_profile";

        JSONObject rpcBody = new JSONObject();
        String bodyStr;
        try {
            rpcBody.put("p_session_token", sessionToken);
            bodyStr = rpcBody.toString();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to prepare profile request.", Toast.LENGTH_SHORT).show();
            if (swipeRefreshProfile != null) swipeRefreshProfile.setRefreshing(false);
            return;
        }
        final String finalBodyStr = bodyStr;

        JsonArrayRequest req = new JsonArrayRequest(Request.Method.POST, url, null,
                response -> {
                    try {
                        if (response == null || response.length() == 0) return;
                        JSONObject s = response.getJSONObject(0);

                        String fn   = s.optString("first_name",  "");
                        String mn   = s.optString("middle_name", "");
                        String ln   = s.optString("last_name",   "");
                        String sid  = s.optString("school_id",   "");
                        String em   = s.optString("email",       "");
                        String age  = s.optString("age",         "");
                        String yr   = s.optString("year_level",  "");
                        String imp  = s.optString("impairment_level", "Not Available");
                        int    ts   = s.optInt("recommended_text_size", 0);
                        String tsStr= ts > 0 ? ts + "sp" : "Not Available";
                        String ageStr = age.trim().isEmpty() ? "Not Available" : age;
                        String yrStr  = yr.trim().isEmpty()  ? "Not Available" : yr;
                        String name = formatProfessionalName(fn, mn, ln);

                        if (txtStudentName   != null) txtStudentName.setText(name);
                        if (txtEmail         != null) txtEmail.setText("Email: " + em);
                        if (txtStudentNumber != null) txtStudentNumber.setText("Student Number: " + sid);
                        if (txtAge           != null) txtAge.setText("Age: " + ageStr);
                        if (txtYearLevel     != null) txtYearLevel.setText("Year Level: " + yrStr);
                        if (txtCourse        != null) txtCourse.setText("Recommended Text Size: " + tsStr);
                        if (txtImpairmentLevel!=null) txtImpairmentLevel.setText(formatImpairmentLevel(imp));

                        prefs.edit()
                                .putString(KEY_IMPAIRMENT_LEVEL,      imp)
                                .putString(KEY_RECOMMENDED_TEXT_SIZE, tsStr)
                                .putString(KEY_YEAR_LEVEL,            yrStr)
                                .apply();

                        applyFontSize();
                        updateVoiceStatus("Profile loaded.");
                    } catch (Exception e) {
                        Toast.makeText(this, "Profile error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    } finally {
                        if (swipeRefreshProfile != null) swipeRefreshProfile.setRefreshing(false);
                    }
                },
                error -> {
                    if (SessionManager.isSessionExpiredError(error)) {
                        SessionManager.forceLogoutAndRedirect(this);
                        return;
                    }
                    Toast.makeText(this, "Failed to load profile.", Toast.LENGTH_LONG).show();
                    if (swipeRefreshProfile != null) swipeRefreshProfile.setRefreshing(false);
                }
        ) {
            @Override
            public byte[] getBody() {
                return finalBodyStr.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String getBodyContentType() {
                return "application/json; charset=utf-8";
            }

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
        VolleySingleton.getInstance(this).getRequestQueue().add(req);
    }


    private void applyFontSize() {
        float b = FontSizeManager.getFontSize(this);

        if (txtStudentInfoLabel != null) txtStudentInfoLabel.setTextSize(b);
        if (txtStudentName      != null) txtStudentName.setTextSize(b + 4);
        if (txtCourse           != null) txtCourse.setTextSize(16f);
        if (txtEmail            != null) txtEmail.setTextSize(16f);
        if (txtStudentNumber    != null) txtStudentNumber.setTextSize(16f);
        if (txtAge              != null) txtAge.setTextSize(16f);
        if (txtYearLevel        != null) txtYearLevel.setTextSize(16f);
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
        int inactive = 0xFF8C4356, active = 0xFF2E0D18;
        iconHome.setColorFilter(inactive); iconMaterials.setColorFilter(inactive); iconProfile.setColorFilter(inactive);
        textHome.setTextColor(inactive);   textMaterials.setTextColor(inactive);   textProfile.setTextColor(inactive);
        if ("home".equals(tab))           { iconHome.setColorFilter(active);      textHome.setTextColor(active); }
        else if ("materials".equals(tab)) { iconMaterials.setColorFilter(active); textMaterials.setTextColor(active); }
        else if ("profile".equals(tab))   { iconProfile.setColorFilter(active);   textProfile.setTextColor(active); }
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
        for (View v : new View[]{btnMenu, optionTts, optionStt, optionHelp, optionPrivacyPolicy, cardProfileInfo, cardImpairmentLevel,
                cardVoiceStatus, cardOptions,
                btnRetakeAssessment, btnLogout}) {
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

    private void animateProfileEntrance() {
        if (topBarProfile != null) { topBarProfile.setAlpha(0f); topBarProfile.setTranslationY(-35f); topBarProfile.animate().alpha(1f).translationY(0f).setDuration(350).start(); }
        if (txtStudentInfoLabel != null) { txtStudentInfoLabel.setAlpha(0f); txtStudentInfoLabel.setTranslationY(20f); txtStudentInfoLabel.animate().alpha(1f).translationY(0f).setStartDelay(80).setDuration(250).start(); }
        animateCard(cardProfileInfo,     120, -40f);
        animateCard(cardImpairmentLevel, 220,  40f);
        animateCard(cardVoiceStatus,     320, -40f);
        animateCard(cardOptions,         420,  40f);
        if (btnLogout     != null) { btnLogout.setAlpha(0f);     btnLogout.setTranslationY(30f);     btnLogout.animate().alpha(1f).translationY(0f).setStartDelay(500).setDuration(280).setInterpolator(new AccelerateDecelerateInterpolator()).start(); }
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
        isNavPending = false;
        setActiveNav("profile");
        loadSavedOptions();
        applyFontSize();
        loadProfileData();
        fetchStudentProfileFromServer();

        boolean nowGranted = MicPermissionHelper.hasAudioPermission(this);
        if (nowGranted && !lastKnownMicPermission && !micPermissionRequestInFlight) {
            updateVoiceStatus("Microphone enabled.");
        }
        lastKnownMicPermission = nowGranted;

        if (isSttEnabled && !isTtsSpeaking) scheduleListening(LISTEN_DELAY_NORMAL);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(delayedStartListening);
        stopListeningSafely();
        // Navigating to another screen leaves this activity paused, not
        // destroyed — its TTS would otherwise keep talking in the background
        // and overlap with the next screen's voice.
        if (googleTts != null) googleTts.stopSpeaking();
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
}