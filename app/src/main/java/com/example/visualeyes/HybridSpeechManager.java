package com.example.visualeyes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Model                voskModel;
    private SpeechService        voskService;
    private HybridSpeechCallback callback;

    private String bestVoskResult = "";
    private volatile boolean isFinishing = false;
    private final Runnable softStopRunnable = this::finishTranscription;

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

    /**
     * @param useWhisper       unused. Whisper refinement used to require a second,
     *                         concurrent AudioRecord tapping the mic alongside
     *                         Vosk's own internal capture — two AudioRecords on the
     *                         same source at once isn't safe on every device/driver
     *                         and could crash or silently fail to init. Removed;
     *                         the parameter stays so existing callers don't all
     *                         need updating.
     * @param fieldDescription unused, same reason as above.
     */
    public void startListening(HybridSpeechCallback cb, boolean useWhisper, String fieldDescription) {
        if (!isReady()) {
            cb.onError("Vosk model not loaded.");
            return;
        }

        this.callback  = cb;
        bestVoskResult = "";
        isFinishing = false;
        mainHandler.removeCallbacks(softStopRunnable);

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

        } catch (Exception e) {
            // Broadened from IOException — Vosk's SpeechService opens its own
            // AudioRecord internally, and a busy/denied mic can surface as other
            // exception types too, not just IOException.
            Log.e(TAG, "Failed to start Vosk: " + e.getMessage());
            cb.onError("Failed to start Vosk: " + e.getMessage());
            return;
        }

        mainHandler.post(cb::onListeningStarted);
    }

    public void stopAndTranscribe() {
        finishTranscription();
    }

    /**
     * Offline batch recognition over already-captured PCM (e.g. audio Cloud STT
     * just recorded but failed to transcribe) — reuses that recording instead of
     * opening a fresh mic capture and asking the user to speak again.
     */
    public void transcribePcm(byte[] pcmData, HybridSpeechCallback cb) {
        if (!isReady()) {
            cb.onError("Vosk model not loaded.");
            return;
        }
        if (pcmData == null || pcmData.length == 0) {
            cb.onError("No captured audio to re-transcribe.");
            return;
        }

        executor.execute(() -> {
            Recognizer recognizer = null;
            try {
                recognizer = new Recognizer(voskModel, SAMPLE_RATE);
                recognizer.setMaxAlternatives(3);
                recognizer.setWords(true);
                recognizer.acceptWaveForm(pcmData, pcmData.length);

                String hypothesis = recognizer.getFinalResult();
                String text = new JSONObject(hypothesis).optString("text", "").trim();
                String result = normalizeSpoken(text);

                mainHandler.post(() -> {
                    if (!result.isEmpty()) cb.onFinalResult(result);
                    else cb.onError("No speech recognized from captured audio.");
                });
            } catch (Exception e) {
                Log.e(TAG, "Vosk batch recognition failed: " + e.getMessage());
                mainHandler.post(() -> cb.onError("Vosk batch recognition failed: " + e.getMessage()));
            } finally {
                if (recognizer != null) {
                    try { recognizer.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    private void finishTranscription() {
        if (isFinishing) return;
        isFinishing = true;
        mainHandler.removeCallbacks(softStopRunnable);

        if (voskService != null) {
            try { voskService.stop(); } catch (Exception ignored) {}
            voskService = null;
        }

        String result = normalizeSpoken(bestVoskResult);
        mainHandler.post(() -> { if (callback != null) callback.onFinalResult(result); });
    }

    public void cancel() {
        isFinishing = true;
        mainHandler.removeCallbacks(softStopRunnable);
        if (voskService != null) {
            try { voskService.stop(); } catch (Exception ignored) {}
            voskService = null;
        }
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
}
