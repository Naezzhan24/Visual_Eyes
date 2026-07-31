package com.example.visualeyes;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies numbers found in narrated text (phone numbers, decades, years)
 * so they are spoken the way a human would read them instead of as plain
 * cardinals — e.g. "1995" as "nineteen ninety-five" rather than "one thousand
 * nine hundred ninety-five", and "555-123-4567" digit-by-digit rather than
 * "five hundred fifty-five...". Years cover 1000-2999; any other 4-digit
 * number in that span found in the text (a quantity, an ID, ...) will also
 * be read as a year-style number rather than a cardinal.
 */
public final class NumberSpeechFormatter {

    private static final String PHONE_RX =
            "\\(?\\d{3}\\)?[-.\\s]\\d{3}[-.\\s]\\d{4}\\b";
    private static final String DECADE_RX =
            "\\b[12]\\d{2}0s\\b";
    private static final String YEAR_RX =
            "\\b[12]\\d{3}\\b";

    private static final Pattern PHONE = Pattern.compile(PHONE_RX);
    private static final Pattern DECADE = Pattern.compile(DECADE_RX);
    private static final Pattern YEAR = Pattern.compile(YEAR_RX);

    // Order matters: alternation picks the first branch that matches at a
    // given position, so the most specific pattern (phone) must come first.
    private static final Pattern NUMERIC_TOKEN =
            Pattern.compile(PHONE_RX + "|" + DECADE_RX + "|" + YEAR_RX);

    private static final String[] ONES = {
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen"
    };
    private static final String[] TENS = {
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    };
    private static final String[] TENS_PLURAL = {
            "hundreds", "tens", "twenties", "thirties", "forties", "fifties",
            "sixties", "seventies", "eighties", "nineties"
    };
    private static final String[] DIGIT_WORDS = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"
    };

    private NumberSpeechFormatter() {}

    /**
     * Wraps recognized number spans in SSML {@code say-as} tags for engines
     * that honor SSML (e.g. Google Cloud TTS). The rest of the text is
     * XML-escaped and left untouched so ordinary quantities still read as
     * plain cardinals.
     */
    public static String toSsml(String text) {
        if (text == null || text.isEmpty()) return "<speak></speak>";

        Matcher m = NUMERIC_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder("<speak>");
        int last = 0;
        while (m.find()) {
            out.append(escapeXml(text.substring(last, m.start())));
            String token = m.group();
            if (PHONE.matcher(token).matches()) {
                out.append("<say-as interpret-as=\"telephone\">")
                        .append(escapeXml(token))
                        .append("</say-as>");
            } else if (DECADE.matcher(token).matches()) {
                out.append(escapeXml(spellDecade(token.substring(0, token.length() - 1))));
            } else {
                out.append("<say-as interpret-as=\"date\" format=\"y\">")
                        .append(escapeXml(token))
                        .append("</say-as>");
            }
            last = m.end();
        }
        out.append(escapeXml(text.substring(last)));
        out.append("</speak>");
        return out.toString();
    }

    /**
     * Rewrites the same number spans as literal words for engines with no
     * SSML support (Android's on-device TextToSpeech).
     */
    public static String toPlainSpeech(String text) {
        if (text == null || text.isEmpty()) return text;

        Matcher m = NUMERIC_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String token = m.group();
            if (PHONE.matcher(token).matches()) {
                out.append(spellDigits(token));
            } else if (DECADE.matcher(token).matches()) {
                out.append(spellDecade(token.substring(0, token.length() - 1)));
            } else {
                out.append(spellYear(token));
            }
            last = m.end();
        }
        out.append(text.substring(last));
        return out.toString();
    }

    /** Spells out every digit in {@code token} one at a time, ignoring non-digit characters. */
    public static String spellDigits(String token) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isDigit(c)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(DIGIT_WORDS[c - '0']);
            }
        }
        return sb.toString();
    }

    private static String spellYear(String yearStr) {
        int year = Integer.parseInt(yearStr);
        if (year == 1000) return "one thousand";
        if (year >= 2000 && year < 2010) {
            int ones = year % 100;
            return ones == 0 ? "two thousand" : "two thousand " + numberToWords(ones);
        }
        int firstTwo = year / 100;
        int lastTwo = year % 100;
        if (lastTwo == 0) return numberToWords(firstTwo) + " hundred";
        if (lastTwo < 10) return numberToWords(firstTwo) + " oh " + numberToWords(lastTwo);
        return numberToWords(firstTwo) + " " + numberToWords(lastTwo);
    }

    private static String spellDecade(String yearStr) {
        int year = Integer.parseInt(yearStr);
        int firstTwo = year / 100;
        int tensDigit = (year % 100) / 10;
        return numberToWords(firstTwo) + " " + TENS_PLURAL[tensDigit];
    }

    private static String numberToWords(int n) {
        if (n < 20) return ONES[n];
        int tens = n / 10;
        int ones = n % 10;
        return ones == 0 ? TENS[tens] : TENS[tens] + "-" + ONES[ones];
    }

    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
