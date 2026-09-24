import XCTest
@testable import VisualEyes

final class AssessmentScoringTests: XCTestCase {
    func testRecommendedTextSizePicksArgmax() {
        // Most words read at the 30pt tier (index 2).
        let readable = [0, 1, 3, 2, 0]
        XCTAssertEqual(AssessmentScoring.recommendedTextSize(readablePerSize: readable), 30)
    }

    func testRecommendedTextSizeTiesResolveToSmallestIndex() {
        let readable = [2, 2, 0, 0, 0]
        XCTAssertEqual(AssessmentScoring.recommendedTextSize(readablePerSize: readable), 18)
    }

    func testImpairmentLevelThresholds() {
        // 5 items: ≥80% (≥4) → Low, ≥45% (≥3) → Moderate, else High.
        XCTAssertEqual(AssessmentScoring.impairmentLevel(yesCount: 5, total: 5), .low)
        XCTAssertEqual(AssessmentScoring.impairmentLevel(yesCount: 4, total: 5), .low)
        XCTAssertEqual(AssessmentScoring.impairmentLevel(yesCount: 3, total: 5), .moderate)
        XCTAssertEqual(AssessmentScoring.impairmentLevel(yesCount: 2, total: 5), .high)
        XCTAssertEqual(AssessmentScoring.impairmentLevel(yesCount: 0, total: 5), .high)
    }

    func testWordBanksHaveFiveTiersOfThreeWordsEach() {
        for bank in [AssessmentScoring.wordTiers, AssessmentScoring.retakeWordTiers] {
            XCTAssertEqual(bank.count, 5)
            for tier in bank {
                XCTAssertEqual(tier.count, 3)
            }
        }
    }

    func testPickWordsReturnsOneWordPerTier() {
        let picked = AssessmentScoring.pickWords(from: AssessmentScoring.wordTiers)
        XCTAssertEqual(picked.count, 5)
        for (index, word) in picked.enumerated() {
            XCTAssertTrue(AssessmentScoring.wordTiers[index].contains(word))
        }
    }
}

final class TextSimilarityTests: XCTestCase {
    func testExactMatch() {
        XCTAssertTrue(TextSimilarity.matches(candidate: "cat", target: "cat"))
    }

    func testSubstringMatch() {
        XCTAssertTrue(TextSimilarity.matches(candidate: "the cat", target: "cat"))
    }

    func testNearMissWithinLevenshteinToleranceMatchesForShortWords() {
        // "cot" vs "cat" — distance 1, target length 3 (≤4 → tolerance 1).
        XCTAssertTrue(TextSimilarity.matches(candidate: "cot", target: "cat"))
    }

    func testTooManyErrorsDoesNotMatch() {
        XCTAssertFalse(TextSimilarity.matches(candidate: "xyz", target: "cat"))
    }

    func testShorterCandidateNeverMatches() {
        // Guards against a still-in-progress partial transcription.
        XCTAssertFalse(TextSimilarity.matches(candidate: "ca", target: "cat"))
    }

    func testLongerWordAllowsDistanceTwo() {
        // "beautifull" (typo, one extra letter) vs "beautiful" — distance 1,
        // target length 9 (>4 → tolerance 2).
        XCTAssertTrue(TextSimilarity.matches(candidate: "beautifull", target: "beautiful"))
    }
}

final class YesNoMatcherTests: XCTestCase {
    func testEnglishYes() {
        XCTAssertTrue(YesNoMatcher.isYes("yes"))
        XCTAssertTrue(YesNoMatcher.isYes("Yes I can"))
        XCTAssertFalse(YesNoMatcher.isYes("no"))
    }

    func testTagalogYes() {
        XCTAssertTrue(YesNoMatcher.isYes("oo"))
        XCTAssertTrue(YesNoMatcher.isYes("opo"))
    }

    func testEnglishNo() {
        XCTAssertTrue(YesNoMatcher.isNo("no"))
        XCTAssertTrue(YesNoMatcher.isNo("nope"))
    }

    func testTagalogNo() {
        XCTAssertTrue(YesNoMatcher.isNo("hindi"))
    }

    func testRepeatAndStop() {
        XCTAssertTrue(YesNoMatcher.isRepeat("please repeat that"))
        XCTAssertTrue(YesNoMatcher.isStop("stop"))
        XCTAssertTrue(YesNoMatcher.isStop("cancel"))
    }
}
