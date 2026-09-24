import XCTest
@testable import VisualEyes

final class VoiceCommandMatcherTests: XCTestCase {
    func testMatchesEnglishAndTagalogSynonyms() {
        XCTAssertEqual(VoiceCommandMatcher.match("next please"), .next)
        XCTAssertEqual(VoiceCommandMatcher.match("susunod"), .next)
        XCTAssertEqual(VoiceCommandMatcher.match("bilisan mo"), .faster)
        XCTAssertEqual(VoiceCommandMatcher.match("bagalan"), .slower)
        XCTAssertEqual(VoiceCommandMatcher.match("lakihan ang text"), .increaseText)
        XCTAssertEqual(VoiceCommandMatcher.match("balik"), .back)
    }

    func testFeedbackWinsOverBackSubstring() {
        // "feedback" contains "back" — must resolve to .feedback, not
        // .back, matching the explicit ordering note in
        // AccessibleMaterialActivity.java's handleCommand().
        XCTAssertEqual(VoiceCommandMatcher.match("leave feedback"), .feedback)
        XCTAssertEqual(VoiceCommandMatcher.match("feedback"), .feedback)
    }

    func testNoMatchReturnsNil() {
        XCTAssertNil(VoiceCommandMatcher.match("banana"))
        XCTAssertNil(VoiceCommandMatcher.match(""))
    }

    func testStopAndCancel() {
        XCTAssertEqual(VoiceCommandMatcher.match("stop"), .stop)
        XCTAssertEqual(VoiceCommandMatcher.match("cancel"), .stop)
        XCTAssertEqual(VoiceCommandMatcher.match("tigil"), .stop)
    }
}
