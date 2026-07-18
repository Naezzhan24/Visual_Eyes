package com.example.visualeyes;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.*;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Locale;

public class VoiceAssistantManager {

    public interface VoiceCallback {
        void onCommand(String command);
        void onStatus(String status);
    }

    private static final String PREFS_NAME = "VisualEyesPrefs";
    private static final String KEY_TTS_ENABLED = "tts_enabled";
    private static final String KEY_STT_ENABLED = "stt_enabled";

    private final Context context;
    private final VoiceCallback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private Intent speechIntent;

    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean isEnabled = true;
    private boolean ttsEnabled = true;
    private boolean sttEnabled = true;

    private String lastSpokenText = "";
    private String lastCommand = "";
    private long lastCommandTime = 0L;

    private static final long LISTEN_DELAY = 500L;
    private static final long LISTEN_DELAY_AFTER_TTS = 400L;
    private static final long COMMAND_COOLDOWN = 900L;

    public VoiceAssistantManager(Context context, VoiceCallback callback) {
        this.context = context;
        this.callback = callback;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ttsEnabled = prefs.getBoolean(KEY_TTS_ENABLED, true);
        sttEnabled = prefs.getBoolean(KEY_STT_ENABLED, true);

        initTTS();
        initSTT();
    }

    private void initTTS() {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.90f);
                tts.setPitch(1.0f);
            }
        });

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                isSpeaking = true;
                stopListeningInternal();
            }

            @Override
            public void onDone(String utteranceId) {
                isSpeaking = false;
                if (sttEnabled && isEnabled) {
                    startListeningDelayed(LISTEN_DELAY_AFTER_TTS);
                }
            }

            @Override
            public void onError(String utteranceId) {
                isSpeaking = false;
                if (sttEnabled && isEnabled) {
                    startListeningDelayed(LISTEN_DELAY_AFTER_TTS);
                }
            }
        });
    }

    private void initSTT() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            if (callback != null) callback.onStatus("Speech recognition not available.");
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(context);

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());

        recognizer.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle params) {
                isListening = true;
                if (callback != null) callback.onStatus("Listening...");
            }

            @Override
            public void onBeginningOfSpeech() {
                if (callback != null) callback.onStatus("Hearing your voice...");
            }

            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
                if (callback != null) callback.onStatus("Processing...");
            }

            @Override
            public void onError(int error) {
                isListening = false;

                if (!isEnabled || !sttEnabled || isSpeaking) return;

                if (callback != null) callback.onStatus(getErrorText(error));

                long delay = (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                        ? LISTEN_DELAY * 2
                        : LISTEN_DELAY;

                startListeningDelayed(delay);
            }

            @Override
            public void onResults(Bundle results) {
                isListening = false;

                if (!isEnabled || !sttEnabled || isSpeaking) {

                    if (sttEnabled && isEnabled && !isSpeaking) {
                        startListeningDelayed(LISTEN_DELAY);
                    }
                    return;
                }

                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                String command = getBestCommand(matches);

                if (!command.isEmpty() && !shouldIgnore(command) && !isDuplicate(command)) {
                    lastCommand = normalize(command);
                    lastCommandTime = System.currentTimeMillis();
                    if (callback != null) callback.onCommand(command);
                } else {

                    startListeningDelayed(LISTEN_DELAY);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (!isEnabled || !sttEnabled || isSpeaking) return;

                ArrayList<String> matches =
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                String command = getBestCommand(matches);

                if (!command.isEmpty() && !shouldIgnore(command) && isQuickCommand(command)) {
                    stopListeningInternal();

                    if (!isDuplicate(command) && callback != null) {
                        lastCommand = normalize(command);
                        lastCommandTime = System.currentTimeMillis();
                        callback.onCommand(command);
                    }
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    public void speak(String text) {
        speak(text, true);
    }

    public void speak(String text, boolean listenAfter) {
        if (text == null) text = "";

        lastSpokenText = text;

        stopListeningInternal();

        if (tts != null) {
            tts.stop();

            if (ttsEnabled) {

                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "VISUALED_TTS");

                if (!listenAfter) {

                    handler.removeCallbacksAndMessages(null);
                }
            } else {

                isSpeaking = false;
                if (listenAfter && sttEnabled && isEnabled) {
                    startListeningDelayed(LISTEN_DELAY);
                }
            }
        }
    }

    public void startListening() {
        if (!isEnabled || !sttEnabled || isListening || isSpeaking || recognizer == null) return;

        handler.removeCallbacksAndMessages(null);

        try { recognizer.cancel(); } catch (Exception ignored) {}

        try {
            recognizer.startListening(speechIntent);
            isListening = true;
        } catch (Exception e) {
            isListening = false;
            startListeningDelayed(LISTEN_DELAY);
        }
    }

    private void stopListeningInternal() {
        try { if (recognizer != null) recognizer.stopListening(); } catch (Exception ignored) {}
        try { if (recognizer != null) recognizer.cancel(); } catch (Exception ignored) {}
        isListening = false;
    }

    public void stopListening() {
        stopListeningInternal();
    }

    public void stopSpeaking() {
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
        isSpeaking = false;
    }

    private void startListeningDelayed(long delayMs) {
        handler.removeCallbacksAndMessages(null);
        if (!isEnabled || !sttEnabled || isSpeaking) return;
        handler.postDelayed(this::startListening, delayMs);
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;

        if (!enabled) {
            handler.removeCallbacksAndMessages(null);
            stopListeningInternal();
            stopSpeaking();
        } else {
            if (sttEnabled) {
                startListeningDelayed(LISTEN_DELAY);
            }
        }
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setTtsEnabled(boolean enabled) {
        ttsEnabled = enabled;

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_TTS_ENABLED, enabled)
                .apply();

        if (!enabled && tts != null) {
            tts.stop();
            isSpeaking = false;

            if (sttEnabled && isEnabled) {
                startListeningDelayed(LISTEN_DELAY);
            }
        }
    }

    public boolean isTtsEnabled() {
        return ttsEnabled;
    }

    public void setSttEnabled(boolean enabled) {
        sttEnabled = enabled;

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_STT_ENABLED, enabled)
                .apply();

        if (!enabled) {
            handler.removeCallbacksAndMessages(null);
            stopListeningInternal();
        } else if (isEnabled && !isSpeaking) {
            startListeningDelayed(LISTEN_DELAY);
        }
    }

    public boolean isSttEnabled() {
        return sttEnabled;
    }

    public void syncPreferences() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean newTts = prefs.getBoolean(KEY_TTS_ENABLED, true);
        boolean newStt = prefs.getBoolean(KEY_STT_ENABLED, true);

        if (ttsEnabled != newTts) {
            ttsEnabled = newTts;
            if (!ttsEnabled && tts != null) {
                tts.stop();
                isSpeaking = false;
            }
        }

        if (sttEnabled != newStt) {
            sttEnabled = newStt;
            if (!sttEnabled) {
                handler.removeCallbacksAndMessages(null);
                stopListeningInternal();
            }
        }

        if (isEnabled && sttEnabled && !isListening && !isSpeaking) {
            startListeningDelayed(LISTEN_DELAY);
        }
    }

    private String getBestCommand(ArrayList<String> matches) {
        if (matches == null || matches.isEmpty()) return "";
        String best = "";
        for (String item : matches) {
            if (item != null && item.length() > best.length()) best = item;
        }
        return best.trim();
    }

    private boolean shouldIgnore(String command) {
        String heard = normalize(command);
        String spoken = normalize(lastSpokenText);
        return heard.isEmpty() || heard.equals(spoken);
    }

    private boolean isDuplicate(String command) {
        String normalized = normalize(command);
        long now = System.currentTimeMillis();
        return normalized.equals(lastCommand) && (now - lastCommandTime) < COMMAND_COOLDOWN;
    }

    private boolean isQuickCommand(String command) {
        String text = normalize(command);
        return text.contains("help")
                || text.contains("repeat")
                || text.contains("home")
                || text.contains("profile")
                || text.contains("materials")
                || text.contains("logout")
                || text.contains("open")
                || text.contains("stop")
                || text.contains("pause")
                || text.contains("resume");
    }

    public static String normalize(String text) {
        if (text == null) return "";
        String normalized = text.toLowerCase(Locale.US).trim();
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    public static boolean containsAny(String text, String... keywords) {
        String normalized = normalize(text);
        for (String keyword : keywords) {
            if (normalized.contains(normalize(keyword))) return true;
        }
        return false;
    }

    private String getErrorText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:           return "Audio error.";
            case SpeechRecognizer.ERROR_CLIENT:          return "Voice client error. Retrying...";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Microphone permission missing.";
            case SpeechRecognizer.ERROR_NETWORK:         return "Network error.";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Network timeout.";
            case SpeechRecognizer.ERROR_NO_MATCH:        return "No clear command heard.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Recognizer busy. Retrying...";
            case SpeechRecognizer.ERROR_SERVER:          return "Speech server error.";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:  return "No speech detected.";
            default:                                     return "Voice recognition error.";
        }
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        stopListeningInternal();
        stopSpeaking();
        try { if (recognizer != null) recognizer.destroy(); } catch (Exception ignored) {}
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) {}
    }
}
