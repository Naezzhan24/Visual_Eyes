package com.example.visualeyes;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.*;

public class GoogleTtsManager {

    public interface TtsCallback {
        void onDone();
    }

    private static final String TAG = "GoogleTTS";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient httpClient = new OkHttpClient();

    // The Android TextToSpeech engine only ever gets used as a fallback when
    // the Google Cloud TTS call fails, but every screen used to construct its
    // own on create anyway — that's a real Binder bind to the system TTS
    // service each time, and doing it on every nav (plus the outgoing
    // screen's shutdown() landing around the same time) was pegging the UI
    // thread for seconds. One shared engine for the process fixes that.
    private static final Object TTS_LOCK = new Object();
    private static volatile TextToSpeech sSharedAndroidTts;
    private static volatile boolean sSharedAndroidTtsReady = false;

    private MediaPlayer mediaPlayer;
    private android.media.AudioFocusRequest audioFocusRequest;

    private int speechGeneration = 0;
    private final boolean respectVoicePreferences;

    public GoogleTtsManager(Context context) {
        this(context, true);
    }

    /**
     * @param respectVoicePreferences pass false for screens that must keep speaking
     *                                 regardless of the app-wide TTS on/off toggle —
     *                                 Login/Register, since voice there is how a user
     *                                 gets into the app in the first place, not a
     *                                 preference they can have already turned off.
     */
    public GoogleTtsManager(Context context, boolean respectVoicePreferences) {
        this.context = context.getApplicationContext();
        this.respectVoicePreferences = respectVoicePreferences;
        initAndroidTts();
    }

    private void initAndroidTts() {
        synchronized (TTS_LOCK) {
            if (sSharedAndroidTts != null) return;
            sSharedAndroidTts = new TextToSpeech(context, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    sSharedAndroidTts.setLanguage(Locale.US);
                    sSharedAndroidTts.setSpeechRate(0.95f);
                    sSharedAndroidTtsReady = true;
                    Log.d(TAG, "Android TTS fallback ready.");
                }
            });
        }
    }

    public void speak(String text, TtsCallback callback) {
        speak(text, 0.95f, callback);
    }

    public void speak(String text, float speakingRate, TtsCallback callback) {
        if (text == null || text.isEmpty()
                || (respectVoicePreferences && !VoicePreferences.isTtsEnabled(context))) {
            if (callback != null) mainHandler.post(callback::onDone);
            return;
        }

        stopSpeaking();
        final int myGeneration = speechGeneration;

        executor.execute(() -> {
            try {
                JSONObject input = new JSONObject();
                input.put("ssml", NumberSpeechFormatter.toSsml(text));

                JSONObject voice = new JSONObject();
                voice.put("languageCode", "en-US");
                voice.put("name", "en-US-Neural2-F");
                voice.put("ssmlGender", "FEMALE");

                JSONObject audioConfig = new JSONObject();
                audioConfig.put("audioEncoding", "MP3");
                audioConfig.put("speakingRate", speakingRate);
                audioConfig.put("pitch", 0.0);

                JSONObject requestBody = new JSONObject();
                requestBody.put("input", input);
                requestBody.put("voice", voice);
                requestBody.put("audioConfig", audioConfig);

                RequestBody body = RequestBody.create(
                        requestBody.toString(),
                        MediaType.parse("application/json"));

                Request request = new Request.Builder()
                        .url(ApiConfig.GOOGLE_TTS_FUNCTION)
                        .header("apikey", ApiConfig.SUPABASE_KEY)
                        .header("Authorization", "Bearer " + ApiConfig.SUPABASE_KEY)
                        .post(body)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String json = response.body().string();
                        String audioContent = new JSONObject(json)
                                .optString("audioContent", "");

                        if (!audioContent.isEmpty()) {
                            byte[] audioBytes = Base64.decode(audioContent, Base64.DEFAULT);
                            playAudio(audioBytes, callback, myGeneration);
                            return;
                        }
                    }
                    Log.e(TAG, "Google TTS failed: " + response.code());
                    fallbackToAndroidTts(NumberSpeechFormatter.toPlainSpeech(text), callback, myGeneration);
                }
            } catch (Exception e) {
                Log.e(TAG, "Google TTS error: " + e.getMessage());
                fallbackToAndroidTts(NumberSpeechFormatter.toPlainSpeech(text), callback, myGeneration);
            }
        });
    }

    private void playAudio(byte[] audioBytes, TtsCallback callback, int myGeneration) {
        try {
            File tempFile = File.createTempFile("tts_", ".mp3", context.getCacheDir());
            tempFile.deleteOnExit();
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(audioBytes);
            }

            mainHandler.post(() -> {

                if (myGeneration != speechGeneration) {
                    tempFile.delete();
                    return;
                }
                try {
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                    mediaPlayer.setDataSource(tempFile.getAbsolutePath());
                    mediaPlayer.prepare();
                    mediaPlayer.setOnCompletionListener(mp -> {
                        mp.release();
                        mediaPlayer = null;
                        tempFile.delete();
                        abandonPlaybackFocus();
                        if (callback != null) callback.onDone();
                    });
                    mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                        mp.release();
                        mediaPlayer = null;
                        abandonPlaybackFocus();
                        fallbackToAndroidTts(null, callback, myGeneration);
                        return true;
                    });
                    requestPlaybackFocus();
                    mediaPlayer.start();
                } catch (Exception e) {
                    Log.e(TAG, "MediaPlayer error: " + e.getMessage());
                    fallbackToAndroidTts(null, callback, myGeneration);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Audio write error: " + e.getMessage());
            fallbackToAndroidTts(null, callback, myGeneration);
        }
    }

    private void fallbackToAndroidTts(String text, TtsCallback callback, int myGeneration) {
        mainHandler.post(() -> {
            if (myGeneration != speechGeneration) return;

            if (sSharedAndroidTtsReady && text != null) {
                String uid = "FALLBACK_" + System.currentTimeMillis();
                sSharedAndroidTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        abandonPlaybackFocus();
                        if (callback != null) mainHandler.post(callback::onDone);
                    }
                    @Override public void onError(String id) {
                        abandonPlaybackFocus();
                        if (callback != null) mainHandler.post(callback::onDone);
                    }
                });
                requestPlaybackFocus();
                sSharedAndroidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid);
            } else {
                if (callback != null) callback.onDone();
            }
        });
    }

    private void requestPlaybackFocus() {
        audioFocusRequest = AudioFocusHelper.requestForPlayback(context, focusChange -> {
            // An incoming call or another app needing the mic/speaker means
            // narration should stop rather than keep talking over it.
            if (focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS
                    || focusChange == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                stopSpeaking();
            }
        });
    }

    private void abandonPlaybackFocus() {
        AudioFocusHelper.abandon(context, audioFocusRequest);
        audioFocusRequest = null;
    }

    public void stopSpeaking() {
        speechGeneration++;
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (sSharedAndroidTtsReady && sSharedAndroidTts != null) {
            sSharedAndroidTts.stop();
        }
        abandonPlaybackFocus();
    }

    public void destroy() {
        stopSpeaking();
        executor.shutdown();
        // sSharedAndroidTts is shared across the process — another screen's
        // GoogleTtsManager may still need it, so don't shut it down here.
    }
}
