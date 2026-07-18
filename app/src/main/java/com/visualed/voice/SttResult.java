package com.visualed.voice;

public final class SttResult {
    private final String rawText;
    private final String normalizedText;
    private final float confidence;
    private final boolean enginesAgreed;
    private final String source;

    public SttResult(String rawText, String normalizedText, float confidence,
                      boolean enginesAgreed, String source) {
        this.rawText = rawText;
        this.normalizedText = normalizedText;
        this.confidence = confidence;
        this.enginesAgreed = enginesAgreed;
        this.source = source;
    }

    public String getRawText() { return rawText; }
    public String getNormalizedText() { return normalizedText; }
    public float getConfidence() { return confidence; }
    public boolean getEnginesAgreed() { return enginesAgreed; }
    public String getSource() { return source; }
}
