import XCTest
@testable import VisualEyes

/// The tab command phrases must behave like the Android fragments'
/// handleCommand() — same phrases, same precedence.
final class TabVoiceCommandsTests: XCTestCase {
    func testHomeCommands() {
        XCTAssertEqual(TabVoiceCommands.interpret("Help", on: .home), .help)
        XCTAssertEqual(TabVoiceCommands.interpret("open latest material", on: .home), .openLatest)
        XCTAssertEqual(TabVoiceCommands.interpret("Materials", on: .home), .goTo(.materials))
        XCTAssertEqual(TabVoiceCommands.interpret("go to my profile", on: .home), .goTo(.profile))
        XCTAssertEqual(TabVoiceCommands.interpret("recommended font", on: .home), .useRecommendedFont)
        XCTAssertEqual(TabVoiceCommands.interpret("default font", on: .home), .useDefaultFont)
        XCTAssertEqual(TabVoiceCommands.interpret("log out", on: .home), .logout)
        XCTAssertEqual(TabVoiceCommands.interpret("home", on: .home), .alreadyHere)
        XCTAssertEqual(TabVoiceCommands.interpret("banana", on: .home), .unknown)
    }

    func testMaterialsCommands() {
        XCTAssertEqual(TabVoiceCommands.interpret("read titles", on: .materials), .readTitles)
        XCTAssertEqual(TabVoiceCommands.interpret("open featured", on: .materials), .openFeatured)
        XCTAssertEqual(TabVoiceCommands.interpret("open second", on: .materials), .openIndex(1))
        XCTAssertEqual(TabVoiceCommands.interpret("open the photosynthesis lesson", on: .materials),
                       .openTitle("photosynthesis"))
        XCTAssertEqual(TabVoiceCommands.interpret("leave feedback", on: .materials), .openFeedback)
        XCTAssertEqual(TabVoiceCommands.interpret("home", on: .materials), .goTo(.home))
    }

    func testMaterialsConfirmation() {
        XCTAssertEqual(TabVoiceCommands.interpret("yes", on: .materials, confirmingOpen: true), .confirmYes)
        XCTAssertEqual(TabVoiceCommands.interpret("no", on: .materials, confirmingOpen: true), .confirmNo)
        XCTAssertEqual(TabVoiceCommands.interpret("maybe", on: .materials, confirmingOpen: true), .confirmUnclear)
    }

    func testProfileCommands() {
        XCTAssertEqual(TabVoiceCommands.interpret("turn off text to speech", on: .profile), .setTTS(false))
        XCTAssertEqual(TabVoiceCommands.interpret("enable speech to text", on: .profile), .setSTT(true))
        XCTAssertEqual(TabVoiceCommands.interpret("text to speech", on: .profile), .setTTS(nil))
        XCTAssertEqual(TabVoiceCommands.interpret("change voice", on: .profile), .changeVoice)
        XCTAssertEqual(TabVoiceCommands.interpret("retake assessment", on: .profile), .retakeAssessment)
        XCTAssertEqual(TabVoiceCommands.interpret("read my profile", on: .profile), .readProfile)
        XCTAssertEqual(TabVoiceCommands.interpret("profile", on: .profile), .alreadyHere)
    }

    func testTitleMatchingPrefersTheBestTitle() {
        let materials = [
            LearningMaterial(id: 1, title: "Introduction to Photosynthesis", filePath: "a.pdf", uploadDate: nil),
            LearningMaterial(id: 2, title: "Cell Division", filePath: "b.pdf", uploadDate: nil)
        ]
        XCTAssertEqual(TabVoiceCommands.bestMatch(for: "photosynthesis", in: materials)?.id, 1)
        XCTAssertEqual(TabVoiceCommands.bestMatch(for: "cell", in: materials)?.id, 2)
        XCTAssertNil(TabVoiceCommands.bestMatch(for: "algebra", in: materials))
    }
}
