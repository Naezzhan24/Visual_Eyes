import Foundation
import Speech
import AVFoundation
import Observation

/// Tracks mic/speech-recognition permission state and re-checks it on
/// every app-foreground, not just at first request.
///
/// This directly targets a real bug from the Android app's history: a
/// student who denied the mic prompt, later granted it from Settings, and
/// returned to the app wasn't noticed until the next cold launch on
/// Login/Register, because those screens had no `onResume()` hook to
/// re-check. Call `refresh()` from a `scenePhase == .active` observer at
/// the app root (see `VisualEyesApp`) so every screen sees an up-to-date
/// value without each one re-implementing the check itself.
@MainActor
@Observable
final class MicPermissionService {
    static let shared = MicPermissionService()

    private(set) var isAuthorized: Bool = false

    private init() {
        refresh()
    }

    func refresh() {
        let speechStatus = SFSpeechRecognizer.authorizationStatus()
        let micGranted: Bool
        if #available(iOS 17.0, *) {
            micGranted = AVAudioApplication.shared.recordPermission == .granted
        } else {
            micGranted = AVAudioSession.sharedInstance().recordPermission == .granted
        }
        isAuthorized = speechStatus == .authorized && micGranted
    }

    /// Prompts for both permissions only if not already granted. Safe to
    /// call every time a voice interaction is about to start.
    @discardableResult
    func requestIfNeeded() async -> Bool {
        refresh()
        if isAuthorized { return true }
        let granted = await SpeechRecognitionService.requestAuthorization()
        isAuthorized = granted
        return granted
    }
}
