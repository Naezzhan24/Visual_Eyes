import Foundation
import Observation

/// Where a tab voice command asks MainTabView to go.
enum TabVoiceNavigation {
    case switchTab(MainTab)
    case openMaterial(LearningMaterial)
    case feedback(LearningMaterial)
    case voiceSettings
    case retakeAssessment
    case logout
}

/// The always-listening voice assistant on Home/Materials/Profile — the
/// iOS side of each fragment's speak → listen → handleCommand loop, plus
/// MainActivity's triple-tap repeat and tab announcements. One controller
/// is shared by the three tabs (Android gave each fragment its own engine;
/// here the tab switch just changes which command set applies).
///
/// Honors the Profile TTS/STT switches like the fragments do: with TTS off
/// it stays silent, with STT off it stops listening.
@MainActor
@Observable
final class TabVoiceController {
    private(set) var status = "Idle"
    private(set) var recognized = "Waiting for speech..."
    private(set) var isListening = false
    private(set) var materials: [LearningMaterial] = []

    /// Set by MainTabView to carry out navigation.
    var navigate: ((TabVoiceNavigation) -> Void)?

    private(set) var activeTab: MainTab = .home
    private var isActive = false
    private var lastMessage = "Welcome to your home screen."
    private var pendingMaterial: LearningMaterial?
    private var flow = 0
    private var consecutiveErrors = 0
    private var didAskForMic = false

    private let tts: CloudTTSService
    private let cascade: SpeechCascadeSession
    private let micPermission: MicPermissionService
    private let materialsRepository: MaterialsRepository
    private let sessionStore: SessionStore

    init(
        tts: CloudTTSService = .shared,
        cascade: SpeechCascadeSession? = nil,
        micPermission: MicPermissionService = .shared,
        materialsRepository: MaterialsRepository = MaterialsRepository(),
        sessionStore: SessionStore = .shared
    ) {
        self.tts = tts
        self.cascade = cascade ?? SpeechCascadeSession()
        self.micPermission = micPermission
        self.materialsRepository = materialsRepository
        self.sessionStore = sessionStore
    }

    // MARK: - Lifecycle

    /// The tabs came on screen: first time, or back from a pushed screen.
    func activate(tab: MainTab, greet: Bool) {
        isActive = true
        activeTab = tab
        Task { await refreshMaterials() }
        if greet {
            Task { await greetCurrentTab() }
        } else {
            scheduleListening(after: .milliseconds(600))
        }
    }

    /// A pushed screen (reader, Help, …) covered the tabs, or they left.
    func deactivate() {
        isActive = false
        flow += 1
        stopListening()
        tts.stop()
        status = "Paused"
    }

    func tabChanged(to tab: MainTab) {
        activeTab = tab
        pendingMaterial = nil
        guard isActive else { return }
        Task { await greetCurrentTab() }
    }

    /// Tapping the tab you're already on (MainActivity.onNavTapped).
    func sameTabTapped() {
        speak("You are currently on the \(activeTab.title.lowercased()) screen.", listenAfter: true)
    }

    /// Triple-tap anywhere (TabFragment.repeatLastInstruction).
    func repeatLastInstruction() {
        speak(lastMessage, listenAfter: true)
    }

    func refreshMaterials() async {
        guard let token = sessionStore.sessionToken else { return }
        if let list = try? await materialsRepository.fetchMaterials(sessionToken: token) {
            materials = list
        }
    }

    private func greetCurrentTab() async {
        await ensureMicPermission()
        guard isActive else { return }
        switch activeTab {
        case .home:
            speak("Welcome to your home screen. Say help for available commands.", listenAfter: true)
        case .materials:
            speak("Materials screen. Say help for available commands.", listenAfter: true)
        case .profile:
            speak("Profile screen. Your registered details and visual impairment level are displayed. "
                  + "Say a command or say help for available options.", listenAfter: true)
        }
    }

    /// checkMicPermission + requestMicPermissionWithRationale, once per run.
    private func ensureMicPermission() async {
        micPermission.refresh()
        guard !micPermission.isAuthorized, !didAskForMic, VoicePreferences.isSttEnabled else { return }
        didAskForMic = true
        status = "Requesting microphone access..."
        flow += 1
        await tts.speak("I need access to your microphone for voice commands. "
                        + "A system permission dialog will appear next — please allow it.")
        let granted = await micPermission.requestIfNeeded()
        status = granted ? "Microphone enabled." : "Microphone permission denied."
    }

    // MARK: - Speaking and listening

    /// speak(text, listenAfter): remembers the line for "repeat"/triple-tap,
    /// stops listening while talking, and listens again 400ms after.
    func speak(_ text: String, listenAfter: Bool, then action: (() -> Void)? = nil) {
        lastMessage = text
        stopListening()
        flow += 1
        let token = flow
        Task {
            await tts.speak(text)
            guard token == flow else { return }
            action?()
            if listenAfter { scheduleListening(after: .milliseconds(400)) }
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
        guard isActive, !isListening else { return }
        guard VoicePreferences.isSttEnabled else {
            status = "Speech-to-Text is OFF."
            recognized = "Speech-to-Text is disabled."
            return
        }
        micPermission.refresh()
        guard micPermission.isAuthorized else {
            status = "Microphone permission needed for voice commands."
            return
        }

        flow += 1
        let token = flow
        isListening = true
        status = "Listening..."
        recognized = "Waiting for speech..."

        cascade.listen(contextualStrings: contextualStrings, cloudFallbackOnSilence: false) { [weak self] event in
            guard let self, token == self.flow else { return }
            switch event {
            case .partial(let text):
                self.recognized = "Hearing: \(text)"
                if TabVoiceCommands.isQuickCommand(TabVoiceCommands.normalize(text)) {
                    self.stopListening()
                    self.handle(text)
                }
            case .final(let text):
                self.isListening = false
                self.consecutiveErrors = 0
                self.handle(text)
            case .timedOut:
                // Nobody spoke — just keep listening (onExhausted → 1s).
                self.isListening = false
                self.consecutiveErrors = 0
                self.scheduleListening(after: .seconds(1))
            case .error:
                self.isListening = false
                self.consecutiveErrors += 1
                self.status = "Voice error."
                // Back off if the recognizer keeps failing rather than
                // spinning on it.
                self.scheduleListening(after: self.consecutiveErrors > 3 ? .seconds(5) : .seconds(1))
            }
        }
    }

    private func stopListening() {
        cascade.stop()
        isListening = false
    }

    private var contextualStrings: [String] {
        switch activeTab {
        case .home:
            return ["help", "read screen", "open latest material", "materials", "profile",
                    "recommended font", "default font", "repeat", "stop", "logout"]
        case .materials:
            return ["help", "read screen", "read titles", "open featured", "open first", "open second",
                    "open third", "home", "profile", "feedback", "repeat", "stop", "yes", "no"]
                + materials.prefix(20).map(\.title)
        case .profile:
            return ["help", "read profile", "turn on text to speech", "turn off text to speech",
                    "turn on speech to text", "turn off speech to text", "change voice",
                    "retake assessment", "home", "materials", "logout"]
        }
    }

    // MARK: - Commands

    func handle(_ spoken: String) {
        let trimmed = spoken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            scheduleListening(after: .milliseconds(400))
            return
        }
        recognized = "Recognized: \(trimmed)"
        status = "Command: \(trimmed)"

        let intent = TabVoiceCommands.interpret(trimmed, on: activeTab, confirmingOpen: pendingMaterial != nil)
        perform(intent)
    }

    private func perform(_ intent: TabVoiceIntent) {
        switch intent {
        case .help:
            speak(helpText, listenAfter: true)
        case .repeatLast:
            speak(lastMessage, listenAfter: true)
        case .stopSpeaking:
            flow += 1
            tts.stop()
            status = "Stopped."
            scheduleListening(after: .milliseconds(600))
        case .logout:
            speak("Logging out.", listenAfter: false) { [weak self] in self?.navigate?(.logout) }
        case .readScreen:
            speak(readScreenText, listenAfter: true)
        case .goTo(let tab):
            speak("Opening \(tab.title.lowercased()).", listenAfter: false) { [weak self] in
                self?.navigate?(.switchTab(tab))
            }
        case .alreadyHere:
            speak("You are already on the \(activeTab.title.lowercased()) screen.", listenAfter: true)

        case .useRecommendedFont:
            UserDefaults.standard.set(true, forKey: FontSizePreferences.useRecommendedKey)
            speak("Recommended text size applied.", listenAfter: true)
        case .useDefaultFont:
            UserDefaults.standard.set(false, forKey: FontSizePreferences.useRecommendedKey)
            speak("Default text size applied.", listenAfter: true)
        case .openLatest:
            guard let latest = materials.first else {
                speak("No learning material available yet.", listenAfter: true)
                return
            }
            speak("Opening latest material.", listenAfter: false) { [weak self] in
                self?.navigate?(.openMaterial(latest))
            }

        case .readTitles:
            guard !materials.isEmpty else {
                speak("There are no learning materials available right now.", listenAfter: true)
                return
            }
            var text = "Here are your learning materials. "
            for (index, material) in materials.enumerated() {
                text += "Material \(index + 1), \(material.title). "
            }
            text += "Say open followed by the title, or say open first, second, or third."
            speak(text, listenAfter: true)
        case .readFeatured:
            guard let featured = featuredMaterial else {
                speak("There is no featured learning material right now.", listenAfter: true)
                return
            }
            speak("Your featured learning material is \(featured.title). Say open featured to open it.", listenAfter: true)
        case .openFeatured:
            guard let featured = featuredMaterial else {
                speak("There is no learning material available right now.", listenAfter: true)
                return
            }
            confirmOpen(featured)
        case .openFeedback:
            guard let target = lastOpenedMaterial else {
                speak("Please open a material first before leaving feedback.", listenAfter: true)
                return
            }
            speak("Opening feedback for \(target.title).", listenAfter: false) { [weak self] in
                self?.navigate?(.feedback(target))
            }
        case .openIndex(let index):
            guard !materials.isEmpty else {
                speak("There are no materials available right now.", listenAfter: true)
                return
            }
            guard index < materials.count else {
                speak("There is no material number \(index + 1).", listenAfter: true)
                return
            }
            confirmOpen(materials[index])
        case .openTitle(let query):
            guard !materials.isEmpty else {
                speak("There are no learning materials available right now.", listenAfter: true)
                return
            }
            guard !query.isEmpty else {
                speak("Please say open followed by the material title.", listenAfter: true)
                return
            }
            if let match = TabVoiceCommands.bestMatch(for: query, in: materials) {
                confirmOpen(match)
            } else {
                speak("Command not recognized. Say help for available commands.", listenAfter: true)
            }
        case .confirmYes:
            guard let material = pendingMaterial else { return }
            pendingMaterial = nil
            speak("Opening \(material.title).", listenAfter: false) { [weak self] in
                self?.navigate?(.openMaterial(material))
            }
        case .confirmNo:
            pendingMaterial = nil
            speak("Okay, cancelled. Say a material title or say read titles to hear what is available.", listenAfter: true)
        case .confirmUnclear:
            speak("Please say yes to open or no to cancel.", listenAfter: true)

        case .readProfile:
            speak(profileText, listenAfter: true)
        case .setTTS(let on):
            setVoicePreference(VoicePreferences.ttsKey, to: on, name: "Text to speech")
        case .setSTT(let on):
            setVoicePreference(VoicePreferences.sttKey, to: on, name: "Speech to text")
        case .changeVoice:
            speak("Opening assistant voice.", listenAfter: false) { [weak self] in
                self?.navigate?(.voiceSettings)
            }
        case .retakeAssessment:
            speak("Opening retake assessment.", listenAfter: false) { [weak self] in
                self?.navigate?(.retakeAssessment)
            }

        case .unknown:
            speak("Command not recognized. Say help for available commands.", listenAfter: true)
        }
    }

    private func confirmOpen(_ material: LearningMaterial) {
        pendingMaterial = material
        speak("Did you mean \(material.title)? Say yes to open or no to cancel.", listenAfter: true)
    }

    /// Flips a Profile switch by voice. ProfileView's @AppStorage picks
    /// the change up and announces it through `voicePreferenceChanged`,
    /// the same path a tap takes.
    private func setVoicePreference(_ key: String, to on: Bool?, name: String) {
        guard let on else {
            speak("Say turn on or turn off \(name.lowercased()).", listenAfter: true)
            return
        }
        let current = UserDefaults.standard.object(forKey: key) as? Bool ?? true
        if current == on {
            speak("\(name) is already \(on ? "enabled" : "disabled").", listenAfter: true)
        } else {
            UserDefaults.standard.set(on, forKey: key)
        }
    }

    /// Called by ProfileView when either switch changes (tap or voice),
    /// matching ProfileFragment.setupSwitches().
    func voicePreferenceChanged(tts: Bool? = nil, stt: Bool? = nil) {
        if let tts {
            if tts {
                speak("Text to speech enabled.", listenAfter: true)
            } else {
                flow += 1
                self.tts.stop()
                scheduleListening(after: .milliseconds(400))
            }
        }
        if let stt {
            if stt {
                speak("Speech to text enabled.", listenAfter: true)
            } else {
                stopListening()
                status = "Speech-to-Text is OFF."
                recognized = "Speech-to-Text is disabled."
                // CloudTTSService still speaks this: TTS is its own switch.
                speak("Speech to text disabled.", listenAfter: false)
            }
        }
    }

    // MARK: - Spoken content

    private var lastOpenedMaterial: LearningMaterial? {
        guard let schoolId = sessionStore.student?.schoolId,
              let id = MaterialReadTracker.lastOpenedId(studentSchoolId: schoolId) else { return nil }
        return materials.first { $0.id == id }
    }

    private var featuredMaterial: LearningMaterial? {
        lastOpenedMaterial ?? materials.first
    }

    private var helpText: String {
        switch activeTab {
        case .home:
            return "Available commands. Say read screen to hear this page. "
                + "Say open latest material to open the newest lesson. "
                + "Say materials to view all learning materials. "
                + "Say profile to open your profile. "
                + "Say recommended font to use the recommended text size. "
                + "Say default font to use the default text size. "
                + "Say repeat to hear my last message. "
                + "Say stop to stop speaking. "
                + "Say logout to sign out."
        case .materials:
            return "Available commands. Say read screen to hear this page. "
                + "Say read titles to hear all material titles. "
                + "Say open followed by the material title to open a lesson. "
                + "Say open first, open second, or open third to open by number. "
                + "Say open featured to open the featured material. "
                + "Say home to go to the home screen. "
                + "Say profile to open your profile. "
                + "Say repeat to hear my last message. "
                + "Say stop to stop speaking."
        case .profile:
            return "Available commands. Say home to go to the home screen. "
                + "Say materials to view all learning materials. "
                + "Say profile to stay on this screen. "
                + "Say retake assessment to redo the visual test. "
                + "Say change voice to choose a different assistant voice. "
                + "Say log out to sign out. "
                + "Say turn on or turn off text to speech. "
                + "Say turn on or turn off speech to text. "
                + "Say read profile to hear your details."
        }
    }

    private var readScreenText: String {
        switch activeTab {
        case .home:
            let name = sessionStore.student?.firstName ?? "Student"
            let latest = materials.first?.title ?? "No learning material yet"
            return "Welcome Back, \(name)!. Ready to continue your accessible learning journey today?. "
                + "Latest learning material: \(latest). Say help for commands."
        case .materials:
            guard !materials.isEmpty else {
                return "You are on the materials screen. There are no learning materials available right now."
            }
            let featured = featuredMaterial?.title ?? ""
            return "You are on the materials screen. You have \(materials.count) learning material"
                + (materials.count > 1 ? "s" : "")
                + ". Featured material is \(featured). Say read titles to hear all materials, "
                + "or say open followed by the title to open a lesson."
        case .profile:
            return profileText
        }
    }

    private var profileText: String {
        guard let s = sessionStore.student else { return "Your profile details are not available yet." }
        let size = s.recommendedTextSize.map { "\(Int($0))" } ?? "not set"
        return "Your profile details. Name: \(s.firstName) \(s.lastName). "
            + "Email: \(s.email ?? "No Email"). "
            + "Student Number: \(s.schoolId). "
            + "Age: \(s.age.map(String.init) ?? "not available"). "
            + "Year Level: \(s.yearLevel ?? "not available"). "
            + "Section: \(s.section ?? "not available"). "
            + "Recommended Text Size: \(size). "
            + "Visual impairment level: \(s.impairmentLevel ?? "not assessed")."
    }
}
