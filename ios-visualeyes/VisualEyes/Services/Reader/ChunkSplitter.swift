import Foundation

/// Splits raw extracted PDF text into paragraph-aware, sentence-boundary
/// -respecting reading chunks, classified for styling.
///
/// This is an approximation of AccessibleMaterialActivity.java's chunking
/// heuristic (paragraph breaks, ≤600-char sentence-aware splitting,
/// heading/body/caption classification) rather than a verified verbatim
/// port — the exact Android heuristic wasn't captured in the porting
/// research at the level of "which regex/heuristic exactly." Revisit
/// against a handful of real materials once this is running on device;
/// the chunk-size cap and general approach (don't split mid-sentence,
/// treat short unpunctuated lines as headings) are intentionally kept
/// close to the documented Android behavior.
enum ChunkSplitter {
    static let maxChunkLength = 600

    static func chunks(from rawText: String) -> [ReaderChunk] {
        let paragraphs = rawText
            .components(separatedBy: "\n\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        var result: [ReaderChunk] = []
        for paragraph in paragraphs {
            let style = classify(paragraph)
            if style != .body || paragraph.count <= maxChunkLength {
                result.append(ReaderChunk(text: paragraph, style: style))
            } else {
                result.append(contentsOf: splitLongParagraph(paragraph))
            }
        }
        return result
    }

    private static func classify(_ paragraph: String) -> ReaderBlockStyle {
        let trimmed = paragraph.trimmingCharacters(in: .whitespacesAndNewlines)
        let isSingleLine = !trimmed.contains("\n")
        let endsWithSentencePunctuation = trimmed.hasSuffix(".") || trimmed.hasSuffix("!") || trimmed.hasSuffix("?")

        let lowered = trimmed.lowercased()
        if lowered.hasPrefix("figure") || lowered.hasPrefix("table") || lowered.hasPrefix("source:") {
            return .caption
        }
        if isSingleLine && trimmed.count <= 80 && !endsWithSentencePunctuation {
            return .heading
        }
        return .body
    }

    private static func splitLongParagraph(_ paragraph: String) -> [ReaderChunk] {
        let sentences = splitIntoSentences(paragraph)
        var chunks: [ReaderChunk] = []
        var current = ""

        for sentence in sentences {
            if !current.isEmpty && current.count + sentence.count + 1 > maxChunkLength {
                chunks.append(ReaderChunk(text: current.trimmingCharacters(in: .whitespaces), style: .body))
                current = ""
            }
            current += (current.isEmpty ? "" : " ") + sentence
        }
        if !current.trimmingCharacters(in: .whitespaces).isEmpty {
            chunks.append(ReaderChunk(text: current.trimmingCharacters(in: .whitespaces), style: .body))
        }
        return chunks
    }

    private static func splitIntoSentences(_ text: String) -> [String] {
        var sentences: [String] = []
        var current = ""
        for character in text {
            current.append(character)
            if character == "." || character == "!" || character == "?" {
                sentences.append(current.trimmingCharacters(in: .whitespaces))
                current = ""
            }
        }
        let remainder = current.trimmingCharacters(in: .whitespaces)
        if !remainder.isEmpty {
            sentences.append(remainder)
        }
        return sentences
    }
}
