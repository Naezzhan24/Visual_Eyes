import XCTest
@testable import VisualEyes

/// The audio prep and output cleanup around WhisperKit (the model itself
/// isn't loaded in unit tests).
@MainActor
final class WhisperSTTServiceTests: XCTestCase {
    func testSilenceMarkersAreDropped() {
        XCTAssertEqual(WhisperSTTService.clean(" [BLANK_AUDIO] "), "")
        XCTAssertEqual(WhisperSTTService.clean("(music) yes  please"), "yes please")
        XCTAssertEqual(WhisperSTTService.clean("<|endoftext|>open first"), "open first")
    }

    func testInt16ToFloat() {
        var samples: [Int16] = [0, Int16.max, -Int16.max]
        let data = Data(bytes: &samples, count: samples.count * 2)
        let floats = WhisperSTTService.floatSamples(fromInt16: data)
        XCTAssertEqual(floats.count, 3)
        XCTAssertEqual(floats[1], 1, accuracy: 0.0001)
        XCTAssertEqual(floats[2], -1, accuracy: 0.0001)
    }

    func testResampleTo16k() {
        let oneSecondAt48k = [Float](repeating: 0.5, count: 48_000)
        let resampled = WhisperSTTService.resample(oneSecondAt48k, from: 48_000, to: 16_000)
        XCTAssertEqual(resampled.count, 16_000)
        XCTAssertEqual(resampled[100], 0.5, accuracy: 0.0001)
        // Already 16kHz: untouched.
        XCTAssertEqual(WhisperSTTService.resample([1, 2, 3], from: 16_000, to: 16_000), [1, 2, 3])
    }
}
