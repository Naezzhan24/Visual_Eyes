import XCTest
@testable import VisualEyes

/// Spoken feedback answers must parse like FeedbackActivity's.
@MainActor
final class FeedbackVoiceParsingTests: XCTestCase {
    typealias VM = FeedbackViewModel

    func testRatingFromDigitsAndWords() {
        XCTAssertEqual(VM.parseRating("four stars"), 4)
        XCTAssertEqual(VM.parseRating("5"), 5)
        XCTAssertEqual(VM.parseRating("I don't know"), 0)
    }

    func testTextSizeAnswers() {
        XCTAssertEqual(VM.parseScaleAnswer("just right", isSize: true), 3)
        XCTAssertEqual(VM.parseScaleAnswer("a little small", isSize: true), 2)
        XCTAssertEqual(VM.parseScaleAnswer("much too big", isSize: true), 5)
        // A request means the opposite: "make it bigger" = too small now.
        XCTAssertEqual(VM.parseScaleAnswer("make it bigger", isSize: true), 2)
        XCTAssertEqual(VM.parseScaleAnswer("medyo maliit", isSize: true), 2)
        XCTAssertEqual(VM.parseScaleAnswer("banana", isSize: true), 0)
    }

    func testSpeedAnswers() {
        XCTAssertEqual(VM.parseScaleAnswer("a little fast", isSize: false), 4)
        XCTAssertEqual(VM.parseScaleAnswer("very slow", isSize: false), 1)
        XCTAssertEqual(VM.parseScaleAnswer("slower please", isSize: false), 4)
    }

    func testConfirmationAndSkip() {
        XCTAssertTrue(VM.isYes("yes"))
        XCTAssertTrue(VM.isYes("that's correct"))
        XCTAssertTrue(VM.isNo("no"))
        XCTAssertTrue(VM.isNo("no that is wrong"))
        XCTAssertFalse(VM.isNo("know"))
        XCTAssertTrue(VM.isSkip("skip"))
        XCTAssertTrue(VM.isSkip("ship"))
    }
}
