import Foundation
import Observation
import CoreGraphics

enum FeedbackStep: Int, CaseIterable {
    case rating, textSize, speed, materialFeedback, instructorFeedback
}

/// Stand-in for FeedbackActivity.java's 5-step wizard.
///
/// SIMPLIFICATION vs Android: the "base" text size/speed this screen
/// previews from currently defaults to the student's assessment-
/// recommended size and a neutral 100% speed, rather than reading the
/// live app-wide `FontSizeManager`/`SpeechRateManager` values (those
/// don't have a Phase 6-built equivalent store yet on this port — the
/// reader screen has its own local `textSizePt`, not a shared global
/// one). Revisit once a shared preferences layer exists so this preview
/// reflects whatever the student is actually currently using.
@MainActor
@Observable
final class FeedbackViewModel {
    let material: LearningMaterial

    private(set) var step: FeedbackStep = .rating
    var rating: Int = 0

    private(set) var textSizeLevel = 0
    private(set) var workTextSize: CGFloat
    private let baseTextSize: CGFloat
    private var textSizeAdjustments = 0

    private(set) var speedLevel = 0
    private(set) var workSpeedScale: Double
    private let baseSpeedScale: Double
    private var speedAdjustments = 0

    var materialFeedbackText = ""
    var instructorFeedbackText = ""

    private(set) var isSubmitting = false
    private(set) var didSubmit = false
    var errorMessage: String?

    private let feedbackRepository: FeedbackRepository
    private let voiceSettingsRepository: VoiceSettingsRepository
    private let tts: CloudTTSService
    private let sessionStore: SessionStore

    init(
        material: LearningMaterial,
        feedbackRepository: FeedbackRepository = FeedbackRepository(),
        voiceSettingsRepository: VoiceSettingsRepository = VoiceSettingsRepository(),
        tts: CloudTTSService = .shared,
        sessionStore: SessionStore = .shared
    ) {
        self.material = material
        self.feedbackRepository = feedbackRepository
        self.voiceSettingsRepository = voiceSettingsRepository
        self.tts = tts
        self.sessionStore = sessionStore

        let initialSize = sessionStore.student?.recommendedTextSize.map { CGFloat($0) } ?? 20
        baseTextSize = initialSize
        workTextSize = initialSize
        baseSpeedScale = 1.0
        workSpeedScale = 1.0
    }

    var textSizePreviewText: String {
        guard textSizeLevel != 0 else { return "" }
        if textSizeLevel == 3 {
            return "Kept at \(FeedbackPreferences.formatted(workTextSize))sp"
        }
        return "Showing \(FeedbackPreferences.formatted(workTextSize))sp (was \(FeedbackPreferences.formatted(baseTextSize))sp). Adjust again, or choose Just right to keep it."
    }

    var speedPreviewText: String {
        guard speedLevel != 0 else { return "" }
        if speedLevel == 3 {
            return "Kept at \(FeedbackPreferences.percent(workSpeedScale))%"
        }
        return "Now reading at \(FeedbackPreferences.percent(workSpeedScale))% (was \(FeedbackPreferences.percent(baseSpeedScale))%). Adjust again, or choose Just right to keep it."
    }

    func selectTextSizeLevel(_ level: Int) {
        textSizeLevel = level
        if level != 3 {
            workTextSize = FeedbackPreferences.targetTextSize(current: workTextSize, level: level)
            textSizeAdjustments += 1
        }
    }

    func selectSpeedLevel(_ level: Int) {
        speedLevel = level
        if level != 3 {
            workSpeedScale = FeedbackPreferences.targetSpeedScale(current: workSpeedScale, level: level)
            speedAdjustments += 1
        }
        Task {
            await tts.speak(
                "This is how fast I will read to you. Tell me if it feels right.",
                rateScale: workSpeedScale
            )
        }
    }

    var canProceed: Bool {
        switch step {
        case .rating: return rating > 0
        default: return true
        }
    }

    var isLastStep: Bool { step == .instructorFeedback }

    func goToNextStep() {
        guard canProceed else { return }
        guard let next = FeedbackStep(rawValue: step.rawValue + 1) else {
            Task { await submit() }
            return
        }
        step = next
    }

    func goToPreviousStep() {
        guard let previous = FeedbackStep(rawValue: step.rawValue - 1) else { return }
        step = previous
    }

    private func submit() async {
        guard let token = sessionStore.sessionToken else { return }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }

        let textSizeAnswer = FeedbackPreferences.describeTextSize(
            level: textSizeLevel, base: baseTextSize, work: workTextSize, adjustments: textSizeAdjustments
        )
        let speedAnswer = FeedbackPreferences.describeSpeed(
            level: speedLevel, base: baseSpeedScale, work: workSpeedScale, adjustments: speedAdjustments
        )
        let blob = FeedbackPreferences.feedbackBlob(
            rating: rating,
            textSizeAnswer: textSizeAnswer,
            speedAnswer: speedAnswer,
            materialFeedback: materialFeedbackText,
            instructorFeedback: instructorFeedbackText
        )

        do {
            let accepted = try await feedbackRepository.submit(
                sessionToken: token, materialId: material.id, rating: rating, feedbackText: blob
            )
            guard accepted else {
                errorMessage = "Feedback wasn't accepted — you may not have access to this material."
                return
            }
            if speedLevel != 0 && workSpeedScale != baseSpeedScale {
                try? await voiceSettingsRepository.save(sessionToken: token, ttsVoice: nil, speechRateScale: workSpeedScale)
            }
            didSubmit = true
        } catch let error as SupabaseError {
            if case .sessionExpired = error {
                sessionStore.forceLogout()
            } else {
                errorMessage = error.localizedDescription
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
