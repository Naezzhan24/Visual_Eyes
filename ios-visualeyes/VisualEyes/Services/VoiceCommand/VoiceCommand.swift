import Foundation

/// The app-wide voice command grammar — English + Tagalog synonyms for
/// every command a voice-driven screen recognizes, centralized in one
/// table rather than scattered inline per screen (which is how Android
/// has it today, split across `AccessibleMaterialActivity`/`HomeFragment`/
/// `MaterialsFragment`/`ProfileFragment`'s individual `handleCommand()`
/// methods). Having one table here means extending the grammar only
/// needs to happen in one place.
enum VoiceCommand: CaseIterable {
    case next
    case previous
    case repeatCommand
    case faster
    case slower
    case restart
    case increaseText
    case decreaseText
    case yes
    case no
    case instruction
    case feedback
    case back
    case stop

    /// Substring phrases recognized for this command, English first then
    /// Tagalog. Order matters where phrases overlap (see
    /// `VoiceCommandMatcher.match` — "feedback" is checked before "back"
    /// since "feedback" contains "back" as a substring, mirroring the
    /// explicit ordering note in AccessibleMaterialActivity.java).
    var phrases: [String] {
        switch self {
        case .next: return ["next", "continue", "susunod"]
        case .previous: return ["previous", "back up", "nakaraan"]
        case .repeatCommand: return ["repeat", "say again", "ulit", "ulitin"]
        case .faster: return ["faster", "speed up", "bilisan", "bilis"]
        case .slower: return ["slower", "slow down", "bagalan", "bagal"]
        case .restart: return ["restart", "from beginning", "start over", "ulit mula sa simula"]
        case .increaseText: return ["increase text", "bigger text", "lakihan"]
        case .decreaseText: return ["decrease text", "smaller text", "liitan"]
        case .yes: return ["yes", "yeah", "yep", "yup", "yas", "oo", "opo"]
        case .no: return ["no", "nope", "nah", "hindi", "hinde"]
        case .instruction: return ["instruction", "help", "tulong"]
        case .feedback: return ["feedback", "leave feedback", "puna"]
        case .back: return ["back", "go back", "balik"]
        case .stop: return ["stop", "cancel", "tigil"]
        }
    }
}

/// Matches a raw STT transcript against the `VoiceCommand` grammar.
enum VoiceCommandMatcher {
    /// Returns the first matching command, checking `.feedback` before
    /// `.back` regardless of `VoiceCommand.allCases` order, since
    /// "feedback" contains "back" as a substring (matches Android's
    /// explicit ordering note in `AccessibleMaterialActivity.handleCommand()`).
    static func match(_ raw: String) -> VoiceCommand? {
        let normalized = YesNoMatcher.normalize(raw)
        guard !normalized.isEmpty else { return nil }

        let orderedCommands: [VoiceCommand] = [.feedback, .back] +
            VoiceCommand.allCases.filter { $0 != .feedback && $0 != .back }

        for command in orderedCommands {
            if command.phrases.contains(where: { normalized.contains($0) }) {
                return command
            }
        }
        return nil
    }
}
