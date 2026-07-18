package com.visualed.voice;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class VoiceRegistrationManager {

    public interface RegistrationListener {
        void speak(String prompt);
        void onStateChanged(State state);
        void onRegistrationComplete(RegistrationData data);
        void onNeedsListening();
    }

    public enum State {
        WELCOME, FIRST_NAME, LAST_NAME, EMAIL_USERNAME, EMAIL_PROVIDER,
        PASSWORD, FINAL_REVIEW, PHONETIC_SPELLING, CONFIRMING, DONE
    }

    public static final class RegistrationData {
        public String firstName = "";
        public String lastName = "";
        public String emailUsername = "";
        public String emailDomain = "";
        public String password = "";

        public String getFirstName() { return firstName; }
        public String getLastName() { return lastName; }
        public String getEmailUsername() { return emailUsername; }
        public String getEmailDomain() { return emailDomain; }
        public String getPassword() { return password; }
        public String getEmail() { return emailUsername + "@" + emailDomain; }
    }

    private static final int MAX_NORMAL_ATTEMPTS = 2;
    private static final String[] PROVIDERS = { "gmail.com", "yahoo.com", "outlook.com" };
    private static final Map<String, Character> PHONETIC = new LinkedHashMap<>();
    static {
        PHONETIC.put("apple", 'a'); PHONETIC.put("ball", 'b'); PHONETIC.put("cat", 'c');
        PHONETIC.put("dog", 'd'); PHONETIC.put("elephant", 'e'); PHONETIC.put("fish", 'f');
        PHONETIC.put("goat", 'g'); PHONETIC.put("house", 'h'); PHONETIC.put("igloo", 'i');
        PHONETIC.put("juice", 'j'); PHONETIC.put("kite", 'k'); PHONETIC.put("lion", 'l');
        PHONETIC.put("monkey", 'm'); PHONETIC.put("nose", 'n'); PHONETIC.put("orange", 'o');
        PHONETIC.put("pig", 'p'); PHONETIC.put("queen", 'q'); PHONETIC.put("rabbit", 'r');
        PHONETIC.put("sun", 's'); PHONETIC.put("table", 't'); PHONETIC.put("umbrella", 'u');
        PHONETIC.put("van", 'v'); PHONETIC.put("water", 'w'); PHONETIC.put("xylophone", 'x');
        PHONETIC.put("yellow", 'y'); PHONETIC.put("zebra", 'z');
    }

    private final RegistrationListener listener;
    public final RegistrationData data = new RegistrationData();

    private State state = State.WELCOME;
    private String pendingValue = "";
    private State stateBeforeConfirm = State.FIRST_NAME;
    private int attemptCount = 0;
    private final StringBuilder spellingBuffer = new StringBuilder();
    private final Random random = new Random();

    public VoiceRegistrationManager(RegistrationListener listener) {
        this.listener = listener;
    }

    public void start() {
        transition(State.WELCOME);
        listener.speak(
                "Welcome to Visual E D registration. I will guide you step by step. " +
                "You can say repeat anytime to hear instructions again. " +
                "Let's begin. Please say your first name.");
        transition(State.FIRST_NAME);
        listener.onNeedsListening();
    }

    public void onSttResult(SttResult result) {
        String text = result.getNormalizedText().trim();

        if (text.equalsIgnoreCase("repeat")) { repeatPrompt(); return; }

        switch (state) {
            case FIRST_NAME:
            case LAST_NAME:
            case EMAIL_USERNAME:
                handleFieldInput(text, result.getConfidence());
                break;
            case CONFIRMING: handleConfirmation(text); break;
            case EMAIL_PROVIDER: handleProviderChoice(text); break;
            case PHONETIC_SPELLING: handlePhoneticInput(text); break;
            case PASSWORD: handlePasswordChoice(text); break;
            case FINAL_REVIEW: handleFinalReview(text); break;
            default: listener.onNeedsListening(); break;
        }
    }

    private void handleFieldInput(String text, float confidence) {
        attemptCount++;
        if (text.isEmpty() || confidence < 0.4f) {
            if (attemptCount >= MAX_NORMAL_ATTEMPTS) { enterPhoneticMode(); return; }
            listener.speak("I didn't catch that clearly. Please say it again.");
            listener.onNeedsListening();
            return;
        }
        pendingValue = text;
        stateBeforeConfirm = state;
        transition(State.CONFIRMING);
        listener.speak("I heard " + spellOut(text) + ", " + text + ". Say yes to confirm, or no to try again.");
        listener.onNeedsListening();
    }

    private void handleConfirmation(String text) {
        if (isYes(text)) {
            commitPending();
            attemptCount = 0;
        } else if (isNo(text)) {
            if (attemptCount >= MAX_NORMAL_ATTEMPTS) {
                enterPhoneticMode();
            } else {
                transition(stateBeforeConfirm);
                listener.speak("Okay, let's try again. " + promptFor(stateBeforeConfirm));
                listener.onNeedsListening();
            }
        } else {
            listener.speak("Please say yes or no.");
            listener.onNeedsListening();
        }
    }

    private void commitPending() {
        switch (stateBeforeConfirm) {
            case FIRST_NAME:
                data.firstName = pendingValue;
                transition(State.LAST_NAME);
                listener.speak("Great. Now, please say your last name.");
                break;
            case LAST_NAME:
                data.lastName = pendingValue;
                transition(State.EMAIL_USERNAME);
                listener.speak("Now your email address. First, tell me the username — " +
                        "the part before the at sign. You can say it or spell it.");
                break;
            case EMAIL_USERNAME:
                data.emailUsername = pendingValue.replace(" ", "").toLowerCase(Locale.ROOT);
                transition(State.EMAIL_PROVIDER);
                listener.speak("Which email provider? Say one for Gmail, two for Yahoo, " +
                        "three for Outlook.");
                break;
            default:
                break;
        }
        listener.onNeedsListening();
    }

    private void handleProviderChoice(String text) {
        int choice;
        if (text.contains("one") || text.contains("1") || text.contains("gmail")) choice = 0;
        else if (text.contains("two") || text.contains("2") || text.contains("yahoo")) choice = 1;
        else if (text.contains("three") || text.contains("3") || text.contains("outlook")) choice = 2;
        else choice = -1;

        if (choice == -1) {
            listener.speak("Please say one for Gmail, two for Yahoo, or three for Outlook.");
            listener.onNeedsListening();
            return;
        }
        data.emailDomain = PROVIDERS[choice];
        transition(State.PASSWORD);
        listener.speak("Your email is " + data.emailUsername + " at " + data.emailDomain + ". " +
                "Now for your password. Say generate, and I will create a secure " +
                "password and read it to you twice. Or say pin to use a six digit voice pin.");
        listener.onNeedsListening();
    }

    private void handlePasswordChoice(String text) {
        if (text.contains("generate")) {
            data.password = generatePassword();
            String spelled = spellOut(data.password);
            listener.speak("Your password is: " + spelled + ". Again: " + spelled + ". " +
                    "Please remember it. Moving to final review.");
            goToFinalReview();
        } else if (text.contains("pin")) {
            listener.speak("Please say six digits, one at a time.");
            transition(State.PASSWORD);
            listener.onNeedsListening();
        } else if (containsDigit(text)) {
            String digits = filterDigits(text);
            if (digits.length() == 6) {
                data.password = digits;
                StringBuilder spoken = new StringBuilder();
                for (int i = 0; i < digits.length(); i++) {
                    if (i > 0) spoken.append(", ");
                    spoken.append(digits.charAt(i));
                }
                listener.speak("Your pin is " + spoken + ". ");
                goToFinalReview();
            } else {
                listener.speak("I need exactly six digits. Please try again.");
                listener.onNeedsListening();
            }
        } else {
            listener.speak("Say generate for an automatic password, or pin for a six digit pin.");
            listener.onNeedsListening();
        }
    }

    private void goToFinalReview() {
        transition(State.FINAL_REVIEW);
        listener.speak("Let me review. First name: " + data.firstName + ". Last name: " + data.lastName + ". " +
                "Email: " + data.emailUsername + " at " + data.emailDomain + ". " +
                "Say confirm to create your account, or say the field name — " +
                "first name, last name, or email — to change it.");
        listener.onNeedsListening();
    }

    private void handleFinalReview(String text) {
        if (text.contains("confirm") || isYes(text)) {
            transition(State.DONE);
            listener.speak("Creating your account. Welcome to Visual E D, " + data.firstName + "!");
            listener.onRegistrationComplete(data);
        } else if (text.contains("first")) {
            attemptCount = 0; transition(State.FIRST_NAME);
            listener.speak("Okay. Please say your first name."); listener.onNeedsListening();
        } else if (text.contains("last")) {
            attemptCount = 0; transition(State.LAST_NAME);
            listener.speak("Okay. Please say your last name."); listener.onNeedsListening();
        } else if (text.contains("email")) {
            attemptCount = 0; transition(State.EMAIL_USERNAME);
            listener.speak("Okay. Please say your email username."); listener.onNeedsListening();
        } else {
            listener.speak("Say confirm, or the field name to change it.");
            listener.onNeedsListening();
        }
    }

    private void enterPhoneticMode() {
        spellingBuffer.setLength(0);
        transition(State.PHONETIC_SPELLING);
        listener.speak("Let's spell it instead, one letter at a time. " +
                "Say each letter using a word, like A as in apple, or B as in ball. " +
                "Say done when finished, or undo to remove the last letter.");
        listener.onNeedsListening();
    }

    private void handlePhoneticInput(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("done")) {
            pendingValue = spellingBuffer.toString();
            transition(State.CONFIRMING);
            listener.speak("You spelled " + spellOut(pendingValue) + ", " + pendingValue + ". " +
                    "Say yes to confirm, or no to start over.");
        } else if (lower.contains("undo")) {
            if (spellingBuffer.length() > 0) spellingBuffer.deleteCharAt(spellingBuffer.length() - 1);
            listener.speak("Removed. Current spelling: " + spellOut(spellingBuffer.toString()) + ". Continue.");
        } else {
            Character letter = null;
            for (Map.Entry<String, Character> entry : PHONETIC.entrySet()) {
                if (lower.contains(entry.getKey())) { letter = entry.getValue(); break; }
            }
            if (letter == null) {
                String trimmed = lower.trim();
                if (trimmed.length() == 1 && trimmed.charAt(0) >= 'a' && trimmed.charAt(0) <= 'z') {
                    letter = trimmed.charAt(0);
                }
            }
            if (letter != null) {
                spellingBuffer.append(letter);
                listener.speak(letter + ". Next letter, or say done.");
            } else {
                listener.speak("I didn't get that letter. Say it like, A as in apple.");
            }
        }
        listener.onNeedsListening();
    }

    private void transition(State newState) {
        state = newState;
        listener.onStateChanged(newState);
    }

    private void repeatPrompt() {
        listener.speak(promptFor(state));
        listener.onNeedsListening();
    }

    private String promptFor(State s) {
        switch (s) {
            case FIRST_NAME: return "Please say your first name.";
            case LAST_NAME: return "Please say your last name.";
            case EMAIL_USERNAME: return "Please say your email username, the part before the at sign.";
            case EMAIL_PROVIDER: return "Say one for Gmail, two for Yahoo, three for Outlook.";
            case PASSWORD: return "Say generate for an automatic password, or pin for a six digit pin.";
            case FINAL_REVIEW: return "Say confirm to create your account, or a field name to change it.";
            default: return "Please continue.";
        }
    }

    private String spellOut(String s) {
        StringBuilder sb = new StringBuilder();
        String upper = s.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            if (i > 0) sb.append(", ");
            char c = upper.charAt(i);
            sb.append(c == ' ' ? "space" : String.valueOf(c));
        }
        return sb.toString();
    }

    private boolean isYes(String t) {
        String lower = t.toLowerCase(Locale.ROOT);
        return lower.contains("yes") || lower.contains("yeah") || lower.contains("oo") ||
                lower.contains("opo") || lower.contains("correct") || lower.contains("confirm");
    }

    private boolean isNo(String t) {
        String lower = t.toLowerCase(Locale.ROOT);
        return lower.contains("no") || lower.contains("hindi") || lower.contains("wrong") ||
                lower.contains("again") || lower.contains("ulit");
    }

    private boolean containsDigit(String t) {
        for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) return true;
        return false;
    }

    private String filterDigits(String t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) sb.append(t.charAt(i));
        return sb.toString();
    }

    private String generatePassword() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(random.nextInt(chars.length())));
        return sb.toString();
    }
}
