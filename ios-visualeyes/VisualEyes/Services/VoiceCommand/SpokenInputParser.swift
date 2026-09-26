import Foundation

/// Turns spoken answers on the Login and Register screens into field
/// values — ports of LoginActivity/RegisterActivity's normalizeSchoolId,
/// parseSpokenBirthdatePassword, convertNumberWords and
/// NumberSpeechFormatter.spellDigits.
enum SpokenInputParser {
    static let monthNames = ["january", "february", "march", "april", "may", "june",
                             "july", "august", "september", "october", "november", "december"]
    private static let digitWords = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]

    /// "two zero two four" → "2 0 2 4".
    static func convertNumberWords(_ input: String) -> String {
        var text = input
        for (digit, word) in digitWords.enumerated() {
            text = text.replacingOccurrences(of: word, with: String(digit))
        }
        return text
    }

    /// Digits only, with the dash after the first two ("21-00123"),
    /// the School ID format.
    static func schoolId(from spoken: String) -> String {
        let digits = convertNumberWords(spoken.lowercased()).filter(\.isNumber)
        guard digits.count >= 3 else { return digits }
        return String(digits.prefix(2)) + "-" + String(digits.dropFirst(2))
    }

    /// "21-00123" → "two one zero zero one two three", so a School ID is
    /// read back digit by digit instead of as one big number.
    static func spellDigits(_ token: String) -> String {
        token.compactMap { $0.wholeNumberValue }.map { digitWords[$0] }.joined(separator: " ")
    }

    /// "February 27th, 2005" → (2, 27, 2005); nil unless month, day and
    /// year are all there.
    static func birthdate(from spoken: String) -> (month: Int, day: Int, year: Int)? {
        var cleaned = convertNumberWords(spoken.lowercased())
        cleaned = cleaned.replacingOccurrences(of: #"\b(\d+)(st|nd|rd|th)\b"#, with: "$1", options: .regularExpression)

        guard let monthIndex = monthNames.firstIndex(where: { cleaned.contains($0) }) else { return nil }
        guard let yearRange = cleaned.range(of: #"\b(19|20)\d{2}\b"#, options: .regularExpression),
              let year = Int(cleaned[yearRange]) else { return nil }

        var rest = cleaned.replacingOccurrences(of: monthNames[monthIndex], with: "")
        rest = rest.replacingOccurrences(of: #"\b(19|20)\d{2}\b"#, with: "", options: .regularExpression)
        guard let dayRange = rest.range(of: #"\b\d{1,2}\b"#, options: .regularExpression),
              let day = Int(rest[dayRange]), (1...31).contains(day) else { return nil }
        return (monthIndex + 1, day, year)
    }

    /// The login password (MM-DD-YYYY) from a spoken birthdate, else the
    /// raw words with "dash"/"dot"/… turned into symbols
    /// (processSpokenPassword).
    static func password(from spoken: String) -> String {
        if let date = birthdate(from: spoken) {
            return String(format: "%02d-%02d-%04d", date.month, date.day, date.year)
        }
        var p = spoken.trimmingCharacters(in: .whitespaces)
        for prefix in ["my password is ", "the password is ", "password is ", "password "]
        where p.lowercased().hasPrefix(prefix) {
            p = String(p.dropFirst(prefix.count)).trimmingCharacters(in: .whitespaces)
            break
        }
        p = p.replacingOccurrences(of: " space ", with: "")
            .replacingOccurrences(of: " underscore ", with: "_")
            .replacingOccurrences(of: " dash ", with: "-")
            .replacingOccurrences(of: " hyphen ", with: "-")
            .replacingOccurrences(of: " at ", with: "@")
            .replacingOccurrences(of: " dot ", with: ".")
        return p.filter { !$0.isWhitespace }
    }

    /// Typing aid for the password field: digits become MM-DD-YYYY as
    /// they're typed (setupPasswordAutoFormat).
    static func formatBirthdateTyping(_ text: String) -> String {
        let digits = String(text.filter(\.isNumber).prefix(8))
        var formatted = ""
        for (index, c) in digits.enumerated() {
            if index == 2 || index == 4 { formatted.append("-") }
            formatted.append(c)
        }
        return formatted
    }
}
