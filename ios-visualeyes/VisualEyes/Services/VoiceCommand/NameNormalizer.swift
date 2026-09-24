import Foundation

/// Fuzzy-corrects spoken/typed Philippine first names and surnames
/// against curated wordlists — port of `com.visualed.voice.NameNormalizer`
/// (same edit-distance algorithm as `TextSimilarity`), using the same
/// wordlist data copied verbatim from `app/src/main/assets/names/`.
/// Used by Register's voice-guided name entry.
struct NameNormalizer {
    private let firstNames: [String]
    private let surnames: [String]

    init() {
        firstNames = Self.loadWordlist(named: "first_names_ph")
        surnames = Self.loadWordlist(named: "surnames_ph")
    }

    /// Returns the closest matching wordlist entry if one is within a
    /// small edit-distance tolerance, else returns `heard` unchanged —
    /// so a valid name that just isn't on the curated list isn't
    /// clobbered into something else.
    func correctedFirstName(_ heard: String) -> String {
        Self.correct(heard, against: firstNames)
    }

    func correctedSurname(_ heard: String) -> String {
        Self.correct(heard, against: surnames)
    }

    private static func correct(_ heard: String, against wordlist: [String]) -> String {
        let trimmed = heard.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !wordlist.isEmpty else { return heard }

        if let exact = wordlist.first(where: { $0.caseInsensitiveCompare(trimmed) == .orderedSame }) {
            return exact
        }

        let normalizedHeard = TextSimilarity.normalize(trimmed)
        var best: (word: String, distance: Int)?
        for word in wordlist {
            let distance = TextSimilarity.levenshteinDistance(normalizedHeard, TextSimilarity.normalize(word))
            if best == nil || distance < best!.distance {
                best = (word, distance)
            }
        }

        guard let best else { return heard }
        // Same tolerance rule as TextSimilarity.matches: ≤1 for short
        // words, ≤2 for longer ones.
        let maxDistance = normalizedHeard.count <= 4 ? 1 : 2
        return best.distance <= maxDistance ? best.word : heard
    }

    private static func loadWordlist(named name: String) -> [String] {
        let url = Bundle.main.url(forResource: name, withExtension: "txt", subdirectory: "names")
            ?? Bundle.main.url(forResource: name, withExtension: "txt")
        guard let url, let contents = try? String(contentsOf: url, encoding: .utf8) else {
            return []
        }
        return contents
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }
}
