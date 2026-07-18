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
    private static final String GOOGLE_TTS_URL =
            "https://texttospeech.googleapis.com/v1/text:synthesize";
    private static final String GOOGLE_API_KEY = "AIzaSyCpzyRVmUoT6iqoZFMpkoBakb55GxKsYd4";

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient httpClient = new OkHttpClient();

    private TextToSpeech androidTts;
    private boolean androidTtsReady = false;
    private MediaPlayer mediaPlayer;

    private int speechGeneration = 0;

    public GoogleTtsManager(Context context) {
        this.context = context.getApplicationContext();
        initAndroidTts();
    }

    private void initAndroidTts() {
        androidTts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {

                androidTts.setLanguage(Locale.US);
                androidTts.setSpeechRate(0.95f);
                androidTtsReady = true;
                Log.d(TAG, "Android TTS fallback ready.");
            }
        });
    }

    public void speak(String text, TtsCallback callback) {
        speak(text, 0.95f, callback);
    }

    public void speak(String text, float speakingRate, TtsCallback callback) {
        if (text == null || text.isEmpty()) {
            if (callback != null) mainHandler.post(callback::onDone);
            return;
        }

        stopSpeaking();
        final int myGeneration = speechGeneration;

        executor.execute(() -> {
            try {
                JSONObject input = new JSONObject();
                input.put("text", text);

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
                        .url(GOOGLE_TTS_URL + "?key=" + GOOGLE_API_KEY)
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
                    fallbackToAndroidTts(text, callback, myGeneration);
                }
            } catch (Exception e) {
                Log.e(TAG, "Google TTS error: " + e.getMessage());
                fallbackToAndroidTts(text, callback, myGeneration);
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
                        if (callback != null) callback.onDone();
                    });
                    mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                        mp.release();
                        mediaPlayer = null;
                        fallbackToAndroidTts(null, callback, myGeneration);
                        return true;
                    });
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

            if (androidTtsReady && text != null) {
                String uid = "FALLBACK_" + System.currentTimeMillis();
                androidTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        if (callback != null) mainHandler.post(callback::onDone);
                    }
                    @Override public void onError(String id) {
                        if (callback != null) mainHandler.post(callback::onDone);
                    }
                });
                androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid);
            } else {
                if (callback != null) callback.onDone();
            }
        });
    }

    public void stopSpeaking() {
        speechGeneration++;
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (androidTtsReady && androidTts != null) {
            androidTts.stop();
        }
    }

    public void destroy() {
        stopSpeaking();
        executor.shutdown();
        if (androidTts != null) androidTts.shutdown();
    }
}
