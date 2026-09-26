import XCTest
@testable import VisualEyes

/// Reader command predicates must match AccessibleMaterialActivity's.
@MainActor
final class ReaderVoiceCommandTests: XCTestCase {
    typealias VM = MaterialReaderViewModel

    func testNormalizationMapsTagalogAndMishearings() {
        XCTAssertEqual(VM.normalizeCommand("Oo po"), "yes")
        XCTAssertEqual(VM.normalizeCommand("guess"), "yes")
        XCTAssertEqual(VM.normalizeCommand("Hindi"), "no")
        XCTAssertEqual(VM.normalizeCommand("Instructions!"), "instruction")
    }

    func testFeedbackIsNotTakenAsBack() {
        XCTAssertTrue(VM.isFeedback("feed back"))
        XCTAssertFalse(VM.isBack("leave feedback"))
        XCTAssertTrue(VM.isBack("go back"))
        XCTAssertTrue(VM.isBack("balik"))
    }

    func testRestartIsNotAYes() {
        XCTAssertFalse(VM.isYes("restart"))
        XCTAssertFalse(VM.isYes("from the beginning"))
        XCTAssertTrue(VM.isYes("start reading"))
        XCTAssertTrue(VM.isYes("sige"))
        XCTAssertTrue(VM.isRestart("restart"))
    }

    func testDecisionWords() {
        XCTAssertTrue(VM.isNext("next"))
        XCTAssertTrue(VM.isNext("susunod"))
        XCTAssertTrue(VM.isRepeat("ulit"))
        XCTAssertTrue(VM.isFaster("bilisan mo"))
        XCTAssertTrue(VM.isSlower("slow down"))
        XCTAssertTrue(VM.isIncreaseText("lakihan"))
        XCTAssertTrue(VM.isDecreaseText("zoom out"))
        XCTAssertTrue(VM.isNoOrStop("pause"))
    }
}
