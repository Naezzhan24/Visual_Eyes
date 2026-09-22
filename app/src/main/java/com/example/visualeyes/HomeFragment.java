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
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.widget.LinearLayout;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HomeFragment extends Fragment implements TabFragment {

    private static final String PREFS_NAME            = "VisualEyesPrefs";
    private static final String KEY_LAST_OPENED_TITLE = "last_opened_title";
    private static final String KEY_LAST_OPENED_URL   = "last_opened_url";
    private static final float  MIN_FONT_SIZE = 14f;
    private static final float  MAX_FONT_SIZE = 34f;

    private TextView txtWelcome, txtSubtitle, txtUpdateTitle;
    private TextView txtLearningMaterialSubtitle, txtVoiceStatus, txtRecognizedText, txtVoiceHint;
    private TextView txtCurrentFontSize;
    private SeekBar seekFontSize;
    private TextView txtFontSizeTitle, txtFontSizeSubtitle;
    private ImageView btnMenu, btnOpenLearningMaterial, btnHelp;
    private CardView cardLearningMaterial, cardFontSizeControl;
    private SwipeRefreshLayout swipeRefreshHome;
    private View topBar;

    private MaterialsDrawerController materialsDrawer;

    private GoogleTtsManager googleTts;
    private GoogleSttManager googleStt;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;

    private HybridSpeechManager hybridSpeech;
    private SttCascadeSession   cascadeSession;

    private AuthManager authManager;
    private SharedPreferences prefs;

    private String latestMaterialId = "";
    private String latestFileUrl    = "";
    private String latestTitle      = "";

    private boolean isListening   = false;
    private boolean isTtsSpeaking = false;
    private String  lastMessage   = "Welcome to your home screen.";

    private int voiceSessionId = 0;

    private boolean micPermissionRequestInFlight = false;
    private boolean lastKnownMicPermission       = false;

    // Standardized across every mic-using screen: give the OS 400ms to
    // actually tear down/recreate the recognizer, then wait another 600ms
    // before the first retry so it isn't immediately busy again.
    private static final long MIC_BUSY_REINIT_DELAY_MS = 400L;
    private static final long MIC_BUSY_RETRY_DELAY_MS  = 600L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable restartListeningRunnable = () -> {
        if (!isListening && !isTtsSpeaking && isResumed()) {
            startListening();
        }
    };

    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                micPermissionRequestInFlight = false;
                lastKnownMicPermission = granted;
                if (granted) {
                    updateVoiceStatus("Microphone enabled.");
                    speak("Microphone permission granted.", true);
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
        stopListening();
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
        stopListening();
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
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        authManager  = new AuthManager(requireContext());
        prefs        = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        googleTts    = new GoogleTtsManager(requireContext());
        googleStt    = new GoogleSttManager(requireContext());
        lastKnownMicPermission = MicPermissionHelper.hasAudioPermission(requireContext());

        bindViews(view);
        applyFontSize();
        setupWelcome();
        setupMaterialCard();
        setupMenuButton();

        DrawerLayout hostDrawerLayout = requireActivity().findViewById(R.id.drawerLayout);
        LinearLayout hostDrawerContainer = requireActivity().findViewById(R.id.drawerMaterialsContainer);
        ImageView hostBtnCloseDrawer = requireActivity().findViewById(R.id.btnCloseDrawer);
        materialsDrawer = new MaterialsDrawerController(requireActivity(), hostDrawerLayout, hostDrawerContainer,
                btnMenu, hostBtnCloseDrawer, this::stopListening, text -> speak(text, false));

        setupFontSizeSeekBar();
        initializeVoiceStatus();
        setupPressAnimations();
        animateHomeEntrance();
        buildSpeechIntent();
        initSpeechRecognizer();
        hybridSpeech = new HybridSpeechManager(requireContext());
        hybridSpeech.initVosk(
                () -> Log.d("Home_STT", "Vosk model ready — now the primary listen engine."),
                () -> Log.e("Home_STT", "Vosk model failed to load — using raw SpeechRecognizer only."));

        cascadeSession = new SttCascadeSession(googleStt, hybridSpeech, handler, false);
        loadLatestMaterial();
        materialsDrawer.load(authManager.getSessionToken());

        if (swipeRefreshHome != null) {
            swipeRefreshHome.setColorSchemeColors(0xFF8C4356);
            swipeRefreshHome.setOnRefreshListener(() -> {
                loadLatestMaterial();
                materialsDrawer.load(authManager.getSessionToken());
            });
        }

        handler.postDelayed(() ->
                speak("Welcome to your home screen. " +
                        "Say help for available commands.", true), 900);
    }

    private void bindViews(View view) {
        topBar                      = view.findViewById(R.id.topBarHome);
        btnMenu                     = view.findViewById(R.id.btnMenu);
        btnHelp                     = view.findViewById(R.id.btnHelp);
        btnOpenLearningMaterial     = view.findViewById(R.id.btnOpenLearningMaterial);
        txtWelcome                  = view.findViewById(R.id.txtWelcome);
        txtSubtitle                 = view.findViewById(R.id.txtSubtitle);
        txtUpdateTitle              = view.findViewById(R.id.textUpdateTitle);
        txtLearningMaterialSubtitle = view.findViewById(R.id.txtLearningMaterialSubtitle);
        txtVoiceStatus              = view.findViewById(R.id.txtVoiceStatus);
        txtRecognizedText           = view.findViewById(R.id.txtRecognizedText);
        txtVoiceHint                = view.findViewById(R.id.txtVoiceHint);
        txtCurrentFontSize          = view.findViewById(R.id.txtCurrentFontSize);
        seekFontSize                = view.findViewById(R.id.seekFontSize);
        txtFontSizeTitle            = view.findViewById(R.id.txtFontSizeTitle);
        txtFontSizeSubtitle         = view.findViewById(R.id.txtFontSizeSubtitle);
        cardLearningMaterial        = view.findViewById(R.id.cardLearningMaterial);
        cardFontSizeControl         = view.findViewById(R.id.cardFontSizeControl);
        swipeRefreshHome             = view.findViewById(R.id.swipeRefreshHome);
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

    private void initSpeechRecognizer() {
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
                updateVoiceStatus("Processing...");
            }

            @Override public void onError(int error) {
                isListening = false;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        updateVoiceStatus("No speech detected.");
                        if (!isTtsSpeaking) cascadeFromBuiltIn();
                        return;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        handler.postDelayed(() -> {
                            initSpeechRecognizer();
                            if (!isTtsSpeaking) scheduleListening(MIC_BUSY_RETRY_DELAY_MS);
                        }, MIC_BUSY_REINIT_DELAY_MS);
                        return;
                    default:
                        Log.e("Home_STT", "Built-in recognizer onError code=" + error);
                        if (SpeechEngineHealth.isRecognizerIncompatible(error)) {
                            Log.e("Home_STT", "Built-in recognizer is not usable on this device — "
                                    + "skipping it from now on.");
                            SpeechEngineHealth.markBuiltInRecognizerBroken(requireContext());
                        }
                        updateVoiceStatus("Voice error.");
                        if (!isTtsSpeaking) cascadeFromBuiltIn();
                        return;
                }
            }

            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    handleCommand(matches.get(0).trim());
                } else {
                    if (!isTtsSpeaking) cascadeFromBuiltIn();
                }
            }

            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> p =
                        partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (p != null && !p.isEmpty()) {
                    updateRecognizedText("Hearing: " + p.get(0));

                    String norm = normalize(p.get(0));
                    if (isQuickCommand(norm)) {
                        stopListening();
                        handleCommand(p.get(0).trim());
                    }
                }
            }

            @Override public void onEvent(int e, Bundle p) {}
        });
    }

    private void startListening() {
        if (isListening || isTtsSpeaking || !isResumed()) return;
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
        if (isListening || isTtsSpeaking || !isResumed()) return;
        isListening = true;
        final int mySession = voiceSessionId;

        cascadeSession.cascade(requireContext(), "command", null, new SttCascadeSession.Listener() {
            @Override public void onListeningStarted() {
                if (mySession != voiceSessionId) return;
                updateVoiceStatus("Listening...");
                updateRecognizedText("Waiting for speech...");
            }

            @Override public void onPartialResult(String partial) {
                if (mySession != voiceSessionId) return;
                updateRecognizedText("Hearing: " + partial);
                String norm = normalize(partial);
                if (isQuickCommand(norm)) {
                    isListening = false;
                    hybridSpeech.cancel();
                    handleCommand(partial.trim());
                }
            }

            @Override public void onTranscript(String transcript) {
                if (mySession != voiceSessionId) return;
                isListening = false;
                handleCommand(transcript);
            }

            @Override public void onExhausted() {
                if (mySession != voiceSessionId) return;
                isListening = false;
                if (!isTtsSpeaking) scheduleListening(1000);
            }
        });
    }

    private void startRawAndroidListening() {
        if (isListening || isTtsSpeaking || speechRecognizer == null || !isResumed()) return;
        // Set synchronously here, not in onReadyForSpeech — that callback
        // fires asynchronously, leaving a window right after this call where
        // isListening is still false and a rapid second tap would bypass
        // this guard and start a second recognizer on top of the first.
        isListening = true;
        try {
            speechRecognizer.cancel();
            updateVoiceStatus("Listening...");
            updateRecognizedText("Waiting for speech...");
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            isListening = false;
            cascadeFromBuiltIn();
        }
    }

    private void stopListening() {
        isListening = false;
        voiceSessionId++;
        if (cascadeSession != null) cascadeSession.cancel();
        try { if (speechRecognizer != null) speechRecognizer.stopListening(); } catch (Exception ignored) {}
        try { if (speechRecognizer != null) speechRecognizer.cancel(); }        catch (Exception ignored) {}
    }

    private void scheduleListening(long delay) {
        handler.removeCallbacks(restartListeningRunnable);
        handler.postDelayed(restartListeningRunnable, delay);
    }

    private void handleCommand(String spoken) {
        String cmd = normalize(spoken);
        updateRecognizedText("Recognized: " + spoken);
        updateVoiceStatus("Command: " + spoken);

        if (cmd.contains("help") || cmd.contains("command") || cmd.contains("what can")) {
            speakHelp();
            return;
        }

        if (cmd.equals("repeat") || cmd.contains("repeat that") || cmd.contains("say again")) {
            speak(lastMessage, true);
            return;
        }

        if (cmd.equals("stop") || cmd.contains("stop speaking")) {
            if (googleTts != null) googleTts.stopSpeaking();
            isTtsSpeaking = false;
            updateVoiceStatus("Stopped.");
            scheduleListening(600);
            return;
        }

        if (cmd.contains("logout") || cmd.contains("log out") || cmd.contains("sign out")) {
            speak("Logging out.", false);
            handler.postDelayed(this::logoutUser, 600);
            return;
        }

        if (cmd.contains("read screen") || cmd.contains("describe")
                || cmd.contains("read page") || cmd.contains("what is on")) {
            readScreen();
            return;
        }

        if (cmd.contains("recommended") || cmd.contains("bigger text")
                || cmd.contains("large text") || cmd.contains("recommended font")) {
            useRecommendedFont();
            return;
        }

        if (cmd.contains("default") || cmd.contains("normal text")
                || cmd.contains("default font")) {
            useDefaultFont();
            return;
        }

        if (cmd.contains("open latest") || cmd.contains("open material")
                || cmd.contains("open lesson") || cmd.contains("learning material")
                || cmd.equals("lesson") || cmd.equals("open")) {
            speak("Opening latest material.", false);
            handler.postDelayed(this::openLatestMaterial, 500);
            return;
        }

        if (cmd.contains("materials") || cmd.contains("material list")) {
            speak("Opening materials.", false);
            handler.postDelayed(this::openMaterials, 500);
            return;
        }

        if (cmd.contains("profile") || cmd.contains("account")) {
            speak("Opening profile.", false);
            handler.postDelayed(this::openProfile, 500);
            return;
        }

        if (cmd.equals("home") || cmd.contains("go home")) {
            speak("You are already on the home screen.", true);
            return;
        }

        speak("Command not recognized. Say help for available commands.", true);
    }

    private boolean isQuickCommand(String normalized) {
        return normalized.equals("home")
                || normalized.equals("materials")
                || normalized.equals("profile")
                || normalized.equals("logout")
                || normalized.equals("stop")
                || normalized.equals("repeat")
                || normalized.equals("help");
    }

    private void speak(String text, boolean listenAfter) {
        lastMessage   = text;
        isTtsSpeaking = true;
        stopListening();
        googleTts.speak(text, () -> {
            isTtsSpeaking = false;
            if (listenAfter) scheduleListening(400);
        });
    }

    @Override public void speakBeforeLeaving(String text) { speak(text, false); }
    @Override public void announceStillOnThisTab(String text) { speak(text, true); }

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.US).trim()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private void speakHelp() {
        speak("Available commands. " +
                "Say read screen to hear this page. " +
                "Say open latest material to open the newest lesson. " +
                "Say materials to view all learning materials. " +
                "Say profile to open your profile. " +
                "Say recommended font to use the recommended text size. " +
                "Say default font to use the default text size. " +
                "Say repeat to hear my last message. " +
                "Say stop to stop speaking. " +
                "Say logout to sign out.", true);
    }

    private void readScreen() {
        String welcome      = txtWelcome      != null ? txtWelcome.getText().toString()      : "Welcome.";
        String subtitle     = txtSubtitle     != null ? txtSubtitle.getText().toString()     : "";
        String material     = txtUpdateTitle  != null ? txtUpdateTitle.getText().toString()  : "No material.";

        speak(welcome + ". " + subtitle + ". "
                + "Latest learning material: " + material + ". "
                + "Say help for commands.", true);
    }

    private void useRecommendedFont() {
        FontSizeManager.useRecommended(requireContext());
        applyFontSize();
        Toast.makeText(requireContext(), "Recommended text size applied.", Toast.LENGTH_SHORT).show();
        speak("Recommended text size applied.", true);
    }

    private void useDefaultFont() {
        FontSizeManager.useDefault(requireContext());
        applyFontSize();
        Toast.makeText(requireContext(), "Default text size applied.", Toast.LENGTH_SHORT).show();
        speak("Default text size applied.", true);
    }

    private void logoutUser() {
        try {
            stopListening();
            if (googleTts != null) googleTts.stopSpeaking();
            if (authManager != null) authManager.logout();
            Intent intent = new Intent(requireContext(), LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            requireActivity().finish();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Logout failed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openLatestMaterial() {
        if (latestFileUrl == null || latestFileUrl.trim().isEmpty()) {
            speak("No learning material available yet.", true);
            return;
        }
        saveLastOpened(latestTitle, latestFileUrl);
        MaterialReadTracker.markOpened(requireContext(), latestMaterialId);
        stopListening();

        // Requests a short-lived signed URL before opening — see
        // MaterialsActivity.openMaterial() for why.
        SignedUrlHelper.resolve(requireContext(), latestFileUrl, new SignedUrlHelper.Callback() {
            @Override public void onSignedUrl(String signedUrl) {
                Intent intent = new Intent(requireContext(), AccessibleMaterialActivity.class);
                intent.putExtra("material_id",     latestMaterialId);
                intent.putExtra("file_url",        signedUrl);
                intent.putExtra("title",           latestTitle);
                intent.putExtra("impairment_level","moderate");
                startActivity(intent);
                requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            }

            @Override public void onError() {
                Toast.makeText(requireContext(), "Unable to open material. Please try again.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void openMaterials() {
        stopListening();
        ((MainActivity) requireActivity()).switchTab("materials");
    }

    private void openProfile() {
        stopListening();
        ((MainActivity) requireActivity()).switchTab("profile");
    }

    private void loadLatestMaterial() {
        String sessionToken = authManager.getSessionToken();

        if (sessionToken == null || sessionToken.trim().isEmpty()) {
            latestMaterialId = ""; latestTitle = ""; latestFileUrl = "";
            if (txtUpdateTitle != null) txtUpdateTitle.setText("No learning material available yet");
            if (txtLearningMaterialSubtitle != null) txtLearningMaterialSubtitle.setText("Please log in again to view your materials.");
            if (swipeRefreshHome != null) swipeRefreshHome.setRefreshing(false);
            return;
        }

        // Calls the get_student_materials RPC instead of directly querying
        // student_material_access with a client-supplied student_id filter —
        // see MaterialsActivity.loadMaterials() for why that filter alone
        // isn't a real access control.
        String url = ApiConfig.SUPABASE_URL + "/rest/v1/rpc/get_student_materials";

        JSONObject rpcBody = new JSONObject();
        String bodyStr;
        try {
            rpcBody.put("p_session_token", sessionToken);
            bodyStr = rpcBody.toString();
        } catch (Exception e) {
            if (txtUpdateTitle != null) txtUpdateTitle.setText("Unable to load latest material");
            if (swipeRefreshHome != null) swipeRefreshHome.setRefreshing(false);
            return;
        }
        final String finalBodyStr = bodyStr;

        RequestQueue queue = VolleySingleton.getInstance(requireContext()).getRequestQueue();
        JsonArrayRequest req = new JsonArrayRequest(Request.Method.POST, url, null,
                this::handleMaterialResponse,
                error -> {
                    if (SessionManager.isSessionExpiredError(error)) {
                        SessionManager.forceLogoutAndRedirect(requireActivity());
                        return;
                    }
                    latestMaterialId = ""; latestTitle = ""; latestFileUrl = "";
                    if (txtUpdateTitle != null) txtUpdateTitle.setText("Unable to load latest material");
                    if (txtLearningMaterialSubtitle != null) txtLearningMaterialSubtitle.setText("Check your connection and try again.");
                    updateVoiceStatus("Unable to load material.");
                    if (swipeRefreshHome != null) swipeRefreshHome.setRefreshing(false);
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
        queue.add(req);
    }

    private void handleMaterialResponse(JSONArray rows) {
        try {
            handleMaterialResponseInner(rows);
        } finally {
            if (swipeRefreshHome != null) swipeRefreshHome.setRefreshing(false);
        }
    }

    private void handleMaterialResponseInner(JSONArray rows) {
        try {
            if (rows == null || rows.length() == 0) {
                latestMaterialId = ""; latestTitle = ""; latestFileUrl = "";
                if (txtUpdateTitle              != null) txtUpdateTitle.setText("No learning material available yet");
                if (txtLearningMaterialSubtitle != null) txtLearningMaterialSubtitle.setText("Wait for your instructor or admin to send a lesson.");
                return;
            }

            JSONObject latest = null;
            String latestDate = "";
            for (int i = 0; i < rows.length(); i++) {
                JSONObject obj = rows.getJSONObject(i);
                String uploadDate = obj.optString("upload_date", "");
                if (latest == null || uploadDate.compareTo(latestDate) > 0) {
                    latest = obj;
                    latestDate = uploadDate;
                }
            }

            if (latest == null) {
                latestMaterialId = ""; latestTitle = ""; latestFileUrl = "";
                if (txtUpdateTitle              != null) txtUpdateTitle.setText("No learning material available yet");
                if (txtLearningMaterialSubtitle != null) txtLearningMaterialSubtitle.setText("Wait for your instructor or admin to send a lesson.");
                return;
            }

            latestMaterialId     = latest.optString("id",    "");
            latestTitle          = latest.optString("title", "Learning Material");
            latestFileUrl        = buildFileUrl(latest.optString("file_path", ""));

            if (txtUpdateTitle              != null) txtUpdateTitle.setText(latestTitle);
            if (txtLearningMaterialSubtitle != null) txtLearningMaterialSubtitle.setText("Tap to open your latest accessible learning material.");
            updateVoiceStatus("Material loaded.");
        } catch (Exception e) {
            latestMaterialId = ""; latestTitle = ""; latestFileUrl = "";
            if (txtUpdateTitle != null) txtUpdateTitle.setText("Unable to read latest material");
            updateVoiceStatus("Error reading material.");
        }
    }

    private String buildFileUrl(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return "";
        filePath = filePath.trim().replace("\\", "/");
        if (filePath.startsWith("http://") || filePath.startsWith("https://")) return filePath.replace(" ", "%20");
        while (filePath.startsWith("/")) filePath = filePath.substring(1);
        if (filePath.startsWith("materials/")) filePath = filePath.substring("materials/".length());
        return ApiConfig.SUPABASE_URL + "/storage/v1/object/public/materials/" + filePath.replace(" ", "%20");
    }

    private void saveLastOpened(String title, String url) {
        prefs.edit().putString(KEY_LAST_OPENED_TITLE, title)
                .putString(KEY_LAST_OPENED_URL,   url).apply();
    }

    private void setupWelcome() {
        String name = authManager.getFirstName();
        if (name == null || name.trim().isEmpty()) name = "Student";

        boolean returning = authManager.hasSeenHome();
        String greeting = returning ? "Welcome Back, " : "Welcome, ";
        if (!returning) authManager.markHomeSeen();

        if (txtWelcome     != null) txtWelcome.setText(greeting + name + "! 👋");
        if (txtSubtitle    != null) txtSubtitle.setText("Ready to start your learning journey?");
    }

    private void initializeVoiceStatus() {
        if (txtVoiceHint != null)
            txtVoiceHint.setText("Voice commands: Help, Read screen, Open latest material, " +
                    "Materials, Profile, Recommended font, Default font, Repeat, Stop, Logout.");
        updateVoiceStatus("Initializing...");
        updateRecognizedText("Waiting for speech...");
    }

    private void applyFontSize() {
        float b = FontSizeManager.getFontSize(requireContext());

        if (txtWelcome                  != null) txtWelcome.setTextSize(b + 6);
        if (txtSubtitle                 != null) txtSubtitle.setTextSize(16f);
        if (txtVoiceStatus              != null) txtVoiceStatus.setTextSize(b - 2);
        if (txtRecognizedText           != null) txtRecognizedText.setTextSize(b - 2);
        if (txtVoiceHint                != null) txtVoiceHint.setTextSize(16f);
        if (txtFontSizeTitle            != null) txtFontSizeTitle.setTextSize(b - 1);
        if (txtFontSizeSubtitle         != null) txtFontSizeSubtitle.setTextSize(16f);
        if (txtCurrentFontSize          != null) txtCurrentFontSize.setText(Math.round(b) + "sp");
        if (seekFontSize                != null) {
            int progress = Math.round(Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, b)) - MIN_FONT_SIZE);
            if (seekFontSize.getProgress() != progress) seekFontSize.setProgress(progress);
        }
        if (txtUpdateTitle              != null) txtUpdateTitle.setTextSize(b + 1);
        if (txtLearningMaterialSubtitle != null) txtLearningMaterialSubtitle.setTextSize(16f);

    }

    private void setupFontSizeSeekBar() {
        if (seekFontSize == null) return;
        seekFontSize.setMax(Math.round(MAX_FONT_SIZE - MIN_FONT_SIZE));
        seekFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                FontSizeManager.saveRecommendedSize(requireContext(), MIN_FONT_SIZE + progress);
                applyFontSize();
            }

            @Override public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int size = Math.round(MIN_FONT_SIZE + sb.getProgress());
                Toast.makeText(requireContext(), "Text size set to " + size + "sp.", Toast.LENGTH_SHORT).show();
                speak("Text size set to " + size + " S P.", true);
            }
        });
    }

    private void setupMenuButton() {
        if (btnMenu != null) btnMenu.setOnClickListener(v -> { bounceView(btnMenu); materialsDrawer.open(); });
        if (btnHelp != null) btnHelp.setOnClickListener(v -> { bounceView(btnHelp); openHelp(); });
    }

    private void openHelp() {
        startActivity(new Intent(requireContext(), HelpActivity.class));
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void setupMaterialCard() {
        if (cardLearningMaterial    != null) cardLearningMaterial.setOnClickListener(v -> { bounceView(cardLearningMaterial); openLatestMaterial(); });
        if (btnOpenLearningMaterial != null) btnOpenLearningMaterial.setOnClickListener(v -> { bounceView(btnOpenLearningMaterial); openLatestMaterial(); });
    }

    private void updateVoiceStatus(String s)   { if (isAdded()) requireActivity().runOnUiThread(() -> { if (txtVoiceStatus    != null) { txtVoiceStatus.setText("Voice: " + s); pulseView(txtVoiceStatus); } }); }
    private void updateRecognizedText(String s) { if (isAdded()) requireActivity().runOnUiThread(() -> { if (txtRecognizedText != null) txtRecognizedText.setText(s); }); }

    private void setupPressAnimations() {
        for (View v : new View[]{btnMenu, btnHelp, btnOpenLearningMaterial,
                cardLearningMaterial}) {
            if (v == null) continue;
            v.setOnTouchListener((view, event) -> {
                switch (event.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:   view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start(); break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: view.animate().scaleX(1f).scaleY(1f).setDuration(90).start();       break;
                }
                return false;
            });
        }
    }

    private void bounceView(View v) { if (v == null) return; v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(90).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start(); }
    private void pulseView(View v)  { if (v == null) return; v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start(); }

    private void animateHomeEntrance() {

        if (topBar != null) { topBar.setAlpha(0f); topBar.setTranslationY(-35f); topBar.animate().alpha(1f).translationY(0f).setDuration(350).start(); }
        if (txtWelcome != null) { txtWelcome.setAlpha(0f); txtWelcome.setTranslationY(-40f); txtWelcome.animate().alpha(1f).translationY(0f).setStartDelay(90).setDuration(380).setInterpolator(new AccelerateDecelerateInterpolator()).start(); }
        if (txtSubtitle != null) { txtSubtitle.setAlpha(0f); txtSubtitle.setTranslationY(-30f); txtSubtitle.animate().alpha(1f).translationY(0f).setStartDelay(150).setDuration(360).start(); }
        if (cardFontSizeControl != null) { cardFontSizeControl.setAlpha(0f); cardFontSizeControl.setTranslationY(55f); cardFontSizeControl.animate().alpha(1f).translationY(0f).setStartDelay(210).setDuration(380).start(); }
        if (cardLearningMaterial!= null) { cardLearningMaterial.setAlpha(0f); cardLearningMaterial.setTranslationY(55f); cardLearningMaterial.animate().alpha(1f).translationY(0f).setStartDelay(390).setDuration(380).start(); }
    }

    @Override
    public void repeatLastInstruction() {
        if (lastMessage == null || lastMessage.trim().isEmpty()) return;
        vibrateShort();
        speak(lastMessage, true);
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
        applyFontSize();
        loadLatestMaterial();

        boolean nowGranted = MicPermissionHelper.hasAudioPermission(requireContext());
        // Covers a grant obtained any way other than our own in-app request —
        // returning from Settings, a permission change made elsewhere, or a
        // process restart landing here with permission already present.
        if (nowGranted && !lastKnownMicPermission && !micPermissionRequestInFlight) {
            updateVoiceStatus("Microphone enabled.");
        }
        lastKnownMicPermission = nowGranted;

        if (!isTtsSpeaking) scheduleListening(600);
    }

    @Override public void onPause() {
        super.onPause();
        handler.removeCallbacks(restartListeningRunnable);
        stopListening();
        // Navigating to another screen (e.g. opening a material) leaves this
        // fragment paused, not destroyed — its TTS would otherwise keep
        // talking in the background and overlap with the next screen's voice.
        if (googleTts != null) googleTts.stopSpeaking();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        stopListening();
        try { if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); } } catch (Exception ignored) {}
        if (hybridSpeech != null) hybridSpeech.destroy();
        if (googleStt != null) googleStt.destroy();
        if (googleTts != null) googleTts.destroy();
        super.onDestroyView();
    }
}
