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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MaterialsActivity extends AppCompatActivity {

    private TextView txtWelcome, txtFeaturedTitle, txtVoiceStatus, txtRecognizedText;
    private TextView textHome, textMaterials, textProfile;
    private ImageView iconHome, iconMaterials, iconProfile;
    private CardView featuredCard, cardVoiceStatus, cardRecentList;
    private ImageView btnFeaturedOpen;
    private LinearLayout recentMaterialsContainer, navHome, navMaterials, navProfile;
    private SwipeRefreshLayout swipeRefreshMaterials;
    private View topBar;
    private ImageView btnMenu;

    private DrawerLayout drawerLayout;
    private LinearLayout drawerMaterialsContainer;
    private ImageView btnCloseDrawer;
    private MaterialsDrawerController materialsDrawer;

    private final ArrayList<LearningMaterial> materialList = new ArrayList<>();

    private GoogleTtsManager googleTts;
    private GoogleSttManager googleStt;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;

    private HybridSpeechManager hybridSpeech;
    private SttCascadeSession   cascadeSession;

    private boolean isListening        = false;
    private boolean isTtsSpeaking      = false;
    private boolean isConfirmingOpen   = false;
    private LearningMaterial pendingMaterial = null;
    private String  lastMessage        = "Materials screen. Your accessible learning materials are shown here.";

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

    private final Runnable restartListeningRunnable = () -> {
        if (!isListening && !isTtsSpeaking && !isFinishing() && !isDestroyed()) {
            startListening();
        }
    };

    private SharedPreferences prefs;
    private static final String PREFS_NAME            = "VisualEyesPrefs";
    private static final String KEY_LAST_OPENED_TITLE = "last_opened_title";
    private static final String KEY_LAST_OPENED_URL   = "last_opened_url";

    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                micPermissionRequestInFlight = false;
                lastKnownMicPermission = granted;
                if (granted) {
                    initSpeechRecognizer();
                    updateVoiceStatus("Microphone enabled.");
                    handler.postDelayed(() ->
                            speak("Materials screen. Say help for available commands.", true), 400);
                } else {
                    updateVoiceStatus("Microphone permission denied.");
                    Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_materials);

        prefs     = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        googleTts = new GoogleTtsManager(this);
        googleStt = new GoogleSttManager(this);
        lastKnownMicPermission = MicPermissionHelper.hasAudioPermission(this);
        hybridSpeech = new HybridSpeechManager(this);
        hybridSpeech.initVosk(
                () -> Log.d("Materials_STT", "Vosk model ready — now the primary listen engine."),
                () -> Log.e("Materials_STT", "Vosk model failed to load — using raw SpeechRecognizer only."));

        cascadeSession = new SttCascadeSession(googleStt, hybridSpeech, handler, false);

        bindViews();
        applyFontSize();
        setActiveNav("materials");
        setupBottomNav();
        setupPressAnimations();
        setupGestures();
        setupMenuButton();
        materialsDrawer = new MaterialsDrawerController(this, drawerLayout, drawerMaterialsContainer,
                btnMenu, btnCloseDrawer, this::stopListening, text -> speak(text, false));
        animateMaterialsEntrance();
        buildSpeechIntent();
        loadMaterials();
        checkMicPermission();

        if (swipeRefreshMaterials != null) {
            swipeRefreshMaterials.setColorSchemeColors(0xFF8C4356);
            swipeRefreshMaterials.setOnRefreshListener(() -> {
                loadMaterials();
                if (materialsDrawer != null) {
                    AuthManager auth = new AuthManager(this);
                    materialsDrawer.load(auth.getSessionToken());
                }
            });
        }
    }

    private void bindViews() {
        topBar                   = findViewById(R.id.topBarMaterials);
        btnMenu                  = findViewById(R.id.btnMenu);
        txtWelcome               = findViewById(R.id.txtWelcome);
        txtFeaturedTitle         = findViewById(R.id.txtFeaturedTitle);
        txtVoiceStatus           = findViewById(R.id.txtVoiceStatus);
        txtRecognizedText        = findViewById(R.id.txtRecognizedText);
        featuredCard             = findViewById(R.id.featuredCard);
        cardVoiceStatus          = findViewById(R.id.cardVoiceStatus);
        cardRecentList           = findViewById(R.id.cardRecentList);
        swipeRefreshMaterials    = findViewById(R.id.swipeRefreshMaterials);
        btnFeaturedOpen          = findViewById(R.id.btnFeaturedOpen);
        recentMaterialsContainer = findViewById(R.id.recentMaterialsContainer);
        navHome                  = findViewById(R.id.navHome);
        navMaterials             = findViewById(R.id.navMaterials);
        navProfile               = findViewById(R.id.navProfile);
        iconHome                 = findViewById(R.id.iconHome);
        iconMaterials            = findViewById(R.id.iconMaterials);
        iconProfile              = findViewById(R.id.iconProfile);
        textHome                 = findViewById(R.id.textHome);
        textMaterials            = findViewById(R.id.textMaterials);
        textProfile              = findViewById(R.id.textProfile);
        drawerLayout             = findViewById(R.id.drawerLayout);
        drawerMaterialsContainer = findViewById(R.id.drawerMaterialsContainer);
        btnCloseDrawer           = findViewById(R.id.btnCloseDrawer);
    }

    private void setupMenuButton() {
        if (btnMenu != null) btnMenu.setOnClickListener(v -> { bounceView(btnMenu); materialsDrawer.open(); });
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

    private void checkMicPermission() {
        if (MicPermissionHelper.hasAudioPermission(this)) {
            initSpeechRecognizer();
            handler.postDelayed(() ->
                    speak("Materials screen. Say help for available commands.", true), 900);
            return;
        }
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
        stopListening();
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
        stopListening();
        googleTts.speak("Microphone access was previously denied and can't be requested again here. " +
                "Opening app settings so you can enable it under Permissions.", () -> {
            isTtsSpeaking = false;
            MicPermissionHelper.openAppSettings(this);
        });
    }

    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateVoiceStatus("Speech recognition not available.");
            return;
        }
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (Exception ignored) {}
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
                updateVoiceStatus("Processing...");
            }

            @Override public void onError(int error) {
                isListening = false;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        updateVoiceStatus("No speech detected.");
                        if (!isTtsSpeaking) scheduleListening(1000);
                        return;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        handler.postDelayed(() -> {
                            initSpeechRecognizer();
                            scheduleListening(800);
                        }, 400);
                        return;
                    default:
                        Log.e("Materials_STT", "Built-in recognizer onError code=" + error);
                        if (SpeechEngineHealth.isRecognizerIncompatible(error)) {
                            Log.e("Materials_STT", "Built-in recognizer is not usable on this device — "
                                    + "skipping it from now on.");
                            SpeechEngineHealth.markBuiltInRecognizerBroken(MaterialsActivity.this);
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
                }
            }

            @Override public void onEvent(int e, Bundle p) {}
        });
    }

    private void startListening() {
        if (isListening || isTtsSpeaking) return;
        if (!MicPermissionHelper.hasAudioPermission(this)) return;
        if (SpeechEngineHealth.isBuiltInRecognizerBroken(this)) {
            cascadeFromBuiltIn();
            return;
        }
        startRawAndroidListening();
    }

    private void cascadeFromBuiltIn() {
        if (isListening || isTtsSpeaking) return;
        isListening = true;
        final int mySession = voiceSessionId;

        cascadeSession.cascade(this, "command", null, new SttCascadeSession.Listener() {
            @Override public void onListeningStarted() {
                if (mySession != voiceSessionId) return;
                updateVoiceStatus("Listening...");
                updateRecognizedText("Waiting for speech...");
            }

            @Override public void onPartialResult(String partial) {
                if (mySession != voiceSessionId) return;
                updateRecognizedText("Hearing: " + partial);
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
        if (isListening || isTtsSpeaking || speechRecognizer == null) return;
        if (!MicPermissionHelper.hasAudioPermission(this)) return;
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

        if (isConfirmingOpen && pendingMaterial != null) {
            if (isYes(cmd)) {
                LearningMaterial toOpen = pendingMaterial;
                isConfirmingOpen = false;
                pendingMaterial  = null;
                speak("Opening " + toOpen.getTitle() + ".", false);
                handler.postDelayed(() -> openMaterial(toOpen), 500);
            } else if (isNo(cmd)) {
                isConfirmingOpen = false;
                pendingMaterial  = null;
                speak("Okay, cancelled. Say a material title or say read titles to hear what is available.", true);
            } else {
                speak("Please say yes to open or no to cancel.", true);
            }
            return;
        }

        if (cmd.contains("help") || cmd.contains("command") || cmd.contains("what can")) {
            speakHelp();
            return;
        }

        if (cmd.equals("repeat") || cmd.contains("repeat that") || cmd.contains("say again")) {
            speak(lastMessage, true);
            return;
        }

        if (cmd.equals("stop") || cmd.equals("cancel") || cmd.contains("stop speaking")) {
            if (googleTts != null) googleTts.stopSpeaking();
            isTtsSpeaking = false;
            updateVoiceStatus("Stopped.");
            scheduleListening(600);
            return;
        }

        if (cmd.contains("read screen") || cmd.contains("describe") || cmd.contains("read page")
                || cmd.contains("what is on")) {
            readScreen();
            return;
        }

        if (cmd.contains("read title") || cmd.contains("list material")
                || cmd.contains("read all") || cmd.contains("what material")
                || cmd.contains("show material")) {
            readTitles();
            return;
        }

        if (cmd.contains("read featured") || cmd.contains("featured title")
                || cmd.contains("latest title")) {
            readFeaturedTitle();
            return;
        }

        if (cmd.contains("open featured") || cmd.contains("open latest")
                || cmd.equals("featured") || cmd.equals("latest")) {
            openFeaturedByVoice();
            return;
        }

        if (cmd.equals("home") || cmd.contains("go home") || cmd.contains("open home")) {
            speak("Opening home.", false);
            handler.postDelayed(this::goHome, 500);
            return;
        }

        if (cmd.equals("profile") || cmd.contains("open profile") || cmd.contains("go profile")) {
            speak("Opening profile.", false);
            handler.postDelayed(this::goProfile, 500);
            return;
        }

        if (cmd.equals("materials") || cmd.contains("materials screen")) {
            speak("You are already on the materials screen.", true);
            return;
        }

        if (cmd.contains("first")  || cmd.equals("1")) { openByIndex(0); return; }
        if (cmd.contains("second") || cmd.equals("2")) { openByIndex(1); return; }
        if (cmd.contains("third")  || cmd.equals("3")) { openByIndex(2); return; }

        if (cmd.startsWith("open") || cmd.contains("open ")) {
            openBySpokenTitle(spoken);
            return;
        }

        LearningMaterial titleMatch = findBestMatch(cmd);
        if (titleMatch != null) {
            confirmOpen(titleMatch);
            return;
        }

        speak("Command not recognized. Say help for available commands.", true);
    }

    private void confirmOpen(LearningMaterial material) {
        isConfirmingOpen = true;
        pendingMaterial  = material;
        speak("Did you mean " + material.getTitle()
                + "? Say yes to open or no to cancel.", true);
    }

    private void openByIndex(int index) {
        if (materialList.isEmpty()) {
            speak("There are no materials available right now.", true);
            return;
        }
        if (index >= materialList.size()) {
            speak("There is no material number " + (index + 1) + ".", true);
            return;
        }
        confirmOpen(materialList.get(index));
    }

    private boolean isYes(String cmd) {
        return cmd.equals("yes") || cmd.equals("yeah") || cmd.equals("yep")
                || cmd.equals("yup") || cmd.equals("correct") || cmd.equals("oo")
                || cmd.contains("yes") || cmd.contains("open it");
    }

    private boolean isNo(String cmd) {
        return cmd.equals("no") || cmd.equals("nope") || cmd.equals("nah")
                || cmd.equals("cancel") || cmd.equals("hindi")
                || cmd.contains("not that") || cmd.startsWith("no ");
    }

    private void speakHelp() {
        speak("Available commands. " +
                "Say read screen to hear this page. " +
                "Say read titles to hear all material titles. " +
                "Say open followed by the material title to open a lesson. " +
                "Say open first, open second, or open third to open by number. " +
                "Say open featured to open the featured material. " +
                "Say home to go to the home screen. " +
                "Say profile to open your profile. " +
                "Say repeat to hear my last message. " +
                "Say stop to stop speaking.", true);
    }

    private void readScreen() {
        String featured = txtFeaturedTitle != null
                ? txtFeaturedTitle.getText().toString() : "No featured material.";
        StringBuilder sb = new StringBuilder("You are on the materials screen. ");
        if (materialList.isEmpty()) {
            sb.append("There are no learning materials available right now.");
        } else {
            sb.append("You have ").append(materialList.size()).append(" learning material");
            if (materialList.size() > 1) sb.append("s");
            sb.append(". Featured material is ").append(featured)
                    .append(". Say read titles to hear all materials, "
                            + "or say open followed by the title to open a lesson.");
        }
        speak(sb.toString(), true);
    }

    private void readTitles() {
        if (materialList.isEmpty()) {
            speak("There are no learning materials available right now.", true);
            return;
        }
        StringBuilder sb = new StringBuilder("Here are your learning materials. ");
        for (int i = 0; i < materialList.size(); i++) {
            sb.append("Material ").append(i + 1).append(", ")
                    .append(materialList.get(i).getTitle()).append(". ");
        }
        sb.append("Say open followed by the title, or say open first, second, or third.");
        speak(sb.toString(), true);
    }

    private void readFeaturedTitle() {
        if (materialList.isEmpty()) {
            speak("There is no featured learning material right now.", true);
            return;
        }
        LearningMaterial featured = getLastOpened();
        if (featured == null) featured = materialList.get(0);
        speak("Your featured learning material is " + featured.getTitle()
                + ". Say open featured to open it.", true);
    }

    private void openFeaturedByVoice() {
        if (materialList.isEmpty()) {
            speak("There is no learning material available right now.", true);
            return;
        }
        LearningMaterial featured = getLastOpened();
        if (featured == null) featured = materialList.get(0);
        confirmOpen(featured);
    }

    private void openBySpokenTitle(String spoken) {
        if (materialList.isEmpty()) {
            speak("There are no learning materials available right now.", true);
            return;
        }

        String target = normalize(spoken)
                .replace("open", "").replace("the", "").replace("material", "")
                .replace("lesson", "").replace("learning", "").replace("file", "")
                .trim();

        if (target.isEmpty()) {
            speak("Please say open followed by the material title.", true);
            return;
        }

        LearningMaterial best = findBestMatch(target);
        if (best != null) {
            confirmOpen(best);
        } else {
            speak("I could not find that material. Say read titles to hear what is available.", true);
        }
    }

    private LearningMaterial findBestMatch(String query) {
        if (query == null || query.trim().isEmpty() || materialList.isEmpty()) return null;
        LearningMaterial best  = null;
        int              score = 0;
        for (LearningMaterial m : materialList) {
            int s = matchScore(query, normalize(m.getTitle()));
            if (s > score) { score = s; best = m; }
        }
        return (best != null && score >= 1) ? best : null;
    }

    private int matchScore(String target, String title) {
        int score = 0;
        for (String word : target.split(" ")) {
            if (word.length() >= 3 && title.contains(word)) score += 2;
        }
        if (title.contains(target)) score += 5;

        if (title.startsWith(target)) score += 3;
        return score;
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

    private String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.US).trim()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private void loadMaterials() {
        AuthManager authManager   = new AuthManager(this);
        String sessionToken       = authManager.getSessionToken();

        if (sessionToken == null || sessionToken.trim().isEmpty()) {
            if (txtFeaturedTitle != null) txtFeaturedTitle.setText("No learning material yet");
            if (txtWelcome != null) txtWelcome.setText("Please log in again to view your materials.");
            applyFontSize();
            if (swipeRefreshMaterials != null) swipeRefreshMaterials.setRefreshing(false);
            return;
        }

        // Calls the get_student_materials RPC instead of directly querying
        // student_material_access with a client-supplied student_id filter —
        // that filter could be bypassed or changed by anyone with the anon
        // key, since the database itself never verified who was asking. The
        // RPC re-verifies email+password server-side and only returns
        // materials for classes this student is actually enrolled in.
        String url = ApiConfig.SUPABASE_URL + "/rest/v1/rpc/get_student_materials";

        JSONObject rpcBody = new JSONObject();
        String bodyStr;
        try {
            rpcBody.put("p_session_token", sessionToken);
            bodyStr = rpcBody.toString();
        } catch (Exception e) {
            if (txtFeaturedTitle != null) txtFeaturedTitle.setText("Unable to read materials");
            applyFontSize();
            if (swipeRefreshMaterials != null) swipeRefreshMaterials.setRefreshing(false);
            return;
        }
        final String finalBodyStr = bodyStr;

        RequestQueue queue = VolleySingleton.getInstance(this).getRequestQueue();
        JsonArrayRequest req = new JsonArrayRequest(Request.Method.POST, url, null,
                response -> {
                    materialList.clear();
                    recentMaterialsContainer.removeAllViews();
                    try {
                        if (response == null || response.length() == 0) {
                            if (txtFeaturedTitle != null) txtFeaturedTitle.setText("No learning material yet");
                            if (txtWelcome != null) txtWelcome.setText("No materials received yet.");
                            applyFontSize();
                            return;
                        }
                        if (txtWelcome != null) txtWelcome.setText("Welcome to your accessible learning materials.");
                        for (int i = 0; i < response.length(); i++) {
                            JSONObject obj = response.getJSONObject(i);
                            materialList.add(new LearningMaterial(
                                    obj.optString("id",          ""),
                                    obj.optString("title",       "Untitled Material"),
                                    "English",
                                    buildFileUrl(obj.optString("file_path", "")),
                                    obj.optString("upload_date", "No Date")
                            ));
                        }
                        bindFeatured();
                        bindRecentList();
                        applyFontSize();
                        animateMaterialRows();
                        if (materialsDrawer != null) materialsDrawer.showFromList(materialList);
                    } catch (Exception e) {
                        if (txtFeaturedTitle != null) txtFeaturedTitle.setText("Unable to read materials");
                        updateVoiceStatus("Parsing error.");
                        applyFontSize();
                    } finally {
                        if (swipeRefreshMaterials != null) swipeRefreshMaterials.setRefreshing(false);
                    }
                },
                error -> {
                    if (SessionManager.isSessionExpiredError(error)) {
                        SessionManager.forceLogoutAndRedirect(this);
                        return;
                    }
                    if (txtFeaturedTitle != null) txtFeaturedTitle.setText("Connection failed");
                    if (txtWelcome != null) txtWelcome.setText("Unable to load learning materials.");
                    updateVoiceStatus("Connection failed.");
                    applyFontSize();
                    if (swipeRefreshMaterials != null) swipeRefreshMaterials.setRefreshing(false);
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
        queue.add(req);
    }

    private String buildFileUrl(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return "";
        filePath = filePath.trim().replace("\\", "/");
        if (filePath.startsWith("http://") || filePath.startsWith("https://"))
            return filePath.replace(" ", "%20");
        while (filePath.startsWith("/")) filePath = filePath.substring(1);
        if (filePath.startsWith("materials/")) filePath = filePath.substring("materials/".length());
        return ApiConfig.SUPABASE_URL + "/storage/v1/object/public/materials/"
                + filePath.replace(" ", "%20");
    }

    private void bindFeatured() {
        if (materialList.isEmpty()) return;
        LearningMaterial featured = getLastOpened();
        if (featured == null) featured = materialList.get(0);
        if (txtFeaturedTitle != null) txtFeaturedTitle.setText(featured.getTitle());
        LearningMaterial finalFeatured = featured;
        View.OnClickListener listener = v -> {
            bounceView(featuredCard);
            handler.postDelayed(() -> openMaterial(finalFeatured), 160);
        };
        if (featuredCard    != null) featuredCard.setOnClickListener(listener);
        if (btnFeaturedOpen != null) btnFeaturedOpen.setOnClickListener(listener);
    }

    private void bindRecentList() {
        recentMaterialsContainer.removeAllViews();
        for (LearningMaterial m : materialList) {
            recentMaterialsContainer.addView(createMaterialRow(m));
        }
    }

    private View createMaterialRow(LearningMaterial material) {
        float baseSize = FontSizeManager.getFontSize(this);

        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(72)));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dpToPx(16), 0, dpToPx(16), 0);
        row.setClickable(true);
        row.setFocusable(true);

        ImageView icon = new ImageView(this);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)));
        icon.setImageResource(android.R.drawable.ic_menu_edit);
        icon.setColorFilter(0xFFFFFFFF);

        TextView title = new TextView(this);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(dpToPx(12));
        title.setLayoutParams(tp);
        title.setText(material.getTitle());
        title.setTextSize(baseSize - 2);
        title.setTextColor(0xFFFFFFFF);

        ImageView arrow = new ImageView(this);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(18), dpToPx(18)));
        arrow.setImageResource(android.R.drawable.ic_media_next);
        arrow.setColorFilter(0xFFFFFFFF);

        View.OnClickListener listener = v -> {
            bounceView(row);
            handler.postDelayed(() -> openMaterial(material), 160);
        };
        row.setOnClickListener(listener);
        arrow.setOnClickListener(listener);

        row.addView(icon);
        row.addView(title);
        row.addView(arrow);
        return row;
    }

    private void openMaterial(LearningMaterial material) {
        if (material == null) { Toast.makeText(this, "Material not found.", Toast.LENGTH_SHORT).show(); return; }
        if (material.getFileUrl() == null || material.getFileUrl().trim().isEmpty()) {
            Toast.makeText(this, "PDF URL not found.", Toast.LENGTH_LONG).show(); return;
        }
        saveLastOpened(material.getTitle(), material.getFileUrl());
        MaterialReadTracker.markOpened(this, material.getId());
        stopListening();
        if (googleTts != null) googleTts.stopSpeaking();
        updateVoiceStatus("Opening material...");

        // Requests a short-lived signed URL before opening — the materials
        // bucket is private now, so the permanently-stored public URL no
        // longer works by itself.
        SignedUrlHelper.resolve(this, material.getFileUrl(), new SignedUrlHelper.Callback() {
            @Override public void onSignedUrl(String signedUrl) {
                Intent intent = new Intent(MaterialsActivity.this, AccessibleMaterialActivity.class);
                intent.putExtra("material_id",     material.getId());
                intent.putExtra("file_url",        signedUrl);
                intent.putExtra("title",           material.getTitle());
                intent.putExtra("impairment_level","moderate");
                startActivity(intent);
                overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
            }

            @Override public void onError() {
                updateVoiceStatus("Unable to open material.");
                Toast.makeText(MaterialsActivity.this, "Unable to open material. Please try again.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private LearningMaterial getLastOpened() {
        String title = prefs.getString(KEY_LAST_OPENED_TITLE, null);
        String url   = prefs.getString(KEY_LAST_OPENED_URL,   null);
        if (title == null || url == null) return null;
        for (LearningMaterial m : materialList) {
            if (title.equals(m.getTitle()) && url.equals(m.getFileUrl())) return m;
        }
        return null;
    }

    private void saveLastOpened(String title, String url) {
        prefs.edit().putString(KEY_LAST_OPENED_TITLE, title)
                .putString(KEY_LAST_OPENED_URL,   url).apply();
    }

    private void goHome() {
        stopListening();
        startNavTransition(new Intent(this, HomeActivity.class), false);
    }

    private void goProfile() {
        stopListening();
        startNavTransition(new Intent(this, ProfileActivity.class), true);
    }

    // Simple slide in/out — Materials sits between Home and Profile, so
    // direction depends on which neighbor we're headed to.
    private void startNavTransition(Intent intent, boolean forward) {
        startActivity(intent);
        overridePendingTransition(forward ? R.anim.slide_in_right : R.anim.slide_in_left,
                                   forward ? R.anim.slide_out_left : R.anim.slide_out_right);
        finish();
    }

    private void setupBottomNav() {
        if (navHome != null) navHome.setOnClickListener(v -> {
            if (isNavPending) return;
            isNavPending = true;
            speak("Opening home.", false);
            handler.postDelayed(this::goHome, 400);
        });
        if (navMaterials != null) navMaterials.setOnClickListener(v -> {
            setActiveNav("materials");
            speak("You are currently on the materials screen.", true);
        });
        if (navProfile != null) navProfile.setOnClickListener(v -> {
            if (isNavPending) return;
            isNavPending = true;
            speak("Opening profile.", false);
            handler.postDelayed(this::goProfile, 400);
        });
    }

    private void applyFontSize() {
        float b = FontSizeManager.getFontSize(this);
        if (txtWelcome        != null) txtWelcome.setTextSize(b + 5);
        if (txtFeaturedTitle  != null) txtFeaturedTitle.setTextSize(b + 1);
        if (txtVoiceStatus    != null) txtVoiceStatus.setTextSize(b - 2);
        if (txtRecognizedText != null) txtRecognizedText.setTextSize(b - 2);
        if (textHome          != null) textHome.setTextSize(b - 4);
        if (textMaterials     != null) textMaterials.setTextSize(b - 4);
        if (textProfile       != null) textProfile.setTextSize(b - 4);
    }

    private void setActiveNav(String tab) {
        if (iconHome == null || iconMaterials == null || iconProfile == null) return;
        int inactive = 0xFF8C4356, active = 0xFF2E0D18;
        iconHome.setColorFilter(inactive); iconMaterials.setColorFilter(inactive); iconProfile.setColorFilter(inactive);
        textHome.setTextColor(inactive);   textMaterials.setTextColor(inactive);   textProfile.setTextColor(inactive);
        if ("home".equals(tab))           { iconHome.setColorFilter(active);      textHome.setTextColor(active); }
        else if ("materials".equals(tab)) { iconMaterials.setColorFilter(active); textMaterials.setTextColor(active); }
        else if ("profile".equals(tab))   { iconProfile.setColorFilter(active);   textProfile.setTextColor(active); }
    }

    private void updateVoiceStatus(String text)   { runOnUiThread(() -> { if (txtVoiceStatus    != null) { txtVoiceStatus.setText(text);    pulseView(txtVoiceStatus); } }); }
    private void updateRecognizedText(String text) { runOnUiThread(() -> { if (txtRecognizedText != null) txtRecognizedText.setText(text); }); }

    private void setupPressAnimations() {
        for (View v : new View[]{btnMenu, featuredCard, btnFeaturedOpen}) {
            if (v == null) continue;
            v.setOnTouchListener((view, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:   view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start(); break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: view.animate().scaleX(1f).scaleY(1f).setDuration(90).start();       break;
                }
                return false;
            });
        }
    }

    private void bounceView(View v)      { if (v == null) return; v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(90).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start(); }
    private void pulseView(View v)       { if (v == null) return; v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start(); }

    private void animateMaterialsEntrance() {

        if (topBar != null) { topBar.setAlpha(0f); topBar.setTranslationY(-35f); topBar.animate().alpha(1f).translationY(0f).setDuration(350).start(); }
        if (txtWelcome != null) { txtWelcome.setAlpha(0f); txtWelcome.setTranslationY(-40f); txtWelcome.animate().alpha(1f).translationY(0f).setStartDelay(100).setDuration(380).setInterpolator(new AccelerateDecelerateInterpolator()).start(); }
        if (cardVoiceStatus != null) { cardVoiceStatus.setAlpha(0f); cardVoiceStatus.setTranslationY(55f); cardVoiceStatus.animate().alpha(1f).translationY(0f).setStartDelay(180).setDuration(380).start(); }
        if (featuredCard != null) { featuredCard.setAlpha(0f); featuredCard.setTranslationY(60f); featuredCard.animate().alpha(1f).translationY(0f).setStartDelay(280).setDuration(420).start(); }
        if (cardRecentList != null) { cardRecentList.setAlpha(0f); cardRecentList.setTranslationY(55f); cardRecentList.animate().alpha(1f).translationY(0f).setStartDelay(380).setDuration(380).start(); }
    }

    private void animateMaterialRows() {
        int count = recentMaterialsContainer.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = recentMaterialsContainer.getChildAt(i);
            child.setAlpha(0f); child.setTranslationY(45f);
            child.animate().alpha(1f).translationY(0f).setStartDelay(i * 80L).setDuration(320).start();
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
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
        if (lastMessage == null || lastMessage.trim().isEmpty()) return;
        vibrateShort();
        speak(lastMessage, true);
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
        setActiveNav("materials");
        applyFontSize();
        loadMaterials();

        boolean nowGranted = MicPermissionHelper.hasAudioPermission(this);
        if (nowGranted && !lastKnownMicPermission && !micPermissionRequestInFlight) {
            updateVoiceStatus("Microphone enabled.");
            initSpeechRecognizer();
        }
        lastKnownMicPermission = nowGranted;

        if (!isTtsSpeaking) scheduleListening(600);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(restartListeningRunnable);
        stopListening();
        // Navigating to another screen (e.g. opening a material) leaves this
        // activity paused, not destroyed — its TTS would otherwise keep
        // talking in the background and overlap with the next screen's voice.
        if (googleTts != null) googleTts.stopSpeaking();
        updateVoiceStatus("Paused.");
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopListening();
        try { if (speechRecognizer != null) { speechRecognizer.cancel(); speechRecognizer.destroy(); } } catch (Exception ignored) {}
        if (hybridSpeech != null) hybridSpeech.destroy();
        if (googleStt != null) googleStt.destroy();
        if (googleTts != null) googleTts.destroy();
        super.onDestroy();
    }
}