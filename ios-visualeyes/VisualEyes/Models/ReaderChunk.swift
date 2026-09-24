import Foundation

enum ReaderBlockStyle {
    case heading
    case body
    case caption
}

/// One "paragraph" the reader speaks and highlights as a unit — the
/// direct analogue of a chunk in AccessibleMaterialActivity.java's
/// `splitIntoSmartChunks`/`groupSentencesIntoChunks`.
struct ReaderChunk: Identifiable {
    let id = UUID()
    let text: String
    let style: ReaderBlockStyle
}
