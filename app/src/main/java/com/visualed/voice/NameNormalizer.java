package com.visualed.voice;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class NameNormalizer {

    public enum FieldType { FIRST_NAME, LAST_NAME, EMAIL_USER, COMMAND, FREE_TEXT }

    public static final class NormalizedResult {
        private final String text;
        private final float matchScore;
        private final boolean wasCorrected;

        public NormalizedResult(String text, float matchScore, boolean wasCorrected) {
            this.text = text;
            this.matchScore = matchScore;
            this.wasCorrected = wasCorrected;
        }

        public String getText() { return text; }
        public float getMatchScore() { return matchScore; }
        public boolean getWasCorrected() { return wasCorrected; }
    }

    private static final Pattern NON_NAME_CHARS = Pattern.compile("[^a-zñ' \\-@.]");

    private final List<String> firstNames;
    private final List<String> surnames;

    private final Map<String, String> sttCorrections = new LinkedHashMap<>();
    {
        sttCorrections.put("dela cruise", "dela cruz");
        sttCorrections.put("della cruz", "dela cruz");
        sttCorrections.put("the la cruz", "dela cruz");
        sttCorrections.put("sun toss", "santos");
        sttCorrections.put("race", "reyes");
        sttCorrections.put("buy tista", "bautista");
        sttCorrections.put("one", "juan");
    }

    public NameNormalizer(Context context) {
        firstNames = loadDict(context, "names/first_names_ph.txt");
        surnames = loadDict(context, "names/surnames_ph.txt");
    }

    public NormalizedResult normalize(String raw, FieldType type) {
        if (raw == null || raw.trim().isEmpty()) return new NormalizedResult(raw == null ? "" : raw, 0f, false);
        String cleaned = NON_NAME_CHARS.matcher(raw.trim().toLowerCase(Locale.ROOT)).replaceAll("");
        if (cleaned.isEmpty()) return new NormalizedResult(raw, 0f, false);

        String text = cleaned;
        for (Map.Entry<String, String> entry : sttCorrections.entrySet()) {
            String wrong = entry.getKey();
            String right = entry.getValue();
            if (type == FieldType.FIRST_NAME && wrong.equals("one") && !text.equals("one")) continue;
            if (text.equals(wrong) || text.contains(wrong)) {
                text = text.replace(wrong, right);
            }
        }

        List<String> dict;
        if (type == FieldType.FIRST_NAME) dict = firstNames;
        else if (type == FieldType.LAST_NAME) dict = surnames;
        else dict = java.util.Collections.emptyList();

        if (!dict.isEmpty()) {
            String best = text;
            double bestScore = 0.0;
            for (String candidate : dict) {
                double score = TextSimilarity.normalizedLevenshtein(text, candidate.toLowerCase(Locale.ROOT));
                if (score > bestScore) { bestScore = score; best = candidate; }
            }
            if (bestScore >= 0.80) {
                return new NormalizedResult(
                        toTitleCase(best),
                        (float) bestScore,
                        !best.equalsIgnoreCase(text));
            }
        }

        return new NormalizedResult(toTitleCase(text), 0.5f, !text.equals(cleaned));
    }

    private String toTitleCase(String s) {
        String[] parts = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(" ");
            sb.append(capitalize(parts[i]));
        }
        return sb.toString();
    }

    private String capitalize(String part) {
        if (part.isEmpty()) return part;
        return Character.toUpperCase(part.charAt(0)) + part.substring(1);
    }

    private List<String> loadDict(Context context, String assetPath) {
        List<String> lines = new ArrayList<>();
        try (InputStream is = context.getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) lines.add(trimmed);
            }
        } catch (IOException e) {

        }
        return lines;
    }
}
