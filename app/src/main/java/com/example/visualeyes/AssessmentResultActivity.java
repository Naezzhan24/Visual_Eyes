package com.example.visualeyes;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * Shown right after the Visual Impairment Level assessment finishes — a readable,
 * voice-narrated summary of the result (support level + recommended text size)
 * before continuing on to Home (first assessment) or Profile (retake). Previously
 * the result was only ever spoken once by TextSizeTestActivity mid-navigation,
 * with nothing left on screen to read back or review.
 */
public class AssessmentResultActivity extends AppCompatActivity {

    private static final long MIC_BUSY_REINIT_DELAY_MS = 400L;
    private static final long MIC_BUSY_RETRY_DELAY_MS  = 600L;
    // How long to wait, after the result has finished being spoken, before
    // moving on by itself — long enough that "repeat" (voice or the delay
    // resetting on any interaction) still has a real chance to be heard/said.
    private static final long AUTO_CONTINUE_DELAY_MS   = 4000L;

    private TextView txtImpairmentLevel, txtRecommendedSize, txtBreakdown, txtVoiceStatus;
    private Button   btnContinue;

    private GoogleTtsManager    googleTts;
    private GoogleSttManager    googleStt;
    private HybridSpeechManager hybridSpeech;
    private SttCascadeSession   cascadeSession;
    private SpeechRecognizer    speechRecognizer;
    private Intent              speechIntent;
    private boolean             recognizerReady = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isListening   = false;
    private boolean isTtsSpeaking = false;
    private int     voiceSessionId = 0;
    private boolean voiceAvailable = false;
    private boolean isContinuing   = false;
    private final Runnable autoContinueRunnable = this::goContinue;

    // The assessment's words, in order, with the text size each was shown at and whether the
    // student actually read it. Null when this screen is opened without them.
    private String[]  words;
    private boolean[] wordRead;
    private float[]   wordSizes;
    private LinearLayout groupWordResults, layoutWordResults;

    private String  impairmentLevel;
    private String  recommendedSize;
    private int      yesCount;
    private int      totalItems;
    private boolean  isRetakeAssessment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assessment_result);

        readExtras();
        bindViews();
        applyFontSize();
        populateResult();

        googleTts = new GoogleTtsManager(this);

        // Voice on this screen is a bonus, not a gate — by the time a student
        // reaches this screen they've already answered an entire assessment by
        // voice, so RECORD_AUDIO is almost certainly already granted. If it
        // somehow isn't, just fall back to the always-available Continue button
        // instead of interrupting a result screen with a permission prompt.
        voiceAvailable = MicPermissionHelper.hasAudioPermission(this);
        if (voiceAvailable) {
            googleStt      = new GoogleSttManager(this);
            hybridSpeech   = new HybridSpeechManager(this);
            hybridSpeech.initVosk(
                    () -> Log.d("AssessResult_STT", "Vosk model ready."),
                    () -> Log.e("AssessResult_STT", "Vosk model failed to load — Cloud STT/built-in only."));
            cascadeSession = new SttCascadeSession(googleStt, hybridSpeech, handler, true);
            initSpeechRecognizer();
        } else {
            updateVoiceStatus("Tap Continue when you're ready.");
        }

        btnContinue.setOnClickListener(v -> goContinue());

        handler.postDelayed(this::speakResult, 500);
    }

    private void readExtras() {
        Intent intent = getIntent();
        impairmentLevel    = intent.getStringExtra("impairmentLevel");
        recommendedSize    = intent.getStringExtra("recommendedTextSize");
        yesCount           = intent.getIntExtra("yesCount", 0);
        totalItems         = intent.getIntExtra("totalItems", 5);
        isRetakeAssessment = intent.getBooleanExtra("isRetakeAssessment", false);
        words     = intent.getStringArrayExtra("words");
        wordRead  = intent.getBooleanArrayExtra("wordRead");
        wordSizes = intent.getFloatArrayExtra("wordSizes");

        if (impairmentLevel == null || impairmentLevel.trim().isEmpty()) {
            impairmentLevel = "Moderate Visual Impairment Support Needed";
        }
        if (recommendedSize == null || recommendedSize.trim().isEmpty()) {
            recommendedSize = "24sp";
        }
    }

    private void bindViews() {
        txtImpairmentLevel = findViewById(R.id.txtImpairmentLevel);
        txtRecommendedSize = findViewById(R.id.txtRecommendedSize);
        txtBreakdown        = findViewById(R.id.txtBreakdown);
        txtVoiceStatus      = findViewById(R.id.txtVoiceStatus);
        btnContinue          = findViewById(R.id.btnContinue);
        groupWordResults    = findViewById(R.id.groupWordResults);
        layoutWordResults   = findViewById(R.id.layoutWordResults);
    }

    private void applyFontSize() {
        float b = FontSizeManager.getFontSize(this);
        if (txtImpairmentLevel != null) txtImpairmentLevel.setTextSize(b);
        if (txtRecommendedSize != null) txtRecommendedSize.setTextSize(b);
        if (txtBreakdown        != null) txtBreakdown.setTextSize(b - 4);
    }

    private void populateResult() {
        if (txtImpairmentLevel != null) txtImpairmentLevel.setText(impairmentLevel);
        if (txtRecommendedSize != null) txtRecommendedSize.setText(recommendedSize);
        if (txtBreakdown != null) {
            txtBreakdown.setText("You could clearly read " + yesCount + " out of " + totalItems + " words.");
        }
        populateWordResults();
    }

    /** One row per assessment word: the word, the size it was shown at, and read / not read. */
    private void populateWordResults() {
        if (groupWordResults == null || layoutWordResults == null) return;
        if (words == null || words.length == 0) {
            groupWordResults.setVisibility(View.GONE);
            return;
        }
        groupWordResults.setVisibility(View.VISIBLE);
        layoutWordResults.removeAllViews();

        float textSize = Math.max(15f, FontSizeManager.getFontSize(this) - 6);
        for (int i = 0; i < words.length; i++) {
            boolean read = wordRead != null && i < wordRead.length && wordRead[i];
            int sp = (wordSizes != null && i < wordSizes.length) ? Math.round(wordSizes[i]) : 0;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView tvWord = new TextView(this);
            tvWord.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tvWord.setText((i + 1) + ". " + words[i] + (sp > 0 ? "  (" + sp + "sp)" : ""));
            tvWord.setTextColor(0xFF6E3142);
            tvWord.setTextSize(textSize);
            tvWord.setTypeface(tvWord.getTypeface(), Typeface.BOLD);

            TextView tvStatus = new TextView(this);
            tvStatus.setText(read ? "✔ Read" : "✘ Not read");
            tvStatus.setTextColor(read ? 0xFF2E7D32 : 0xFFC62828);
            tvStatus.setTextSize(textSize);
            tvStatus.setTypeface(tvStatus.getTypeface(), Typeface.BOLD);

            row.addView(tvWord);
            row.addView(tvStatus);
            // The check/cross symbols aren't reliably announced, so give screen readers the plain words.
            row.setContentDescription("Word " + (i + 1) + ", " + words[i]
                    + (sp > 0 ? ", shown at " + sp + " sp" : "") + ": " + (read ? "read" : "not read"));
            layoutWordResults.addView(row);
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void speakResult() {
        handler.removeCallbacks(autoContinueRunnable);
        isTtsSpeaking = true;
        stopListeningSafe();

        String sizeSpoken = recommendedSize.toLowerCase(Locale.US).replace("sp", "").trim();
        String msg = "Your visual impairment support level is " + impairmentLevel + ". "
                + "Recommended text size is " + sizeSpoken + ". "
                + "You could clearly read " + yesCount + " out of " + totalItems + " words. "
                + (voiceAvailable
                        ? "We will continue automatically in a few seconds. Say repeat to hear this again, or tap Continue now."
                        : "We will continue automatically in a few seconds, or tap Continue now.");

        googleTts.speak(msg, () -> {
            isTtsSpeaking = false;
            if (voiceAvailable) startListeningSafe();
            // No button tap / voice command needed — this is a result screen,
            // not a decision point, so it moves on by itself. The Continue
            // button (and "repeat"/"continue" by voice) stay as an override for
            // anyone who wants to skip the wait or hear it again first.
            handler.postDelayed(autoContinueRunnable, AUTO_CONTINUE_DELAY_MS);
        });
    }

    private void updateVoiceStatus(String text) {
        if (txtVoiceStatus != null) txtVoiceStatus.setText(text);
    }

    // ------------------------------------------------------------------
    // Voice — built-in recognizer first, Cloud STT/Vosk cascade fallback,
    // same ladder every other voice screen in the app uses.
    // ------------------------------------------------------------------

    private void initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizerReady = false;
            return;
        }
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (Exception ignored) {}
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizerReady  = true;

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,            "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,     true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,         5);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,      false);
        speechIntent.putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                new ArrayList<>(Arrays.asList("continue", "next", "repeat", "yes", "okay")));

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { updateVoiceStatus("Listening..."); }
            @Override public void onBeginningOfSpeech()       { updateVoiceStatus("Hearing your voice..."); }
            @Override public void onRmsChanged(float r)       {}
            @Override public void onBufferReceived(byte[] b)  {}

            @Override public void onEndOfSpeech() {
                isListening = false;
                updateVoiceStatus("Processing...");
            }

            @Override public void onError(int error) {
                isListening = false;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        if (!isTtsSpeaking) cascadeFromBuiltIn();
                        return;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        handler.postDelayed(() -> {
                            initSpeechRecognizer();
                            if (!isTtsSpeaking) startListeningSafe();
                        }, MIC_BUSY_REINIT_DELAY_MS);
                        return;
                    default:
                        Log.e("AssessResult_STT", "Built-in recognizer onError code=" + error);
                        if (SpeechEngineHealth.isRecognizerIncompatible(error)) {
                            SpeechEngineHealth.markBuiltInRecognizerBroken(AssessmentResultActivity.this);
                        }
                        if (!isTtsSpeaking) cascadeFromBuiltIn();
                }
            }

            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    handleCommand(matches.get(0));
                } else if (!isTtsSpeaking) {
                    cascadeFromBuiltIn();
                }
            }

            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> p = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (p != null && !p.isEmpty()) updateVoiceStatus("Hearing: " + p.get(0));
            }

            @Override public void onEvent(int e, Bundle p) {}
        });
    }

    private void startListeningSafe() {
        if (!voiceAvailable || isListening || isTtsSpeaking) return;
        if (!MicPermissionHelper.hasAudioPermission(this)) return;

        if (SpeechEngineHealth.isBuiltInRecognizerBroken(this) || !recognizerReady || speechRecognizer == null) {
            cascadeFromBuiltIn();
            return;
        }

        isListening = true;
        // Probes whether the mic is genuinely ready instead of guessing with a
        // fixed delay — resolves almost instantly in the common case, since the
        // assessment the user just finished already had the mic warmed up.
        MicReadiness.awaitReady(handler, () -> {
            if (!isListening || speechRecognizer == null) return;
            try {
                speechRecognizer.startListening(speechIntent);
            } catch (Exception e) {
                isListening = false;
            }
        });
    }

    private void cascadeFromBuiltIn() {
        if (!voiceAvailable || isListening || isTtsSpeaking || cascadeSession == null) return;
        isListening = true;
        final int mySession = ++voiceSessionId;

        cascadeSession.cascade(this, "command", "continue or repeat", new SttCascadeSession.Listener() {
            @Override public void onListeningStarted() {
                if (mySession != voiceSessionId) return;
                updateVoiceStatus("Listening...");
            }

            @Override public void onPartialResult(String partial) {
                if (mySession != voiceSessionId) return;
                updateVoiceStatus("Hearing: " + partial);
            }

            @Override public void onTranscript(String transcript) {
                if (mySession != voiceSessionId) return;
                isListening = false;
                handleCommand(transcript);
            }

            @Override public void onExhausted() {
                if (mySession != voiceSessionId) return;
                isListening = false;
                updateVoiceStatus("Didn't catch that — tap Continue when you're ready.");
            }
        });
    }

    private void handleCommand(String spoken) {
        String cmd = normalize(spoken);

        if (cmd.contains("repeat") || cmd.contains("ulit") || cmd.contains("again")) {
            speakResult();
            return;
        }
        if (cmd.contains("continue") || cmd.contains("next") || cmd.contains("proceed")
                || cmd.equals("yes") || cmd.contains("go") || cmd.contains("ok") || cmd.contains("sige")) {
            goContinue();
            return;
        }
        updateVoiceStatus("Didn't catch that. Say continue, or tap the button.");
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).trim();
    }

    private void stopListeningSafe() {
        voiceSessionId++;
        if (cascadeSession != null) cascadeSession.cancel();
        if (speechRecognizer != null) {
            try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
            try { speechRecognizer.cancel();        } catch (Exception ignored) {}
        }
        isListening = false;
    }

    private void goContinue() {
        // Reachable from three places that can race each other — the auto-continue
        // timer, a voice "continue"/"repeat"-then-continue, and the button tap —
        // so only the first one actually gets to navigate.
        if (isContinuing) return;
        isContinuing = true;

        handler.removeCallbacks(autoContinueRunnable);
        stopListeningSafe();
        if (googleTts != null) googleTts.stopSpeaking();

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("startTab", isRetakeAssessment ? "profile" : "home");
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        goContinue();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(autoContinueRunnable);
        if (googleTts != null) googleTts.stopSpeaking();
        stopListeningSafe();
    }

    @Override
    protected void onDestroy() {
        stopListeningSafe();
        if (googleTts    != null) googleTts.destroy();
        if (googleStt    != null) googleStt.destroy();
        if (hybridSpeech != null) hybridSpeech.destroy();
        if (speechRecognizer != null) { try { speechRecognizer.destroy(); } catch (Exception ignored) {} }
        super.onDestroy();
    }
}
