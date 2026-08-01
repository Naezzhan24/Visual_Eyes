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
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.drawerlayout.widget.DrawerLayout;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonArrayRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HomeActivity extends AppCompatActivity {

    private static final String PREFS_NAME            = "VisualEyesPrefs";
    private static final String KEY_LAST_OPENED_TITLE = "last_opened_title";
    private static final String KEY_LAST_OPENED_URL   = "last_opened_url";
    private static final float  MIN_FONT_SIZE = 14f;
    private static final float  MAX_FONT_SIZE = 34f;

    private TextView txtWelcome, txtSubtitle, txtAnnouncement, txtUpdateTitle;
    private TextView txtLearningMaterialSubtitle, txtVoiceStatus, txtRecognizedText, txtVoiceHint;
    private TextView txtCurrentFontSize;
    private SeekBar seekFontSize;
    private TextView txtFontSizeTitle, txtFontSizeSubtitle;
    private ImageView btnMenu, btnOpenLearningMaterial, btnHelp;
    private ImageView iconHome, iconMaterials, iconProfile;
    private TextView textHome, textMaterials, textProfile;
    private LinearLayout navHome, navMaterials, navProfile;
    private CardView cardAnnouncement, cardLearningMaterial, cardFontSizeControl, bottomNavCard;
    private View topBar;

    private DrawerLayout drawerLayout;
    private LinearLayout drawerMaterialsContainer;
    private ImageView btnCloseDrawer;
    private MaterialsDrawerController materialsDrawer;

    private GoogleTtsManager googleTts;
    private GoogleSttManager googleStt;
    private SpeechRecognizer speechRecognizer;
    private android.content.Intent speechIntent;

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

    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    updateVoiceStatus("Microphone enabled.");
                    if (!isTtsSpeaking) scheduleListening(400);
                } else {
                    updateVoiceStatus("Microphone permission denied.");
                }
            });

    private void checkMicPermission() {
        if (MicPermissionHelper.hasAudioPermission(this)) return;
        if (MicPermissionHelper.isPermanentlyDenied(this)) {
            updateVoiceStatus("Microphone access blocked. Enable it in Settings for voice commands.");
            return;
        }
        if (MicPermissionHelper.isScreenReaderActive(this)) {
            updateVoiceStatus("Microphone permission needed for voice commands.");
            return;
        }
        MicPermissionHelper.markRequested(this);
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        authManager  = new AuthManager(this);
        prefs        = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        googleTts    = new GoogleTtsManager(this);
        googleStt    = new GoogleSttManager(this);

        bindViews();
        setActiveNav("home");
        applyFontSize();
        setupWelcome();
        setupNavigation();
        setupMaterialCard();
        setupMenuButton();
        setupGestures();
        materialsDrawer = new MaterialsDrawerController(this, drawerLayout, drawerMaterialsContainer,
                btnMenu, btnCloseDrawer, this::stopListening);
        setupFontSizeSeekBar();
        initializeVoiceStatus();
        setupPressAnimations();
        animateHomeEntrance();
        buildSpeechIntent();
        initSpeechRecognizer();
        hybridSpeech = new HybridSpeechManager(this);
        hybridSpeech.initVosk(
                () -> Log.d("Home_STT", "Vosk model ready â now the primary listen engine."),
                () -> Log.e("Home_STT", "Vosk model failed to load â using raw SpeechRecognizer only."));

        cascadeSession = new SttCascadeSession(googleStt, hybridSpeech, handler, false);
        loadLatestMaterial();
        materialsDrawer.load(authManager.getStudentId());

        handler.postDelayed(() ->
                speak("Welcome to your home screen. " +
                        "Say help for available commands.", true), 900);
    }

    private void bindViews() {
        topBar                      = findViewById(R.id.topBarHome);
        btnMenu                     = findViewById(R.id.btnMenu);
        btnHelp                     = findViewById(R.id.btnHelp);
        btnOpenLearningMaterial     = findViewById(R.id.btnOpenLearningMaterial);
        txtWelcome                  = findViewById(R.id.txtWelcome);
        txtSubtitle                 = findViewById(R.id.txtSubtitle);
        txtAnnouncement             = findViewById(R.id.txtAnnouncement);
        txtUpdateTitle              = findViewById(R.id.textUpdateTitle);
        txtLearningMaterialSubtitle = findViewById(R.id.txtLearningMaterialSubtitle);
        txtVoiceStatus              = findViewById(R.id.txtVoiceStatus);
        txtRecognizedText           = findViewById(R.id.txtRecognizedText);
        txtVoiceHint                = findViewById(R.id.txtVoiceHint);
        txtCurrentFontSize          = findViewById(R.id.txtCurrentFontSize);
        seekFontSize                = findViewById(R.id.seekFontSize);
        txtFontSizeTitle            = findViewById(R.id.txtFontSizeTitle);
        txtFontSizeSubtitle         = findViewById(R.id.txtFontSizeSubtitle);
        navHome                     = findViewById(R.id.navHome);
        navMaterials                = findViewById(R.id.navMaterials);
        navProfile                  = findViewById(R.id.navProfile);
        iconHome                    = findViewById(R.id.iconHome);
        iconMaterials               = findViewById(R.id.iconMaterials);
        iconProfile                 = findViewById(R.id.iconProfile);
        textHome                    = findViewById(R.id.textHome);
        textMaterials               = findViewById(R.id.textMaterials);
        textProfile                 = findViewById(R.id.textProfile);
        cardAnnouncement            = findViewById(R.id.cardAnnouncement);
        cardLearningMaterial        = findViewById(R.id.cardLearningMaterial);
        cardFontSizeControl         = findViewById(R.id.cardFontSizeControl);
        bottomNavCard                = findViewById(R.id.bottomNavCard);
        drawerLayout                = findViewById(R.id.drawerLayout);
        drawerMaterialsContainer    = findViewById(R.id.drawerMaterialsContainer);
        btnCloseDrawer              = findViewById(R.id.btnCloseDrawer);
    }

    private void buildSpeechIntent() {
        speechIntent = new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
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
                isListening = true;
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
                        Log.e("Home_STT", "Built-in recognizer onError code=" + error);
                        if (SpeechEngineHealth.isRecognizerIncompatible(error)) {
                            Log.e("Home_STT", "Built-in recognizer is not usable on this device â "
                                    + "skipping it from now on.");
                            SpeechEngineHealth.markBuiltInRecognizerBroken(HomeActivity.this);
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
        if (isListening || isTtsSpeaking) return;
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
        if (isListening || isTtsSpeaking) return;
        isListening = true;

        cascadeSession.cascade(this, "command", null, new SttCascadeSession.Listener() {
            @Override public void onListeningStarted() {
                updateVoiceStatus("Listening...");
                updateRecognizedText("Waiting for speech...");
            }

            @Override public void onPartialResult(String partial) {
                updateRecognizedText("Hearing: " + partial);
                String norm = normalize(partial);
                if (isQuickCommand(norm)) {
                    isListening = false;
                    hybridSpeech.cancel();
                    handleCommand(partial.trim());
                }
            }

            @Override public void onTranscript(String transcript) {
                isListening = false;
                handleCommand(transcript);
            }

            @Override public void onExhausted() {
                isListening = false;
                if (!isTtsSpeaking) scheduleListening(1000);
            }
        });
    }

    private void startRawAndroidListening() {
        if (isListening || isTtsSpeaking || speechRecognizer == null) return;
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

        if (cmd.contains("announcement") || cmd.contains("read update")
                || cmd.contains("read notice")) {
            readAnnouncement();
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
                "Say read announcement to hear the instructor message. " +
                "Say recommended font to use the recommended text size. " +
                "Say default font to use the default text size. " +
                "Say repeat to hear my last message. " +
                "Say stop to stop speaking. " +
                "Say logout to sign out.", true);
    }

    private void readScreen() {
        String welcome      = txtWelcome      != null ? txtWelcome.getText().toString()      : "Welcome.";
        String subtitle     = txtSubtitle     != null ? txtSubtitle.getText().toString()     : "";
        String announcement = txtAnnouncement != null ? txtAnnouncement.getText().toString() : "No announcement.";
        String material     = txtUpdateTitle  != null ? txtUpdateTitle.getText().toString()  : "No material.";

        speak(welcome + ". " + subtitle + ". "
                + "Latest learning material: " + material + ". "
                + "Announcement: " + announcement + ". "
                + "Say help for commands.", true);
    }

    private void readAnnouncement() {
        String text = txtAnnouncement != null ? txtAnnouncement.getText().toString().trim() : "";
        if (text.isEmpty()
                || text.equalsIgnoreCase("Instructor announcement will appear here.")
                || text.equalsIgnoreCase("No announcement available yet.")) {
            speak("There is no instructor announcement available yet.", true);
        } else {
            speak("Instructor announcement. " + text, true);
        }
    }

    private void useRecommendedFont() {
        FontSizeManager.useRecommended(this);
        applyFontSize();
        Toast.makeText(this, "Recommended text size applied.", Toast.LENGTH_SHORT).show();
        speak("Recommended text size applied.", true);
    }

    private void useDefaultFont() {
        FontSizeManager.useDefault(this);
        applyFontSize();
        Toast.makeText(this, "Default text size applied.", Toast.LENGTH_SHORT).show();
        speak("Default text size applied.", true);
    }

    private void logoutUser() {
        try {
            stopListening();
            if (googleTts != null) googleTts.stopSpeaking();
            if (authManager != null) authManager.logout();
            Intent intent = new Intent(HomeActivity.this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Logout failed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openLatestMaterial() {
        if (latestFileUrl == null || latestFileUrl.trim().isEmpty()) {
            speak("No learning material available yet.", true);
            return;
        }
        saveLastOpened(latestTitle, latestFileUrl);
        MaterialReadTracker.markOpened(this, latestMaterialId);
        stopListening();
        Intent intent = new Intent(HomeActivity.this, AccessibleMaterialActivity.class);
        intent.putExtra("material_id",     latestMaterialId);
        intent.putExtra("file_url",        latestFileUrl);
        intent.putExtra("title",           latestTitle);
        intent.putExtra("impairment_level","moderate");
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void openMaterials() {
        stopListening();
        Intent intent = new Intent(HomeActivity.this, MaterialsActivity.class);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        finish();
    }

    private void openProfile() {
        stopListening();
        Intent intent = new Intent(HomeActivity.this, ProfileActivity.class);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        finish();
    }

    private void loadLatestMaterial() {
        String studentId = authManager.getStudentId();

        if (studentId == null || studentId.trim().isEmpty()) {
            latestMaterialId = ""; latestTitle = ""; latestFileUrl = "";
            if (txtUpdateTitle != null) txtUpdateTitle.setText("No learning material available yet");
            if (txtLearningMaterialSubtitle != null) txtLearningMaterialSubtitle.setText("Please log in again to view your materials.");
            return;
        }

        String url = ApiConfig.STUDENT_ACCESS
                + "?student_id=eq." + android.net.Uri.encode(studentId)
                + "&select=materials(id,title,file_path,upload_date,is_sent_to_app,admin_approval_status)"
                + "&materials.is_sent_to_app=eq.true"
                + "&materials.admin_approval_status=eq.approved";

        RequestQueue queue = Volley.newRequestQueue(this);
        JsonArrayRequest req = new JsonArrayRequest(Request.Method.GET, url, null,
                this::handleMaterialResponse,
                error -> {
                    latestMaterialId = ""; latestTitle = ""; latestFileUrl = "";
                    if (txtUpdateTitle != null) txtUpdateTitle.setText("Unable to load latest material");
                    if (txtLearningMaterialSubtitle != null) txtLearningMaterialSubtitle.setText("Check your connection and try again.");
                    updateVoiceStatus("Unable to load material.");
                }
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
        queue.add(req);
    }

    private void handleMaterialResponse(JSONArray rows) {
        try {
            if (rows == null || rows.length() == 0) {
                latestMaterialId = ""; latestTitle = ""; latestFileUrl = "";
                if (txtUpdateTitle              != null) txtUpdateTitle.setText("No learning material available yet");
                if (txtLearningMaterialSubtitle != null) txtLearningMaterialSubtitle.setText("Wait for your instructor or admin to send a lesson.");
                if (txtAnnouncement             != null) txtAnnouncement.setText("No announcement available yet.");
                return;
            }

            JSONObject latest = null;
            String latestDate = "";
            for (int i = 0; i < rows.length(); i++) {
                JSONObject obj = rows.getJSONObject(i).optJSONObject("materials");
                if (obj == null) continue;
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
                if (txtAnnouncement             != null) txtAnnouncement.setText("No announcement available yet.");
                return;
            }

            latestMaterialId     = latest.optString("id",    "");
            latestTitle          = latest.optString("title", "Learning Material");
            latestFileUrl        = buildFileUrl(latest.optString("file_path", ""));
            String announcement  = latest.optString("announcement", "").trim();

            if (txtAnnouncement             != null) txtAnnouncement.setText(!announcement.isEmpty() ? announcement : "No announcement available yet.");
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
        if (txtWelcome     != null) txtWelcome.setText("Welcome Back, " + name + "! ðð»");
        if (txtSubtitle    != null) txtSubtitle.setText("Ready to start your learning journey?");
        if (txtAnnouncement!= null) txtAnnouncement.setText("Instructor announcement will appear here.");
    }

    private void initializeVoiceStatus() {
        if (txtVoiceHint != null)
            txtVoiceHint.setText("Voice commands: Help, Read screen, Open latest material, " +
                    "Materials, Profile, Read announcement, Recommended font, Default font, Repeat, Stop, Logout.");
        updateVoiceStatus("Initializing...");
        updateRecognizedText("Waiting for speech...");
    }

    private void applyFontSize() {
        float b = FontSizeManager.getFontSize(this);

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
        if (txtAnnouncement             != null) txtAnnouncement.setTextSize(16f);
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
                FontSizeManager.saveRecommendedSize(HomeActivity.this, MIN_FONT_SIZE + progress);
                applyFontSize();
            }

            @Override public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                int size = Math.round(MIN_FONT_SIZE + sb.getProgress());
                Toast.makeText(HomeActivity.this, "Text size set to " + size + "sp.", Toast.LENGTH_SHORT).show();
                speak("Text size set to " + size + " S P.", true);
            }
        });
    }

    private void setupMenuButton() {

        if (btnMenu          != null) btnMenu.setOnClickListener(v -> { bounceView(btnMenu); materialsDrawer.open(); });
        if (btnHelp          != null) btnHelp.setOnClickListener(v -> { bounceView(btnHelp); openHelp(); });
        if (cardAnnouncement != null) cardAnnouncement.setOnClickListener(v -> { bounceView(cardAnnouncement); readAnnouncement(); });
    }

    private void openHelp() {
        startActivity(new Intent(this, HelpActivity.class));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void setupMaterialCard() {
        if (cardLearningMaterial    != null) cardLearningMaterial.setOnClickListener(v -> { bounceView(cardLearningMaterial); openLatestMaterial(); });
        if (btnOpenLearningMaterial != null) btnOpenLearningMaterial.setOnClickListener(v -> { bounceView(btnOpenLearningMaterial); openLatestMaterial(); });
    }

    private void setupNavigation() {
        if (navHome != null) navHome.setOnClickListener(v -> {
            setActiveNav("home");
            speak("You are already on the home screen.", true);
        });
        if (navMaterials != null) navMaterials.setOnClickListener(v -> {
            speak("Opening materials.", false);
            handler.postDelayed(this::openMaterials, 400);
        });
        if (navProfile != null) navProfile.setOnClickListener(v -> {
            speak("Opening profile.", false);
            handler.postDelayed(this::openProfile, 400);
        });
    }

    private void updateVoiceStatus(String s)   { runOnUiThread(() -> { if (txtVoiceStatus    != null) { txtVoiceStatus.setText("Voice: " + s); pulseView(txtVoiceStatus); } }); }
    private void updateRecognizedText(String s) { runOnUiThread(() -> { if (txtRecognizedText != null) txtRecognizedText.setText(s); }); }

    private void setupPressAnimations() {
        for (View v : new View[]{btnMenu, btnHelp, btnOpenLearningMaterial, cardAnnouncement,
                cardLearningMaterial}) {
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

    private void bounceView(View v) { if (v == null) return; v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(90).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start(); }
    private void pulseView(View v)  { if (v == null) return; v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start(); }

    private void setActiveNav(String tab) {
        if (iconHome == null || iconMaterials == null || iconProfile == null) return;
        int inactive = 0xFF8C4356, active = 0xFF2E0D18;
        iconHome.setColorFilter(inactive); iconMaterials.setColorFilter(inactive); iconProfile.setColorFilter(inactive);
        textHome.setTextColor(inactive);   textMaterials.setTextColor(inactive);   textProfile.setTextColor(inactive);
        if ("home".equals(tab))           { iconHome.setColorFilter(active);      textHome.setTextColor(active); }
        else if ("materials".equals(tab)) { iconMaterials.setColorFilter(active); textMaterials.setTextColor(active); }
        else if ("profile".equals(tab))   { iconProfile.setColorFilter(active);   textProfile.setTextColor(active); }
    }

    private void animateHomeEntrance() {

        if (topBar != null) { topBar.setAlpha(0f); topBar.setTranslationY(-35f); topBar.animate().alpha(1f).translationY(0f).setDuration(350).start(); }
        if (txtWelcome != null) { txtWelcome.setAlpha(0f); txtWelcome.setTranslationY(-40f); txtWelcome.animate().alpha(1f).translationY(0f).setStartDelay(90).setDuration(380).setInterpolator(new AccelerateDecelerateInterpolator()).start(); }
        if (txtSubtitle != null) { txtSubtitle.setAlpha(0f); txtSubtitle.setTranslationY(-30f); txtSubtitle.animate().alpha(1f).translationY(0f).setStartDelay(150).setDuration(360).start(); }
        if (cardFontSizeControl != null) { cardFontSizeControl.setAlpha(0f); cardFontSizeControl.setTranslationY(55f); cardFontSizeControl.animate().alpha(1f).translationY(0f).setStartDelay(210).setDuration(380).start(); }
        if (cardAnnouncement    != null) { cardAnnouncement.setAlpha(0f);    cardAnnouncement.setTranslationY(55f);    cardAnnouncement.animate().alpha(1f).translationY(0f).setStartDelay(300).setDuration(380).start(); }
        if (cardLearningMaterial!= null) { cardLearningMaterial.setAlpha(0f); cardLearningMaterial.setTranslationY(55f); cardLearningMaterial.animate().alpha(1f).translationY(0f).setStartDelay(390).setDuration(380).start(); }
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
        applyFontSize();
        loadLatestMaterial();
        if (!isTtsSpeaking) scheduleListening(600);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(restartListeningRunnable);
        stopListening();
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