import Foundation

/// Tier 3 of the STT cascade — offline recognition via Vosk, matching
/// Android's `HybridSpeechManager.java` (bundled
/// `vosk-model-en-us-0.22-lgraph`, ~205MB).
///
/// **NOT YET FUNCTIONAL.** Wiring this up needs hands-on setup on the Mac
/// that this code can't do from here:
///   1. Add a Vosk iOS package to the Xcode project (check `vosk-api`'s
///      current iOS distribution method at
///      https://github.com/alphacep/vosk-api — it may be an XCFramework,
///      SPM package, or CocoaPod depending on what's current when you
///      set this up; this wasn't verified against the live repo).
///   2. Add the actual ~205MB `vosk-model-en-us-0.22-lgraph` model folder
///      into the app bundle. The same model already exists on the
///      Android side at `app/src/main/assets/vosk-model-en-us-0.22-lgraph`
///      — copy it across via AirDrop/external drive/cloud storage rather
///      than through git (it's intentionally excluded from this repo via
///      `.gitignore` to avoid permanently bloating the repo with a
///      205MB binary).
///   3. Replace this stub's `recognize(...)` body with real calls into
///      whatever the Vosk package's Swift/Obj-C API surface looks like.
///
/// Until then, `isAvailable` is `false` and `SpeechCascadeSession` never
/// calls into this tier — a voice interaction that exhausts Tier 1 and
/// Tier 2 (e.g. no internet AND the on-device recognizer failed) simply
/// times out with no offline fallback yet, same as before this tier
/// existed in the plan. This mirrors the honest state of things rather
/// than pretending offline recognition works when it doesn't.
final class VoskSTTService {
    static let shared = VoskSTTService()

    private init() {}

    /// Flip to `true` once the real package + model are wired in.
    var isAvailable: Bool { false }

    enum VoskError: Error, LocalizedError {
        case notYetImplemented
        var errorDescription: String? {
            "Offline recognition isn't set up on this build yet."
        }
    }

    /// Batch-recognizes already-captured PCM — mirrors
    /// `HybridSpeechManager.reuseAudioForVosk()`'s "reuse Tier 2's audio,
    /// don't reopen the mic" behavior once this is implemented.
    func recognize(pcm: Data, sampleRate: Double) async throws -> String {
        throw VoskError.notYetImplemented
    }
}
