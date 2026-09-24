import Foundation
import PDFKit

/// Extracts plain text from a downloaded material PDF. `PDFKit` is a
/// first-party, free Apple framework — this fully replaces the
/// PDFBox-Android dependency the Android app uses, with no third-party
/// license question at all (unlike the Syncfusion package a Flutter port
/// would have needed).
enum PDFTextExtractor {
    enum ExtractionError: Error, LocalizedError {
        case couldNotOpen
        case noText

        var errorDescription: String? {
            switch self {
            case .couldNotOpen: return "Could not open this material's PDF file."
            case .noText: return "This material doesn't contain any readable text."
            }
        }
    }

    /// Blocking/synchronous by design — callers should run this off the
    /// main actor (see `MaterialReaderViewModel.load()`, which wraps it
    /// in `Task.detached`) since PDFKit's text extraction isn't async and
    /// can take a noticeable moment on a long document.
    static func extractText(from url: URL) throws -> String {
        guard let document = PDFDocument(url: url) else {
            throw ExtractionError.couldNotOpen
        }

        var fullText = ""
        for pageIndex in 0..<document.pageCount {
            guard let page = document.page(at: pageIndex), let pageText = page.string else { continue }
            fullText += pageText
            fullText += "\n\n"
        }

        let trimmed = fullText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw ExtractionError.noText }
        return trimmed
    }
}
