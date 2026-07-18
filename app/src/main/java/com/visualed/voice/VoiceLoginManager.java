package com.visualed.voice;

import java.util.Locale;

public class VoiceLoginManager {

    public interface LoginListener {
        void speak(String prompt);
        void onStateChanged(State state);
        void onNeedsListening();

        void onAttemptLogin(String email, String password);
    }

    public enum State { WELCOME, EMAIL_USERNAME, EMAIL_PROVIDER, PASSWORD, DONE }

    private static final String[] PROVIDERS = { "gmail.com", "yahoo.com", "outlook.com" };

    private final LoginListener listener;
    private State state = State.WELCOME;
    private String emailUsername = "";
    private String emailDomain = "";

    public VoiceLoginManager(LoginListener listener) {
        this.listener = listener;
    }

    public void start() {
        transition(State.WELCOME);
        listener.speak("Welcome back to Visual E D. Please say your email username, " +
                "the part before the at sign.");
        transition(State.EMAIL_USERNAME);
        listener.onNeedsListening();
    }

    public void retryPassword() {
        transition(State.PASSWORD);
        listener.speak("That didn't match. Please say your password again.");
        listener.onNeedsListening();
    }

    public void restart() {
        emailUsername = "";
        emailDomain = "";
        start();
    }

    public void onSttResult(SttResult result) {
        String text = result.getNormalizedText().trim();
        switch (state) {
            case EMAIL_USERNAME: handleEmailUsername(text); break;
            case EMAIL_PROVIDER: handleProviderChoice(text); break;
            case PASSWORD: handlePassword(text); break;
            default: listener.onNeedsListening(); break;
        }
    }

    private void handleEmailUsername(String text) {
        if (text.isEmpty()) {
            listener.speak("I didn't catch that. Please say your email username again.");
            listener.onNeedsListening();
            return;
        }
        emailUsername = text.replace(" ", "").toLowerCase(Locale.ROOT);
        transition(State.EMAIL_PROVIDER);
        listener.speak("Got it. Which email provider? Say one for Gmail, two for Yahoo, three for Outlook.");
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
        emailDomain = PROVIDERS[choice];
        transition(State.PASSWORD);
        listener.speak("Now please say or spell your password.");
        listener.onNeedsListening();
    }

    private void handlePassword(String text) {
        String password = text.replace(" ", "");
        if (password.isEmpty()) {
            listener.speak("I didn't catch that. Please say your password again.");
            listener.onNeedsListening();
            return;
        }
        transition(State.DONE);
        String email = emailUsername + "@" + emailDomain;
        listener.speak("Checking your account.");
        listener.onAttemptLogin(email, password);
    }

    private void transition(State newState) {
        state = newState;
        listener.onStateChanged(newState);
    }
}
