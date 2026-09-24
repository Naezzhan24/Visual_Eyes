import Foundation

/// English + Tagalog yes/no detection, ported verbatim from
/// `TextSizeTestActivity.java`'s `isYes()`/`isNo()`/`normalizeAnswer()`.
/// Deliberately simple substring/exact matching (not the fuzzy
/// Levenshtein matcher in `TextSimilarity`) — same as Android, since
/// yes/no words are short and a fuzzy match risks false positives.
enum YesNoMatcher {
    static func isYes(_ raw: String) -> Bool {
        let t = normalize(raw)
        return t == "yes" || t == "yeah" || t == "yep" || t == "yup" || t == "yas"
            || t == "oo" || t == "opo"
            || t.contains(" yes") || t.hasPrefix("yes ")
            || t.contains(" oo ") || t.hasPrefix("oo ") || t.hasSuffix(" oo")
    }

    static func isNo(_ raw: String) -> Bool {
        let t = normalize(raw)
        return t == "no" || t == "nope" || t == "nah" || t == "di"
            || t == "hindi" || t == "hinde" || t == "know"
            || t.contains(" no") || t.hasPrefix("no ")
            || t.contains(" hindi") || t.hasPrefix("hindi ")
    }

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
