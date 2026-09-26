import Foundation

/// What a spoken command on Home/Materials/Profile asks for. Pure data so
/// the phrase matching is unit-testable apart from the speech engine.
enum TabVoiceIntent: Equatable {
    case help
    case repeatLast
    case stopSpeaking
    case logout
    case readScreen
    case goTo(MainTab)
    case alreadyHere

    // Home
    case useRecommendedFont
    case useDefaultFont
    case openLatest

    // Materials
    case readTitles
    case readFeatured
    case openFeatured
    case openFeedback
    case openIndex(Int)
    case openTitle(String)
    case confirmYes
    case confirmNo
    case confirmUnclear

    // Profile
    case readProfile
    case setTTS(Bool?)
    case setSTT(Bool?)
    case changeVoice
    case retakeAssessment

    case unknown
}

/// Ports of HomeFragment / MaterialsFragment / ProfileFragment
/// `handleCommand()`: same phrases, same order of checks (order matters,
/// e.g. Profile checks "home" before "profile").
enum TabVoiceCommands {
    /// Lowercase, keep `[a-z0-9 ]`, collapse spaces — Android `normalize()`.
    static func normalize(_ text: String) -> String {
        let lowered = text.lowercased()
        let mapped = lowered.unicodeScalars.map { scalar -> Character in
            let c = Character(scalar)
            return (c.isASCII && (c.isLetter || c.isNumber)) ? c : " "
        }
        return String(mapped).split(separator: " ").joined(separator: " ")
    }

    /// Answers acted on straight from a partial result (isQuickCommand).
    static func isQuickCommand(_ normalized: String) -> Bool {
        ["home", "materials", "profile", "logout", "stop", "repeat", "help"].contains(normalized)
    }

    static func interpret(_ spoken: String, on tab: MainTab, confirmingOpen: Bool = false) -> TabVoiceIntent {
        let cmd = normalize(spoken)
        guard !cmd.isEmpty else { return .unknown }
        switch tab {
        case .home: return home(cmd)
        case .materials: return materials(cmd, confirmingOpen: confirmingOpen)
        case .profile: return profile(cmd)
        }
    }

    private static func isHelp(_ cmd: String) -> Bool {
        cmd.contains("help") || cmd.contains("command") || cmd.contains("what can")
    }

    private static func isRepeat(_ cmd: String) -> Bool {
        cmd == "repeat" || cmd.contains("repeat that") || cmd.contains("say again")
    }

    private static func isLogout(_ cmd: String) -> Bool {
        cmd.contains("logout") || cmd.contains("log out") || cmd.contains("sign out")
    }

    private static func isReadScreen(_ cmd: String) -> Bool {
        cmd.contains("read screen") || cmd.contains("describe")
            || cmd.contains("read page") || cmd.contains("what is on")
    }

    private static func home(_ cmd: String) -> TabVoiceIntent {
        if isHelp(cmd) { return .help }
        if isRepeat(cmd) { return .repeatLast }
        if cmd == "stop" || cmd.contains("stop speaking") { return .stopSpeaking }
        if isLogout(cmd) { return .logout }
        if isReadScreen(cmd) { return .readScreen }
        if cmd.contains("recommended") || cmd.contains("bigger text") || cmd.contains("large text") {
            return .useRecommendedFont
        }
        if cmd.contains("default") || cmd.contains("normal text") { return .useDefaultFont }
        if cmd.contains("open latest") || cmd.contains("open material") || cmd.contains("open lesson")
            || cmd.contains("learning material") || cmd == "lesson" || cmd == "open" {
            return .openLatest
        }
        if cmd.contains("materials") || cmd.contains("material list") { return .goTo(.materials) }
        if cmd.contains("profile") || cmd.contains("account") { return .goTo(.profile) }
        if cmd == "home" || cmd.contains("go home") { return .alreadyHere }
        return .unknown
    }

    private static func materials(_ cmd: String, confirmingOpen: Bool) -> TabVoiceIntent {
        if confirmingOpen {
            if cmd == "yes" || cmd == "yeah" || cmd == "yep" || cmd == "yup" || cmd == "correct"
                || cmd == "oo" || cmd.contains("yes") || cmd.contains("open it") {
                return .confirmYes
            }
            if cmd == "no" || cmd == "nope" || cmd == "nah" || cmd == "cancel" || cmd == "hindi"
                || cmd.contains("not that") || cmd.hasPrefix("no ") {
                return .confirmNo
            }
            return .confirmUnclear
        }
        if isHelp(cmd) { return .help }
        if isRepeat(cmd) { return .repeatLast }
        if cmd == "stop" || cmd == "cancel" || cmd.contains("stop speaking") { return .stopSpeaking }
        if isReadScreen(cmd) { return .readScreen }
        if cmd.contains("read title") || cmd.contains("list material") || cmd.contains("read all")
            || cmd.contains("what material") || cmd.contains("show material") {
            return .readTitles
        }
        if cmd.contains("read featured") || cmd.contains("featured title") || cmd.contains("latest title") {
            return .readFeatured
        }
        if cmd.contains("open featured") || cmd.contains("open latest") || cmd == "featured" || cmd == "latest" {
            return .openFeatured
        }
        if cmd == "home" || cmd.contains("go home") || cmd.contains("open home") { return .goTo(.home) }
        if cmd == "profile" || cmd.contains("open profile") || cmd.contains("go profile") { return .goTo(.profile) }
        if cmd == "materials" || cmd.contains("materials screen") { return .alreadyHere }
        if cmd.contains("feedback") || cmd.contains("puna") || cmd.contains("komento") { return .openFeedback }
        if cmd.contains("first") || cmd == "1" { return .openIndex(0) }
        if cmd.contains("second") || cmd == "2" { return .openIndex(1) }
        if cmd.contains("third") || cmd == "3" { return .openIndex(2) }
        if cmd.hasPrefix("open") || cmd.contains("open ") {
            let query = cmd.split(separator: " ")
                .filter { !["open", "the", "material", "lesson", "learning", "file"].contains($0) }
                .joined(separator: " ")
            return .openTitle(query)
        }
        // A bare title ("photosynthesis") is matched by the caller against
        // the list and confirmed before opening.
        return .openTitle(cmd)
    }

    private static func profile(_ cmd: String) -> TabVoiceIntent {
        if isHelp(cmd) { return .help }
        if isRepeat(cmd) { return .repeatLast }
        if cmd == "stop" || cmd.contains("stop speaking") { return .stopSpeaking }
        if cmd.contains("read profile") || cmd.contains("read my profile")
            || cmd.contains("read details") || cmd.contains("describe") {
            return .readProfile
        }
        if cmd.contains("text to speech") { return .setTTS(onOff(cmd)) }
        if cmd.contains("speech to text") { return .setSTT(onOff(cmd)) }
        if cmd.contains("change voice") || cmd.contains("assistant voice") || cmd.contains("voice settings")
            || cmd.contains("change the voice") || cmd.contains("choose voice") {
            return .changeVoice
        }
        if isLogout(cmd) { return .logout }
        if cmd.contains("home") { return .goTo(.home) }
        if cmd.contains("material") { return .goTo(.materials) }
        if cmd.contains("profile") { return .alreadyHere }
        if cmd.contains("retake") || cmd.contains("assessment") || cmd.contains("test again")
            || cmd.contains("take again") {
            return .retakeAssessment
        }
        return .unknown
    }

    private static func onOff(_ cmd: String) -> Bool? {
        if cmd.contains("turn on") || cmd.contains("enable") { return true }
        if cmd.contains("turn off") || cmd.contains("disable") { return false }
        return nil
    }

    // MARK: - Title matching (MaterialsFragment.findBestMatch/matchScore)

    static func bestMatch(for query: String, in materials: [LearningMaterial]) -> LearningMaterial? {
        let target = normalize(query)
        guard !target.isEmpty else { return nil }
        var best: LearningMaterial?
        var bestScore = 0
        for material in materials {
            let score = matchScore(target, normalize(material.title))
            if score > bestScore {
                bestScore = score
                best = material
            }
        }
        return bestScore >= 1 ? best : nil
    }

    static func matchScore(_ target: String, _ title: String) -> Int {
        var score = 0
        for word in target.split(separator: " ") where word.count >= 3 && title.contains(word) {
            score += 2
        }
        if title.contains(target) { score += 5 }
        if title.hasPrefix(target) { score += 3 }
        return score
    }
}
