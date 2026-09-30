import Foundation

/// English + Tagalog yes/no detection, mirroring `YesNoMatcher.java` on Android.
/// Whole-word matching (not the fuzzy Levenshtein matcher in `TextSimilarity`,
/// since yes/no words are short and a fuzzy match risks false positives), so a
/// natural answer like "yeah I can" or "nope, too small" counts. When the
/// student corrects themselves ("no wait, yes") the LAST decisive word wins.
enum YesNoMatcher {
    enum Answer { case yes, no, unknown }

    private static let yesWords: Set<String> = [
        "yes", "yeah", "yea", "yep", "yup", "yas", "oo", "opo", "sure", "okay", "ok"
    ]
    private static let noWords: Set<String> = [
        "no", "nope", "nah", "not", "cannot", "cant", "hindi", "hinde", "di"
    ]
    /// "can't" / "don't" / "didn't" ... normalize to "can t" / "don t" — the "t" is a No.
    private static let negatable: Set<String> = [
        "can", "don", "didn", "won", "couldn", "isn", "wasn", "aren", "doesn"
    ]

    static func parse(_ raw: String) -> Answer {
        let text = normalize(raw)
        if text.isEmpty { return .unknown }
        // "no" is often heard as "know" when it is the whole utterance.
        if text == "know" { return .no }

        var result = Answer.unknown
        var previous = ""
        for word in text.split(separator: " ").map(String.init) {
            if yesWords.contains(word) {
                result = .yes
            } else if noWords.contains(word) || (word == "t" && negatable.contains(previous)) {
                result = .no
            }
            previous = word
        }
        return result
    }

    static func isYes(_ raw: String) -> Bool { parse(raw) == .yes }

    static func isNo(_ raw: String) -> Bool { parse(raw) == .no }

    static func isRepeat(_ raw: String) -> Bool {
        normalize(raw).contains("repeat") || normalize(raw).contains("ulit")
    }

    static func isStop(_ raw: String) -> Bool {
        let t = normalize(raw)
        return t.contains("stop") || t.contains("cancel") || t.contains("tigil")
    }

    /// Lowercase → replace anything that isn't `[a-z\s]` with a space →
    /// collapse whitespace → trim. Matches Java's
    /// `replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim()`.
    static func normalize(_ text: String) -> String {
        let lowered = text.lowercased()
        let allowed = CharacterSet.lowercaseLetters.union(.whitespaces)
        let scrubbed = String(lowered.unicodeScalars.map { allowed.contains($0) ? Character($0) : " " })
        return scrubbed
            .split(separator: " ", omittingEmptySubsequences: true)
            .joined(separator: " ")
    }
}
