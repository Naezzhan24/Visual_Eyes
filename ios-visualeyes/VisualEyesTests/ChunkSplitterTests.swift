import XCTest
@testable import VisualEyes

final class ChunkSplitterTests: XCTestCase {
    func testShortParagraphBecomesOneChunk() {
        let text = "This is a short paragraph. It has two sentences."
        let chunks = ChunkSplitter.chunks(from: text)
        XCTAssertEqual(chunks.count, 1)
        XCTAssertEqual(chunks[0].text, text)
        XCTAssertEqual(chunks[0].style, .body)
    }

    func testShortUnpunctuatedLineIsClassifiedAsHeading() {
        let text = "Chapter One"
        let chunks = ChunkSplitter.chunks(from: text)
        XCTAssertEqual(chunks.count, 1)
        XCTAssertEqual(chunks[0].style, .heading)
    }

    func testLongParagraphSplitsWithoutBreakingSentences() {
        let sentence = "The quick brown fox jumps over the lazy dog and keeps running through the field. "
        let longParagraph = String(repeating: sentence, count: 10)
        let chunks = ChunkSplitter.chunks(from: longParagraph)

        XCTAssertGreaterThan(chunks.count, 1)
        for chunk in chunks {
            XCTAssertLessThanOrEqual(chunk.text.count, ChunkSplitter.maxChunkLength + sentence.count)
            // Every chunk should end on a sentence boundary, not mid-word.
            XCTAssertTrue(chunk.text.hasSuffix("."))
        }
    }

    func testFigureCaptionIsClassified() {
        let text = "Figure 1. A diagram showing the water cycle."
        let chunks = ChunkSplitter.chunks(from: text)
        XCTAssertEqual(chunks[0].style, .caption)
    }

    func testBlankLinesSeparateParagraphs() {
        let text = "First paragraph here.\n\nSecond paragraph here."
        let chunks = ChunkSplitter.chunks(from: text)
        XCTAssertEqual(chunks.count, 2)
    }
}
