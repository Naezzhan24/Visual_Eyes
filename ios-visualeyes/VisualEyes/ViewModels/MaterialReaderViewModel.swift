import Foundation
import Observation
import AVFoundation

/// Port of AccessibleMaterialActivity.java: downloads the material's PDF
/// via a signed URL, extracts + chunks its text, and reads it aloud one
/// paragraph at a time with live word highlighting. Voice driven like
/// Android: after each paragraph it asks "next, repeat, faster, or slower"
/// and listens; "yes", "no", "restart", "instruction", "feedback", "back",
/// "increase text"… work at any time. The on-screen buttons call the same
/// actions.
@MainActor
@Observable
final class MaterialReaderViewModel {
    let material: LearningMaterial

    private(set) var chunks: [ReaderChunk] = []
    private(set) var currentChunkIndex = 0
    private(set) var highlightedRange: NSRange?
    /// A paragraph is being spoken right now.
    private(set) var isPlaying = false
    private(set) var isLoading = true
    private(set) var errorMessage: String?
    private(set) var voiceStatus = "Voice: waiting..."
    private(set) var isListening = false
    /// Set when "back" / "feedback" is said; the view acts on them.
    var requestedBack = false
    var requestedFeedback = false
    /// Starts at the app-wide size, like AccessibleMaterialActivity reading
    /// FontSizeManager.getFontSize.
    var textSizePt = CGFloat(min(max(FontSizePreferences.fontSize, 14), 34))

    private let materialsRepository: MaterialsRepository
    private let sessionStore: SessionStore
    private let readerTTS = OnDeviceReaderTTSService()
    private let cascade = SpeechCascadeSession()
    private let micPermission = MicPermissionService.shared

    /// Android's speechRate multiplier: 0.5×–1.5× in 0.1 steps.
    private var rateMultiplier: Float = 0.9
    private var speechRate: Float { AVSpeechUtteranceDefaultSpeechRate * rateMultiplier }

    /// A read session is running (speaking, or waiting on the prompt).
    private(set) var isReading = false
    private var awaitingChunkDecision = false
    private var hasReadAChunk = false
    private var screenActive = false
    private var didStart = false
    private var pausedForBackground = false
    private var flow = 0
    private var consecutiveErrors = 0

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

    // MARK: - Screen lifecycle (onCreate / onResume / onPause)

    func screenAppeared() {
        screenActive = true
        if !didStart {
            didStart = true
            Task { await openMaterial() }
        } else if pausedForBackground {
            pausedForBackground = false
            voiceStatus = "Reading paused"
            say("Reading paused. Say yes to continue, or say instruction for help.")
        } else if !isReading {
            scheduleListening(after: .milliseconds(700))
        }
    }

    /// Leaving the screen, or covering it with Feedback: stop talking and
    /// listening so nothing carries over to the next screen.
    func screenDisappeared() {
        screenActive = false
        flow += 1
        stopListening()
        readerTTS.stop()
        if isReading { pausedForBackground = true }
        isReading = false
        awaitingChunkDecision = false
        isPlaying = false
        highlightedRange = nil
    }

    func stopEverything() { screenDisappeared() }

    /// The instructions play while the PDF loads (speakInstructionsAndAsk),
    /// then "Material loaded…".
    private func openMaterial() async {
        await micPermission.requestIfNeeded()
        async let instructionsDone: Void = speakAndWait(Self.instructions)
        await load()
        await instructionsDone
        guard screenActive else { return }
        if errorMessage != nil, chunks.isEmpty {
            voiceStatus = "Voice: load failed"
            say("Could not load the material. Please check your connection and try again.")
        } else if chunks.isEmpty {
            say("No readable content available in this PDF.")
        } else {
            voiceStatus = "Loaded — \(chunks.count) sections"
            if currentChunkIndex > 0 {
                say("Material loaded. You have unfinished reading — say yes to continue where you left off, "
                    + "or say restart to begin from the start.")
            } else {
                say("Material loaded. Say yes to start reading, or say instruction for help.")
            }
        }
    }

    private static let instructions = "Opened learning material. "
        + "Please wait while the content loads. "
        + "When ready, say yes to start reading. "
        + "After each paragraph, I will ask you to say next to continue, "
        + "repeat to hear it again, or faster or slower to change reading speed. "
        + "Say no or stop to pause. "
        + "Say increase text or decrease text to adjust the font size. "
        + "Say instruction to hear this guide again. "
        + "Say feedback to leave feedback on this material. "
        + "Say back to return to the materials screen."

    func load() async {
        isLoading = true
        errorMessage = nil
        if let schoolId = sessionStore.student?.schoolId {
            MaterialReadTracker.markOpened(materialId: material.id, studentSchoolId: schoolId)
        }
        do {
            let signedURL = try await materialsRepository.signedURL(forFilePath: material.filePath)
            let (data, response) = try await URLSession.shared.data(from: signedURL)
            // Without this a storage error body would be handed to PDFKit
            // and surface as a confusing "couldn't read PDF" message.
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                throw SupabaseError.httpError(statusCode: http.statusCode, body: "Could not download this material.")
            }

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


    // MARK: - Reading (resumeReading / pauseReading / startReading / readNextChunk)

    /// Continue button and "yes"/"start": resume mid-way, else start over.
    func resumeReading() {
        guard !isLoading else {
            say("Material is still loading. Please wait.")
            return
        }
        guard !chunks.isEmpty else {
            say("No readable content available.")
            return
        }
        if currentChunkIndex > 0 {
            isReading = true
            say("Resuming.", listenAfter: false) { [weak self] in self?.readCurrentChunk() }
        } else {
            startReading()
        }
    }

    /// Pause button and "no"/"stop"/"pause".
    func pauseReading() {
        isReading = false
        awaitingChunkDecision = false
        isPlaying = false
        highlightedRange = nil
        readerTTS.stop()
        say("Reading stopped. Say yes to resume.")
    }

    func togglePlayback() {
        if isReading || isPlaying { pauseReading() } else { resumeReading() }
    }

    private func startReading() {
        guard !isLoading else {
            say("Material is still loading. Please wait.")
            return
        }
        guard !chunks.isEmpty else {
            say("No readable content available.")
            return
        }
        currentChunkIndex = 0
        isReading = true
        awaitingChunkDecision = false
        voiceStatus = "Reading started..."
        readCurrentChunk()
    }

    private func readCurrentChunk() {
        guard isReading, screenActive else { return }
        guard let chunk = currentChunk else {
            finishMaterial()
            return
        }
        stopListening()
        flow += 1
        let token = flow
        hasReadAChunk = true
        isPlaying = true
        highlightedRange = nil
        persistProgress()

        // "Paragraph N." / "Heading." is spoken but not shown, so word
        // ranges are shifted back by its length (pendingWordPrefixLen).
        let prefix = chunk.style == .heading ? "Heading. " : "Paragraph \(paragraphNumber(at: currentChunkIndex)). "
        let prefixLength = (prefix as NSString).length
        voiceStatus = "Voice: speaking..."
        readerTTS.speak(
            prefix + chunk.text,
            rate: speechRate,
            onWordBoundary: { [weak self] word in
                guard let self, token == self.flow else { return }
                let start = word.range.location - prefixLength
                guard start + word.range.length > 0 else { return }
                self.highlightedRange = NSRange(location: max(start, 0), length: word.range.length)
            },
            onFinished: { [weak self] in
                guard let self, token == self.flow else { return }
                self.isPlaying = false
                self.highlightedRange = nil
                if self.isReading { self.askChunkDecision() }
            }
        )
    }

    /// Paragraph numbers skip headings, like Android's paragraphNumber.
    private func paragraphNumber(at index: Int) -> Int {
        chunks.prefix(index + 1).filter { $0.style != .heading }.count
    }

    private func askChunkDecision() {
        awaitingChunkDecision = true
        var prompt = "Say next, repeat, faster, or slower."
        if paragraphNumber(at: currentChunkIndex) == 1 {
            prompt += " You can also say feedback at any time to leave feedback on this material."
        }
        say(prompt)
    }

    private func readNextChunk() {
        currentChunkIndex += 1
        guard currentChunkIndex < chunks.count else {
            finishMaterial()
            return
        }
        readCurrentChunk()
    }

    private func finishMaterial() {
        isReading = false
        awaitingChunkDecision = false
        isPlaying = false
        currentChunkIndex = 0
        persistProgress()
        say("End of material. Say yes to read again.")
    }

    // MARK: - Buttons

    func next() {
        guard currentChunkIndex + 1 < chunks.count else { return }
        readerTTS.stop()
        isPlaying = false
        currentChunkIndex += 1
        highlightedRange = nil
        persistProgress()
        if isReading { readCurrentChunk() }
    }

    func previous() {
        guard currentChunkIndex > 0 else { return }
        readerTTS.stop()
        isPlaying = false
        currentChunkIndex -= 1
        highlightedRange = nil
        persistProgress()
        if isReading { readCurrentChunk() }
    }

    func repeatChunk() {
        isReading = true
        awaitingChunkDecision = false
        readCurrentChunk()
    }

    /// Stops reading without announcing it (e.g. before Feedback opens).
    func pause() {
        guard isReading || isPlaying else { return }
        isReading = false
        awaitingChunkDecision = false
        isPlaying = false
        readerTTS.stop()
    }

    func increaseTextSize() { applyTextSize(textSizePt + 2) }
    func decreaseTextSize() { applyTextSize(textSizePt - 2) }

    private func applyTextSize(_ size: CGFloat) {
        textSizePt = min(max(size, 14), 34)
        if !isReading {
            say("Text size is now \(Int(textSizePt)) points.")
        }
    }

    func faster() {
        rateMultiplier = min(rateMultiplier + 0.1, 1.5)
        if isReading { voiceStatus = "Speed up — applies next paragraph" } else { say("Speed increased.") }
    }

    func slower() {
        rateMultiplier = max(rateMultiplier - 0.1, 0.5)
        if isReading { voiceStatus = "Speed down — applies next paragraph" } else { say("Speed decreased.") }
    }

    // MARK: - Voice commands (handleCommand)

    private func handle(_ spoken: String) {
        let cmd = Self.normalizeCommand(spoken)
        voiceStatus = "Heard: \(cmd)"
        guard !cmd.isEmpty else {
            scheduleListening(after: .milliseconds(400))
            return
        }

        if awaitingChunkDecision {
            awaitingChunkDecision = false
            if Self.isNext(cmd) { readNextChunk(); return }
            if Self.isRepeat(cmd) { repeatChunk(); return }
            if Self.isFaster(cmd) { rateMultiplier = min(rateMultiplier + 0.1, 1.5); repeatChunk(); return }
            if Self.isSlower(cmd) { rateMultiplier = max(rateMultiplier - 0.1, 0.5); repeatChunk(); return }
            // Otherwise fall through to stop/back/feedback/instruction/yes.
        }

        if Self.isInstruction(cmd) { say(Self.instructions); return }
        // Feedback before back: "feedback" contains "back".
        if Self.isFeedback(cmd) { openFeedback(); return }
        if Self.isBack(cmd) { goBack(); return }
        if Self.isRestart(cmd) { startReading(); return }
        if Self.isRepeat(cmd) && hasReadAChunk { repeatChunk(); return }
        if Self.isYes(cmd) { resumeReading(); return }
        if Self.isNoOrStop(cmd) { pauseReading(); return }
        if Self.isIncreaseText(cmd) { increaseTextSize(); return }
        if Self.isDecreaseText(cmd) { decreaseTextSize(); return }
        if Self.isFaster(cmd) { faster(); return }
        if Self.isSlower(cmd) { slower(); return }

        say("Command not recognised. Say yes to read, no to stop, or instruction for help.")
    }

    func openFeedback() {
        pause()
        stopListening()
        requestedFeedback = true
    }

    private func goBack() {
        screenDisappeared()
        pausedForBackground = false
        requestedBack = true
    }

    static func normalizeCommand(_ input: String) -> String {
        var cmd = TabVoiceCommands.normalize(input)
        if ["yas", "yess", "guess", "jes", "o o", "oo po", "oo"].contains(cmd) { cmd = "yes" }
        if cmd == "hinde" || cmd == "hindi" { cmd = "no" }
        if cmd == "instructions" { cmd = "instruction" }
        return cmd
    }

    static func isYes(_ c: String) -> Bool {
        if c.contains("restart") || c.contains("beginning") { return false }
        return c == "yes" || c.contains("start reading") || c.contains("start")
            || c.contains("begin") || c == "go" || c == "opo" || c.contains("sige")
    }
    static func isNoOrStop(_ c: String) -> Bool {
        c == "no" || c.contains("stop") || c.contains("pause") || c.contains("ayoko")
    }
    static func isRestart(_ c: String) -> Bool {
        c.contains("restart") || c.contains("from beginning") || c.contains("muli")
    }
    static func isInstruction(_ c: String) -> Bool {
        c.contains("instruction") || c.contains("help") || c.contains("guide")
    }
    static func isFeedback(_ c: String) -> Bool {
        c.contains("feedback") || c.contains("feed back") || c.contains("puna") || c.contains("komento")
    }
    static func isBack(_ c: String) -> Bool {
        !isFeedback(c) && (c.contains("back") || c.contains("return") || c.contains("balik"))
    }
    static func isIncreaseText(_ c: String) -> Bool {
        ["increase text", "bigger text", "larger text", "zoom in", "lakihan", "palakihin"].contains { c.contains($0) }
    }
    static func isDecreaseText(_ c: String) -> Bool {
        ["decrease text", "smaller text", "reduce text", "zoom out", "liitan", "paliitin"].contains { c.contains($0) }
    }
    static func isFaster(_ c: String) -> Bool {
        ["faster", "increase speed", "speed up", "bilisan"].contains { c.contains($0) }
    }
    static func isSlower(_ c: String) -> Bool {
        ["slower", "decrease speed", "slow down", "bagalan"].contains { c.contains($0) }
    }
    static func isNext(_ c: String) -> Bool {
        c == "next" || c.contains("next paragraph") || c.contains("continue") || c.contains("susunod")
    }
    static func isRepeat(_ c: String) -> Bool {
        c.contains("repeat") || c.contains("again") || c.contains("ulit")
    }

    // MARK: - Speaking prompts and listening

    /// speakNow: a short prompt in the reader voice, then listen again.
    private func say(_ text: String, listenAfter: Bool = true, then action: (() -> Void)? = nil) {
        guard screenActive else { return }
        stopListening()
        flow += 1
        let token = flow
        isPlaying = false
        readerTTS.speak(text, rate: AVSpeechUtteranceDefaultSpeechRate, onWordBoundary: { _ in }, onFinished: { [weak self] in
            guard let self, token == self.flow else { return }
            action?()
            if listenAfter { self.scheduleListening(after: .milliseconds(400)) }
        })
    }

    /// Speaks and waits until done, or until something else takes over.
    private func speakAndWait(_ text: String) async {
        guard screenActive else { return }
        flow += 1
        let token = flow
        let done = OneShot()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            readerTTS.speak(text, rate: AVSpeechUtteranceDefaultSpeechRate, onWordBoundary: { _ in }, onFinished: {
                if done.fire() { continuation.resume() }
            })
            Task { [weak self] in
                while !done.hasFired {
                    try? await Task.sleep(for: .milliseconds(300))
                    guard let self, token == self.flow, self.screenActive else { break }
                }
                if done.fire() { continuation.resume() }
            }
        }
    }

    private func scheduleListening(after delay: Duration) {
        let token = flow
        Task {
            try? await Task.sleep(for: delay)
            guard token == flow else { return }
            listen()
        }
    }

    private func listen() {
        guard screenActive, !isListening, !isPlaying else { return }
        micPermission.refresh()
        guard micPermission.isAuthorized else {
            voiceStatus = "Voice: microphone permission missing"
            return
        }
        flow += 1
        let token = flow
        isListening = true
        voiceStatus = "Voice: listening..."
        let contextual = ["yes", "no", "next", "repeat", "faster", "slower", "restart", "instruction",
                          "feedback", "back", "increase text", "decrease text", "stop"]
        // The reader works by voice even with the Profile STT switch off
        // (Android builds its recognizer with respectVoicePreferences=false).
        cascade.listen(contextualStrings: contextual, respectsPreferences: false,
                       cloudFallbackOnSilence: false) { [weak self] event in
            guard let self, token == self.flow else { return }
            switch event {
            case .partial(let text):
                self.voiceStatus = "Hearing: \(text)"
            case .final(let text):
                self.isListening = false
                self.consecutiveErrors = 0
                self.handle(text)
            case .timedOut:
                self.isListening = false
                self.consecutiveErrors = 0
                self.scheduleListening(after: .seconds(1))
            case .error:
                self.isListening = false
                self.consecutiveErrors += 1
                self.scheduleListening(after: self.consecutiveErrors > 3 ? .seconds(5) : .seconds(1))
            }
        }
    }

    private func stopListening() {
        cascade.stop()
        isListening = false
    }

    private func persistProgress() {
        guard let schoolId = sessionStore.student?.schoolId else { return }
        MaterialReadTracker.saveChunkIndex(currentChunkIndex, studentSchoolId: schoolId, materialId: material.id)
    }
}

/// Resumes a continuation exactly once.
@MainActor
private final class OneShot {
    private(set) var hasFired = false
    func fire() -> Bool {
        guard !hasFired else { return false }
        hasFired = true
        return true
    }
}
