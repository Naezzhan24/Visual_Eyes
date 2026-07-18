package com.example.visualeyes;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AccessibleMaterialActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final int REQ_RECORD_AUDIO = 101;
    private static final int MIN_TEXT_SIZE = 14;
    private static final int MAX_TEXT_SIZE = 34;

    private TextView txtVoiceStatus, txtReaderTitle, txtReaderInfo, txtReaderContent, txtCurrentSize;
    private ScrollView scrollView;
    private ImageView btnBack;
    private Button btnDecreaseText, btnIncreaseText;
    private SeekBar seekTextSize;

    private String materialId    = "";
    private String fileUrl       = "";
    private String title         = "Learning Material";
    private String content       = "Loading material content...";
    private String impairmentLevel = "moderate";
    private int    recommendedTextSize = 24;

    private TextToSpeech    tts;
    private SpeechRecognizer speechRecognizer;
    private Intent          speechIntent;

    private HybridSpeechManager hybridSpeech;
    private static final long VOSK_LISTEN_TIMEOUT_MS = 6000L;

    private final Handler       handler       = new Handler(Looper.getMainLooper());
    private       ArrayList<String> chunks    = new ArrayList<>();
    private       int           currentChunkIndex = 0;

    private boolean ttsReady       = false;
    private boolean isListening    = false;
    private boolean isReading      = false;
    private boolean isTtsSpeaking  = false;
    private boolean recognizerReady= false;
    private boolean materialLoaded = false;

    private float speechRate = 0.85f;
    private float startY     = 0f;

    private final Runnable restartListeningRunnable = this::startListeningSafe;
    private final Runnable nextChunkRunnable        = this::readNextChunk;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessible_material);

        materialId     = safe(getIntent().getStringExtra("material_id"),    "");
        fileUrl        = safe(getIntent().getStringExtra("file_url"),        "");
        title          = safe(getIntent().getStringExtra("title"),           "Learning Material");
        impairmentLevel= safe(getIntent().getStringExtra("impairment_level"),"moderate");

        txtVoiceStatus  = findViewById(R.id.txtVoiceStatus);
        txtReaderTitle  = findViewById(R.id.txtReaderTitle);
        txtReaderInfo   = findViewById(R.id.txtReaderInfo);
        txtReaderContent= findViewById(R.id.txtReaderContent);
        txtCurrentSize  = findViewById(R.id.txtCurrentSize);
        scrollView      = findViewById(R.id.scrollView);
        btnBack         = findViewById(R.id.btnBack);
        btnDecreaseText = findViewById(R.id.btnDecreaseText);
        btnIncreaseText = findViewById(R.id.btnIncreaseText);
        seekTextSize    = findViewById(R.id.seekTextSize);

        ImageView btnFeedback = findViewById(R.id.btnFeedback);
        if (btnFeedback != null) btnFeedback.setOnClickListener(v -> openFeedback());

        CardView cardControls = findViewById(R.id.cardControls);
        CardView cardContent  = findViewById(R.id.cardContent);
        if (cardControls != null) UiAnim.rotateFadeIn(cardControls, 60);

        if (cardContent  != null) UiAnim.fadeSlideIn(cardContent, 160);

        if (btnBack != null)         UiAnim.attachPressFeedback(btnBack);
        if (btnFeedback != null)     UiAnim.attachPressFeedback(btnFeedback);
        if (btnDecreaseText != null) UiAnim.attachPressFeedback(btnDecreaseText);
        if (btnIncreaseText != null) UiAnim.attachPressFeedback(btnIncreaseText);

        recommendedTextSize = (int) FontSizeManager.getFontSize(this);
        txtReaderTitle.setText(title);
        txtReaderContent.setText(content);
        txtReaderContent.setLineSpacing(10f, 1.2f);
        setVoiceStatus("Voice: initializing...");

        setupTextSizeControls();
        applyTextSize(recommendedTextSize, false);
        setupTwoFingerSpeedControl();

        if (btnBack != null) btnBack.setOnClickListener(v -> handleBackAction());

        tts = new TextToSpeech(this, this);
        initSpeechRecognizer();
        hybridSpeech = new HybridSpeechManager(this);
        hybridSpeech.initVosk(
                () -> Log.d("Reader_STT", "Vosk model ready — now the primary listen engine."),
                () -> Log.e("Reader_STT", "Vosk model failed to load — using raw SpeechRecognizer only."));
        checkMicPermission();

        fetchMaterialContent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (txtReaderContent != null) applyTextSize((int) FontSizeManager.getFontSize(this), false);
        if (!isTtsSpeaking && !isReading) restartListeningDelayed(700);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(restartListeningRunnable);
        handler.removeCallbacks(nextChunkRunnable);
        stopListeningSafe();
    }

    @Override
    public void onBackPressed() {
        handleBackAction();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (tts != null) { tts.stop(); tts.shutdown(); }
        destroySpeechRecognizer();
        if (hybridSpeech != null) hybridSpeech.destroy();
        super.onDestroy();
    }

    private void fetchMaterialContent() {
        if (fileUrl == null || fileUrl.trim().isEmpty()) {
            materialLoaded = false;
            content = "PDF URL not found.";
            txtReaderContent.setText(content);
            setVoiceStatus("PDF URL missing");
            return;
        }

        setVoiceStatus("Loading PDF...");
        txtReaderContent.setText("Extracting PDF content. Please wait...");

        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            PDDocument document = null;

            try {

                String finalUrl = fileUrl.trim().replace(" ", "%20");
                if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                    throw new Exception("Invalid URL format: " + finalUrl);
                }

                byte[] pdfBytes = downloadWithRedirects(finalUrl, 5);
                if (pdfBytes == null || pdfBytes.length == 0) {
                    throw new Exception("Downloaded file is empty. Check the URL.");
                }

                document = PDDocument.load(pdfBytes);

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setAddMoreFormatting(false);
                String extracted = stripper.getText(document);

                if (extracted == null || extracted.trim().length() < 20) {
                    extracted = extractPageByPage(document);
                }

                final String finalText = cleanText(extracted);

                final CharSequence styledText = finalText.trim().isEmpty()
                        ? finalText
                        : buildStyledContent(finalText, extractStyleRuns(document));

                final PDDocument docToClose = document;
                document = null;

                runOnUiThread(() -> {
                    try { docToClose.close(); } catch (Exception ignored) {}

                    if (finalText.trim().isEmpty()
                            || finalText.equalsIgnoreCase("No readable content available.")) {
                        materialLoaded = false;
                        content = "No readable text found in this PDF.\n\n"
                                + "The file may be scanned or image-based.\n"
                                + "Please ask your teacher for a text-based version.";
                        UiAnim.crossfadeText(txtReaderContent, content);
                        setVoiceStatus("No readable content");
                        speakNow("No readable content available in this PDF.", "STOP_MSG");
                    } else {
                        materialLoaded  = true;
                        content         = finalText;
                        chunks          = splitIntoSmartChunks(content);
                        currentChunkIndex = 0;

                        txtReaderTitle.setText(title);
                        UiAnim.crossfadeText(txtReaderContent, styledText);
                        applyTextSize(recommendedTextSize, false);

                        setVoiceStatus("Loaded — " + chunks.size() + " sections");
                        speakNow("Material loaded. Say yes to start reading, or say instruction for help.", "STOP_MSG");
                    }
                });

            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    materialLoaded = false;
                    content = "Could not load PDF.\n\nReason: " + msg + "\n\nURL: " + fileUrl;
                    UiAnim.crossfadeText(txtReaderContent, content);
                    setVoiceStatus("Load failed");
                    speakNow("Could not load the material. Please check your connection and try again.", "STOP_MSG");
                });
            } finally {

                if (document != null) {
                    try { document.close(); } catch (Exception ignored) {}
                }
                executor.shutdown();
            }
        });
    }

    private byte[] downloadWithRedirects(String urlString, int maxRedirects) throws Exception {
        for (int attempt = 0; attempt < maxRedirects; attempt++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            conn.setRequestProperty("Accept", "application/pdf,*/*");
            conn.connect();

            int code = conn.getResponseCode();

            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new Exception("Redirect received but Location header is missing.");
                }

                if (!location.startsWith("http")) {
                    URL base = new URL(urlString);
                    location = base.getProtocol() + "://" + base.getHost() + location;
                }
                urlString = location;
                continue;
            }

            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new Exception("Server returned HTTP " + code + ".");
            }

            try (InputStream is = new BufferedInputStream(conn.getInputStream(), 16_384)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[16_384];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                conn.disconnect();
                return baos.toByteArray();
            }
        }
        throw new Exception("Too many redirects (>" + maxRedirects + ").");
    }

    private String extractPageByPage(PDDocument document) {
        StringBuilder sb = new StringBuilder();
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int pages = document.getNumberOfPages();
            for (int i = 1; i <= pages; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(document);
                if (pageText != null && !pageText.trim().isEmpty()) {
                    sb.append(pageText.trim()).append("\n\n");
                }
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    private static class StyleRun {
        final String text;
        final boolean bold;
        final float fontSize;
        StyleRun(String text, boolean bold, float fontSize) {
            this.text = text; this.bold = bold; this.fontSize = fontSize;
        }
    }

    private List<StyleRun> extractStyleRuns(PDDocument document) {
        List<StyleRun> runs = new ArrayList<>();
        try {
            PDFTextStripper styleStripper = new PDFTextStripper() {
                @Override
                protected void writeString(String string, List<TextPosition> textPositions) throws java.io.IOException {
                    if (string != null && !string.trim().isEmpty() && !textPositions.isEmpty()) {
                        TextPosition first = textPositions.get(0);
                        PDFont font = first.getFont();
                        String fontName = (font != null && font.getName() != null)
                                ? font.getName().toLowerCase(Locale.US) : "";
                        boolean bold = fontName.contains("bold") || fontName.contains("black")
                                || fontName.contains("heavy") || fontName.contains("semibold");
                        runs.add(new StyleRun(string, bold, first.getFontSizeInPt()));
                    }
                    super.writeString(string, textPositions);
                }
            };
            styleStripper.setSortByPosition(true);
            styleStripper.getText(document);
        } catch (Exception e) {
            Log.e("PDF_STYLE", "Style run extraction failed: " + e.getMessage());
        }
        return runs;
    }

    private CharSequence buildStyledContent(String plainText, List<StyleRun> runs) {
        if (plainText == null || plainText.isEmpty() || runs.isEmpty()) return plainText;

        List<Float> sizes = new ArrayList<>();
        for (StyleRun r : runs) if (r.fontSize > 0) sizes.add(r.fontSize);
        if (sizes.isEmpty()) return plainText;
        Collections.sort(sizes);
        float bodySize = sizes.get(sizes.size() / 2);
        float headingThreshold = bodySize * 1.2f;

        SpannableString spannable = new SpannableString(plainText);
        int cursor = 0;

        for (StyleRun run : runs) {
            String needle = run.text;
            if (needle == null || needle.trim().isEmpty()) continue;

            int start = plainText.indexOf(needle, cursor);
            if (start < 0) continue;
            int end = start + needle.length();

            boolean isHeading = run.fontSize >= headingThreshold;
            if (run.bold || isHeading) {
                spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (isHeading) {
                spannable.setSpan(new RelativeSizeSpan(1.15f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new ForegroundColorSpan(0xFF6E3142), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            cursor = end;
        }

        return spannable;
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            setVoiceStatus("Voice: TTS init failed");
            return;
        }

        int result = tts.setLanguage(Locale.US);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            setVoiceStatus("Voice: TTS language unsupported");
            return;
        }

        ttsReady = true;
        tts.setPitch(1.0f);
        tts.setSpeechRate(speechRate);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                isTtsSpeaking = true;
                runOnUiThread(() -> setVoiceStatus("Voice: speaking..."));
            }

            @Override public void onDone(String id) {
                isTtsSpeaking = false;
                runOnUiThread(() -> {
                    if ("CHUNK".equals(id) && isReading) {
                        handler.postDelayed(nextChunkRunnable, 250);
                    } else {

                        restartListeningDelayed(400);
                    }
                });
            }

            @Override public void onError(String id) {
                isTtsSpeaking = false;
                runOnUiThread(() -> restartListeningDelayed(500));
            }
        });

        speakInstructionsAndAsk();
    }

    private void speakInstructionsAndAsk() {
        if (!ttsReady) return;
        isReading = false;
        stopListeningSafe();

        String msg = "Opened learning material. " +
                "Please wait while the content loads. " +
                "When ready, say yes to start reading. " +
                "Say no or stop to pause. " +
                "Say increase text or decrease text to adjust the font size. " +
                "Say faster or slower to change reading speed. " +
                "Say instruction to hear this guide again. " +
                "Say back to return to the materials screen.";

        tts.stop();
        tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "INSTRUCTIONS");
    }

    private void speakNow(String text, String utteranceId) {
        if (!ttsReady || tts == null) return;
        stopListeningSafe();
        tts.stop();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
    }

    private void initSpeechRecognizer() {

        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::initSpeechRecognizer);
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setVoiceStatus("Voice: speech recognition unavailable");
            recognizerReady = false;
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizerReady  = true;

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,       "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,     5);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) {
                isListening = true;
                setVoiceStatus("Voice: listening...");
            }
            @Override public void onBeginningOfSpeech() {
                setVoiceStatus("Voice: hearing...");
            }
            @Override public void onEndOfSpeech() {
                isListening = false;
                setVoiceStatus("Voice: processing...");
            }
            @Override public void onRmsChanged(float r) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEvent(int t, Bundle p) {}

            @Override public void onError(int error) {
                isListening = false;
                setVoiceStatus("Voice: " + speechErrorToText(error));

                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        || error == SpeechRecognizer.ERROR_CLIENT) {
                    destroySpeechRecognizer();
                    handler.postDelayed(() -> {
                        initSpeechRecognizer();
                        if (!isTtsSpeaking) restartListeningDelayed(600);
                    }, 400);
                    return;
                }

                if (!isTtsSpeaking) restartListeningDelayed(1000);
            }

            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) handleCommand(matches.get(0));
                if (!isTtsSpeaking) restartListeningDelayed(800);
            }

            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> matches =
                        partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    setVoiceStatus("Hearing: " + matches.get(0));
                }
            }
        });
    }

    private void destroySpeechRecognizer() {
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel();  } catch (Exception ignored) {}
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
        recognizerReady = false;
        isListening     = false;
    }

    private void checkMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            setVoiceStatus("Voice: microphone ready");
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
        }
    }

    private void startListeningSafe() {
        if (isListening || isTtsSpeaking) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setVoiceStatus("Voice: microphone permission missing");
            return;
        }
        if (hybridSpeech != null && hybridSpeech.isReady()) {
            startVoskListening();
        } else {
            startRawAndroidListening();
        }
    }

    private void startVoskListening() {
        if (isListening || isTtsSpeaking) return;
        isListening = true;
        setVoiceStatus("Voice: listening...");

        boolean useWhisper = NetworkUtils.hasInternet(this);
        hybridSpeech.startListening(new HybridSpeechManager.HybridSpeechCallback() {
            @Override public void onListeningStarted() {  }

            @Override public void onPartialResult(String partial) {
                setVoiceStatus("Hearing: " + partial);
            }

            @Override public void onFinalResult(String transcript) {
                isListening = false;
                if (transcript != null && !transcript.trim().isEmpty()) {
                    handleCommand(transcript);
                }
                if (!isTtsSpeaking) restartListeningDelayed(800);
            }

            @Override public void onError(String message) {
                isListening = false;
                Log.e("Reader_STT", "Vosk failed (" + message + "), falling back to raw recognizer.");
                startRawAndroidListening();
            }
        }, useWhisper, null);

        handler.postDelayed(() -> {
            if (isListening) hybridSpeech.stopAndTranscribe();
        }, VOSK_LISTEN_TIMEOUT_MS);
    }

    private void startRawAndroidListening() {
        if (!recognizerReady || speechRecognizer == null || speechIntent == null) return;
        if (isListening || isTtsSpeaking) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setVoiceStatus("Voice: microphone permission missing");
            return;
        }
        try {
            speechRecognizer.cancel();
            handler.postDelayed(() -> {
                try {
                    if (speechRecognizer != null) speechRecognizer.startListening(speechIntent);
                } catch (Exception e) { isListening = false; }
            }, 250);
        } catch (Exception e) { isListening = false; }
    }

    private void stopListeningSafe() {
        if (hybridSpeech != null) hybridSpeech.cancel();
        if (speechRecognizer != null) {
            try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
            try { speechRecognizer.cancel();        } catch (Exception ignored) {}
        }
        isListening = false;
    }

    private void restartListeningDelayed(long ms) {
        handler.removeCallbacks(restartListeningRunnable);
        handler.postDelayed(restartListeningRunnable, ms);
    }

    private void handleCommand(String command) {
        String cmd = normalizeCommand(command);
        setVoiceStatus("Heard: " + cmd);

        if (isInstructionCommand(cmd))  { speakInstructionsAndAsk(); return; }
        if (isBackCommand(cmd))         { handleBackAction();         return; }

        if (isYesCommand(cmd)) {
            if (!materialLoaded) {
                speakNow("Material is still loading. Please wait.", "STOP_MSG");
                return;
            }

            if (currentChunkIndex > 0 && currentChunkIndex < chunks.size()) {
                isReading = true;
                speakNow("Resuming.", "RESUME");

                handler.postDelayed(() -> {
                    if (isReading) readNextChunk();
                }, 1200);
            } else {
                startReading();
            }
            return;
        }

        if (isNoOrStopCommand(cmd)) {
            isReading = false;
            handler.removeCallbacks(nextChunkRunnable);
            if (tts != null) tts.stop();
            speakNow("Reading stopped. Say yes to resume.", "STOP_MSG");
            return;
        }

        if (isRestartCommand(cmd))      { startReading();    return; }
        if (isIncreaseTextCommand(cmd)) { increaseTextSize(); return; }
        if (isDecreaseTextCommand(cmd)) { decreaseTextSize(); return; }
        if (isFasterCommand(cmd))       { increaseSpeed();    return; }
        if (isSlowerCommand(cmd))       { decreaseSpeed();    return; }

        speakNow("Command not recognised. Say yes to read, no to stop, or instruction for help.", "STOP_MSG");
    }

    private String normalizeCommand(String input) {
        if (input == null) return "";
        String cmd = input.toLowerCase(Locale.ROOT).trim();
        cmd = cmd.replaceAll("[^a-z0-9\\s]", "").replaceAll("\\s+", " ").trim();

        if (cmd.equals("yas") || cmd.equals("yess") || cmd.equals("guess") || cmd.equals("jes"))
            cmd = "yes";
        if (cmd.equals("o o") || cmd.equals("oo po") || cmd.equals("oo"))
            cmd = "yes";
        if (cmd.equals("hinde") || cmd.equals("hindi"))
            cmd = "no";
        if (cmd.equals("instructions"))
            cmd = "instruction";

        return cmd;
    }

    private boolean isYesCommand(String c) {
        return c.equals("yes") || c.contains("start reading") || c.contains("start")
                || c.contains("begin") || c.equals("go") || c.equals("opo") || c.contains("sige");
    }
    private boolean isNoOrStopCommand(String c) {
        return c.equals("no") || c.contains("stop") || c.contains("pause") || c.contains("ayoko");
    }
    private boolean isRestartCommand(String c) {
        return c.contains("restart") || c.contains("from beginning") || c.contains("muli");
    }
    private boolean isInstructionCommand(String c) {
        return c.contains("instruction") || c.contains("repeat") || c.contains("help")
                || c.contains("guide") || c.contains("ulit");
    }
    private boolean isBackCommand(String c) {
        return c.contains("back") || c.contains("return") || c.contains("balik");
    }
    private boolean isIncreaseTextCommand(String c) {
        return c.contains("increase text") || c.contains("bigger text") || c.contains("larger text")
                || c.contains("zoom in") || c.contains("lakihan") || c.contains("palakihin");
    }
    private boolean isDecreaseTextCommand(String c) {
        return c.contains("decrease text") || c.contains("smaller text") || c.contains("reduce text")
                || c.contains("zoom out") || c.contains("liitan") || c.contains("paliitin");
    }
    private boolean isFasterCommand(String c) {
        return c.contains("faster") || c.contains("increase speed") || c.contains("speed up")
                || c.contains("bilisan");
    }
    private boolean isSlowerCommand(String c) {
        return c.contains("slower") || c.contains("decrease speed") || c.contains("slow down")
                || c.contains("bagalan");
    }

    private void startReading() {
        if (!materialLoaded) {
            speakNow("Material is still loading. Please wait.", "STOP_MSG");
            return;
        }

        chunks = splitIntoSmartChunks(content);
        if (chunks.isEmpty()) {
            speakNow("No readable content available.", "STOP_MSG");
            return;
        }

        if (tts != null) tts.stop();
        isReading         = true;
        currentChunkIndex = 0;
        handler.removeCallbacks(nextChunkRunnable);
        setVoiceStatus("Reading started...");
        readNextChunk();
    }

    private void readNextChunk() {
        if (!isReading) return;

        if (currentChunkIndex >= chunks.size()) {
            isReading = false;
            currentChunkIndex = 0;
            speakNow("End of material. Say yes to read again.", "STOP_MSG");
            return;
        }

        String chunk = chunks.get(currentChunkIndex);
        scrollToChunk(chunk);
        stopListeningSafe();

        if (tts != null) {
            tts.stop();
            tts.setSpeechRate(speechRate);
            tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "CHUNK");
        }

        currentChunkIndex++;
    }

    private ArrayList<String> splitIntoSmartChunks(String text) {
        ArrayList<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;

        text = text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
        String[] paragraphs = text.split("\n\\s*\n");

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;
            if (para.length() > 220) {
                for (String s : para.split("(?<=[.!?])\\s+")) {
                    String clean = s.trim();
                    if (!clean.isEmpty()) result.add(clean);
                }
            } else {
                result.add(para);
            }
        }
        return result;
    }

    private void scrollToChunk(String chunk) {
        if (txtReaderContent == null || scrollView == null || chunk == null) return;
        int position = content.indexOf(chunk);
        if (position < 0) return;

        scrollView.post(() -> {
            Layout layout = txtReaderContent.getLayout();
            if (layout == null) return;
            int line   = layout.getLineForOffset(Math.min(position, content.length() - 1));
            int scrollY= Math.max(0, layout.getLineTop(line) - 120);
            scrollView.smoothScrollTo(0, scrollY);
        });
    }

    private void increaseSpeed() {
        speechRate = Math.min(speechRate + 0.10f, 1.50f);
        if (tts != null) tts.setSpeechRate(speechRate);
        if (!isReading) speakNow("Speed increased.", "STOP_MSG");
        else setVoiceStatus("Speed up — applies next sentence");
    }

    private void decreaseSpeed() {
        speechRate = Math.max(speechRate - 0.10f, 0.50f);
        if (tts != null) tts.setSpeechRate(speechRate);
        if (!isReading) speakNow("Speed decreased.", "STOP_MSG");
        else setVoiceStatus("Speed down — applies next sentence");
    }

    private void setupTwoFingerSpeedControl() {
        scrollView.setOnTouchListener((v, event) -> {
            if (event.getPointerCount() != 2) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_DOWN:
                    startY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float diff = event.getY() - startY;
                    if (Math.abs(diff) > 150) {
                        if (diff < 0) increaseSpeed(); else decreaseSpeed();
                        startY = event.getY();
                    }
                    return true;
                default: return true;
            }
        });
    }

    private void setupTextSizeControls() {
        if (btnIncreaseText != null) btnIncreaseText.setOnClickListener(v -> increaseTextSize());
        if (btnDecreaseText != null) btnDecreaseText.setOnClickListener(v -> decreaseTextSize());

        if (seekTextSize != null) {
            seekTextSize.setMax(MAX_TEXT_SIZE - MIN_TEXT_SIZE);
            int prog = Math.max(0, Math.min(recommendedTextSize - MIN_TEXT_SIZE, seekTextSize.getMax()));
            seekTextSize.setProgress(prog);

            seekTextSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    if (fromUser) applyTextSize(MIN_TEXT_SIZE + p, false);
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    speakNow("Text size adjusted.", "STOP_MSG");
                }
            });
        }
    }

    private void applyTextSize(int size, boolean speakFeedback) {
        size = Math.max(MIN_TEXT_SIZE, Math.min(MAX_TEXT_SIZE, size));
        recommendedTextSize = size;

        if (txtReaderContent != null)
            txtReaderContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (txtReaderTitle != null)
            txtReaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, size + 2);
        if (txtReaderInfo != null) {
            txtReaderInfo.setText("Impairment: " + impairmentLevel + " • Text: " + size + "sp");
            txtReaderInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12, size - 4));
        }
        if (txtCurrentSize != null)
            txtCurrentSize.setText("Text Size: " + size + "sp");
        if (seekTextSize != null) {
            int prog = Math.max(0, Math.min(size - MIN_TEXT_SIZE, seekTextSize.getMax()));
            seekTextSize.setProgress(prog);
        }
        if (speakFeedback)
            speakNow("Text size is now " + size + " S P.", "STOP_MSG");
    }

    private void increaseTextSize() { applyTextSize(recommendedTextSize + 2, true); }
    private void decreaseTextSize() { applyTextSize(recommendedTextSize - 2, true); }

    private void handleBackAction() {
        isReading     = false;
        isTtsSpeaking = false;
        handler.removeCallbacks(nextChunkRunnable);
        handler.removeCallbacks(restartListeningRunnable);
        stopListeningSafe();
        if (tts != null) tts.stop();
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void openFeedback() {
        Intent intent = new Intent(this, FeedbackActivity.class);
        intent.putExtra("material_id", materialId);
        intent.putExtra("file_url",    fileUrl);
        intent.putExtra("title",       title);
        startActivity(intent);
    }

    private void setVoiceStatus(String text) {
        if (txtVoiceStatus == null) return;
        txtVoiceStatus.setText(text);
        if (text != null && text.contains("listening")) {
            if (txtVoiceStatus.getAnimation() == null) {
                txtVoiceStatus.startAnimation(AnimationUtils.loadAnimation(this, R.anim.viewer_pulse));
            }
        } else {
            txtVoiceStatus.clearAnimation();
        }
    }

    private String safe(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }

    private String cleanText(String text) {
        if (text == null || text.trim().isEmpty() || text.equalsIgnoreCase("null"))
            return "No readable content available.";
        return text
                .replaceAll("\\\\n", "\n")
                .replaceAll("\\r\\n", "\n")
                .replaceAll("\\r",    "\n")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String speechErrorToText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:                  return "audio error";
            case SpeechRecognizer.ERROR_CLIENT:                 return "client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "permission denied";
            case SpeechRecognizer.ERROR_NETWORK:                return "network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:        return "network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:               return "no match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:        return "recognizer busy";
            case SpeechRecognizer.ERROR_SERVER:                 return "server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:         return "speech timeout";
            default:                                             return "unknown error";
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setVoiceStatus("Voice: microphone granted");
                restartListeningDelayed(500);
            } else {
                setVoiceStatus("Voice: microphone denied");
                Toast.makeText(this,
                        "Microphone permission is required for voice commands.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
