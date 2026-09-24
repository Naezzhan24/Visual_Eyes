import Foundation

/// Fuzzy word-match verifier, ported verbatim (same algorithm, same
/// thresholds) from `TextSizeTestActivity.java`'s `matchesTargetWord()` /
/// `com.visualed.voice.TextSimilarity.java`. Used both by the assessment
/// (verifying a spoken/typed word against the target) and, later, by the
/// voice-command grammar matcher (Phase 5) and Materials' fuzzy title
/// matching.
enum TextSimilarity {
    /// Lowercase → strip to `[a-z]` only → exact/substring match →
    /// Levenshtein distance ≤1 (target ≤4 letters) / ≤2 (longer),
    /// gated on candidate length ≥ target length so a still-in-progress
    /// partial transcription isn't mistaken for a finished (if imperfect)
    /// attempt at the whole word.
    static func matches(candidate: String, target: String) -> Bool {
        let normalizedCandidate = normalize(candidate)
        let normalizedTarget = normalize(target)
        guard !normalizedCandidate.isEmpty, !normalizedTarget.isEmpty else { return false }

        if normalizedCandidate == normalizedTarget { return true }
        if normalizedCandidate.contains(normalizedTarget) { return true }

        guard normalizedCandidate.count >= normalizedTarget.count else { return false }

        let maxDistance = normalizedTarget.count <= 4 ? 1 : 2
        return levenshteinDistance(normalizedCandidate, normalizedTarget) <= maxDistance
    }

    static func normalize(_ text: String) -> String {
        text.lowercased().filter { $0.isASCII && $0.isLetter }
    }

    static func levenshteinDistance(_ a: String, _ b: String) -> Int {
        let aChars = Array(a)
        let bChars = Array(b)
        let m = aChars.count
        let n = bChars.count
        if m == 0 { return n }
        if n == 0 { return m }

        var previousRow = Array(0...n)
        var currentRow = [Int](repeating: 0, count: n + 1)

        for i in 1...m {
            currentRow[0] = i
            for j in 1...n {
                let cost = aChars[i - 1] == bChars[j - 1] ? 0 : 1
                currentRow[j] = min(
                    previousRow[j] + 1,        // deletion
                    currentRow[j - 1] + 1,     // insertion
                    previousRow[j - 1] + cost  // substitution
                )
            }
            previousRow = currentRow
        }
        return previousRow[n]
    }
}
