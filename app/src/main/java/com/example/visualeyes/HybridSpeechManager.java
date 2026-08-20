package com.example.visualeyes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.util.ArrayList;
import java.util.List;
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

    // Unpacking + loading the Vosk model from disk is expensive, and every
    // screen (Home/Materials/Profile, etc.) creates its own HybridSpeechManager
    // on create and used to reload it every time. Caching the loaded Model for
    // the life of the process means only the first screen pays that cost —
    // nav between screens just reuses it instead of reloading from scratch.
    private static volatile Model sSharedVoskModel;

    // Two screens can call initVosk() back-to-back before the first load
    // finishes (e.g. navigating away immediately after login) — without this,
    // each one kicks off its own StorageService.unpack()/Model() load, and two
    // of those running at once was enough to peg the CPU and freeze the UI for
    // several seconds. Callers that arrive while a load is already in flight
    // just queue behind it instead of starting a redundant one.
    private static final Object VOSK_LOAD_LOCK = new Object();
    private static boolean sVoskLoadInFlight = false;
    private static final List<Runnable> sPendingReady  = new ArrayList<>();
    private static final List<Runnable> sPendingFailed = new ArrayList<>();

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Model                voskModel;
    private SpeechService        voskService;
    private HybridSpeechCallback callback;

    private String bestVoskResult = "";
    private volatile boolean isFinishing = false;
    private final Runnable softStopRunnable = this::finishTranscription;

    private android.media.audiofx.NoiseSuppressor      voskNoiseSuppressor;
    private android.media.audiofx.AutomaticGainControl voskAgc;

    private final boolean respectVoicePreferences;

    public HybridSpeechManager(Context context) {
        this(context, true);
    }

    /**
     * @param respectVoicePreferences pass false for screens that must keep listening
     *                                 regardless of the app-wide STT on/off toggle —
     *                                 Login/Register, since voice there is how a user
     *                                 gets into the app in the first place, not a
     *                                 preference they can have already turned off.
     */
    public HybridSpeechManager(Context context, boolean respectVoicePreferences) {
        this.context = context.getApplicationContext();
        this.respectVoicePreferences = respectVoicePreferences;
    }

    public void initVosk(Runnable onReady, Runnable onFailed) {
        if (sSharedVoskModel != null) {
            voskModel = sSharedVoskModel;
            mainHandler.post(onReady);
            return;
        }
        synchronized (VOSK_LOAD_LOCK) {
            if (sSharedVoskModel != null) {
                voskModel = sSharedVoskModel;
                mainHandler.post(onReady);
                return;
            }
            sPendingReady.add(() -> { voskModel = sSharedVoskModel; onReady.run(); });
            sPendingFailed.add(onFailed);
            if (sVoskLoadInFlight) return;
            sVoskLoadInFlight = true;
        }

        // Dispatched at background thread priority so the (large, slow-to-load)
        // model unpack/parse doesn't compete with the UI thread for CPU time —
        // that contention, not a lock, was what read as a freeze on-screen.
        executor.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            StorageService.unpack(context, "vosk-model-en-us-0.22-lgraph", "model",
                model -> {
                    Log.d(TAG, "Vosk model loaded OK!");
                    List<Runnable> ready;
                    synchronized (VOSK_LOAD_LOCK) {
                        sSharedVoskModel = model;
                        sVoskLoadInFlight = false;
                        ready = new ArrayList<>(sPendingReady);
                        sPendingReady.clear();
                        sPendingFailed.clear();
                    }
                    for (Runnable r : ready) mainHandler.post(r);
                },
                exception -> {
                    Log.e(TAG, "StorageService unpack failed: " + exception.getMessage());
                    List<Runnable> failed;
                    synchronized (VOSK_LOAD_LOCK) {
                        sVoskLoadInFlight = false;
                        failed = new ArrayList<>(sPendingFailed);
                        sPendingReady.clear();
                        sPendingFailed.clear();
                    }
                    for (Runnable r : failed) mainHandler.post(r);
                }
            );
        });
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
        if (respectVoicePreferences && !VoicePreferences.isSttEnabled(context)) {
            cb.onError("Speech-to-Text is disabled.");
            return;
        }
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
            attachVoskAudioEffects(voskService);
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

    /**
     * Vosk's SpeechService opens its own AudioRecord internally and doesn't
     * expose it publicly, so the session id has to be pulled via reflection.
     * This is a best-effort add-on, not required for STT to work — if the
     * library's internal field name ever changes, this just silently skips
     * noise reduction for this offline-only live-capture path instead of
     * breaking recognition.
     */
    private void attachVoskAudioEffects(SpeechService service) {
        try {
            java.lang.reflect.Field recorderField = SpeechService.class.getDeclaredField("recorder");
            recorderField.setAccessible(true);
            android.media.AudioRecord recorder = (android.media.AudioRecord) recorderField.get(service);
            if (recorder == null) return;
            int sessionId = recorder.getAudioSessionId();

            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                voskNoiseSuppressor = android.media.audiofx.NoiseSuppressor.create(sessionId);
                if (voskNoiseSuppressor != null) voskNoiseSuppressor.setEnabled(true);
            }
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                voskAgc = android.media.audiofx.AutomaticGainControl.create(sessionId);
                if (voskAgc != null) voskAgc.setEnabled(true);
            }
        } catch (Exception e) {
            Log.d(TAG, "Vosk audio effects unavailable: " + e.getMessage());
        }
    }

    private void releaseVoskAudioEffects() {
        try { if (voskNoiseSuppressor != null) { voskNoiseSuppressor.release(); voskNoiseSuppressor = null; } } catch (Exception ignored) {}
        try { if (voskAgc != null) { voskAgc.release(); voskAgc = null; } } catch (Exception ignored) {}
    }

    private void finishTranscription() {
        if (isFinishing) return;
        isFinishing = true;
        mainHandler.removeCallbacks(softStopRunnable);

        if (voskService != null) {
            try { voskService.stop(); } catch (Exception ignored) {}
            voskService = null;
        }
        releaseVoskAudioEffects();

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
        releaseVoskAudioEffects();
        callback = null;
    }

    public void destroy() {
        cancel();
        executor.shutdown();
        // voskModel is shared across the process (see sSharedVoskModel) and
        // outlives any single screen's manager instance, so just drop this
        // instance's reference — don't close a model another screen may
        // still be using.
        voskModel = null;
    }

    private static String normalizeSpoken(String spoken) {
        if (spoken == null) return "";
        return spoken.trim().replaceAll("\\s+", " ");
    }
}
