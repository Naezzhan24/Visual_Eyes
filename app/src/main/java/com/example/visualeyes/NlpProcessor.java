package com.example.visualeyes;

import java.text.Normalizer;
import java.util.Locale;

public class NlpProcessor {

    public static class NlpResult {
        public String originalText;
        public String normalizedText;
        public String intent;
        public String target;
        public boolean isValid;

        public NlpResult(String originalText, String normalizedText, String intent, String target, boolean isValid) {
            this.originalText = originalText;
            this.normalizedText = normalizedText;
            this.intent = intent;
            this.target = target;
            this.isValid = isValid;
        }
    }

    public NlpResult process(String input) {
        String normalized = normalize(input);

        if (normalized.isEmpty()) {
            return new NlpResult(input, normalized, "UNKNOWN", "", false);
        }

        System.out.println("RAW: " + input);
        System.out.println("NORMALIZED: " + normalized);

        if (containsAny(normalized,
                "stop", "stop reading", "tumigil", "itigil", "enough",
                "stop now", "please stop", "pause", "pause reading")) {
            return new NlpResult(input, normalized, "STOP", "", true);
        }

        if (containsAny(normalized,
                "repeat", "repeat that", "ulitin", "say that again",
                "again", "repeat please", "ulit", "pakulit")) {
            return new NlpResult(input, normalized, "REPEAT", "", true);
        }

        if (isHomeIntent(normalized)) {
            return new NlpResult(input, normalized, "OPEN_HOME", "HOME", true);
        }

        if (isProfileIntent(normalized)) {
            return new NlpResult(input, normalized, "OPEN_PROFILE", "PROFILE", true);
        }

        if (isMaterialsIntent(normalized)) {
            return new NlpResult(input, normalized, "OPEN_MATERIALS_SCREEN", "MATERIALS", true);
        }

        if (isReadTitlesIntent(normalized)) {
            return new NlpResult(input, normalized, "READ_TITLES", "", true);
        }

        if (isReadFeaturedTitleIntent(normalized)) {
            return new NlpResult(input, normalized, "READ_FEATURED_TITLE", "FEATURED", true);
        }

        if (isOpenIntent(normalized)) {
            String target = extractMaterialTarget(normalized);

            if (!target.isEmpty()) {
                return new NlpResult(input, normalized, "OPEN_MATERIAL", target, true);
            }

            return new NlpResult(input, normalized, "OPEN_MATERIAL", "FEATURED", true);
        }

        return new NlpResult(input, normalized, "UNKNOWN", "", false);
    }

    private boolean isHomeIntent(String text) {
        return containsAny(text,
                "home",
                "go home",
                "open home",
                "go to home",
                "home screen",
                "main screen",
                "go to main screen",
                "balik home",
                "sa home",
                "umuwi");
    }

    private boolean isProfileIntent(String text) {
        return containsAny(text,
                "profile",
                "open profile",
                "go to profile",
                "profile screen",
                "my profile",
                "student profile",
                "buksan ang profile",
                "sa profile",
                "punta sa profile");
    }

    private boolean isMaterialsIntent(String text) {
        return containsAny(text,
                "materials",
                "material",
                "open materials",
                "go to materials",
                "materials screen",
                "learning materials",
                "open learning materials",
                "show materials",
                "sa materials",
                "punta sa materials");
    }

    private boolean isOpenIntent(String text) {
        return containsAny(text,
                "open", "buksan", "pakibukas", "i open", "iopen",
                "show", "ipakita", "load", "start material", "open material",
                "open lesson", "open file", "open pdf",
                "can you open", "please open", "open this",
                "featured material", "latest material", "first material",
                "second material", "third material");
    }

    private boolean isReadTitlesIntent(String text) {
        return containsAny(text,
                "read titles",
                "read title list",
                "read material titles",
                "read material list",
                "read the titles",
                "read the pdf titles",
                "list materials",
                "read materials",
                "what are the materials",
                "basahin ang title",
                "basahin ang titles",
                "basahin ang list",
                "sabihin ang titles",
                "ano ang materials");
    }

    private boolean isReadFeaturedTitleIntent(String text) {
        boolean hasRead = containsAny(text, "read", "basahin", "sabihin");
        boolean hasTitle = containsAny(text, "title", "pangalan", "name");
        boolean hasContext = containsAny(text,
                "featured",
                "latest",
                "current material",
                "continue where you left off",
                "last opened",
                "recent",
                "huling binuksan");

        return (hasRead && hasTitle && hasContext)
                || text.contains("read featured title")
                || text.contains("read current material title")
                || text.contains("read latest title");
    }

    private String extractMaterialTarget(String text) {

        if (containsAny(text,
                "first", "one", "material one", "una", "unang",
                "number one", "1st", "first material")) {
            return "FIRST";
        }

        if (containsAny(text,
                "second", "two", "material two", "pangalawa", "ikalawa",
                "number two", "2nd", "second material")) {
            return "SECOND";
        }

        if (containsAny(text,
                "third", "three", "material three", "pangatlo", "ikatlo",
                "number three", "3rd", "third material")) {
            return "THIRD";
        }

        if (containsAny(text,
                "featured", "latest", "current",
                "continue where you left off",
                "last opened", "recent",
                "huling binuksan",
                "featured material",
                "latest material")) {
            return "FEATURED";
        }

        return "";
    }

    private String normalize(String text) {
        if (text == null) return "";

        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        normalized = normalized.toLowerCase(Locale.ROOT).trim();
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();

        normalized = normalized.replace("pakibuksan", "pakibukas");
        normalized = normalized.replace("buksan mo", "buksan");
        normalized = normalized.replace("yung", "ang");
        normalized = normalized.replace("iyon", "yun");

        normalized = normalized.replace("go to home", "home");
        normalized = normalized.replace("open home", "home");
        normalized = normalized.replace("go to profile", "profile");
        normalized = normalized.replace("open profile", "profile");
        normalized = normalized.replace("go to materials", "materials");
        normalized = normalized.replace("open materials", "materials");
        normalized = normalized.replace("open the first material", "open first material");
        normalized = normalized.replace("open the second material", "open second material");
        normalized = normalized.replace("open the third material", "open third material");
        normalized = normalized.replace("open latest material", "open featured material");
        normalized = normalized.replace("open featured", "open featured material");
        normalized = normalized.replace("continue learning", "read featured title");
        normalized = normalized.replace("repeat that", "repeat");

        return normalized;
    }

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
