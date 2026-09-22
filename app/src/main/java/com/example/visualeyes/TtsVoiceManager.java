package com.example.visualeyes;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The assistant voice each student picked for the app's spoken prompts (Google Cloud
 * text-to-speech, used through GoogleTtsManager). Stored per student account — see
 * {@link AccountPrefs} — so a different account on the same phone starts on the default voice
 * until it chooses its own. The choice is also saved to the student's row in the database
 * (VoiceSettingsSync) so it follows the account to another phone.
 *
 * Note: the material reader speaks with the phone's own text-to-speech engine (it needs the
 * per-word highlighting), so this choice does not change how a material is read aloud.
 */
public final class TtsVoiceManager {

    /** One selectable voice. {@code id} is the Google voice name sent to the API. */
    public static final class Option {
        public final String id;
        public final String label;
        public final String gender;   // "FEMALE" / "MALE" — sent along with the name

        Option(String id, String label, String gender) {
            this.id = id;
            this.label = label;
            this.gender = gender;
        }
    }

    /** In the order they are offered. The first one is the default. */
    public static final Option[] OPTIONS = {
            new Option("en-US-Neural2-F", "Female 1 (default)", "FEMALE"),
            new Option("en-US-Neural2-D", "Male 1",             "MALE"),
            new Option("en-US-Neural2-C", "Female 2",           "FEMALE"),
            new Option("en-US-Neural2-J", "Male 2",             "MALE"),
    };

    private static final String PREF_NAME = "VisualEyesPrefs";
    private static final String KEY_VOICE = "tts_voice";

    private TtsVoiceManager() {}

    public static Option defaultOption() {
        return OPTIONS[0];
    }

    /** The option with this voice id, or null if it isn't one of ours. */
    public static Option find(String id) {
        if (id == null) return null;
        for (Option o : OPTIONS) if (o.id.equals(id)) return o;
        return null;
    }

    public static int indexOf(Option option) {
        for (int i = 0; i < OPTIONS.length; i++) if (OPTIONS[i] == option) return i;
        return 0;
    }

    /** The current student's voice — the default if they never chose one. */
    public static Option getOption(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Option saved = find(prefs.getString(AccountPrefs.key(context, KEY_VOICE), null));
        return saved != null ? saved : defaultOption();
    }

    /** Remembers the choice for the current student on this phone. Unknown ids are ignored. */
    public static void setVoice(Context context, String voiceId) {
        if (find(voiceId) == null) return;
        context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(AccountPrefs.key(context, KEY_VOICE), voiceId)
                .apply();
    }
}
