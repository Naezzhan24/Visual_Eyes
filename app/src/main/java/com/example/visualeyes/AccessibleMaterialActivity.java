package com.example.visualeyes;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;

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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AccessibleMaterialActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final int REQ_RECORD_AUDIO = 101;
    private static final int MIN_TEXT_SIZE = 14;
    private static final int MAX_TEXT_SIZE = 34;

    // Hints the built-in recognizer toward this screen's actual reading-mode
    // vocabulary (English + Tagalog) instead of leaving it to guess freely.
    private static final String[] COMMAND_PHRASE_BOOST = {
            "yes", "start reading", "start", "begin", "go", "opo", "sige",
            "no", "stop", "pause", "ayoko",
            "restart", "from the beginning", "muli",
            "instruction", "repeat", "help", "guide", "ulit",
            "back", "return", "balik",
            "increase text", "bigger text", "larger text", "lakihan", "palakihin",
            "decrease text", "smaller text", "reduce text", "liitan", "paliitin",
            "faster", "increase speed", "speed up", "bilisan",
            "slower", "decrease speed", "slow down", "bagalan"
    };

    private TextView txtVoiceStatus, txtReaderTitle, txtReaderInfo, txtReaderContent, txtCurrentSize;
    private TextView txtAdjustTextSizeLabel, txtVoiceHint;
    private ScrollView scrollView;
    private LinearLayout contentContainer;
    private ImageView btnBack;
    private Button btnDecreaseText, btnIncreaseText;
    private SeekBar seekTextSize;

    private final List<TextView> bodyTextViews    = new ArrayList<>();
    private final List<TextView> captionTextViews = new ArrayList<>();
    private final List<View>     blockViews       = new ArrayList<>();

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
    private       ArrayList<ReaderBlock> chunks = new ArrayList<>();
    private       int           currentChunkIndex = 0;

    private boolean ttsReady       = false;
    private boolean isListening    = false;
    private boolean isReading      = false;
    private boolean isTtsSpeaking  = false;
    private boolean recognizerReady= false;
    private boolean materialLoaded = false;

    private float speechRate = 0.85f;
    private float startY     = 0f;

    private android.view.ScaleGestureDetector scaleGestureDetector;
    private android.view.GestureDetector      doubleTapDetector;
    private View zoomTarget;
    private float currentZoomScale = 1.0f;
    private static final float MIN_ZOOM_SCALE = 1.0f;
    private static final float MAX_ZOOM_SCALE = 3.0f;

    // Two-finger touches on the reader are ambiguous between "pinch to zoom" and
    // the existing vertical swipe for reading speed — decided once per gesture by
    // whichever movement (finger-distance vs. shared vertical drag) crosses this
    // threshold first, so a gesture can't do both at once.
    private enum TwoFingerMode { UNDECIDED, PINCH, SWIPE }
    private TwoFingerMode twoFingerMode = TwoFingerMode.UNDECIDED;
    private float twoFingerStartDistance = 0f;
    private float twoFingerStartAvgY     = 0f;
    private static final float GESTURE_DECISION_THRESHOLD_PX = 24f;

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
        txtAdjustTextSizeLabel = findViewById(R.id.txtAdjustTextSizeLabel);
        txtVoiceHint    = findViewById(R.id.txtVoiceHint);
        scrollView      = findViewById(R.id.scrollView);
        contentContainer= findViewById(R.id.contentContainer);
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
        bodyTextViews.add(txtReaderContent);
        setVoiceStatus("Voice: initializing...");

        setupTextSizeControls();
        applyTextSize(recommendedTextSize, false);
        setupGestures();

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

                final List<String> textChunks = finalText.trim().isEmpty()
                        ? new ArrayList<>()
                        : splitIntoSmartChunks(finalText);

                final List<ReaderBlock> readerBlocks = textChunks.isEmpty()
                        ? new ArrayList<>()
                        : buildReaderBlocks(document, textChunks, extractStyleRuns(document));

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
                        renderPlainMessage(content);
                        setVoiceStatus("No readable content");
                        speakNow("No readable content available in this PDF.", "STOP_MSG");
                    } else {
                        materialLoaded  = true;
                        content         = finalText;
                        chunks          = new ArrayList<>(readerBlocks);
                        currentChunkIndex = 0;

                        txtReaderTitle.setText(title);
                        renderReaderBlocks(chunks);
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
                    location = new URL(new URL(urlString), location).toString();
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
        final String  text;
        final boolean bold;
        final float   fontSize;
        final float   yTop;
        final int     pageNo;
        StyleRun(String text, boolean bold, float fontSize, float yTop, int pageNo) {
            this.text = text; this.bold = bold; this.fontSize = fontSize;
            this.yTop = yTop; this.pageNo = pageNo;
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
                        runs.add(new StyleRun(string, bold, first.getFontSizeInPt(),
                                first.getYDirAdj(), getCurrentPageNo()));
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

    private float computeHeadingThreshold(List<StyleRun> runs) {
        List<Float> sizes = new ArrayList<>();
        for (StyleRun r : runs) if (r.fontSize > 0) sizes.add(r.fontSize);
        if (sizes.isEmpty()) return Float.MAX_VALUE;
        Collections.sort(sizes);
        float bodySize = sizes.get(sizes.size() / 2);
        return bodySize * 1.2f;
    }

    /** Builds a regex that matches {@code needle} against a haystack while tolerating
     *  differences in whitespace (runs of whitespace in the needle become {@code \s+}). */
    private static Pattern whitespaceTolerantPattern(String needle) {
        String[] tokens = needle.trim().split("\\s+");
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) regex.append("\\s+");
            regex.append(Pattern.quote(tokens[i]));
        }
        return Pattern.compile(regex.toString());
    }

    /**
     * Applies bold/heading spans to a single chunk of text, consuming style runs
     * from {@code runs} starting at {@code runIndexHolder[0]}. Runs that don't match
     * within this chunk are left for the next chunk (chunks are processed in order,
     * mirroring the document's reading order).
     */
    private CharSequence styleChunkText(String chunkText, List<StyleRun> runs, int[] runIndexHolder,
                                         float headingThreshold) {
        if (chunkText == null || chunkText.isEmpty() || runs.isEmpty()) return chunkText;

        SpannableString spannable = new SpannableString(chunkText);
        int cursor = 0;
        boolean matchedAny = false;

        while (runIndexHolder[0] < runs.size()) {
            StyleRun run = runs.get(runIndexHolder[0]);
            String needle = run.text;
            if (needle == null || needle.trim().isEmpty()) { runIndexHolder[0]++; continue; }

            // needle is raw PDFBox text; chunkText has been whitespace-normalized by
            // cleanText(). Match tolerating whitespace differences so one run's raw
            // spacing can't cause indexOf to miss and permanently stall styling for
            // every later chunk.
            Matcher matcher = whitespaceTolerantPattern(needle).matcher(chunkText);
            if (!matcher.find(cursor)) break;
            int start = matcher.start();
            int end   = matcher.end();

            boolean isHeading = run.fontSize >= headingThreshold;
            if (run.bold || isHeading) {
                spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (isHeading) {
                spannable.setSpan(new RelativeSizeSpan(1.15f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new ForegroundColorSpan(0xFF6E3142), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            cursor = end;
            matchedAny = true;
            runIndexHolder[0]++;
        }

        return matchedAny ? spannable : chunkText;
    }

    private static class ReaderBlock {
        enum Type { TEXT, IMAGE }
        final Type type;
        CharSequence text;
        Bitmap image;
        String caption;

        private ReaderBlock(Type type) { this.type = type; }

        static ReaderBlock text(CharSequence text) {
            ReaderBlock b = new ReaderBlock(Type.TEXT);
            b.text = text;
            return b;
        }
        static ReaderBlock image(Bitmap image, String caption) {
            ReaderBlock b = new ReaderBlock(Type.IMAGE);
            b.image   = image;
            b.caption = caption;
            return b;
        }
    }

    private static class ImageWithCaption {
        final Bitmap bitmap;
        String caption;
        String leadingText  = "";
        String trailingText = "";
        ImageWithCaption(Bitmap bitmap, String caption) {
            this.bitmap = bitmap; this.caption = caption;
        }
    }

    private static class CaptionSpan {
        int    endChunkIndex;
        String captionText;
        String leadingText;
        String trailingText;
    }

    private static final float CAPTION_MAX_GAP_PT      = 40f;
    private static final float CAPTION_CONTINUE_GAP_PT = 20f;
    private static final int   CAPTION_MAX_CHARS       = 400;
    private static final int   MAX_CAPTION_SPAN_CHUNKS  = 5;

    /**
     * Grows the caption match forward from {@code startIndex} across as many
     * following chunks as needed for their concatenation to actually contain
     * {@code caption} (findCaption() assembles caption text purely by vertical
     * position, so it can span more text than a single chunk holds). Stops as soon
     * as a match is found, so any leftover text before the match belongs only to
     * the first chunk and any leftover after it belongs only to the last chunk in
     * the span — safe to split out as leading/trailing body text.
     */
    private CaptionSpan growCaptionSpan(List<String> chunks, boolean[] consumed, int startIndex, String caption) {
        Pattern pattern = whitespaceTolerantPattern(caption);
        StringBuilder combined = new StringBuilder();

        for (int k = startIndex; k < chunks.size() && (k - startIndex) < MAX_CAPTION_SPAN_CHUNKS; k++) {
            if (k != startIndex && consumed[k]) break;
            if (combined.length() > 0) combined.append(' ');
            combined.append(chunks.get(k));

            Matcher m = pattern.matcher(combined);
            if (m.find()) {
                CaptionSpan span = new CaptionSpan();
                span.endChunkIndex = k;
                span.captionText   = combined.substring(m.start(), m.end()).trim();
                span.leadingText   = combined.substring(0, m.start()).trim();
                span.trailingText  = combined.substring(m.end()).trim();
                return span;
            }
        }
        return null;
    }

    /**
     * Finds the text immediately below an image on the same page (within a small
     * gap) and treats it as that image's caption/description — the description is
     * printed directly under the picture inside the PDF itself, there is no
     * separate metadata field for it.
     */
    private String findCaption(PdfPageImageExtractor.ExtractedImage img, List<StyleRun> pageRunsSortedByY) {
        StringBuilder caption = new StringBuilder();
        float lastY = img.bottomY;
        boolean started = false;

        for (StyleRun run : pageRunsSortedByY) {
            if (run.yTop < img.bottomY - 1f) continue;
            float gap = run.yTop - lastY;
            if (!started) {
                if (gap > CAPTION_MAX_GAP_PT) break;
                started = true;
            } else if (gap > CAPTION_CONTINUE_GAP_PT) {
                break;
            }
            if (caption.length() > 0) caption.append(' ');
            caption.append(run.text.trim());
            lastY = run.yTop;
            if (caption.length() >= CAPTION_MAX_CHARS) break;
        }
        return caption.length() > 0 ? caption.toString() : null;
    }

    private String normalizeForMatch(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }

    private String captionSnippet(String caption) {
        String norm = normalizeForMatch(caption);
        String[] words = norm.split(" ");
        StringBuilder sb = new StringBuilder();
        int n = Math.min(10, words.length);
        for (int i = 0; i < n; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }

    /**
     * Merges extracted images into the paragraph chunk list, matching each image
     * to the chunk that contains its caption (found via {@link #findCaption}) so the
     * caption is displayed once, directly under the image, instead of also being
     * read as a separate floating paragraph.
     */
    private List<ReaderBlock> buildReaderBlocks(PDDocument document, List<String> chunks, List<StyleRun> runs) {
        List<ReaderBlock> blocks = new ArrayList<>();

        Map<Integer, List<PdfPageImageExtractor.ExtractedImage>> imagesByPage = new HashMap<>();
        try {
            int pageCount = document.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                List<PdfPageImageExtractor.ExtractedImage> pageImages =
                        new PdfPageImageExtractor(document.getPage(i)).extract();
                if (!pageImages.isEmpty()) imagesByPage.put(i + 1, pageImages);
            }
        } catch (Exception e) {
            Log.e("PDF_IMAGES", "Image extraction failed: " + e.getMessage());
        }

        float headingThreshold = computeHeadingThreshold(runs);

        if (imagesByPage.isEmpty()) {
            int[] runIndex = {0};
            for (String chunk : chunks) {
                blocks.add(ReaderBlock.text(styleChunkText(chunk, runs, runIndex, headingThreshold)));
            }
            return blocks;
        }

        List<ImageWithCaption> pending = new ArrayList<>();
        for (Map.Entry<Integer, List<PdfPageImageExtractor.ExtractedImage>> entry : imagesByPage.entrySet()) {
            int pageNo = entry.getKey();
            List<StyleRun> pageRuns = new ArrayList<>();
            for (StyleRun r : runs) if (r.pageNo == pageNo) pageRuns.add(r);
            Collections.sort(pageRuns, (a, b) -> Float.compare(a.yTop, b.yTop));

            for (PdfPageImageExtractor.ExtractedImage img : entry.getValue()) {
                pending.add(new ImageWithCaption(img.bitmap, findCaption(img, pageRuns)));
            }
        }

        boolean[] consumed = new boolean[chunks.size()];
        Map<Integer, List<ImageWithCaption>> insertBefore = new HashMap<>();
        List<ImageWithCaption> unmatched = new ArrayList<>();

        for (ImageWithCaption iwc : pending) {
            int matchIndex = -1;
            if (iwc.caption != null && !iwc.caption.trim().isEmpty()) {
                String snippet = captionSnippet(iwc.caption);
                for (int j = 0; j < chunks.size(); j++) {
                    if (consumed[j]) continue;
                    if (!snippet.isEmpty() && normalizeForMatch(chunks.get(j)).contains(snippet)) {
                        matchIndex = j;
                        break;
                    }
                }
            }
            if (matchIndex >= 0) {
                // findCaption() walks style runs purely by vertical position, so the
                // caption it assembled can span more text than fits in one
                // splitIntoSmartChunks() chunk. Grow the match across as many
                // following chunks as needed to actually cover the caption text,
                // consuming only those chunks — never the single matched chunk alone
                // when the caption doesn't fit inside it.
                CaptionSpan span = growCaptionSpan(chunks, consumed, matchIndex, iwc.caption);
                if (span != null) {
                    for (int k = matchIndex; k <= span.endChunkIndex; k++) consumed[k] = true;
                    iwc.caption      = span.captionText;
                    iwc.leadingText  = span.leadingText;
                    iwc.trailingText = span.trailingText;
                } else {
                    // Couldn't resolve exactly which chunks the caption spans — leave
                    // every chunk unconsumed so no body text is silently dropped, even
                    // though the caption text may then also be read once more as a
                    // normal paragraph (rare edge case, e.g. non-whitespace text
                    // differences between the raw PDF runs and the cleaned chunks).
                    iwc.caption = iwc.caption.trim();
                }
                insertBefore.computeIfAbsent(matchIndex, k -> new ArrayList<>()).add(iwc);
            } else {
                unmatched.add(iwc);
            }
        }

        int[] runIndex = {0};
        for (int j = 0; j < chunks.size(); j++) {
            List<ImageWithCaption> before = insertBefore.get(j);
            if (before != null) {
                for (ImageWithCaption iwc : before) {
                    if (!iwc.leadingText.isEmpty())  blocks.add(ReaderBlock.text(iwc.leadingText));
                    blocks.add(ReaderBlock.image(iwc.bitmap, iwc.caption));
                    if (!iwc.trailingText.isEmpty()) blocks.add(ReaderBlock.text(iwc.trailingText));
                }
            }
            CharSequence styled = styleChunkText(chunks.get(j), runs, runIndex, headingThreshold);
            if (!consumed[j]) {
                blocks.add(ReaderBlock.text(styled));
            }
        }
        for (ImageWithCaption iwc : unmatched) blocks.add(ReaderBlock.image(iwc.bitmap, iwc.caption));

        return blocks;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void renderPlainMessage(String text) {
        contentContainer.removeAllViews();
        blockViews.clear();
        bodyTextViews.clear();
        captionTextViews.clear();

        txtReaderContent.setVisibility(View.VISIBLE);
        txtReaderContent.setText(text);
        contentContainer.addView(txtReaderContent);
        bodyTextViews.add(txtReaderContent);
    }

    private void renderReaderBlocks(List<ReaderBlock> blocks) {
        contentContainer.removeAllViews();
        blockViews.clear();
        bodyTextViews.clear();
        captionTextViews.clear();

        for (ReaderBlock block : blocks) {
            if (block.type == ReaderBlock.Type.TEXT) {
                TextView tv = new TextView(this);
                tv.setTextColor(0xFF2F2A2C);
                tv.setLineSpacing(10f, 1.2f);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, recommendedTextSize);
                tv.setText(block.text);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(12);
                tv.setLayoutParams(lp);

                contentContainer.addView(tv);
                bodyTextViews.add(tv);
                blockViews.add(tv);
            } else {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(block.image);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setMaxHeight(dp(260));
                iv.setContentDescription(block.caption != null ? block.caption : "Image");
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ivLp.topMargin = dp(6);
                iv.setLayoutParams(ivLp);

                contentContainer.addView(iv);
                blockViews.add(iv);

                if (block.caption != null && !block.caption.trim().isEmpty()) {
                    TextView caption = new TextView(this);
                    caption.setText(block.caption);
                    caption.setTypeface(caption.getTypeface(), Typeface.ITALIC);
                    caption.setGravity(Gravity.CENTER);
                    caption.setTextColor(0xFF6E3142);
                    caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12, recommendedTextSize - 6));
                    LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    capLp.topMargin    = dp(6);
                    capLp.bottomMargin = dp(14);
                    caption.setLayoutParams(capLp);

                    contentContainer.addView(caption);
                    captionTextViews.add(caption);
                }
            }
        }
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
                "Say feedback to leave feedback on this material. " +
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
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,            "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,     true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,         5);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,      false);
        speechIntent.putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,
                new ArrayList<>(Arrays.asList(COMMAND_PHRASE_BOOST)));
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,          1500L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,                   800L);

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
        if (MicPermissionHelper.hasAudioPermission(this)) {
            setVoiceStatus("Voice: microphone ready");
            return;
        }
        if (MicPermissionHelper.isPermanentlyDenied(this)) {
            setVoiceStatus("Voice: microphone access blocked in Settings");
            return;
        }
        if (MicPermissionHelper.isScreenReaderActive(this)) {
            setVoiceStatus("Voice: microphone permission needed");
            return;
        }
        MicPermissionHelper.markRequested(this);
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
    }

    private void startListeningSafe() {
        if (isListening || isTtsSpeaking) return;
        if (!MicPermissionHelper.hasAudioPermission(this)) {
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
        setVoiceStatus("Get ready...");

        AudioCue.playThen(handler, () -> {
            if (!isListening) return;
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
        });
    }

    private void startRawAndroidListening() {
        if (!recognizerReady || speechRecognizer == null || speechIntent == null) return;
        if (isListening || isTtsSpeaking) return;
        if (!MicPermissionHelper.hasAudioPermission(this)) {
            setVoiceStatus("Voice: microphone permission missing");
            return;
        }
        try {
            speechRecognizer.cancel();
            isListening = true;
            setVoiceStatus("Get ready...");
            AudioCue.playThen(handler, () -> {
                if (!isListening) return;
                handler.postDelayed(() -> {
                    try {
                        if (speechRecognizer != null) speechRecognizer.startListening(speechIntent);
                    } catch (Exception e) { isListening = false; }
                }, 250);
            });
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
        if (isRestartCommand(cmd))      { startReading();    return; }
        if (isFeedbackCommand(cmd))     { openFeedback();     return; }

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
        if (c.contains("restart") || c.contains("beginning")) return false;
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
    private boolean isFeedbackCommand(String c) {
        return c.contains("feedback") || c.contains("puna") || c.contains("komento");
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

        ReaderBlock block = chunks.get(currentChunkIndex);
        scrollToChunk(currentChunkIndex);
        stopListeningSafe();

        String spoken;
        if (block.type == ReaderBlock.Type.TEXT) {
            spoken = block.text != null ? block.text.toString() : "";
        } else {
            spoken = "Image. " + (block.caption != null && !block.caption.trim().isEmpty()
                    ? block.caption : "No description available.");
        }

        if (tts != null) {
            tts.stop();
            tts.setSpeechRate(speechRate);
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "CHUNK");
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

    private void scrollToChunk(int index) {
        if (scrollView == null || index < 0 || index >= blockViews.size()) return;
        View target = blockViews.get(index);
        if (target == null) return;

        scrollView.post(() -> {
            int scrollY = Math.max(0, target.getTop() - 120);
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

    /**
     * Reading content only (not the whole activity root — buttons/controls must
     * never be scaled or pushed off screen), pinch-zoomed via scaleGestureDetector,
     * with a double-tap reset back to 1.0x since a low-vision user may not be able
     * to reliably reverse a pinch. Two-finger touches are disambiguated between
     * pinch-zoom and the existing vertical-swipe speed control so a single gesture
     * can't trigger both.
     */
    private void setupGestures() {
        zoomTarget = contentContainer;

        scaleGestureDetector = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector detector) {
                        if (twoFingerMode != TwoFingerMode.PINCH || zoomTarget == null) return true;
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

        doubleTapDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (zoomTarget == null) return true;
                        currentZoomScale = 1.0f;
                        zoomTarget.setScaleX(1f);
                        zoomTarget.setScaleY(1f);
                        return true;
                    }
                });

        scrollView.setOnTouchListener((v, event) -> {
            doubleTapDetector.onTouchEvent(event);
            scaleGestureDetector.onTouchEvent(event);

            if (event.getPointerCount() != 2) {
                twoFingerMode = TwoFingerMode.UNDECIDED;
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_DOWN:
                    startY = event.getY();
                    twoFingerStartDistance = twoFingerDistance(event);
                    twoFingerStartAvgY     = twoFingerAvgY(event);
                    twoFingerMode = TwoFingerMode.UNDECIDED;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (twoFingerMode == TwoFingerMode.UNDECIDED) {
                        float distDelta = Math.abs(twoFingerDistance(event) - twoFingerStartDistance);
                        float yDelta     = Math.abs(twoFingerAvgY(event) - twoFingerStartAvgY);
                        if (distDelta > GESTURE_DECISION_THRESHOLD_PX || yDelta > GESTURE_DECISION_THRESHOLD_PX) {
                            twoFingerMode = (distDelta >= yDelta) ? TwoFingerMode.PINCH : TwoFingerMode.SWIPE;
                        }
                    }
                    if (twoFingerMode == TwoFingerMode.SWIPE) {
                        float diff = event.getY() - startY;
                        if (Math.abs(diff) > 150) {
                            if (diff < 0) increaseSpeed(); else decreaseSpeed();
                            startY = event.getY();
                        }
                    }
                    return true;
                default: return true;
            }
        });
    }

    private static float twoFingerDistance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float twoFingerAvgY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) / 2f;
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

        for (TextView tv : bodyTextViews) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        for (TextView tv : captionTextViews) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12, size - 6));

        if (txtReaderTitle != null)
            txtReaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, size + 2);
        if (txtReaderInfo != null) {
            txtReaderInfo.setText("Impairment: " + impairmentLevel + " • Text: " + size + "sp");
            txtReaderInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12, size - 4));
        }
        if (txtCurrentSize != null) {
            txtCurrentSize.setText("Text Size: " + size + "sp");
            txtCurrentSize.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12, size - 4));
        }
        if (txtVoiceStatus != null)
            txtVoiceStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12, size - 6));
        if (txtAdjustTextSizeLabel != null)
            txtAdjustTextSizeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(12, size - 8));
        if (txtVoiceHint != null)
            txtVoiceHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(11, size - 10));
        if (btnDecreaseText != null)
            btnDecreaseText.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(14, size - 4));
        if (btnIncreaseText != null)
            btnIncreaseText.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(14, size - 4));
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
