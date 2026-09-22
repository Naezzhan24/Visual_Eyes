package com.example.visualeyes;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/**
 * Lets a student choose the assistant voice for the app's spoken prompts. Every voice is heard
 * BEFORE it is kept: tapping a voice (or saying "next" / its name) plays a sample in that voice,
 * and only "Keep this voice" saves it — to this student's account, so it follows them to another
 * phone and never leaks to another student on this one.
 *
 * Fully usable by voice: "next", "previous", a voice name ("male two"), "keep", "cancel", "repeat".
 * Listening uses the app's Cloud STT cascade (SttCascadeSession); the buttons always work too.
 */
public class VoiceSettingsActivity extends AppCompatActivity {

    private static final String TAG = "VoiceSettings";
    private static final float  SAMPLE_RATE = 0.95f;
    // After this many silent listens in a row it stops nagging; the buttons stay.
    private static final int    MAX_SILENT_PROMPTS = 2;

    private final Button[] optionButtons = new Button[TtsVoiceManager.OPTIONS.length];
    private Button   btnKeep, btnCancel;
    private TextView txtStatus, txtIntro;

    private GoogleTtsManager  googleTts;
    private GoogleSttManager  googleStt;
    private SttCascadeSession cascade;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean voiceAvailable;
    private boolean paused        = false;
    private boolean isListening   = false;
    private boolean isSpeaking    = false;
    private int     voiceSession  = 0;
    private int     speakGen      = 0;
    private int     silentPrompts = 0;

    private int savedIndex;      // the voice the account is using now
    private int selectedIndex;   // the voice currently being previewed
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_settings);

        txtStatus = findViewById(R.id.txtVoiceStatus);
        txtIntro  = findViewById(R.id.txtVoiceIntro);
        btnKeep   = findViewById(R.id.btnKeepVoice);
        btnCancel = findViewById(R.id.btnCancelVoice);
        int[] ids = {R.id.btnVoice0, R.id.btnVoice1, R.id.btnVoice2, R.id.btnVoice3};
        for (int i = 0; i < optionButtons.length; i++) {
            final int index = i;
            optionButtons[i] = findViewById(ids[i]);
            optionButtons[i].setText((i + 1) + ". " + TtsVoiceManager.OPTIONS[i].label);
            optionButtons[i].setOnClickListener(v -> preview(index));
            UiAnim.attachPressFeedback(optionButtons[i]);
        }
        applyFontSize();

        savedIndex    = TtsVoiceManager.indexOf(TtsVoiceManager.getOption(this));
        selectedIndex = savedIndex;
        highlight(selectedIndex);

        btnKeep.setOnClickListener(v -> keepSelected());
        btnCancel.setOnClickListener(v -> cancel());
        UiAnim.attachPressFeedback(btnKeep);
        UiAnim.attachPressFeedback(btnCancel);

        googleTts = new GoogleTtsManager(this);
        voiceAvailable = MicPermissionHelper.hasAudioPermission(this);
        if (voiceAvailable) {
            googleStt = new GoogleSttManager(this, false);
            // No Vosk here: a voice name is short, and the buttons cover the offline case.
            cascade = new SttCascadeSession(googleStt, null, handler, true);
        }

        setStatus("You are using " + TtsVoiceManager.OPTIONS[savedIndex].label);
        handler.postDelayed(this::speakIntro, 500);
    }

    private void applyFontSize() {
        float b = FontSizeManager.getFontSize(this);
        for (Button button : optionButtons) button.setTextSize(Math.max(16f, b - 4));
        txtIntro.setTextSize(Math.max(15f, b - 6));
    }

    // ------------------------------------------------------------------
    // Choosing and previewing
    // ------------------------------------------------------------------

    private void speakIntro() {
        String msg = "Assistant voice. You are using " + TtsVoiceManager.OPTIONS[savedIndex].label + ". "
                + (voiceAvailable
                    ? "Say next to hear another voice, keep to save the voice you just heard, or cancel to go back."
                    : "Tap a voice to hear it, then tap Keep this voice.");
        speak(msg, TtsVoiceManager.OPTIONS[savedIndex], this::startListening);
    }

    /** Plays a sample in the given voice and makes it the one that "Keep" would save. */
    private void preview(int index) {
        if (saving) return;
        selectedIndex = index;
        highlight(index);
        TtsVoiceManager.Option option = TtsVoiceManager.OPTIONS[index];
        setStatus("Voice " + (index + 1) + ": " + option.label);
        silentPrompts = 0;
        speak("Hello, this is " + option.label.replace(" (default)", "") + ". "
                + (voiceAvailable ? "Say keep to use this voice, or next to hear another."
                                  : "Tap Keep this voice to use it."),
                option, this::startListening);
    }

    private void highlight(int selected) {
        for (int i = 0; i < optionButtons.length; i++) {
            boolean on = i == selected;
            optionButtons[i].setBackgroundResource(on ? R.drawable.bg_btn_yes_blue : R.drawable.bg_btn_login_maroon);
            optionButtons[i].setSelected(on);
        }
    }

    private void keepSelected() {
        if (saving) return;
        saving = true;
        stopSpeechNow();

        final TtsVoiceManager.Option option = TtsVoiceManager.OPTIONS[selectedIndex];
        // This phone first, so the new voice is used straight away…
        TtsVoiceManager.setVoice(this, option.id);
        savedIndex = selectedIndex;
        setStatus("Saving " + option.label + "...");

        // …then the account, so it follows them to another phone.
        VoiceSettingsSync.save(this, option.id, null, (ok, detail) -> {
            if (isFinishing() || isDestroyed()) return;
            if (!ok) {
                Toast.makeText(this, "Saved on this phone only — not saved to your account: " + detail,
                        Toast.LENGTH_LONG).show();
            }
            // Spoken in the voice that was just chosen (it is the current one now).
            speak(ok ? "Voice saved. I will use this voice from now on."
                     : "Voice saved on this phone.",
                    option, this::finishScreen);
            handler.postDelayed(this::finishScreen, 15000); // never stuck if the voice can't finish
        });
    }

    private void cancel() {
        stopSpeechNow();
        finishScreen();
    }

    private void finishScreen() {
        if (isFinishing() || isDestroyed()) return;
        finish();
    }

    // ------------------------------------------------------------------
    // Speech output / input
    // ------------------------------------------------------------------

    private void speak(String message, TtsVoiceManager.Option option, Runnable then) {
        stopListening();
        isSpeaking = true;
        final int gen = ++speakGen;
        googleTts.speak(message, SAMPLE_RATE, option, () -> {
            if (gen != speakGen) return;
            isSpeaking = false;
            if (then != null) then.run();
        });
    }

    private void stopSpeechNow() {
        speakGen++;
        isSpeaking = false;
        if (googleTts != null) googleTts.stopSpeaking();
        stopListening();
    }

    private void startListening() {
        if (!voiceAvailable || isListening || isSpeaking || paused || saving) return;
        isListening = true;
        final int mySession = ++voiceSession;

        cascade.cascade(this, "command", "voice name", new SttCascadeSession.Listener() {
            @Override public void onListeningStarted() {
                if (mySession != voiceSession) return;
                setStatus("Listening... say next, keep, or cancel");
            }

            @Override public void onPartialResult(String partial) {
                if (mySession != voiceSession) return;
                setStatus("Hearing: " + partial);
            }

            @Override public void onTranscript(String transcript) {
                if (mySession != voiceSession) return;
                isListening = false;
                handleCommand(transcript);
            }

            @Override public void onExhausted() {
                if (mySession != voiceSession) return;
                isListening = false;
                silentPrompts++;
                if (silentPrompts <= MAX_SILENT_PROMPTS) {
                    speak("Say next, keep, or cancel.", TtsVoiceManager.OPTIONS[selectedIndex], VoiceSettingsActivity.this::startListening);
                } else {
                    // Stop talking over them, but keep the mic open quietly.
                    setStatus("Still listening — say next, keep, or cancel");
                    handler.postDelayed(VoiceSettingsActivity.this::startListening, 500);
                }
            }
        });
    }

    private void stopListening() {
        voiceSession++;
        if (cascade != null) cascade.cancel();
        isListening = false;
    }

    private void handleCommand(String spoken) {
        // Anything heard while the app is talking is its own voice or a late result — never a command.
        if (isSpeaking) return;
        String t = normalize(spoken);
        Log.d(TAG, "heard \"" + t + "\"");
        setStatus("Heard: " + t);

        // Short words must match as whole words: "oo" would otherwise hit inside "too" ("female too").
        if (containsAny(t, "cancel", "exit", "go back", "no thanks")) { cancel(); return; }
        if (containsAny(t, "keep", "save", "use this")
                || hasWord(t, "yes") || hasWord(t, "okay") || hasWord(t, "sige") || hasWord(t, "oo")) {
            keepSelected();
            return;
        }
        if (containsAny(t, "repeat", "again", "ulit")) { preview(selectedIndex); return; }
        if (containsAny(t, "previous", "before", "last one")) {
            preview((selectedIndex - 1 + TtsVoiceManager.OPTIONS.length) % TtsVoiceManager.OPTIONS.length);
            return;
        }
        if (containsAny(t, "next", "another", "other", "change", "try")) {
            preview((selectedIndex + 1) % TtsVoiceManager.OPTIONS.length);
            return;
        }
        int named = parseVoiceName(t);
        if (named >= 0) { preview(named); return; }

        silentPrompts++;
        speak("Sorry. Say next to hear another voice, keep to save this one, or cancel.",
                TtsVoiceManager.OPTIONS[selectedIndex], this::startListening);
    }

    /**
     * "female one" / "male two" / "default" -> index into OPTIONS, or -1. A gender alone picks that
     * gender's first voice. "female" is checked before "male" because it contains it.
     */
    private static int parseVoiceName(String t) {
        boolean female = t.contains("female") || hasWord(t, "woman") || hasWord(t, "babae");
        boolean male   = !female && (t.contains("male") || hasWord(t, "man") || hasWord(t, "lalaki"));
        // "two" is often heard as "to" / "too".
        boolean two    = hasWord(t, "two") || hasWord(t, "2") || hasWord(t, "second")
                || hasWord(t, "to") || hasWord(t, "too");
        if (t.contains("default")) return 0;
        if (female) return two ? 2 : 0;
        if (male)   return two ? 3 : 1;
        return -1;
    }

    /** True if {@code word} appears as a whole word (not inside a longer one). */
    private static boolean hasWord(String text, String word) {
        return (" " + text + " ").contains(" " + word + " ");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String n : needles) if (text.contains(n)) return true;
        return false;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private void setStatus(String text) {
        if (txtStatus != null) txtStatus.setText(text);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        stopSpeechNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (paused) {
            paused = false;
            handler.postDelayed(this::startListening, 500);
        }
    }

    @Override
    public void onBackPressed() {
        cancel();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopListening();
        if (googleTts != null) googleTts.destroy();
        if (googleStt != null) googleStt.destroy();
        super.onDestroy();
    }
}
