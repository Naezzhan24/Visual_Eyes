import AVFoundation

/// Coordinates the shared `AVAudioSession` between TTS playback and STT
/// recording so they don't fight each other — the iOS analogue of
/// `AudioFocusHelper.java`'s role (transient-exclusive focus while
/// recording, ducking-not-silencing other audio during playback).
enum AudioSessionCoordinator {
    static func prepareForPlayback() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try session.setActive(true, options: .notifyOthersOnDeactivation)
    }

    static func prepareForRecording() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: [])
        try session.setActive(true, options: .notifyOthersOnDeactivation)
    }

    static func deactivate() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
