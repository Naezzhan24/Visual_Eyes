import XCTest
@testable import VisualEyes

/// Spoken Login/Register answers must parse like the Android screens.
@MainActor
final class SpokenInputParserTests: XCTestCase {
    func testSchoolIdFromDigitsAndWords() {
        XCTAssertEqual(SpokenInputParser.schoolId(from: "two one zero zero one two three"), "21-00123")
        XCTAssertEqual(SpokenInputParser.schoolId(from: "21 00123"), "21-00123")
        XCTAssertEqual(SpokenInputParser.spellDigits("21-00123"), "two one zero zero one two three")
    }

    func testBirthdatePassword() {
        XCTAssertEqual(SpokenInputParser.password(from: "February 27th, 2005"), "02-27-2005")
        XCTAssertEqual(SpokenInputParser.password(from: "my birthday is january 5 2006"), "01-05-2006")
        XCTAssertNil(SpokenInputParser.birthdate(from: "february 2005"))
    }

    func testBirthdateTypingFormat() {
        XCTAssertEqual(SpokenInputParser.formatBirthdateTyping("02272005"), "02-27-2005")
        XCTAssertEqual(SpokenInputParser.formatBirthdateTyping("022"), "02-2")
    }

    func testRegisterParsers() {
        XCTAssertEqual(RegisterViewModel.parseSpokenEmail("juan dot cruz at gmail dot com"), "juan.cruz@gmail.com")
        XCTAssertEqual(RegisterViewModel.parseSpokenEmail("my email is ana at school dot edu ph"), "ana@school.edu.ph")
        XCTAssertEqual(RegisterViewModel.normalizeYearLevel("third year"), "3rd Year")
        XCTAssertEqual(RegisterViewModel.capitalizeName("maria CLARA"), "Maria Clara")
        XCTAssertEqual(RegisterViewModel.spellOut("Ana"), "A, N, A")
    }

    func testLetterCorrectionParsing() {
        XCTAssertEqual(RegisterViewModel.parseLetterPosition("letter three", maxLength: 5), 3)
        XCTAssertEqual(RegisterViewModel.parseLetterPosition("pangalawa", maxLength: 5), -1)
        XCTAssertEqual(RegisterViewModel.parseLetterPosition("second", maxLength: 5), 2)
        XCTAssertEqual(RegisterViewModel.parseLetterPosition("letter 9", maxLength: 5), -1)
        XCTAssertEqual(RegisterViewModel.parseSpokenLetters("double l"), "LL")
        XCTAssertEqual(RegisterViewModel.parseSpokenLetters("double u"), "W")
        XCTAssertEqual(RegisterViewModel.parseSpokenLetters("bee"), "B")
    }
}
