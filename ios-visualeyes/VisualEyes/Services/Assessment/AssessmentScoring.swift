import Foundation

/// The 5-word/5-size reading-accuracy ladder and its scoring rules,
/// ported verbatim from `TextSizeTestActivity.java` (word banks,
/// `getRecommendedTextSize()`, `getImpairmentLevel()`). This is deliberately
/// framework-free pure logic — no SwiftUI/Speech import — so it's directly
/// unit-testable and has no accidental dependency on the voice pipeline.
enum AssessmentScoring {
    /// Escalating font sizes shown for each of the 5 items, in points —
    /// same values as Android's `textSizes` (sp there, pt here; visually
    /// equivalent at standard scale).
    static let textSizes: [CGFloat] = [18, 24, 30, 36, 42]

    /// First-assessment word bank — 5 tiers of 3 candidate words, one
    /// picked at random per tier. Verbatim from `wordTiers` in
    /// TextSizeTestActivity.java.
    static let wordTiers: [[String]] = [
        ["cat", "dog", "sun"],
        ["apple", "chair", "green"],
        ["garden", "pencil", "window"],
        ["bicycle", "hospital", "elephant"],
        ["beautiful", "important", "wonderful"]
    ]

    /// Retake word bank — different words than the first assessment so a
    /// retaking student can't just remember the previous answers.
    /// Verbatim from `retakeWordTiers`.
    static let retakeWordTiers: [[String]] = [
        ["cup", "pen", "red"],
        ["table", "paper", "happy"],
        ["teacher", "student", "library"],
        ["computer", "notebook", "mountain"],
        ["chocolate", "adventure", "celebration"]
    ]

    /// Picks one random word from each tier, matching
    /// `pickWordsFromTiers()`'s per-tier-random-choice behavior.
    static func pickWords(from tiers: [[String]]) -> [String] {
        tiers.map { $0.randomElement() ?? $0[0] }
    }

    /// Argmax of readable-word-count per size tier — the size at which
    /// the student read the most words correctly. Ties resolve to the
    /// smallest (first) index, matching Android's `>` (not `>=`) compare.
    static func recommendedTextSize(readablePerSize: [Int]) -> CGFloat {
        var best = 0
        for i in 1..<readablePerSize.count where readablePerSize[i] > readablePerSize[best] {
            best = i
        }
        return textSizes[best]
    }

    enum ImpairmentLevel: String {
        case low = "Low Visual Impairment Support Needed"
        case moderate = "Moderate Visual Impairment Support Needed"
        case high = "High Visual Impairment Support Needed"
    }

    /// Verbatim from `getImpairmentLevel()`: `yesCount` out of 5,
    /// ≥80% → Low, ≥45% → Moderate, else High. Uses `ceil` on the
    /// percentage thresholds exactly like the Java `Math.ceil` call, so
    /// the same boundary counts (e.g. 4/5 = 80% exactly) land the same
    /// way here as on Android.
    static func impairmentLevel(yesCount: Int, total: Int) -> ImpairmentLevel {
        let lowThreshold = Int((Double(total) * 0.80).rounded(.up))
        let moderateThreshold = Int((Double(total) * 0.45).rounded(.up))
        if yesCount >= lowThreshold { return .low }
        if yesCount >= moderateThreshold { return .moderate }
        return .high
    }
}
