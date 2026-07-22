package com.example.visualeyes;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

/**
 * Shared "Cloud STT → Vosk" cascade mechanics, factored out of five near-identical
 * copies in LoginActivity, RegisterActivity, HomeActivity, MaterialsActivity, and
 * ProfileActivity. The built-in Android SpeechRecognizer stays owned by each
 * activity (its RecognitionListener boilerplate genuinely differs per screen);
 * this only covers what happens once the built-in engine has already failed.
 *
 * Single-capture: Cloud STT already records raw PCM before sending it to Google.
 * If that attempt fails or comes back empty, the SAME recording is handed to
 * Vosk's offline batch recognizer (GoogleSttManager.getLastPcmData() +
 * HybridSpeechManager.transcribePcm()) instead of opening a fresh mic capture and
 * asking the user to speak again. A live Vosk capture only happens when there was
 * no internet to even attempt Cloud STT in the first place — in that case there's
 * no prior recording to reuse.
 *
 * Empty-or-failed results advance to the next engine rather than giving up
 * immediately — every engine gets a real chance before onExhausted().
 */
public class SttCascadeSession {

    public interface Listener {
        void onListeningStarted();
        void onPartialResult(String partial);
        void onTranscript(String transcript);
        void onExhausted();
    }

    private static final String TAG = "SttCascade";
    private static final long   CLOUD_STT_SAFETY_TIMEOUT_MS = 11000L;
    private static final long   VOSK_LISTEN_TIMEOUT_MS      = 6000L;

    private final GoogleSttManager    googleStt;
    private final HybridSpeechManager hybridSpeech;
    private final Handler             handler;
    private final boolean             useAudioCue;

    public SttCascadeSession(GoogleSttManager googleStt, HybridSpeechManager hybridSpeech,
                              Handler handler, boolean useAudioCue) {
        this.googleStt    = googleStt;
        this.hybridSpeech = hybridSpeech;
        this.handler      = handler;
        this.useAudioCue  = useAudioCue;
    }

    /** Replaces each activity's own cascadeFromBuiltIn(): picks Cloud STT or Vosk based on connectivity. */
    public void cascade(Context context, String mode, String voskFieldDescription, Listener listener) {
        if (NetworkUtils.hasInternet(context)) {
            startCloudStt(mode, voskFieldDescription, listener);
        } else if (hybridSpeech != null && hybridSpeech.isReady()) {
            startVoskLive(voskFieldDescription, listener);
        } else {
            listener.onExhausted();
        }
    }

    private void playCueThen(Runnable then) {
        if (useAudioCue) AudioCue.playThen(handler, then);
        else then.run();
    }

    private void startCloudStt(String mode, String voskFieldDescription, Listener listener) {
        playCueThen(() -> {
            listener.onListeningStarted();
            final boolean[] stopTriggered = {false};
            GoogleSttManager.SttCallback sttCallback = new GoogleSttManager.SttCallback() {
                @Override public void onResult(String transcript) {
                    stopTriggered[0] = true;
                    if (transcript != null && !transcript.trim().isEmpty()) {
                        listener.onTranscript(transcript.trim());
                    } else {
                        reuseAudioForVosk(listener);
                    }
                }

                @Override public void onError(String message) {
                    Log.e(TAG, "Cloud STT failed (" + message + "), reusing captured audio for Vosk.");
                    stopTriggered[0] = true;
                    reuseAudioForVosk(listener);
                }
            };
            Runnable stopAndTranscribe = () -> {
                if (stopTriggered[0]) return;
                stopTriggered[0] = true;
                googleStt.stopAndRecognize(mode, sttCallback);
            };

            googleStt.startRecording(stopAndTranscribe::run, sttCallback);
            handler.postDelayed(stopAndTranscribe, CLOUD_STT_SAFETY_TIMEOUT_MS);
        });
    }

    /** Re-runs Vosk over the audio Cloud STT already captured — no new mic capture, no beep. */
    private void reuseAudioForVosk(Listener listener) {
        if (hybridSpeech == null || !hybridSpeech.isReady()) {
            listener.onExhausted();
            return;
        }
        byte[] pcm = googleStt.getLastPcmData();
        if (pcm == null || pcm.length == 0) {
            listener.onExhausted();
            return;
        }

        hybridSpeech.transcribePcm(pcm, new HybridSpeechManager.HybridSpeechCallback() {
            @Override public void onListeningStarted() {}
            @Override public void onPartialResult(String partial) {}

            @Override public void onFinalResult(String transcript) {
                if (transcript != null && !transcript.trim().isEmpty()) {
                    listener.onTranscript(transcript.trim());
                } else {
                    listener.onExhausted();
                }
            }

            @Override public void onError(String message) {
                Log.e(TAG, "Vosk re-recognition of captured audio failed (" + message + ") — all engines exhausted.");
                listener.onExhausted();
            }
        });
    }

    /** Live mic capture via Vosk — only reached when there was no internet to attempt Cloud STT at all. */
    private void startVoskLive(String voskFieldDescription, Listener listener) {
        if (hybridSpeech == null || !hybridSpeech.isReady()) {
            listener.onExhausted();
            return;
        }

        playCueThen(() -> {
            hybridSpeech.cancel();
            final boolean[] finished = {false};
            hybridSpeech.startListening(new HybridSpeechManager.HybridSpeechCallback() {
                @Override public void onListeningStarted() { listener.onListeningStarted(); }

                @Override public void onPartialResult(String partial) {
                    listener.onPartialResult(partial);
                }

                @Override public void onFinalResult(String transcript) {
                    if (finished[0]) return;
                    finished[0] = true;
                    if (transcript != null && !transcript.trim().isEmpty()) {
                        listener.onTranscript(transcript.trim());
                    } else {
                        listener.onExhausted();
                    }
                }

                @Override public void onError(String message) {
                    if (finished[0]) return;
                    finished[0] = true;
                    Log.e(TAG, "Vosk failed (" + message + ") — all engines exhausted.");
                    listener.onExhausted();
                }
            }, false, voskFieldDescription);

            handler.postDelayed(() -> {
                if (!finished[0]) hybridSpeech.stopAndTranscribe();
            }, VOSK_LISTEN_TIMEOUT_MS);
        });
    }

    public void cancel() {
        if (googleStt != null) googleStt.cancel();
        if (hybridSpeech != null) hybridSpeech.cancel();
    }
}
