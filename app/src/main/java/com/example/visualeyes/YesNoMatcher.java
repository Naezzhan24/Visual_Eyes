package com.example.visualeyes;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Turns what the recognizer heard into a yes / no for the assessment question
 * "Can you read this word?".
 *
 * Works on whole words, so a natural answer like "yeah I can" or "nope, too small"
 * counts, where the old exact/substring checks only accepted the bare word. When the
 * student corrects themselves ("no wait, yes") the LAST decisive word wins. English and
 * the Tagalog oo / opo / hindi are understood.
 */
final class YesNoMatcher {

    enum Answer { YES, NO, UNKNOWN }

    private static final Set<String> YES_WORDS = new HashSet<>(Arrays.asList(
            "yes", "yeah", "yea", "yep", "yup", "yas", "oo", "opo", "sure", "okay", "ok"));

    private static final Set<String> NO_WORDS = new HashSet<>(Arrays.asList(
            "no", "nope", "nah", "not", "cannot", "cant", "hindi", "hinde", "di"));

    /** "can't" / "don't" / "didn't" ... normalize to "can t" / "don t" — the "t" is a No. */
    private static final Set<String> NEGATABLE = new HashSet<>(Arrays.asList(
            "can", "don", "didn", "won", "couldn", "isn", "wasn", "aren", "doesn"));

    private YesNoMatcher() {}

    static Answer parse(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) return Answer.UNKNOWN;

        // "no" is often heard as "know" when it is the whole utterance.
        if (text.equals("know")) return Answer.NO;

        Answer result = Answer.UNKNOWN;
        String previous = "";
        for (String word : text.split(" ")) {
            if (YES_WORDS.contains(word)) {
                result = Answer.YES;
            } else if (NO_WORDS.contains(word)
                    || (word.equals("t") && NEGATABLE.contains(previous))) {
                result = Answer.NO;
            }
            previous = word;
        }
        return result;
    }

    static boolean isYes(String raw) { return parse(raw) == Answer.YES; }

    static boolean isNo(String raw)  { return parse(raw) == Answer.NO; }

    /** Lowercase, anything that isn't a letter becomes a space, whitespace collapsed. */
    static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.US)
                .replaceAll("[^a-z\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
