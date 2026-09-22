package com.example.visualeyes;

import android.Manifest;
import android.content.Context;
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
import android.view.LayoutInflater;
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
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
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

public class ProfileFragment extends Fragment implements TabFragment {

    private static final String PREFS_NAME               = "VisualEyesPrefs";
    private static final String KEY_TTS_ENABLED          = "tts_enabled";
    private static final String KEY_STT_ENABLED          = "stt_enabled";
    private static final String KEY_IMPAIRMENT_LEVEL     = "impairmentLevel";
    private static final String KEY_RECOMMENDED_TEXT_SIZE= "recommendedTextSize";
    private static final String KEY_YEAR_LEVEL           = "yearLevel";
    private static final String KEY_SECTION              = "section";
    private static final long   LISTEN_DELAY_NORMAL      = 500L;
    private static final long   LISTEN_DELAY_AFTER_TTS   = 400L;
    private static final long   COMMAND_COOLDOWN         = 900L;
    // Standardized across every mic-using screen: give the OS 400ms to
    // actually tear down/recreate the recognizer, then wait another 600ms
    // before the first retry so it isn't immediately busy again.
    private static final long   MIC_BUSY_REINIT_DELAY_MS = 400L;
    private static final long   MIC_BUSY_RETRY_DELAY_MS  = 600L;

    private TextView txtStudentName, txtCourse, txtEmail, txtStudentNumber, txtAge, txtYearLevel, txtSection, txtImpairmentLevel;
    private TextView txtVoiceStatus, txtRecognizedText, txtVoiceHint;
    private TextView txtStudentInfoLabel;
    private LinearLayout optionTts, optionStt, optionHelp, optionPrivacyPolicy, optionVoice;
    private TextView txtOptionVoice;
    private TextView txtAppVersion;
    private SwitchCompat switchTts, switchStt;
    private Button btnRetakeAssessment, btnLogout;
    private CardView cardProfileInfo, cardImpairmentLevel, cardVoiceStatus, cardOptions;
    private SwipeRefreshLayout swipeRefreshProfile;
    private View topBarProfile;
    private ImageView btnMenu;

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

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable delayedStartListening = () -> {
        if (isSttEnabled && isAdded() && !isTtsSpeaking && !isListening) {
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
        if (MicPermissionHelper.hasAudioPermission(requireContext())) return;
        if (MicPermissionHelper.isPermanentlyDenied(requireActivity())) {
            explainPermanentDenialAndOpenSettings();
            return;
        }
        if (MicPermissionHelper.isScreenReaderActive(requireContext())) {
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
            MicPermissionHelper.markRequested(requireContext());
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
            MicPermissionHelper.openAppSettings(requireActivity());
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authManager = new AuthManager(requireContext());
        prefs       = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        googleTts = new GoogleTtsManager(requireContext());
        googleStt = new GoogleSttManager(requireContext());
        lastKnownMicPermission = MicPermissionHelper.hasAudioPermission(requireContext());
        hybridSpeech = new HybridSpeechManager(requireContext());
        hybridSpeech.initVosk(
                () -> Log.d("Profile_STT", "Vosk model ready — now the primary listen engine."),
                () -> Log.e("Profile_STT", "Vosk model failed to load — using raw SpeechRecognizer only."));

        cascadeSession = new SttCascadeSession(googleStt, hybridSpeech, handler, false);

        ensureDefaultVoiceOptions();
        bindViews(view);
        loadSavedOptions();
        applyFontSize();
        loadProfileData();
        showAppVersion();
        setupSwitches();
        setupClickActions();
        initializeVoiceStatus();
        buildSpeechIntent();
        setupSpeechRecognizer();
        setupPressAnimations();
        setupMenuButton();

        DrawerLayout hostDrawerLayout = requireActivity().findViewById(R.id.drawerLayout);
        LinearLayout hostDrawerContainer = requireActivity().findViewById(R.id.drawerMaterialsContainer);
        ImageView hostBtnCloseDrawer = requireActivity().findViewById(R.id.btnCloseDrawer);
        materialsDrawer = new MaterialsDrawerController(requireActivity(), hostDrawerLayout, hostDrawerContainer,
                btnMenu, hostBtnCloseDrawer, this::stopListeningSafely, text -> speak(text, false));
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

    private void bindViews(View view) {
        topBarProfile      = view.findViewById(R.id.topBarProfile);
        btnMenu            = view.findViewById(R.id.btnMenu);
        txtStudentInfoLabel= view.findViewById(R.id.txtStudentInfoLabel);
        txtStudentName     = view.findViewById(R.id.txtStudentName);
        txtCourse          = view.findViewById(R.id.txtCourse);
        txtEmail           = view.findViewById(R.id.txtEmail);
        txtStudentNumber   = view.findViewById(R.id.txtStudentNumber);
        txtAge             = view.findViewById(R.id.txtAge);
        txtYearLevel       = view.findViewById(R.id.txtYearLevel);
        txtSection         = view.findViewById(R.id.txtSection);
        txtImpairmentLevel = view.findViewById(R.id.txtImpairmentLevel);
        txtVoiceStatus     = view.findViewById(R.id.txtVoiceStatus);
        txtRecognizedText  = view.findViewById(R.id.txtRecognizedText);
        txtVoiceHint       = view.findViewById(R.id.txtVoiceHint);
        optionTts          = view.findViewById(R.id.optionTts);
        optionStt          = view.findViewById(R.id.optionStt);
        optionVoice        = view.findViewById(R.id.optionVoice);
        txtOptionVoice     = view.findViewById(R.id.txtOptionVoice);
        optionHelp         = view.findViewById(R.id.optionHelp);
        optionPrivacyPolicy = view.findViewById(R.id.optionPrivacyPolicy);
        txtAppVersion      = view.findViewById(R.id.txtAppVersion);
        switchTts          = view.findViewById(R.id.switchTts);
        switchStt          = view.findViewById(R.id.switchStt);
        cardProfileInfo    = view.findViewById(R.id.cardProfileInfo);
        cardImpairmentLevel= view.findViewById(R.id.cardImpairmentLevel);
        cardVoiceStatus    = view.findViewById(R.id.cardVoiceStatus);
        cardOptions        = view.findViewById(R.id.cardOptions);
        swipeRefreshProfile= view.findViewById(R.id.swipeRefreshProfile);
        btnRetakeAssessment= view.findViewById(R.id.btnRetakeAssessment);
        btnLogout          = view.findViewById(R.id.btnLogout);
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
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            updateVoiceStatus("Speech recognition not available.");
            return;
        }
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (Exception ignored) {}
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext());
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
                        if (isSttEnabled && !isTtsSpeaking) cascadeFromBuiltIn();
                        return;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        updateVoiceStatus("Recognizer busy.");
                        updateRecognizedText("Waiting for speech...");
                        handler.postDelayed(() -> {
                            setupSpeechRecognizer();
                            if (isSttEnabled && !isTtsSpeaking) scheduleListening(MIC_BUSY_RETRY_DELAY_MS);
                        }, MIC_BUSY_REINIT_DELAY_MS);
                        return;
                    default:
                        Log.e("Profile_STT", "Built-in recognizer onError code=" + error);
                        if (SpeechEngineHealth.isRecognizerIncompatible(error)) {
                            Log.e("Profile_STT", "Built-in recognizer is not usable on this device — "
                                    + "skipping it from now on.");
                            SpeechEngineHealth.markBuiltInRecognizerBroken(requireContext());
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
                    "Say change voice to choose a different assistant voice. " +
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

        if (cmd.contains("change voice") || cmd.contains("assistant voice") || cmd.contains("voice settings")
                || cmd.contains("change the voice") || cmd.contains("choose voice")) {
            speak("Opening assistant voice.", false);
            handler.postDelayed(this::openVoiceSettings, 500);
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
        String section    = txtSection         != null ? txtSection.getText().toString()          : "Unknown";
        String textSize   = txtCourse         != null ? txtCourse.getText().toString()           : "Unknown";
        String impairment = txtImpairmentLevel!= null ? txtImpairmentLevel.getText().toString()  : "Unknown";

        String message = "Your profile details. "
                + "Name: " + name + ". "
                + email + ". "
                + schoolNum + ". "
                + age + ". "
                + yearLevel + ". "
                + section + ". "
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

    @Override public void speakBeforeLeaving(String text) { speak(text, false); }
    @Override public void announceStillOnThisTab(String text) { speak(text, true); }

    private void startVoiceRecognition() {
        if (!isSttEnabled || isTtsSpeaking || isListening) return;
        if (!MicPermissionHelper.hasAudioPermission(requireContext())) {
            checkMicPermission();
            return;
        }
        if (SpeechEngineHealth.isBuiltInRecognizerBroken(requireContext())) {
            cascadeFromBuiltIn();
            return;
        }
        startRawAndroidListening();
    }

    private void cascadeFromBuiltIn() {
        if (!isSttEnabled || isTtsSpeaking || isListening || !isResumed()) return;
        commandHandled = false;
        isListening    = true;
        final int mySession = voiceSessionId;

        cascadeSession.cascade(requireContext(), "command", null, new SttCascadeSession.Listener() {
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
        if (!isSttEnabled || isTtsSpeaking || isListening || !isResumed()) return;
        if (speechRecognizer == null) {
            Log.e("Profile_STT", "Built-in recognizer unavailable on this device — using Cloud STT.");
            SpeechEngineHealth.markBuiltInRecognizerBroken(requireContext());
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
                Toast.makeText(requireContext(), "Text-to-Speech enabled", Toast.LENGTH_SHORT).show();
                speak("Text to speech enabled.", true);
            } else {
                Toast.makeText(requireContext(), "Text-to-Speech disabled", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(requireContext(), "Speech-to-Text enabled", Toast.LENGTH_SHORT).show();
                speak("Speech to text enabled.", true);
            } else {
                updateVoiceStatus("Speech-to-Text is OFF.");
                updateRecognizedText("Speech-to-Text is disabled.");
                Toast.makeText(requireContext(), "Speech-to-Text disabled", Toast.LENGTH_SHORT).show();
                handler.removeCallbacks(delayedStartListening);
                stopListeningSafely();
                speak("Speech to text disabled.", false);
            }
        });

        optionTts.setOnClickListener(v -> { bounceView(optionTts); switchTts.toggle(); });
        optionStt.setOnClickListener(v -> { bounceView(optionStt); switchStt.toggle(); });
        if (optionVoice != null) optionVoice.setOnClickListener(v -> { bounceView(optionVoice); openVoiceSettings(); });
        if (optionHelp != null) optionHelp.setOnClickListener(v -> { bounceView(optionHelp); openHelp(); });
        if (optionPrivacyPolicy != null) optionPrivacyPolicy.setOnClickListener(v -> { bounceView(optionPrivacyPolicy); openPrivacyPolicy(); });
    }

    private void openPrivacyPolicy() {
        startActivity(new Intent(requireContext(), PrivacyPolicyActivity.class));
    }

    /** Opens the assistant-voice picker; this tab's mic and voice stop while it is on top. */
    private void openVoiceSettings() {
        updateVoiceStatus("Opening assistant voice...");
        stopListeningSafely();
        handler.removeCallbacks(delayedStartListening);
        if (googleTts != null) googleTts.stopSpeaking();
        startActivity(new Intent(requireContext(), VoiceSettingsActivity.class));
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    /** Shows which voice this student is using, on the Assistant voice row. */
    private void updateVoiceOptionLabel() {
        if (txtOptionVoice == null || !isAdded()) return;
        txtOptionVoice.setText("Assistant voice: " + TtsVoiceManager.getOption(requireContext()).label);
    }

    private void showAppVersion() {
        if (txtAppVersion == null) return;
        try {
            String versionName = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            txtAppVersion.setText("VisualED v" + versionName);
        } catch (Exception ignored) {}
    }

    private void openHelp() {
        startActivity(new Intent(requireContext(), HelpActivity.class));
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void setupMenuButton() {
        if (btnMenu != null) btnMenu.setOnClickListener(v -> { bounceView(btnMenu); materialsDrawer.open(); });
    }

    private void setupClickActions() {
        if (btnRetakeAssessment != null)
            btnRetakeAssessment.setOnClickListener(v -> { bounceView(btnRetakeAssessment); openRetakeAssessment(); });

        if (btnLogout != null)
            btnLogout.setOnClickListener(v -> {
                bounceView(btnLogout);
                speak("Logging out.", false);
                handler.postDelayed(this::logoutUser, 400);
            });
    }

    private void openHome() {
        ((MainActivity) requireActivity()).switchTab("home");
    }

    private void openMaterials() {
        ((MainActivity) requireActivity()).switchTab("materials");
    }

    private void openRetakeAssessment() {
        updateVoiceStatus("Opening retake assessment...");
        stopListeningSafely();
        handler.removeCallbacks(delayedStartListening);
        if (googleTts != null) googleTts.stopSpeaking();
        Intent intent = new Intent(requireContext(), TextSizeTestActivity.class);
        intent.putExtra("isRetakeAssessment", true);
        startActivity(intent);
        requireActivity().overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    private void logoutUser() {
        handler.removeCallbacksAndMessages(null);
        stopListeningSafely();
        isSttEnabled  = false;
        isTtsSpeaking = false;
        if (googleTts != null) googleTts.stopSpeaking();

        if (authManager != null) authManager.logout();

        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        requireActivity().finish();
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
        String section    = prefs.getString(KEY_SECTION,                "Not Available");

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
        if (txtSection       != null) txtSection.setText("Section: " + section);
        if (txtCourse        != null) txtCourse.setText("Recommended Text Size: " + textSize);
        if (txtImpairmentLevel!= null) txtImpairmentLevel.setText(formatImpairmentLevel(impairment));
        applyFontSize();
    }

    private void fetchStudentProfileFromServer() {
        String sessionToken = authManager.getSessionToken();

        if (sessionToken == null || sessionToken.trim().isEmpty()) {
            Toast.makeText(requireContext(), "No saved student account.", Toast.LENGTH_LONG).show();
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
            Toast.makeText(requireContext(), "Failed to prepare profile request.", Toast.LENGTH_SHORT).show();
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
                        String sec  = s.optString("section",     "");
                        String imp  = s.optString("impairment_level", "Not Available");
                        int    ts   = s.optInt("recommended_text_size", 0);
                        String tsStr= ts > 0 ? ts + "sp" : "Not Available";
                        String ageStr = age.trim().isEmpty() ? "Not Available" : age;
                        String yrStr  = yr.trim().isEmpty()  ? "Not Available" : yr;
                        String secStr = sec.trim().isEmpty() ? "Not Available" : sec;
                        String name = formatProfessionalName(fn, mn, ln);

                        if (txtStudentName   != null) txtStudentName.setText(name);
                        if (txtEmail         != null) txtEmail.setText("Email: " + em);
                        if (txtStudentNumber != null) txtStudentNumber.setText("Student Number: " + sid);
                        if (txtAge           != null) txtAge.setText("Age: " + ageStr);
                        if (txtYearLevel     != null) txtYearLevel.setText("Year Level: " + yrStr);
                        if (txtSection       != null) txtSection.setText("Section: " + secStr);
                        if (txtCourse        != null) txtCourse.setText("Recommended Text Size: " + tsStr);
                        if (txtImpairmentLevel!=null) txtImpairmentLevel.setText(formatImpairmentLevel(imp));

                        prefs.edit()
                                .putString(KEY_IMPAIRMENT_LEVEL,      imp)
                                .putString(KEY_RECOMMENDED_TEXT_SIZE, tsStr)
                                .putString(KEY_YEAR_LEVEL,            yrStr)
                                .putString(KEY_SECTION,               secStr)
                                .apply();

                        applyFontSize();
                        updateVoiceStatus("Profile loaded.");
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "Profile error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    } finally {
                        if (swipeRefreshProfile != null) swipeRefreshProfile.setRefreshing(false);
                    }
                },
                error -> {
                    if (SessionManager.isSessionExpiredError(error)) {
                        SessionManager.forceLogoutAndRedirect(requireActivity());
                        return;
                    }
                    Toast.makeText(requireContext(), "Failed to load profile.", Toast.LENGTH_LONG).show();
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
        VolleySingleton.getInstance(requireContext()).getRequestQueue().add(req);
    }


    private void applyFontSize() {
        float b = FontSizeManager.getFontSize(requireContext());

        if (txtStudentInfoLabel != null) txtStudentInfoLabel.setTextSize(b);
        if (txtStudentName      != null) txtStudentName.setTextSize(b + 4);
        if (txtCourse           != null) txtCourse.setTextSize(16f);
        if (txtEmail            != null) txtEmail.setTextSize(16f);
        if (txtStudentNumber    != null) txtStudentNumber.setTextSize(16f);
        if (txtAge              != null) txtAge.setTextSize(16f);
        if (txtYearLevel        != null) txtYearLevel.setTextSize(16f);
        if (txtSection          != null) txtSection.setTextSize(16f);
        if (txtImpairmentLevel  != null) txtImpairmentLevel.setTextSize(b + 8);
        if (btnRetakeAssessment != null) btnRetakeAssessment.setTextSize(16f);
        if (btnLogout           != null) btnLogout.setTextSize(b - 2);
        if (txtVoiceStatus      != null) txtVoiceStatus.setTextSize(b - 2);
        if (txtRecognizedText   != null) txtRecognizedText.setTextSize(b - 2);
        if (txtVoiceHint        != null) txtVoiceHint.setTextSize(16f);
    }

    private void updateVoiceStatus(String s)   { if (isAdded()) requireActivity().runOnUiThread(() -> { if (txtVoiceStatus    != null) txtVoiceStatus.setText("Voice: " + s); }); }
    private void updateRecognizedText(String s) { if (isAdded()) requireActivity().runOnUiThread(() -> { if (txtRecognizedText != null) txtRecognizedText.setText(s); }); }

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
                    case android.view.MotionEvent.ACTION_DOWN:   view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start(); break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: view.animate().scaleX(1f).scaleY(1f).setDuration(80).start();       break;
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

    @Override
    public void repeatLastInstruction() {
        if (lastSpokenInstruction == null || lastSpokenInstruction.trim().isEmpty()) return;
        vibrateShort();
        if (googleTts != null) googleTts.speak(lastSpokenInstruction, null);
    }

    private void vibrateShort() {
        Vibrator vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(40);
        }
    }

    @Override public void onResume() {
        super.onResume();
        loadSavedOptions();
        applyFontSize();
        updateVoiceOptionLabel();
        loadProfileData();
        fetchStudentProfileFromServer();

        boolean nowGranted = MicPermissionHelper.hasAudioPermission(requireContext());
        if (nowGranted && !lastKnownMicPermission && !micPermissionRequestInFlight) {
            updateVoiceStatus("Microphone enabled.");
        }
        lastKnownMicPermission = nowGranted;

        if (isSttEnabled && !isTtsSpeaking) scheduleListening(LISTEN_DELAY_NORMAL);
    }

    @Override public void onPause() {
        super.onPause();
        handler.removeCallbacks(delayedStartListening);
        stopListeningSafely();
        // Navigating to another screen leaves this fragment paused, not
        // destroyed — its TTS would otherwise keep talking in the background
        // and overlap with the next screen's voice.
        if (googleTts != null) googleTts.stopSpeaking();
        commandHandled = false;
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        stopListeningSafely();
        try { if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); } } catch (Exception ignored) {}
        if (hybridSpeech != null) hybridSpeech.destroy();
        if (googleStt != null) googleStt.destroy();
        if (googleTts != null) googleTts.destroy();
        super.onDestroyView();
    }
}
