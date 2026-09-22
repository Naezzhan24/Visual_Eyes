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
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class TextSizeTestActivity extends AppCompatActivity {

    private TextView txtStep, txtInstruction, txtWord, txtStatus;
    private Button btnStart, btnYes, btnNo, btnSubmitWord;
    private android.widget.LinearLayout layoutTypedWord;
    private android.widget.EditText edtTypedWord;

    private GoogleTtsManager    googleTts;
    private GoogleSttManager    googleStt;
    private HybridSpeechManager hybridSpeech;
    private SttCascadeSession   cascadeSession;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private int voiceSessionId = 0;

    private boolean isTtsSpeaking      = false;
    private boolean testStarted        = false;
    private boolean waitingForAnswer   = false;
    private boolean testFinished       = false;
    private boolean answerHandled      = false;
    private boolean isRetakeAssessment = false;

    private boolean isRecognizerListening = false;

    private boolean isReadingWord            = false;
    private long    readAloudDeadlineElapsed = 0L;
    private int     readAloudAttemptId       = 0;

    // True while the typed-word field is showing, waiting for the student to
    // type back the word they read — the button-triggered "Yes" answer's
    // verification step, in place of the voice-triggered "Yes" answer's
    // read-aloud verification (beginReadAloudPhase()).
    private boolean isTypingWord             = false;

    private final String[][] wordTiers = {
            {"cat", "dog", "sun"},
            {"apple", "chair", "green"},
            {"garden", "pencil", "window"},
            {"bicycle", "hospital", "elephant"},
            {"beautiful", "important", "wonderful"},
    };
    private final String[][] retakeWordTiers = {
            {"cup", "pen", "red"},
            {"table", "paper", "happy"},
            {"teacher", "student", "library"},
            {"computer", "notebook", "mountain"},
            {"chocolate", "adventure", "celebration"},
    };
    private String[][] activeWordTiers;
    private String[]   assignedWords;
    private final Random wordRandom = new Random();

    private final float[] textSizes = {18f, 24f, 30f, 36f, 42f};

    private int currentSizeIndex = 0;
    private int yesCount  = 0;
    private int noCount   = 0;
    private int retryCount = 0;
    private int micReconnectAttempts = 0;

    private final int[] readablePerSize = new int[5];
    // Per word (same index as assignedWords / textSizes): true if the student actually
    // read it — a "yes" that was then confirmed by reading it aloud or typing it. Passed
    // to AssessmentResultActivity so the result screen can list every word and its outcome.
    private final boolean[] wordRead = new boolean[5];

    private static final int MAX_RETRY            = 3;
    // If the built-in recognizer keeps erroring out this many times in a row,
    // stop retrying it and fall back to the Cloud STT/Vosk cascade instead —
    // without this cap, a persistently broken built-in recognizer (not just a
    // transient busy state) retried itself forever, leaving the user stuck on
    // "Reconnecting microphone…" and never actually heard.
    private static final int MAX_MIC_RECONNECT_ATTEMPTS = 2;

    private static final long NEXT_ITEM_DELAY    = 550L;
    private static final long ASK_DELAY          = 900L;

    private static final long RECOGNIZER_REBUILD_DELAY = 400L;
    // Matches the reinit-settle + retry pacing standardized across every
    // mic-using screen (400ms to tear down/recreate, 600ms before retry).
    private static final long MIC_BUSY_RETRY_DELAY_MS  = 600L;

    private static final long READ_ALOUD_TIMEOUT_MS        = 15000L;
    private static final long READ_ALOUD_ATTEMPT_TIMEOUT_MS = 6000L;

    // Spoken whenever voice input isn't working and the user needs to fall
    // back to tapping — matches the blue/yellow button colors in
    // activity_text_size_test.xml, a strong, distinct contrast pair that
    // still reads apart for most forms of color vision deficiency.
    private static final String YES_NO_BUTTON_COLOR_HINT =
            "The blue button is Yes, and the yellow button is No.";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_size_test);

        txtStep        = findViewById(R.id.txtStep);
        txtInstruction = findViewById(R.id.txtInstruction);
        txtWord        = findViewById(R.id.txtWord);
        txtStatus      = findViewById(R.id.txtStatus);
        btnStart       = findViewById(R.id.btnStart);
        btnYes         = findViewById(R.id.btnYes);
        btnNo          = findViewById(R.id.btnNo);
        layoutTypedWord= findViewById(R.id.layoutTypedWord);
        edtTypedWord   = findViewById(R.id.edtTypedWord);
        btnSubmitWord  = findViewById(R.id.btnSubmitWord);

        btnYes.setEnabled(false);
        btnNo.setEnabled(false);

        isRetakeAssessment = getIntent().getBooleanExtra("isRetakeAssessment", false);
        activeWordTiers    = isRetakeAssessment ? retakeWordTiers : wordTiers;
        assignedWords      = pickWordsFromTiers(activeWordTiers);

        // respectVoicePreferences=false + built-in-recognizer-first with a Cloud
        // STT -> Vosk cascade fallback: same accuracy stack as Register/Feedback,
        // now including an offline (Vosk) tier this screen never had before.
        googleTts    = new GoogleTtsManager(this, false);
        googleStt    = new GoogleSttManager(this, false);
        hybridSpeech = new HybridSpeechManager(this, false);
        hybridSpeech.initVosk(
                () -> Log.d("Assessment_STT", "Vosk model ready — offline fallback available."),
                () -> Log.e("Assessment_STT", "Vosk model failed to load — Cloud STT/raw recognizer only."));
        cascadeSession = new SttCascadeSession(googleStt, hybridSpeech, handler, true);
        buildSpeechIntent();

        showCurrentItem(false);

        btnStart.setOnClickListener(v -> {
            if (!testStarted) {
                if (!hasAudioPermission()) requestAudioPermission();
                else startAssessment();
            }
        });

        btnYes.setOnClickListener(v -> handleManualAnswer(true));
        btnNo.setOnClickListener(v  -> handleManualAnswer(false));

        btnSubmitWord.setOnClickListener(v -> submitTypedWord());
        edtTypedWord.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitTypedWord();
                return true;
            }
            return false;
        });

        handler.postDelayed(() -> {
            if (!hasAudioPermission()) {
                requestAudioPermission();
            } else if (!testStarted) {
                startAssessment();
            }
        }, 900);
    }

    private void buildSpeechIntent() {
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8);

        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        speechIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // Matches the faster silence/minimum-length pacing used on
        // Register/Feedback — the previous, longer values here forced the
        // recognizer to keep the mic open well past when a short "yes" or a
        // single word was actually finished, often blowing past
        // READ_ALOUD_ATTEMPT_TIMEOUT_MS before a result ever came back.
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L);
    }

    private void rebuildRecognizer() {
        destroyRecognizer();
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition not available. Use Yes/No buttons.");
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new AssessmentRecognitionListener());
    }

    private void destroyRecognizer() {
        isRecognizerListening = false;
        try { if (speechRecognizer != null) speechRecognizer.cancel(); }  catch (Exception ignored) {}
        try { if (speechRecognizer != null) speechRecognizer.destroy(); } catch (Exception ignored) {}
        speechRecognizer = null;
    }

    private void startAssessment() {
        if (testStarted) return;
        testStarted      = true;
        testFinished     = false;
        waitingForAnswer = false;
        answerHandled    = false;
        retryCount       = 0;

        btnStart.setEnabled(false);
        btnStart.setText("Assessment Started");
        btnYes.setEnabled(true);
        btnNo.setEnabled(true);

        currentSizeIndex = 0;
        yesCount = 0;
        noCount  = 0;
        assignedWords = pickWordsFromTiers(activeWordTiers);
        for (int i = 0; i < readablePerSize.length; i++) readablePerSize[i] = 0;
        java.util.Arrays.fill(wordRead, false);

        showCurrentItem(true);
        setStatus("Assessment is startingÃ¢Â€Â¦");
        stopListeningSafely();

        handler.postDelayed(() -> {
            speakGeneral(isRetakeAssessment
                    ? "Welcome. We will now begin the retake text size assessment."
                    : "Welcome. We will now begin the text size assessment.");
            handler.postDelayed(this::askCurrentQuestion, 2300);
        }, 700);
    }

    private void askCurrentQuestion() {
        if (!testStarted || testFinished) return;

        retryCount       = 0;
        answerHandled    = false;
        waitingForAnswer = false;
        stopListeningSafely();
        hideTypedWordField();

        setStatus("Can you read this word? Say yes or no, or tap a button.");
        speakQuestion("Can you read this word? Please say yes or no, or tap the button.");
    }

    private void repeatCurrentQuestion() {
        if (!testStarted || testFinished || answerHandled) return;

        waitingForAnswer = false;
        stopListeningSafely();
        setStatus("Repeating questionÃ¢Â€Â¦");
        speakQuestion("Repeating. Can you read this word? Please say yes or no.");
    }

    private void handleManualAnswer(boolean isYes) {
        if (!testStarted || testFinished || answerHandled) return;
        stopListeningSafely();
        waitingForAnswer = false;
        // A button-tapped "Yes" is verified by typing the word instead of
        // reading it aloud — a voice-tapped "Yes" (handleVoiceAnswer) still
        // goes through beginReadAloudPhase(), since they can just say it.
        if (isYes) beginTypedWordPhase();
        else       recordAnswer(false);
    }

    private void beginTypedWordPhase() {
        if (testFinished) return;
        isTypingWord      = true;
        answerHandled     = false;
        waitingForAnswer  = false;
        stopListeningSafely();

        setStatus("Type the word you read, then press Submit.");
        speakGeneral("Please type the word you read, then press Submit.");

        edtTypedWord.setText("");
        layoutTypedWord.setVisibility(View.VISIBLE);
        edtTypedWord.requestFocus();
    }

    private void hideTypedWordField() {
        isTypingWord = false;
        if (layoutTypedWord != null) layoutTypedWord.setVisibility(View.GONE);
        if (edtTypedWord != null) edtTypedWord.setText("");
        hideKeyboard();
    }

    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && edtTypedWord != null) imm.hideSoftInputFromWindow(edtTypedWord.getWindowToken(), 0);
        } catch (Exception ignored) {}
    }

    private void submitTypedWord() {
        if (!isTypingWord || testFinished || answerHandled) return;

        String typed = edtTypedWord.getText().toString().trim();
        if (typed.isEmpty()) {
            setStatus("Please type the word first, then press Submit.");
            return;
        }

        boolean correct = matchesTargetWord(typed);
        hideTypedWordField();
        recordAnswer(correct);
    }

    private void handleVoiceAnswer(ArrayList<String> matches) {
        if (testFinished || answerHandled) return;

        if (matches == null || matches.isEmpty()) {
            retryOrWaitForButton("I did not hear your answer.");
            return;
        }

        setStatus("Heard: " + matches.get(0));

        for (String match : matches) {
            String text = normalizeAnswer(match);
            if (text.contains("repeat"))                               { repeatCurrentQuestion(); return; }
            if (text.contains("stop") || text.contains("cancel"))      { stopByVoice();           return; }
            if (isYes(text)) { stopListeningSafely(); beginReadAloudPhase(); return; }
            if (isNo(text))  { stopListeningSafely(); recordAnswer(false);   return; }
        }

        retryOrWaitForButton("Please answer yes or no.");
    }

    private void beginReadAloudPhase() {
        if (testFinished) return;
        isReadingWord     = true;
        answerHandled     = false;
        waitingForAnswer  = false;
        stopListeningSafely();
        readAloudDeadlineElapsed = android.os.SystemClock.elapsedRealtime() + READ_ALOUD_TIMEOUT_MS;
        setStatus("Please read the word out loud now.");
        speakReadPrompt("Great. Please read the word out loud now.");
    }

    /** Cloud STT -> Vosk fallback for the read-aloud phase, reached when the
     *  built-in recognizer errors or comes back empty, within the overall
     *  read-aloud time budget. */
    private void cascadeReadAloud(int attemptId) {
        if (attemptId != readAloudAttemptId) return;
        if (!isReadingWord || testFinished || answerHandled) return;

        long now = android.os.SystemClock.elapsedRealtime();
        if (now >= readAloudDeadlineElapsed) { finishReadAloud(false); return; }
        if (cascadeSession == null) { scheduleReadAloudRetryOrFinish(); return; }

        isRecognizerListening = true;
        final int mySession = ++voiceSessionId;
        setStatus("Listening for your reading…");

        // "word:<target>" boosts recognition toward the exact word being read
        // instead of the "command" mode's unrelated yes/no/login/register
        // phrase list, which was actively biasing recognition away from it.
        cascadeSession.cascade(this, "word:" + assignedWords[currentSizeIndex], "reading the word",
                new SttCascadeSession.Listener() {
                    @Override public void onListeningStarted() {
                        if (mySession != voiceSessionId) return;
                        setStatus("Listening for your reading…");
                    }

                    @Override public void onPartialResult(String partial) {
                        if (mySession != voiceSessionId || attemptId != readAloudAttemptId) return;
                        if (matchesTargetWord(partial)) { finishReadAloud(true); return; }
                        setStatus("Hearing: " + partial);
                    }

                    @Override public void onTranscript(String transcript) {
                        if (mySession != voiceSessionId) return;
                        isRecognizerListening = false;
                        if (attemptId != readAloudAttemptId) return;
                        ArrayList<String> matches = new ArrayList<>();
                        matches.add(transcript);
                        handleReadAloudResult(matches);
                    }

                    @Override public void onExhausted() {
                        if (mySession != voiceSessionId) return;
                        isRecognizerListening = false;
                        if (attemptId != readAloudAttemptId) return;
                        scheduleReadAloudRetryOrFinish();
                    }
                });
    }

    private void startAndroidReadAloudListening(int attemptId) {
        if (attemptId != readAloudAttemptId) return;
        if (!isReadingWord || testFinished || answerHandled) return;

        long now = android.os.SystemClock.elapsedRealtime();
        if (now >= readAloudDeadlineElapsed) { finishReadAloud(false); return; }

        rebuildRecognizer();
        if (speechRecognizer == null) { finishReadAloud(true); return; }

        // Biases the built-in recognizer toward the exact word being read —
        // same fix as the "word:" Cloud STT mode, applied to the primary
        // engine this time, since it's tried first on every attempt.
        speechIntent.putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                new ArrayList<>(java.util.Collections.singletonList(assignedWords[currentSizeIndex])));

        try {
            isRecognizerListening = true;
            speechRecognizer.startListening(speechIntent);
            setStatus("Listening for your readingÃ¢Â€Â¦");
        } catch (Exception e) {
            isRecognizerListening = false;
            Log.e("STT", "read-aloud fallback startListening failed: " + e.getMessage());
            scheduleReadAloudRetryOrFinish();
            return;
        }

        long remaining      = readAloudDeadlineElapsed - now;
        long attemptTimeout = Math.min(remaining, READ_ALOUD_ATTEMPT_TIMEOUT_MS);
        handler.postDelayed(() -> {
            if (attemptId != readAloudAttemptId) return;
            if (isReadingWord && isRecognizerListening) {
                Log.e("STT", "Read-aloud recognizer silent hang Ã¢Â€Â” forcing retry.");
                isRecognizerListening = false;
                try { if (speechRecognizer != null) speechRecognizer.cancel(); } catch (Exception ignored) {}
                scheduleReadAloudRetryOrFinish();
            }
        }, attemptTimeout);
    }

    private void handleReadAloudResult(ArrayList<String> matches) {
        if (!isReadingWord || testFinished || answerHandled) return;

        if (matches != null) {
            for (String m : matches) {
                if (matchesTargetWord(m)) { finishReadAloud(true); return; }
            }
        }
        scheduleReadAloudRetryOrFinish();
    }

    private void handleReadAloudError(int error) {
        if (!isReadingWord || testFinished || answerHandled) return;
        cascadeReadAloud(readAloudAttemptId);
    }

    private void scheduleReadAloudRetryOrFinish() {
        if (!isReadingWord || testFinished || answerHandled) return;
        if (android.os.SystemClock.elapsedRealtime() >= readAloudDeadlineElapsed) {
            finishReadAloud(false);
        } else {
            handler.postDelayed(() -> startAndroidReadAloudListening(++readAloudAttemptId), 300);
        }
    }

    private boolean matchesTargetWord(String heard) {
        if (heard == null) return false;
        String norm   = heard.toLowerCase(Locale.US).replaceAll("[^a-z]", "").trim();
        String target = assignedWords[currentSizeIndex].toLowerCase(Locale.US).replaceAll("[^a-z]", "").trim();
        if (norm.isEmpty() || target.isEmpty()) return false;
        if (norm.equals(target) || norm.contains(target)) return true;

        // Tolerate a near-miss transcription (e.g. the recognizer hearing
        // "cot" for "cat") instead of requiring a letter-perfect match — a
        // single garbled phoneme shouldn't fail an otherwise-correct
        // read-aloud attempt. Skipped when `norm` is shorter than the target,
        // since that's most likely a still-in-progress partial result, not a
        // finished (if imperfect) attempt at the whole word.
        if (norm.length() < target.length()) return false;
        int maxDistance = target.length() <= 4 ? 1 : 2;
        return levenshteinDistance(norm, target) <= maxDistance;
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private void finishReadAloud(boolean success) {
        readAloudAttemptId++;
        isReadingWord = false;
        stopListeningSafely();
        recordAnswer(success);
    }

    private void recordAnswer(boolean isYes) {
        if (testFinished || answerHandled) return;

        answerHandled    = true;
        waitingForAnswer = false;
        retryCount       = 0;
        stopListeningSafely();
        hideTypedWordField();

        if (isYes) { yesCount++; readablePerSize[currentSizeIndex]++; wordRead[currentSizeIndex] = true; setStatus("Saved: Yes"); }
        else       { noCount++;                                         setStatus("Saved: No");  }

        handler.postDelayed(this::moveToNextItem, NEXT_ITEM_DELAY);
    }

    private void retryOrWaitForButton(String message) {
        if (testFinished || answerHandled) return;

        waitingForAnswer = false;
        retryCount++;

        if (retryCount >= MAX_RETRY) {
            setStatus(message + " Please tap Yes or No to continue.");
            speakGeneral("No clear voice detected. Please tap yes or no to continue. "
                    + YES_NO_BUTTON_COLOR_HINT);
            return;
        }

        setStatus(message + " Say yes or no, or tap Yes/No.");
        speakQuestion(message + " Please say yes or no.");
    }

    private void moveToNextItem() {
        if (testFinished) return;

        retryCount       = 0;
        answerHandled    = false;
        waitingForAnswer = false;

        currentSizeIndex++;

        if (currentSizeIndex >= textSizes.length) { finishTest(); return; }

        showCurrentItem(true);
        handler.postDelayed(this::askCurrentQuestion, ASK_DELAY);
    }

    private void finishTest() {
        if (testFinished) return;

        testFinished     = true;
        waitingForAnswer = false;
        answerHandled    = true;

        btnYes.setEnabled(false);
        btnNo.setEnabled(false);
        stopListeningSafely();
        hideTypedWordField();

        String recommendedSize = getRecommendedTextSize();
        String impairmentLevel = getImpairmentLevel();

        setStatus("Assessment complete.");
        // Kept short deliberately — the full result (level, size, and how many
        // words were readable) is read out in full on AssessmentResultActivity,
        // which this flow lands on next. Speaking it twice would be redundant.
        speakGeneral("Assessment complete. Let's look at your results.");

        handler.postDelayed(() -> {
            saveTestResult(recommendedSize, impairmentLevel);
            saveTestResultToDatabase(recommendedSize, impairmentLevel);
        }, 1800);
    }

    private void stopByVoice() {
        testFinished     = true;
        waitingForAnswer = false;
        answerHandled    = true;
        stopListeningSafely();
        hideTypedWordField();

        btnStart.setEnabled(true);
        btnStart.setText("Start Assessment");
        btnYes.setEnabled(false);
        btnNo.setEnabled(false);

        setStatus("Assessment stopped.");
        speakGeneral("Assessment stopped. You may press start again if you want to retake.");
    }

    /** Cloud STT -> Vosk fallback for the yes/no question, reached when the
     *  built-in recognizer errors or comes back empty. */
    private void cascadeYesNo() {
        if (testFinished || answerHandled) return;
        if (isTtsSpeaking) return;
        if (cascadeSession == null) { retryOrWaitForButton("I did not hear your answer."); return; }

        waitingForAnswer      = true;
        isRecognizerListening = true;
        final int mySession = ++voiceSessionId;
        setStatus("Listening… You may also tap Yes or No.");

        cascadeSession.cascade(this, "command", "yes or no answer", new SttCascadeSession.Listener() {
            @Override public void onListeningStarted() {
                if (mySession != voiceSessionId) return;
                setStatus("Listening… You may also tap Yes or No.");
            }

            @Override public void onPartialResult(String partial) {
                if (mySession != voiceSessionId) return;
                setStatus("Hearing: " + partial);
            }

            @Override public void onTranscript(String transcript) {
                if (mySession != voiceSessionId) return;
                isRecognizerListening = false;
                waitingForAnswer      = false;
                if (testFinished || answerHandled) return;
                ArrayList<String> matches = new ArrayList<>();
                matches.add(transcript);
                handleVoiceAnswer(matches);
            }

            @Override public void onExhausted() {
                if (mySession != voiceSessionId) return;
                isRecognizerListening = false;
                waitingForAnswer      = false;
                if (testFinished || answerHandled) return;
                retryOrWaitForButton("I did not hear your answer.");
            }
        });
    }

    private void startAndroidVoiceRecognition() {
        if (testFinished || answerHandled) { waitingForAnswer = false; return; }
        if (isTtsSpeaking) { waitingForAnswer = false; return; }
        // Guards every caller, not just the mic-busy retry that schedules
        // this — a delayed retry from the yes/no question can otherwise fire
        // after the user has already moved into the read-aloud phase and
        // steal the mic back into yes/no-listening mode mid-reading.
        if (isReadingWord) { waitingForAnswer = false; return; }
        if (!hasAudioPermission()) {
            waitingForAnswer = false;
            setStatus("Microphone permission not granted. Tap Yes or No.");
            speakGeneral("Microphone permission is not granted. " + YES_NO_BUTTON_COLOR_HINT);
            return;
        }

        rebuildRecognizer();

        if (speechRecognizer == null) {
            waitingForAnswer = false;
            setStatus("Speech recognizer unavailable. Tap Yes or No.");
            speakGeneral("Voice recognition is not available on this device. " + YES_NO_BUTTON_COLOR_HINT);
            return;
        }

        waitingForAnswer = true;
        setStatus("ListeningÃ¢Â€Â¦ You may also tap Yes or No.");

        // Probes whether the mic is actually ready instead of blindly waiting a
        // fixed delay — this used to be a flat LISTEN_START_DELAY (600ms) on top
        // of the 600ms speakQuestion() already waits after the TTS question
        // finishes, so a user answering "yes"/"no" right away (the natural
        // reaction) had their answer missed for a full 1.2s before the
        // recognizer was actually listening. MicReadiness resolves almost
        // immediately once the mic is genuinely free, instead of always paying
        // that worst-case delay.
        MicReadiness.awaitReady(handler, () -> {
            if (testFinished || answerHandled || isTtsSpeaking || speechRecognizer == null) {
                waitingForAnswer = false;
                return;
            }
            // Biases the built-in recognizer toward yes/no-type words for this
            // attempt — same COMMAND_PHRASE_BOOST list the Cloud STT "command"
            // mode already uses, now also applied to the primary engine.
            speechIntent.putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                    new ArrayList<>(Arrays.asList(GoogleSttManager.COMMAND_PHRASE_BOOST)));

            try {
                isRecognizerListening = true;
                speechRecognizer.startListening(speechIntent);
            } catch (Exception e) {
                isRecognizerListening = false;
                waitingForAnswer = false;
                setStatus("Voice failed to start. Tap Yes or No.");
                Log.e("STT", "startListening failed: " + e.getMessage());
            }
        });
    }

    private void stopListeningSafely() {
        waitingForAnswer      = false;
        isRecognizerListening = false;
        voiceSessionId++;
        if (cascadeSession != null) cascadeSession.cancel();
        if (googleStt != null) googleStt.cancel();
        try { if (speechRecognizer != null) speechRecognizer.stopListening(); } catch (Exception ignored) {}
        try { if (speechRecognizer != null) speechRecognizer.cancel(); }        catch (Exception ignored) {}
    }

    private class AssessmentRecognitionListener implements RecognitionListener {

        // Captured when this listener (and its SpeechRecognizer) is created —
        // stopListeningSafely() bumps voiceSessionId on every phase change
        // (e.g. yes/no answered -> read-aloud starting). A callback arriving
        // from an older, already-superseded recognizer instance (one that was
        // mid-teardown when the phase changed) is stale and must be ignored,
        // instead of acting on it as if it were for the CURRENT phase — that
        // was hijacking the read-aloud phase back into the yes/no "Reconnecting
        // microphone…" retry loop, and the word was never actually listened for.
        private final int mySession = voiceSessionId;

        @Override
        public void onReadyForSpeech(Bundle p) {
            if (mySession != voiceSessionId) return;
            isRecognizerListening = true;
            micReconnectAttempts = 0;
            if (isReadingWord) {
                setStatus("Listening for your readingÃ¢Â€Â¦");
            } else {
                waitingForAnswer = true;
                setStatus("ListeningÃ¢Â€Â¦ Say yes or no, or tap a button.");
            }
        }

        @Override public void onBeginningOfSpeech() {
            if (mySession != voiceSessionId) return;
            setStatus("Hearing your voiceÃ¢Â€Â¦");
        }
        @Override public void onRmsChanged(float r)  {}
        @Override public void onBufferReceived(byte[] b) {}

        @Override
        public void onEndOfSpeech() {
            if (mySession != voiceSessionId) return;
            isRecognizerListening = false;
            if (!answerHandled && !testFinished) {
                setStatus(isReadingWord ? "Processing your readingÃ¢Â€Â¦"
                        : "ProcessingÃ¢Â€Â¦ You may also tap Yes or No.");
            }
        }

        @Override
        public void onError(int error) {
            if (mySession != voiceSessionId) return;
            isRecognizerListening = false;
            waitingForAnswer      = false;
            if (testFinished || answerHandled) return;

            if (isReadingWord) { handleReadAloudError(error); return; }

            switch (error) {
                case SpeechRecognizer.ERROR_CLIENT:
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: {

                    micReconnectAttempts++;
                    if (micReconnectAttempts > MAX_MIC_RECONNECT_ATTEMPTS) {
                        Log.e("STT", "Built-in recognizer kept erroring (code=" + error
                                + ") — giving up on it and falling back to the cascade.");
                        SpeechEngineHealth.markBuiltInRecognizerBroken(TextSizeTestActivity.this);
                        micReconnectAttempts = 0;
                        destroyRecognizer();
                        cascadeYesNo();
                        return;
                    }

                    destroyRecognizer();
                    handler.postDelayed(() -> {
                        // mySession guard here too — this retry was scheduled
                        // for the yes/no question, but by the time it actually
                        // fires (~1s later) the user may have already answered
                        // "yes" and moved into the read-aloud phase. Without
                        // this check, the retry would barge back in and steal
                        // the mic into yes/no-listening mode right as they're
                        // reading the word, which is what made it look like the
                        // read-aloud phase itself was stuck reconnecting.
                        if (mySession == voiceSessionId && !isReadingWord
                                && !testFinished && !answerHandled && !isTtsSpeaking) {
                            handler.postDelayed(TextSizeTestActivity.this::startAndroidVoiceRecognition, MIC_BUSY_RETRY_DELAY_MS);
                        }
                    }, RECOGNIZER_REBUILD_DELAY);
                    setStatus("Reconnecting microphone…");
                    return;
                }
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: {
                    setStatus("Microphone permission missing. Tap Yes or No.");
                    speakGeneral("Microphone permission is missing. " + YES_NO_BUTTON_COLOR_HINT);
                    return;
                }
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: {

                    micReconnectAttempts = 0;
                    cascadeYesNo();
                    return;
                }
                default: {
                    micReconnectAttempts = 0;
                    cascadeYesNo();
                }
            }
        }

        @Override
        public void onResults(Bundle results) {
            if (mySession != voiceSessionId) return;
            isRecognizerListening = false;
            waitingForAnswer      = false;
            if (testFinished || answerHandled) return;

            ArrayList<String> matches =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

            if (isReadingWord) handleReadAloudResult(matches);
            else               handleVoiceAnswer(matches);
        }

        @Override
        public void onPartialResults(Bundle partial) {
            if (mySession != voiceSessionId) return;
            if (testFinished || answerHandled) return;

            ArrayList<String> p =
                    partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (p == null || p.isEmpty()) return;

            String text = p.get(0).toLowerCase(Locale.US).trim();
            setStatus("Hearing: " + text);

            if (isReadingWord) {
                if (matchesTargetWord(text)) { stopListeningSafely(); finishReadAloud(true); }
                return;
            }

            String norm = normalizeAnswer(text);

            if (norm.contains("repeat"))                               { stopListeningSafely(); repeatCurrentQuestion();  }
            else if (norm.contains("stop") || norm.contains("cancel")) { stopListeningSafely(); stopByVoice();            }
            else if (isYes(norm))                                      { stopListeningSafely(); beginReadAloudPhase();    }
            else if (isNo(norm))                                       { stopListeningSafely(); recordAnswer(false);      }
        }

        @Override public void onEvent(int e, Bundle p) {}
    }

    private void speakQuestion(String text) {
        isTtsSpeaking = true;
        stopListeningSafely();
        googleTts.speak(text, () -> {
            isTtsSpeaking = false;
            if (testStarted && !testFinished && !answerHandled) {
                handler.postDelayed(TextSizeTestActivity.this::startAndroidVoiceRecognition, 600);
            }
        });
    }

    private void speakGeneral(String text) {
        isTtsSpeaking = true;
        stopListeningSafely();
        googleTts.speak(text, () -> isTtsSpeaking = false);
    }

    private void speakReadPrompt(String text) {
        isTtsSpeaking = true;
        stopListeningSafely();
        googleTts.speak(text, () -> {
            isTtsSpeaking = false;
            if (testStarted && !testFinished && isReadingWord) {
                handler.postDelayed(() -> startAndroidReadAloudListening(++readAloudAttemptId), 400);
            }
        });
    }

    private void showCurrentItem(boolean activeTest) {
        txtStep.setText("Size " + (currentSizeIndex + 1) + " of " + textSizes.length);
        txtInstruction.setText("Look at the word. Say yes/no or tap the Yes/No button.");
        txtWord.setText(assignedWords[currentSizeIndex]);
        txtWord.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizes[currentSizeIndex]);

        if (!activeTest) {
            setStatus(isRetakeAssessment
                    ? "Press Start to begin retake assessment."
                    : "Press Start to begin.");
        }
    }

    private void setStatus(String msg) {
        if (txtStatus != null) txtStatus.setText(msg);
    }

    private String[] pickWordsFromTiers(String[][] tiers) {
        String[] result = new String[tiers.length];
        for (int i = 0; i < tiers.length; i++) {
            String[] tier = tiers[i];
            result[i] = tier[wordRandom.nextInt(tier.length)];
        }
        return result;
    }

    private String normalizeAnswer(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.US).trim()
                .replaceAll("[^a-z\\s]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private boolean isYes(String t) {
        return t.equals("yes") || t.equals("yeah") || t.equals("yep") || t.equals("yup")
                || t.equals("yas") || t.equals("oo") || t.equals("opo")
                || t.contains(" yes") || t.startsWith("yes ")
                || t.contains(" oo ") || t.startsWith("oo ") || t.endsWith(" oo");
    }

    private boolean isNo(String t) {
        return t.equals("no") || t.equals("nope") || t.equals("nah") || t.equals("di")
                || t.equals("hindi") || t.equals("hinde") || t.equals("know")
                || t.contains(" no") || t.startsWith("no ")
                || t.contains(" hindi") || t.startsWith("hindi ");
    }

    private String getRecommendedTextSize() {
        int best = 0;
        for (int i = 1; i < readablePerSize.length; i++) {
            if (readablePerSize[i] > readablePerSize[best]) best = i;
        }
        float size = textSizes[best];
        if (size == 18f) return "18sp";
        if (size == 24f) return "24sp";
        if (size == 30f) return "30sp";
        if (size == 36f) return "36sp";
        return "42sp";
    }

    private String getImpairmentLevel() {
        int total = textSizes.length;
        if (yesCount >= Math.ceil(total * 0.80f)) return "Low Visual Impairment Support Needed";
        if (yesCount >= Math.ceil(total * 0.45f)) return "Moderate Visual Impairment Support Needed";
        return "High Visual Impairment Support Needed";
    }

    private void saveTestResult(String size, String level) {
        getSharedPreferences("VisualEyesPrefs", MODE_PRIVATE).edit()
                .putInt("yesCount", yesCount)
                .putInt("noCount", noCount)
                .putString("recommendedTextSize", size)
                .putString("impairmentLevel", level)
                .putInt("size1Readable", readablePerSize[0])
                .putInt("size2Readable", readablePerSize[1])
                .putInt("size3Readable", readablePerSize[2])
                .putInt("size4Readable", readablePerSize[3])
                .putInt("size5Readable", readablePerSize[4])
                .putBoolean("profile_completed", true)
                .putBoolean("lastAssessmentWasRetake", isRetakeAssessment)
                .apply();
        new AuthManager(this).setProfileCompleted(true);

        FontSizeManager.saveRecommendedSize(this, parseSize(size));
    }

    private void saveTestResultToDatabase(String recommendedSize, String impairmentLevel) {
        AuthManager auth         = new AuthManager(this);
        String      sessionToken = auth.getSessionToken();

        if (sessionToken == null || sessionToken.trim().isEmpty()) {
            Log.e("Assessment", "No session token found in AuthManager — skipping server save. "
                    + "studentId=" + auth.getStudentId() + " isLoggedIn=" + auth.isLoggedIn());
            Toast.makeText(this, "Student session not found. Saved locally only.", Toast.LENGTH_LONG).show();
            goToNextScreen();
            return;
        }
        Log.d("Assessment", "Saving assessment via student_save_assessment for studentId=" + auth.getStudentId());

        JSONObject body = new JSONObject();
        try {
            body.put("p_session_token", sessionToken);
            body.put("p_impairment_level",      impairmentLevel);
            body.put("p_recommended_text_size", parseSize(recommendedSize));
            body.put("p_yes_count", yesCount);
            body.put("p_no_count",  noCount);
        } catch (Exception e) {
            Toast.makeText(this, "Failed to prepare result.", Toast.LENGTH_SHORT).show();
            goToNextScreen();
            return;
        }

        // Calls the student_save_assessment RPC instead of PATCHing the students
        // table directly â€” the function re-verifies email+password server-side,
        // so RLS no longer needs a wide-open UPDATE policy on students for this.
        String url     = ApiConfig.SUPABASE_URL + "/rest/v1/rpc/student_save_assessment";
        String bodyStr = body.toString();

        StringRequest req = new StringRequest(Request.Method.POST, url,
                response -> {
                    Log.d("Assessment", "student_save_assessment succeeded: " + response);
                    Toast.makeText(this, "Assessment saved.", Toast.LENGTH_SHORT).show();
                    goToNextScreen();
                },
                error -> {
                    if (SessionManager.isSessionExpiredError(error)) {
                        Log.e("Assessment", "student_save_assessment: session expired, forcing logout.");
                        SessionManager.forceLogoutAndRedirect(this);
                        return;
                    }
                    String msg = "Save failed.";
                    if (error.networkResponse != null && error.networkResponse.data != null) {
                        msg = "Save failed: " + new String(error.networkResponse.data, StandardCharsets.UTF_8);
                    } else if (error.getMessage() != null) {
                        msg = "Save failed: " + error.getMessage();
                    }
                    Log.e("Assessment", "student_save_assessment failed (status "
                            + (error.networkResponse != null ? error.networkResponse.statusCode : -1) + "): " + msg);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    goToNextScreen();
                }
        ) {
            @Override public byte[]              getBody()            { return bodyStr.getBytes(StandardCharsets.UTF_8); }
            @Override public String              getBodyContentType() { return "application/json; charset=utf-8"; }
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

    private void goToNextScreen() {
        try {
            Intent intent = new Intent(this, AssessmentResultActivity.class);
            intent.putExtra("impairmentLevel",    getImpairmentLevel());
            intent.putExtra("recommendedTextSize", getRecommendedTextSize());
            intent.putExtra("yesCount",            yesCount);
            intent.putExtra("totalItems",          textSizes.length);
            intent.putExtra("isRetakeAssessment",  isRetakeAssessment);
            intent.putExtra("words",     assignedWords);
            intent.putExtra("wordRead",  wordRead);
            intent.putExtra("wordSizes", textSizes);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to open next screen.", Toast.LENGTH_LONG).show();
        }
    }

    private int parseSize(String s) {
        if (s == null) return 24;
        try { return Integer.parseInt(s.toLowerCase(Locale.US).replace("sp", "").trim()); }
        catch (Exception e) { return 24; }
    }

    private boolean hasAudioPermission() {
        return MicPermissionHelper.hasAudioPermission(this);
    }

    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this,
                            "Microphone denied. The blue button is Yes, the yellow button is No.",
                            Toast.LENGTH_SHORT).show();
                }
                startAssessment();
            });

    private void requestAudioPermission() {
        if (MicPermissionHelper.isPermanentlyDenied(this)) {
            explainPermanentDenialAndOpenSettings();
            return;
        }
        if (MicPermissionHelper.isScreenReaderActive(this)) {
            setStatus("Microphone permission needed for voice commands.");
            startAssessment();
            return;
        }
        setStatus("Requesting microphone access...");
        MicPermissionHelper.markRequested(this);
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void explainPermanentDenialAndOpenSettings() {
        setStatus("Microphone permission blocked.");
        googleTts.speak("Microphone access was previously denied and can't be requested again here. " +
                "Opening app settings so you can enable it under Permissions. " +
                "You can still use the Yes or No buttons for this assessment. " + YES_NO_BUTTON_COLOR_HINT, () -> {
            MicPermissionHelper.openAppSettings(this);
            startAssessment();
        });
    }

    private String getSpeechErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:           return "Audio recording error.";
            case SpeechRecognizer.ERROR_CLIENT:          return "Microphone reconnecting.";
            case SpeechRecognizer.ERROR_NETWORK:         return "Network error.";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Network timeout.";
            case SpeechRecognizer.ERROR_NO_MATCH:        return "No clear voice detected.";
            case SpeechRecognizer.ERROR_SERVER:          return "Speech server error.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:  return "No speech detected.";
            default:                                     return "Voice not detected.";
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopListeningSafely();

        if (googleTts != null) googleTts.stopSpeaking();
    }

    @Override
    protected void onDestroy() {
        stopListeningSafely();
        destroyRecognizer();
        handler.removeCallbacksAndMessages(null);
        if (googleTts    != null) googleTts.destroy();
        if (googleStt    != null) googleStt.destroy();
        if (hybridSpeech != null) hybridSpeech.destroy();
        super.onDestroy();
    }
}