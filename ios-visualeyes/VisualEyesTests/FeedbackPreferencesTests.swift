import XCTest
@testable import VisualEyes

final class FeedbackPreferencesTests: XCTestCase {
    func testTargetTextSizeStepsFromCurrentNotBase() {
        // Level 1 = "Much too small" = +4sp, taken from `current`.
        XCTAssertEqual(FeedbackPreferences.targetTextSize(current: 20, level: 1), 24)
        // Level 5 = "Much too big" = -4sp.
        XCTAssertEqual(FeedbackPreferences.targetTextSize(current: 20, level: 5), 16)
        // Level 3 = "Just right" = no-op.
        XCTAssertEqual(FeedbackPreferences.targetTextSize(current: 20, level: 3), 20)
    }

    func testTargetTextSizeClampsToRange() {
        XCTAssertEqual(FeedbackPreferences.targetTextSize(current: 41, level: 1), 42) // clamp to max
        XCTAssertEqual(FeedbackPreferences.targetTextSize(current: 15, level: 5), 14) // clamp to min
    }

    func testTargetSpeedScaleStepsFromCurrentNotBase() {
        XCTAssertEqual(FeedbackPreferences.targetSpeedScale(current: 1.0, level: 1), 1.10, accuracy: 0.001)
        XCTAssertEqual(FeedbackPreferences.targetSpeedScale(current: 1.0, level: 5), 0.90, accuracy: 0.001)
        XCTAssertEqual(FeedbackPreferences.targetSpeedScale(current: 1.0, level: 3), 1.0, accuracy: 0.001)
    }

    func testTargetSpeedScaleClampsToRange() {
        XCTAssertEqual(FeedbackPreferences.targetSpeedScale(current: 1.25, level: 1), 1.30, accuracy: 0.001)
        XCTAssertEqual(FeedbackPreferences.targetSpeedScale(current: 0.75, level: 5), 0.70, accuracy: 0.001)
    }

    func testDescribeTextSizeNotAnswered() {
        XCTAssertEqual(FeedbackPreferences.describeTextSize(level: 0, base: 20, work: 20, adjustments: 0), "Not answered")
    }

    func testDescribeTextSizeNoChange() {
        let result = FeedbackPreferences.describeTextSize(level: 3, base: 20, work: 20, adjustments: 0)
        XCTAssertEqual(result, "Just right (no change, 20sp)")
    }

    func testDescribeTextSizeAdjustedSingleStep() {
        let result = FeedbackPreferences.describeTextSize(level: 1, base: 20, work: 24, adjustments: 1)
        XCTAssertEqual(result, "Adjusted after previewing 1 step: 20sp -> 24sp (last answer: Much too small)")
    }

    func testDescribeTextSizeAdjustedMultipleSteps() {
        let result = FeedbackPreferences.describeTextSize(level: 1, base: 20, work: 28, adjustments: 2)
        XCTAssertEqual(result, "Adjusted after previewing 2 steps: 20sp -> 28sp (last answer: Much too small)")
    }

    func testDescribeSpeedNoChange() {
        let result = FeedbackPreferences.describeSpeed(level: 3, base: 1.0, work: 1.0, adjustments: 0)
        XCTAssertEqual(result, "Just right (no change, 100%)")
    }

    func testDescribeSpeedAdjusted() {
        let result = FeedbackPreferences.describeSpeed(level: 1, base: 1.0, work: 1.10, adjustments: 1)
        XCTAssertEqual(result, "Adjusted after previewing 1 step: 100% -> 110% (last answer: Much too slow)")
    }

    func testSatisfactionLabels() {
        XCTAssertEqual(FeedbackPreferences.satisfactionLabel(forRating: 1), "Not Satisfied")
        XCTAssertEqual(FeedbackPreferences.satisfactionLabel(forRating: 3), "Neutral")
        XCTAssertEqual(FeedbackPreferences.satisfactionLabel(forRating: 5), "Very Satisfied")
    }

    func testFeedbackBlobFormatExact() {
        let blob = FeedbackPreferences.feedbackBlob(
            rating: 5,
            textSizeAnswer: "Not answered",
            speedAnswer: "Not answered",
            materialFeedback: "",
            instructorFeedback: ""
        )
        let expected = """
        Satisfaction: Very Satisfied
        Text Size: Not answered
        Reading Speed: Not answered
        Material Feedback: No material feedback provided.
        Instructor Feedback: No instructor feedback provided.
        """
        XCTAssertEqual(blob, expected)
    }

    func testFeedbackBlobPreservesProvidedText() {
        let blob = FeedbackPreferences.feedbackBlob(
            rating: 4,
            textSizeAnswer: "Just right (no change, 24sp)",
            speedAnswer: "Just right (no change, 100%)",
            materialFeedback: "Great lesson!",
            instructorFeedback: "Please add more diagrams."
        )
        XCTAssertTrue(blob.contains("Material Feedback: Great lesson!"))
        XCTAssertTrue(blob.contains("Instructor Feedback: Please add more diagrams."))
    }
}
