package com.example.visualeyes;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.*;

public class GoogleSttManager {

    public interface SttCallback {
        void onResult(String transcript);
        void onError(String message);
    }

    public interface SpeechEndListener {
        void onSpeechEnded();
    }

    private static final String TAG = "GoogleSTT";
    private static final String GOOGLE_STT_URL =
            "https://speech.googleapis.com/v1/speech:recognize";
    private static final String GOOGLE_API_KEY = "AIzaSyCpzyRVmUoT6iqoZFMpkoBakb55GxKsYd4";

    private static final int SAMPLE_RATE = 16000;

    // Boosted so short given names aren't misheard as common English words
    // (e.g. "bren" being transcribed as "bread"). Also reused by
    // LoginActivity/RegisterActivity as RecognizerIntent.EXTRA_BIASING_STRINGS
    // hints for the built-in SpeechRecognizer, since that's the primary engine.
    static final String[] NAME_PHRASE_BOOST = {
            "bren", "jerome", "dela pena", "santos", "reyes", "garcia", "cruz",
            "bautista", "gonzales", "ramos", "mendoza", "torres", "flores",
            "villanueva", "aquino", "castillo"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient httpClient = new OkHttpClient();

    private android.media.AudioRecord audioRecord;
    private android.media.audiofx.AutomaticGainControl agc;
    private android.media.audiofx.NoiseSuppressor noiseSuppressor;
    private boolean isRecording = false;
    private final java.util.List<byte[]> audioChunks = new java.util.ArrayList<>();
    private static final int BUFFER_SIZE = android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT);

    private static final long   VAD_CALIBRATION_MS    = 350L;
    private static final short  VAD_MIN_THRESHOLD      = 800;
    private static final double VAD_NOISE_MULTIPLIER   = 3.0;
    private static final long   VAD_SILENCE_TO_STOP_MS = 900L;
    private static final long   VAD_MIN_SPEECH_MS      = 300L;
    private static final long   VAD_MAX_DURATION_MS    = 10000L;

    private volatile SpeechEndListener speechEndListener;
    private volatile boolean vadEnabled            = false;
    private volatile boolean vadCalibrating        = false;
    private volatile boolean vadSpeechDetected     = false;
    private volatile boolean vadEndSignaled        = false;
    private volatile long    vadSilenceStartMs     = -1L;
    private volatile long    vadRecordingStartMs   = -1L;
    private volatile double  vadNoiseFloorSum      = 0;
    private volatile int     vadNoiseFloorCount    = 0;
    private volatile double  vadSpeechThreshold    = VAD_MIN_THRESHOLD;

    public void startRecording() {
        startRecording(null);
    }

    public void startRecording(SpeechEndListener listener) {
        audioChunks.clear();
        isRecording = true;

        speechEndListener   = listener;
        vadEnabled          = listener != null;
        vadCalibrating      = vadEnabled;
        vadSpeechDetected   = false;
        vadEndSignaled      = false;
        vadSilenceStartMs   = -1L;
        vadRecordingStartMs = System.currentTimeMillis();
        vadNoiseFloorSum    = 0;
        vadNoiseFloorCount  = 0;
        vadSpeechThreshold  = VAD_MIN_THRESHOLD;

        if (BUFFER_SIZE <= 0) {
            Log.e(TAG, "Invalid BUFFER_SIZE (" + BUFFER_SIZE + ") — device does not support this audio config.");
            isRecording = false;
            return;
        }

        try {
            audioRecord = new android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    BUFFER_SIZE * 4);
        } catch (SecurityException se) {
            Log.e(TAG, "AudioRecord creation denied — RECORD_AUDIO permission not granted: " + se.getMessage());
            audioRecord = null;
            isRecording = false;
            return;
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord creation threw an exception: " + e.getMessage());
            audioRecord = null;
            isRecording = false;
            return;
        }

        if (audioRecord.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord did NOT initialize (state=" + audioRecord.getState() +
                    "). Likely causes: mic held by another app/component, or permission not " +
                    "actually granted yet when this ran.");
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
            isRecording = false;
            return;
        }

        attachAudioEffects(audioRecord.getAudioSessionId());

        audioRecord.startRecording();

        if (audioRecord.getRecordingState() != android.media.AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "AudioRecord.startRecording() was called but state is still not RECORDING.");
            isRecording = false;
            return;
        }

        Log.d(TAG, "Recording started successfully (sessionId=" + audioRecord.getAudioSessionId() + ").");

        executor.execute(() -> {
            while (isRecording) {
                android.media.AudioRecord ar = audioRecord;
                if (ar == null) break;
                byte[] buffer = new byte[BUFFER_SIZE];
                int read = ar.read(buffer, 0, buffer.length);
                if (read > 0) {
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    if (vadEnabled) {
                        evaluateVad(chunk, read);
                    } else {
                        audioChunks.add(chunk);
                    }
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord.read() returned error code: " + read);
                    break;
                }
            }
        });
    }

    private void evaluateVad(byte[] chunk, int len) {
        if (vadEndSignaled) return;

        long now = System.currentTimeMillis();
        long elapsedSinceStart = now - vadRecordingStartMs;
        double rms = computeRms(chunk, len);

        if (vadCalibrating) {
            if (elapsedSinceStart < VAD_CALIBRATION_MS) {
                vadNoiseFloorSum += rms;
                vadNoiseFloorCount++;
                return;
            }
            double noiseFloor = vadNoiseFloorCount > 0 ? (vadNoiseFloorSum / vadNoiseFloorCount) : 0;
            vadSpeechThreshold = Math.max(VAD_MIN_THRESHOLD, noiseFloor * VAD_NOISE_MULTIPLIER);
            vadCalibrating = false;
        }

        // FIX: every chunk from here on is kept regardless of amplitude — the
        // threshold below now only decides WHEN to stop listening, not which
        // bytes get sent to Google. This used to skip adding "quiet" chunks
        // outright, which silently discarded real speech whenever the
        // calibrated threshold came out a bit too high for the room/mic (e.g.
        // AutomaticGainControl isn't available on every device) — the
        // transcript would get truncated down to just the loudest syllable,
        // producing an empty/garbled result even though the user spoke
        // normally the whole time.
        audioChunks.add(chunk);

        if (elapsedSinceStart >= VAD_MAX_DURATION_MS) {
            signalSpeechEnded();
            return;
        }

        if (rms >= vadSpeechThreshold) {
            vadSpeechDetected = true;
            vadSilenceStartMs = -1L;
            return;
        }

        if (!vadSpeechDetected) return;

        if (vadSilenceStartMs < 0) {
            vadSilenceStartMs = now;
            return;
        }

        long silenceDuration = now - vadSilenceStartMs;
        if (silenceDuration >= VAD_SILENCE_TO_STOP_MS && elapsedSinceStart >= VAD_MIN_SPEECH_MS) {
            signalSpeechEnded();
        }
    }

    private static double computeRms(byte[] chunk, int len) {
        int samples = len / 2;
        if (samples == 0) return 0;
        long sumOfSquares = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            short sample = (short) ((chunk[i + 1] << 8) | (chunk[i] & 0xFF));
            sumOfSquares += (long) sample * sample;
        }
        return Math.sqrt((double) sumOfSquares / samples);
    }

    private void signalSpeechEnded() {
        if (vadEndSignaled) return;
        vadEndSignaled = true;
        SpeechEndListener listener = speechEndListener;
        if (listener != null) mainHandler.post(listener::onSpeechEnded);
    }

    private void attachAudioEffects(int sessionId) {
        try {
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                agc = android.media.audiofx.AutomaticGainControl.create(sessionId);
                if (agc != null) {
                    agc.setEnabled(true);
                    Log.d(TAG, "AutomaticGainControl enabled.");
                }
            } else {
                Log.d(TAG, "AutomaticGainControl not available on this device.");
            }
        } catch (Exception e) {
            Log.e(TAG, "AutomaticGainControl init failed: " + e.getMessage());
        }

        try {
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                noiseSuppressor = android.media.audiofx.NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                    Log.d(TAG, "NoiseSuppressor enabled.");
                }
            } else {
                Log.d(TAG, "NoiseSuppressor not available on this device.");
            }
        } catch (Exception e) {
            Log.e(TAG, "NoiseSuppressor init failed: " + e.getMessage());
        }
    }

    private void releaseAudioEffects() {
        try { if (agc != null) { agc.release(); agc = null; } } catch (Exception ignored) {}
        try { if (noiseSuppressor != null) { noiseSuppressor.release(); noiseSuppressor = null; } } catch (Exception ignored) {}
    }

    public void stopAndRecognize(String mode, SttCallback callback) {
        isRecording = false;

        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        releaseAudioEffects();

        int totalBytes = 0;
        for (byte[] c : audioChunks) totalBytes += c.length;
        byte[] pcmData = new byte[totalBytes];
        int pos = 0;
        for (byte[] c : audioChunks) {
            System.arraycopy(c, 0, pcmData, pos, c.length);
            pos += c.length;
        }
        audioChunks.clear();

        if (pcmData.length < SAMPLE_RATE) {

            Log.e(TAG, "Audio too short: captured " + pcmData.length +
                    " bytes, need at least " + SAMPLE_RATE + ".");
            final int captured = pcmData.length;
            mainHandler.post(() -> callback.onError("Audio too short (" + captured + " bytes captured)."));
            return;
        }

        String audioBase64 = android.util.Base64.encodeToString(pcmData, android.util.Base64.NO_WRAP);

        executor.execute(() -> sendToGoogle(audioBase64, mode, callback));
    }

    private void sendToGoogle(String audioBase64, String mode, SttCallback callback) {
        try {

            String primaryLang = "en-US";
            String altLang     = "fil-PH";

            JSONObject config = new JSONObject();
            config.put("encoding", "LINEAR16");
            config.put("sampleRateHertz", SAMPLE_RATE);
            config.put("languageCode", primaryLang);
            config.put("alternativeLanguageCodes", new JSONArray().put(altLang));
            config.put("enableAutomaticPunctuation", false);
            config.put("model", "default");

            if (mode.equals("email")) {
                JSONObject speechContext = new JSONObject();
                JSONArray phrases = new JSONArray();
                phrases.put("gmail"); phrases.put("yahoo"); phrases.put("outlook");
                phrases.put("at"); phrases.put("dot"); phrases.put("com");
                phrases.put("ph"); phrases.put("edu"); phrases.put("net");
                for (String name : NAME_PHRASE_BOOST) phrases.put(name);
                speechContext.put("phrases", phrases);
                speechContext.put("boost", 20);
                config.put("speechContexts", new JSONArray().put(speechContext));
            } else if (mode.equals("name")) {

                JSONObject speechContext = new JSONObject();
                JSONArray phrases = new JSONArray();
                for (String name : NAME_PHRASE_BOOST) phrases.put(name);
                speechContext.put("phrases", phrases);
                speechContext.put("boost", 20);
                config.put("speechContexts", new JSONArray().put(speechContext));
            }

            JSONObject audio = new JSONObject();
            audio.put("content", audioBase64);

            JSONObject requestBody = new JSONObject();
            requestBody.put("config", config);
            requestBody.put("audio", audio);

            RequestBody body = RequestBody.create(
                    requestBody.toString(),
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(GOOGLE_STT_URL + "?key=" + GOOGLE_API_KEY)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String json = response.body().string();
                    Log.d(TAG, "Google STT response: " + json);

                    JSONObject result = new JSONObject(json);
                    JSONArray results = result.optJSONArray("results");

                    if (results != null && results.length() > 0) {
                        JSONArray alternatives = results.getJSONObject(0)
                                .optJSONArray("alternatives");
                        if (alternatives != null && alternatives.length() > 0) {
                            String transcript = alternatives.getJSONObject(0)
                                    .optString("transcript", "").trim();
                            Log.d(TAG, "Transcript [" + mode + "]: " + transcript);
                            mainHandler.post(() -> callback.onResult(transcript));
                            return;
                        }
                    }
                    mainHandler.post(() -> callback.onError("No speech detected."));
                } else {
                    String errBody = response.body() != null ? response.body().string() : "";
                    Log.e(TAG, "Google STT error " + response.code() + ": " + errBody);
                    mainHandler.post(() -> callback.onError("STT error: " + response.code()));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Google STT failed: " + e.getMessage());
            mainHandler.post(() -> callback.onError("STT failed: " + e.getMessage()));
        }
    }

    public void cancel() {
        isRecording = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        releaseAudioEffects();
        audioChunks.clear();
    }

    public void destroy() {
        cancel();
        executor.shutdown();
    }
}
