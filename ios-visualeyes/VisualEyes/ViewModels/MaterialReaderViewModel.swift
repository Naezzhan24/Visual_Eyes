import Foundation
import Observation
import AVFoundation

/// Stand-in for AccessibleMaterialActivity.java: downloads the material's
/// PDF via a signed URL, extracts + chunks its text, and reads it aloud
/// with live word-highlighting via `OnDeviceReaderTTSService`.
///
/// Full voice-command control (next/repeat/faster/slower by voice, EN+
/// Tagalog synonyms) lands in Phase 5 — Phase 4 wires the equivalent
/// on-screen buttons plus the resume-per-material/text-size/pinch-zoom
/// pieces that don't depend on the voice-command grammar table.
@MainActor
@Observable
final class MaterialReaderViewModel {
    let material: LearningMaterial

    private(set) var chunks: [ReaderChunk] = []
    private(set) var currentChunkIndex = 0
    private(set) var highlightedRange: NSRange?
    private(set) var isPlaying = false
    private(set) var isLoading = true
    private(set) var errorMessage: String?
    var textSizePt: CGFloat = 20

    private let materialsRepository: MaterialsRepository
    private let sessionStore: SessionStore
    private let readerTTS = OnDeviceReaderTTSService()
    private var speechRate: Float = AVSpeechUtteranceDefaultSpeechRate * 0.9

    var currentChunk: ReaderChunk? {
        guard currentChunkIndex < chunks.count else { return nil }
        return chunks[currentChunkIndex]
    }

    var progressText: String {
        chunks.isEmpty ? "" : "Paragraph \(currentChunkIndex + 1) of \(chunks.count)"
    }

    var isAtFirstChunk: Bool { currentChunkIndex == 0 }
    var isAtLastChunk: Bool { chunks.isEmpty || currentChunkIndex >= chunks.count - 1 }

    init(
        material: LearningMaterial,
        materialsRepository: MaterialsRepository = MaterialsRepository(),
        sessionStore: SessionStore = .shared
    ) {
        self.material = material
        self.materialsRepository = materialsRepository
        self.sessionStore = sessionStore
    }

    func load() async {
        isLoading = true
        errorMessage = nil
        do {
            let signedURL = try await materialsRepository.signedURL(forFilePath: material.filePath)
            let (data, _) = try await URLSession.shared.data(from: signedURL)

            let tempURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("material-\(material.id).pdf")
            try data.write(to: tempURL)

            // PDFKit's text extraction isn't async and can take a
            // noticeable moment on a long document — keep it off the
            // main actor.
            let text = try await Task.detached(priority: .userInitiated) {
                try PDFTextExtractor.extractText(from: tempURL)
            }.value

            chunks = ChunkSplitter.chunks(from: text)
            if let schoolId = sessionStore.student?.schoolId, !chunks.isEmpty {
                let resumeIndex = MaterialReadTracker.lastChunkIndex(studentSchoolId: schoolId, materialId: material.id)
                currentChunkIndex = min(max(resumeIndex, 0), chunks.count - 1)
            }
        } catch let error as SupabaseError {
            if case .sessionExpired = error {
                sessionStore.forceLogout()
            } else {
                errorMessage = error.localizedDescription
            }
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    func togglePlayback() {
        if isPlaying {
            pause()
        } else {
            playCurrentChunk()
        }
    }

    private func playCurrentChunk() {
        guard let chunk = currentChunk else { return }
        isPlaying = true
        highlightedRange = nil
        readerTTS.speak(
            chunk.text,
            rate: speechRate,
            onWordBoundary: { [weak self] wordRange in
                self?.highlightedRange = wordRange.range
            },
            onFinished: { [weak self] in
                self?.advanceAfterFinishingChunk()
            }
        )
    }

    private func advanceAfterFinishingChunk() {
        isPlaying = false
        highlightedRange = nil
        guard currentChunkIndex + 1 < chunks.count else { return }
        currentChunkIndex += 1
        persistProgress()
        playCurrentChunk()
    }

    func pause() {
        readerTTS.stop()
        isPlaying = false
    }

    func next() {
        pause()
        guard currentChunkIndex + 1 < chunks.count else { return }
        currentChunkIndex += 1
        highlightedRange = nil
        persistProgress()
    }

    func previous() {
        pause()
        guard currentChunkIndex > 0 else { return }
        currentChunkIndex -= 1
        highlightedRange = nil
        persistProgress()
    }

    func repeatChunk() {
        pause()
        playCurrentChunk()
    }

    func increaseTextSize() {
        textSizePt = min(textSizePt + 2, 34)
    }

    func decreaseTextSize() {
        textSizePt = max(textSizePt - 2, 14)
    }

    func faster() {
        speechRate = min(speechRate + 0.05, 0.8)
    }

    func slower() {
        speechRate = max(speechRate - 0.05, 0.25)
    }

    private func persistProgress() {
        guard let schoolId = sessionStore.student?.schoolId else { return }
        MaterialReadTracker.saveChunkIndex(currentChunkIndex, studentSchoolId: schoolId, materialId: material.id)
    }

    func stopEverything() {
        pause()
    }
}
