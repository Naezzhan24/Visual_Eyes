package com.visualed.voice;

public final class TextSimilarity {

    private TextSimilarity() {}

    public static double normalizedLevenshtein(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int d = levenshtein(a, b);
        return 1.0 - (double) d / Math.max(a.length(), b.length());
    }

    public static int levenshtein(String a, String b) {
        int[] dp = new int[b.length() + 1];
        for (int i = 0; i <= b.length(); i++) dp[i] = i;
        for (int i = 1; i <= a.length(); i++) {
            int prev = dp[0];
            dp[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int tmp = dp[j];
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[j] = Math.min(Math.min(dp[j] + 1, dp[j - 1] + 1), prev + cost);
                prev = tmp;
            }
        }
        return dp[b.length()];
    }
}
