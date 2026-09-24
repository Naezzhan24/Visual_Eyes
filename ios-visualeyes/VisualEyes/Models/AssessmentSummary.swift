import Foundation

/// One assessment item's outcome — same shape as the `words[]`/
/// `wordRead[]`/`wordSizes[]` arrays AssessmentResultActivity receives.
struct AssessmentWordOutcome: Identifiable {
    let id = UUID()
    let word: String
    let size: CGFloat
    let wasRead: Bool
}

/// Everything the Assessment Result screen needs, computed once the
/// 5-item ladder finishes.
struct AssessmentSummary {
    let outcomes: [AssessmentWordOutcome]
    let impairmentLevel: AssessmentScoring.ImpairmentLevel
    let recommendedSize: CGFloat
    let yesCount: Int
    let totalItems: Int
    let isRetake: Bool
}
