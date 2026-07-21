package com.example.visualeyes;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.*;

public class HybridSpeechManager {

    public interface HybridSpeechCallback {
        void onPartialResult(String partial);
        void onFinalResult(String transcript);
        void onError(String message);
        void onListeningStarted();
    }

    private static final String TAG         = "HybridSpeech";
    private static final int    SAMPLE_RATE = 16000;

    private static final long   SOFT_STOP_GRACE_MS = 500L;

    private static final String WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String WHISPER_API_KEY = "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";

    private final Context         context;
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final OkHttpClient    httpClient  = new OkHttpClient();

    private Model                voskModel;
    private SpeechService        voskService;
    private HybridSpeechCallback callback;

    private AudioRecord          audioRecord;
    private boolean              isRecording = false;
    private final java.util.List<byte[]> audioChunks = new java.util.ArrayList<>();
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

    private String bestVoskResult = "";
    private volatile boolean isFinishing = false;
    private final Runnable softStopRunnable = this::finishTranscription;

    private boolean pendingUseWhisper      = false;
    private String  pendingFieldDescription = null;

    public HybridSpeechManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void initVosk(Runnable onReady, Runnable onFailed) {
        StorageService.unpack(context, "vosk-model-en-us-0.22-lgraph", "model",
                model -> {
                    voskModel = model;
                    Log.d(TAG, "Vosk model loaded OK!");
                    mainHandler.post(onReady);
                },
                exception -> {
                    Log.e(TAG, "StorageService unpack failed: " + exception.getMessage());
                    mainHandler.post(onFailed);
                }
        );
    }

    public boolean isReady() {
        return voskModel != null;
    }

    public void startListening(HybridSpeechCallback cb, boolean useWhisper, String fieldDescription) {
        if (!isReady()) {
            cb.onError("Vosk model not loaded.");
            return;
        }

        this.callback  = cb;
        pendingUseWhisper       = useWhisper;
        pendingFieldDescription = fieldDescription;
        bestVoskResult = "";
        isFinishing = false;
        mainHandler.removeCallbacks(softStopRunnable);
        audioChunks.clear();

        try {
            Recognizer recognizer = new Recognizer(voskModel, SAMPLE_RATE);
            recognizer.setMaxAlternatives(3);
            recognizer.setWords(true);

            voskService = new SpeechService(recognizer, SAMPLE_RATE);
            voskService.startListening(new RecognitionListener() {

                @Override
                public void onPartialResult(String hypothesis) {
                    try {
                        String partial = new JSONObject(hypothesis).optString("partial", "").trim();
                        if (!partial.isEmpty()) {
                            bestVoskResult = partial;

                            mainHandler.removeCallbacks(softStopRunnable);
                            mainHandler.post(() -> cb.onPartialResult(partial));
                        }
                    } catch (Exception ignored) {}
                }

                @Override
                public void onResult(String hypothesis) {
                    try {
                        String text = new JSONObject(hypothesis).optString("text", "").trim();
                        if (!text.isEmpty()) {
                            bestVoskResult = text;

                            mainHandler.removeCallbacks(softStopRunnable);
                            mainHandler.postDelayed(softStopRunnable, SOFT_STOP_GRACE_MS);
                        }
                    } catch (Exception ignored) {}
                }

                @Override
                public void onFinalResult(String hypothesis) {
                    try {
                        String text = new JSONObject(hypothesis).optString("text", "").trim();
                        if (!text.isEmpty()) bestVoskResult = text;
                    } catch (Exception ignored) {}
                }

                @Override
                public void onError(Exception e) {
                    mainHandler.post(() -> cb.onError("Vosk error: " + e.getMessage()));
                }

                @Override
                public void onTimeout() {
                    mainHandler.post(() -> finishTranscription());
                }
            });

        } catch (IOException e) {
            cb.onError("Failed to start Vosk: " + e.getMessage());
            return;
        }

        startPcmCapture();
        mainHandler.post(cb::onListeningStarted);
    }

    private void startPcmCapture() {
        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                BUFFER_SIZE * 4);

        audioRecord.startRecording();
        isRecording = true;

        executor.execute(() -> {
            while (isRecording) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    audioChunks.add(chunk);
                }
            }
        });
    }

    public void stopAndTranscribe() {
        finishTranscription();
    }

    private void finishTranscription() {

        if (isFinishing) return;
        isFinishing = true;
        mainHandler.removeCallbacks(softStopRunnable);

        if (!pendingUseWhisper) {
            stopCaptureInternal();
            String result = normalizeSpoken(bestVoskResult);
            mainHandler.post(() -> { if (callback != null) callback.onFinalResult(result); });
            return;
        }

        byte[] pcmData = stopCaptureInternal();
        if (pcmData.length < SAMPLE_RATE * 2) {
            String fallback = normalizeSpoken(bestVoskResult);
            mainHandler.post(() -> { if (callback != null) callback.onFinalResult(fallback); });
            return;
        }

        byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
        sendToWhisper(wavData, bestVoskResult, pendingFieldDescription);
    }

    private byte[] stopCaptureInternal() {
        if (voskService != null) {
            voskService.stop();
            voskService = null;
        }

        isRecording = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }

        int totalBytes = 0;
        for (byte[] c : audioChunks) totalBytes += c.length;
        byte[] pcmData = new byte[totalBytes];
        int pos = 0;
        for (byte[] c : audioChunks) {
            System.arraycopy(c, 0, pcmData, pos, c.length);
            pos += c.length;
        }
        return pcmData;
    }

    private void sendToWhisper(byte[] wavData, String voskFallback, String fieldDescription) {
        executor.execute(() -> {
            try {
                boolean isEmailLike = fieldDescription != null
                        && fieldDescription.toLowerCase(Locale.US).contains("email");

                String resultTl = callWhisperLang(wavData, "tl", buildPrompt(fieldDescription, true));

                String resultEn = callWhisperLang(wavData, "en", buildPrompt(fieldDescription, false));

                Log.d(TAG, "Whisper TL raw: " + resultTl);
                Log.d(TAG, "Whisper EN raw: " + resultEn);

                String normalizedTl = normalizeSpoken(resultTl);
                String normalizedEn = normalizeSpoken(resultEn);

                Log.d(TAG, "Whisper TL normalized: " + normalizedTl);
                Log.d(TAG, "Whisper EN normalized: " + normalizedEn);

                String best;
                if (isEmailLike) {

                    if (normalizedTl.contains("@") && normalizedEn.contains("@")) {
                        best = normalizedTl.length() >= normalizedEn.length() ? normalizedTl : normalizedEn;
                    } else if (normalizedTl.contains("@")) {
                        best = normalizedTl;
                    } else if (normalizedEn.contains("@")) {
                        best = normalizedEn;
                    } else {
                        best = normalizedTl.length() >= normalizedEn.length() ? normalizedTl : normalizedEn;
                    }
                } else if (fieldDescription == null) {

                    best = !normalizedEn.isEmpty() ? normalizedEn : normalizedTl;
                } else {
                    best = normalizedTl.length() >= normalizedEn.length() ? normalizedTl : normalizedEn;
                }

                if (best.isEmpty()) best = normalizeSpoken(voskFallback);

                Log.d(TAG, "Whisper final: " + best);
                String finalResult = best;
                mainHandler.post(() -> { if (callback != null) callback.onFinalResult(finalResult); });

            } catch (Exception e) {
                Log.e(TAG, "Whisper failed: " + e.getMessage());
                mainHandler.post(() -> { if (callback != null) callback.onFinalResult(normalizeSpoken(voskFallback)); });
            }
        });
    }

    private String buildPrompt(String fieldDescription, boolean tagalog) {
        String desc = (fieldDescription == null || fieldDescription.trim().isEmpty())
                ? "a value" : fieldDescription.trim();
        if (tagalog) {
            return "Sinasabi ng speaker ang kanyang " + desc + ". " +
                    "Karaniwang mga Filipino na pangalan: bren, jerome, dela pena, santos, reyes, garcia, cruz. " +
                    "I-output lang ang sinabi, walang iba.";
        }
        return "The speaker is saying their " + desc + ". " +
                "Filipino names are common: bren, jerome, dela pena, santos, reyes, garcia, cruz. " +
                "Numbers spelled out: one=1, two=2, three=3, four=4, five=5, six=6, seven=7, eight=8, nine=9, zero=0. " +
                "If it's an email, \"at\" means @ and \"dot\" means period. Output only the spoken value exactly.";
    }

    private String callWhisperLang(byte[] wavData, String language, String prompt) {
        try {
            RequestBody audioBody = RequestBody.create(wavData, MediaType.parse("audio/wav"));

            MultipartBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav", audioBody)
                    .addFormDataPart("model", "whisper-1")
                    .addFormDataPart("language", language)
                    .addFormDataPart("prompt", prompt)
                    .addFormDataPart("temperature", "0")
                    .build();

            Request request = new Request.Builder()
                    .url(WHISPER_API_URL)
                    .addHeader("Authorization", "Bearer " + WHISPER_API_KEY)
                    .post(requestBody)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    return new JSONObject(json).optString("text", "").trim();
                } else {
                    Log.e(TAG, "Whisper " + language + " error: " + response.code());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "callWhisperLang [" + language + "] failed: " + e.getMessage());
        }
        return "";
    }

    public void cancel() {
        isFinishing = true;
        mainHandler.removeCallbacks(softStopRunnable);
        isRecording = false;
        if (voskService != null) {
            try { voskService.stop(); } catch (Exception ignored) {}
            voskService = null;
        }
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        audioChunks.clear();
        callback = null;
    }

    public void destroy() {
        cancel();
        executor.shutdown();
        if (voskModel != null) {
            voskModel.close();
            voskModel = null;
        }
    }

    private static String normalizeSpoken(String spoken) {
        if (spoken == null) return "";
        return spoken.trim().replaceAll("\\s+", " ");
    }

    private static byte[] pcmToWav(byte[] pcm, int sampleRate, int channels, int bitDepth) {
        int byteRate = sampleRate * channels * bitDepth / 8;
        int dataSize = pcm.length;
        byte[] wav   = new byte[44 + dataSize];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(wav)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes());  bb.putInt(36 + dataSize);
        bb.put("WAVE".getBytes());
        bb.put("fmt ".getBytes());  bb.putInt(16);
        bb.putShort((short) 1);
        bb.putShort((short) channels);
        bb.putInt(sampleRate);
        bb.putInt(byteRate);
        bb.putShort((short) (channels * bitDepth / 8));
        bb.putShort((short) bitDepth);
        bb.put("data".getBytes());  bb.putInt(dataSize);
        System.arraycopy(pcm, 0, wav, 44, dataSize);
        return wav;
    }
}
