import Foundation

/// PostgREST renders `numeric`, `integer` and `text` columns differently
/// (e.g. `age` is computed with EXTRACT, so it may come back as `21` or
/// `21.0`, and older rows stored `recommended_text_size` as text). The
/// Android app reads every field with `optString`/`optInt`, which never
/// fails; these helpers give the iOS models the same tolerance so one odd
/// column doesn't fail the whole login or materials list.
extension KeyedDecodingContainer {
    func lenientString(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) { return value }
        if let value = try? decodeIfPresent(Int.self, forKey: key) { return String(value) }
        if let value = try? decodeIfPresent(Double.self, forKey: key) { return String(value) }
        return nil
    }

    func lenientDouble(_ key: Key) -> Double? {
        if let value = try? decodeIfPresent(Double.self, forKey: key) { return value }
        if let text = try? decodeIfPresent(String.self, forKey: key) {
            // Tolerates "24", "24sp", "24.0".
            let digits = text.trimmingCharacters(in: .whitespaces)
                .prefix { $0.isNumber || $0 == "." }
            return Double(digits)
        }
        return nil
    }

    func lenientInt(_ key: Key) -> Int? {
        lenientDouble(key).map { Int($0) }
    }
}
