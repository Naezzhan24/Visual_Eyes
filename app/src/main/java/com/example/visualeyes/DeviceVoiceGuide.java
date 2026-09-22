package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Makes the material reader follow the student's chosen assistant voice.
 *
 * The reader has to speak with the phone's own TextToSpeech (it needs the per-word highlighting
 * events, which a downloaded audio clip can't give), so it can't use the Google Cloud voices the
 * rest of the app speaks with. What it CAN do is pick an installed phone voice of the same
 * gender. Android doesn't say which voice is male or female, so this measures it: each English
 * voice is asked to say a short sentence, the pitch (fundamental frequency) of that audio is
 * estimated, and the voice is classified once and remembered. Female 1/2 and Male 1/2 then map to
 * the 1st/2nd installed voice of that gender.
 *
 * Until the one-time measuring has finished (a few seconds after the app opens), or when the phone
 * has no voice of the wanted gender, it falls back to the default voice with a pitch shift.
 */
final class DeviceVoiceGuide {

    private static final String TAG        = "DeviceVoiceGuide";
    private static final String PREF_NAME  = "VisualEyesPrefs";
    private static final String KEY_PREFIX = "voice_f0_";        // + voice name -> median F0 in Hz, -1 = unknown
    private static final float  MALE_BELOW_HZ = 158f;            // typical male < ~155 Hz, female > ~165 Hz
    private static final int    MAX_VOICES    = 12;
    private static final String PROBE_TEXT =
            "Hello, this is a short sample of my voice for the reading assistant.";

    private static volatile boolean started = false;
    // Set when the chosen phone voice failed to speak: from then on (this app launch) the default
    // voice is used, so a bad voice can never leave the reader silent.
    private static volatile boolean problemDetected = false;

    private DeviceVoiceGuide() {}

    /** The chosen phone voice didn't work: go back to the phone's default voice, for good this session. */
    static void markProblemAndReset(TextToSpeech tts) {
        problemDetected = true;
        try {
            if (tts != null) {
                Voice def = tts.getDefaultVoice();
                if (def != null) tts.setVoice(def);
                tts.setPitch(1.0f);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not reset to the default voice", e);
        }
        Log.w(TAG, "Phone voice problem: the reader is back on the default voice for this session.");
    }

    // ------------------------------------------------------------------
    // Choosing a voice for the reader
    // ------------------------------------------------------------------

    /** Sets the voice (and, if needed, a pitch) on the reader's TextToSpeech to match the option. */
    static void apply(Context context, TextToSpeech tts, TtsVoiceManager.Option option) {
        if (tts == null || option == null || problemDetected) return;
        boolean wantMale = "MALE".equals(option.gender);

        // Female 1 / Male 1 are the first of their gender, Female 2 / Male 2 the second.
        int nth = 0;
        for (TtsVoiceManager.Option o : TtsVoiceManager.OPTIONS) {
            if (o == option) break;
            if (o.gender.equals(option.gender)) nth++;
        }

        List<Voice> pool = classifiedVoices(context, tts, wantMale);
        float pitch = 1.0f;
        Voice chosen = null;
        if (!pool.isEmpty()) {
            chosen = nth < pool.size() ? pool.get(nth) : pool.get(0);
            // Only one voice of this gender installed: give the "2" option a slightly different sound.
            if (nth >= pool.size() && nth > 0) pitch = wantMale ? 0.90f : 1.12f;
        } else if (wantMale) {
            pitch = 0.80f;   // no measured male voice (yet): lower the default one
        }

        try {
            if (chosen != null) tts.setVoice(chosen);
            tts.setPitch(pitch);
        } catch (Exception e) {
            Log.e(TAG, "Could not apply reader voice", e);
        }
        Log.i(TAG, "Reader voice for " + option.label + ": "
                + (chosen != null ? chosen.getName() : "default voice") + ", pitch " + pitch);
    }

    /** Installed English voices already measured as the wanted gender, best first. */
    private static List<Voice> classifiedVoices(Context context, TextToSpeech tts, boolean male) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<Voice> result = new ArrayList<>();
        Set<Voice> all;
        try {
            all = tts.getVoices();
        } catch (Exception e) {
            return result;
        }
        if (all == null) return result;

        for (Voice v : all) {
            if (!isUsableEnglish(v)) continue;
            float f0 = prefs.getFloat(KEY_PREFIX + v.getName(), -1f);
            if (f0 <= 0f) continue;
            if ((f0 < MALE_BELOW_HZ) == male) result.add(v);
        }
        Collections.sort(result, (a, b) -> {
            int byOrder = Integer.compare(sortKey(a), sortKey(b));
            return byOrder != 0 ? byOrder : a.getName().compareTo(b.getName());
        });
        return result;
    }

    private static boolean isUsableEnglish(Voice v) {
        if (v == null || v.getLocale() == null || !"en".equals(v.getLocale().getLanguage())) return false;
        Set<String> features = v.getFeatures();
        return features == null || !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    /** US English first, then voices that work offline (they support the word highlighting reliably). */
    private static int sortKey(Voice v) {
        int key = 0;
        if (!"US".equals(v.getLocale().getCountry())) key += 2;
        if (v.isNetworkConnectionRequired()) key += 1;
        return key;
    }

    // ------------------------------------------------------------------
    // One-time measuring of every English voice's pitch (in the background)
    // ------------------------------------------------------------------

    /** Measures any voice not measured yet. Cheap to call repeatedly: it runs once per app launch. */
    static void ensureClassified(final Context context) {
        if (started) return;
        started = true;
        final Context app = context.getApplicationContext();
        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(app, status -> {
            if (status != TextToSpeech.SUCCESS) {
                shutdown(holder[0]);
                return;
            }
            new Thread(() -> {
                try {
                    probeAll(app, holder[0]);
                } catch (Exception e) {
                    Log.e(TAG, "Voice measuring failed", e);
                } finally {
                    shutdown(holder[0]);
                }
            }, "voice-probe").start();
        });
    }

    private static void shutdown(TextToSpeech tts) {
        try { if (tts != null) tts.shutdown(); } catch (Exception ignored) {}
    }

    private static void probeAll(Context app, TextToSpeech tts) throws InterruptedException {
        Set<Voice> all = tts.getVoices();
        if (all == null || all.isEmpty()) return;

        SharedPreferences prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        List<Voice> todo = new ArrayList<>();
        for (Voice v : all) {
            if (isUsableEnglish(v) && !prefs.contains(KEY_PREFIX + v.getName())) todo.add(v);
        }
        Collections.sort(todo, (a, b) -> {
            int byOrder = Integer.compare(sortKey(a), sortKey(b));
            return byOrder != 0 ? byOrder : a.getName().compareTo(b.getName());
        });
        if (todo.size() > MAX_VOICES) todo = todo.subList(0, MAX_VOICES);
        if (todo.isEmpty()) return;

        final AtomicReference<CountDownLatch> waiting = new AtomicReference<>();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id)  { CountDownLatch l = waiting.get(); if (l != null) l.countDown(); }
            @Override public void onError(String id) { CountDownLatch l = waiting.get(); if (l != null) l.countDown(); }
        });

        File wav = new File(app.getCacheDir(), "voice_probe.wav");
        for (int i = 0; i < todo.size(); i++) {
            Voice v = todo.get(i);
            try {
                if (tts.setVoice(v) != TextToSpeech.SUCCESS) continue;
                CountDownLatch latch = new CountDownLatch(1);
                waiting.set(latch);
                if (wav.exists()) wav.delete();
                if (tts.synthesizeToFile(PROBE_TEXT, null, wav, "probe_" + i) != TextToSpeech.SUCCESS) continue;
                if (!latch.await(12, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Timed out measuring " + v.getName());
                    continue;
                }
                float f0 = estimateMedianF0(wav);
                prefs.edit().putFloat(KEY_PREFIX + v.getName(), f0 > 0f ? f0 : -1f).apply();
                Log.i(TAG, "Measured " + v.getName() + " (" + v.getLocale() + (v.isNetworkConnectionRequired() ? ", network" : ", local")
                        + "): median F0 = " + Math.round(f0) + " Hz -> "
                        + (f0 <= 0f ? "unknown" : (f0 < MALE_BELOW_HZ ? "MALE" : "FEMALE")));
            } catch (Exception e) {
                Log.e(TAG, "Could not measure " + v.getName(), e);
            }
        }
        if (wav.exists()) wav.delete();
    }

    // ------------------------------------------------------------------
    // Pitch estimation from the synthesized audio
    // ------------------------------------------------------------------

    /** Median fundamental frequency (Hz) of the voiced parts of a 16-bit PCM WAV, or -1 if none found. */
    private static float estimateMedianF0(File wav) throws Exception {
        byte[] bytes;
        try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) {
            bytes = new byte[(int) raf.length()];
            raf.readFully(bytes);
        }
        if (bytes.length < 64) return -1f;

        int channels   = le16(bytes, 22);
        int sampleRate = le32(bytes, 24);
        int bits       = le16(bytes, 34);
        if (bits != 16 || channels < 1 || sampleRate < 8000) return -1f;

        // Find the "data" chunk instead of assuming a 44-byte header.
        int dataStart = -1, dataLen = 0;
        for (int p = 12; p + 8 <= bytes.length; ) {
            int size = le32(bytes, p + 4);
            if (bytes[p] == 'd' && bytes[p + 1] == 'a' && bytes[p + 2] == 't' && bytes[p + 3] == 'a') {
                dataStart = p + 8;
                dataLen = Math.min(size < 0 ? Integer.MAX_VALUE : size, bytes.length - dataStart);
                break;
            }
            p += 8 + size + (size & 1);
        }
        if (dataStart < 0 || dataLen < sampleRate / 2) return -1f;   // less than half a second

        int frames = dataLen / (2 * channels);
        float[] x = new float[frames];
        for (int i = 0; i < frames; i++) {
            int idx = dataStart + i * 2 * channels;          // first channel only
            x[i] = (short) ((bytes[idx + 1] << 8) | (bytes[idx] & 0xFF));
        }

        int frameLen = (int) (sampleRate * 0.046f);   // ~46 ms
        int hop      = frameLen / 2;
        int minLag   = sampleRate / 400;              // up to 400 Hz
        int maxLag   = sampleRate / 70;               // down to 70 Hz
        if (frameLen <= maxLag + 1) return -1f;

        // Loudest frame, so quiet frames (pauses, breaths) can be ignored.
        double maxRms = 0;
        for (int s = 0; s + frameLen <= frames; s += hop) maxRms = Math.max(maxRms, rms(x, s, frameLen));
        if (maxRms < 200) return -1f;

        List<Float> f0s = new ArrayList<>();
        for (int s = 0; s + frameLen <= frames; s += hop) {
            if (rms(x, s, frameLen) < maxRms * 0.35) continue;
            double r0 = 0;
            for (int i = 0; i < frameLen - maxLag; i++) r0 += (double) x[s + i] * x[s + i];
            if (r0 <= 0) continue;
            double bestR = 0;
            int bestLag = -1;
            for (int lag = minLag; lag <= maxLag; lag++) {
                double r = 0;
                for (int i = 0; i < frameLen - maxLag; i++) r += (double) x[s + i] * x[s + i + lag];
                if (r > bestR) { bestR = r; bestLag = lag; }
            }
            // Strongly periodic = voiced speech; ignore noisy / unvoiced frames.
            if (bestLag > 0 && bestR / r0 > 0.55) f0s.add((float) sampleRate / bestLag);
        }
        if (f0s.size() < 5) return -1f;
        Float[] arr = f0s.toArray(new Float[0]);
        Arrays.sort(arr);
        return arr[arr.length / 2];
    }

    private static double rms(float[] x, int start, int len) {
        double sum = 0;
        for (int i = start; i < start + len; i++) sum += (double) x[i] * x[i];
        return Math.sqrt(sum / len);
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
