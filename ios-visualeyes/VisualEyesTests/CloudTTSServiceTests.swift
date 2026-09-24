import XCTest
@testable import VisualEyes

final class CloudTTSServiceTests: XCTestCase {
    func testEscapeForSSMLEscapesReservedCharacters() {
        let input = "Read \"Tom & Jerry\" — 5 < 10 > 3"
        let escaped = CloudTTSService.escapeForSSML(input)

        XCTAssertFalse(escaped.contains("<"), "raw '<' should be escaped")
        XCTAssertFalse(escaped.contains(">"), "raw '>' should be escaped")
        XCTAssertTrue(escaped.contains("&amp;"))
        XCTAssertTrue(escaped.contains("&lt;"))
        XCTAssertTrue(escaped.contains("&gt;"))
    }

    func testAssistantVoiceSSMLGenderMapping() {
        XCTAssertEqual(AssistantVoice.femaleOne.ssmlGender, "FEMALE")
        XCTAssertEqual(AssistantVoice.femaleTwo.ssmlGender, "FEMALE")
        XCTAssertEqual(AssistantVoice.maleOne.ssmlGender, "MALE")
        XCTAssertEqual(AssistantVoice.maleTwo.ssmlGender, "MALE")
    }
}
