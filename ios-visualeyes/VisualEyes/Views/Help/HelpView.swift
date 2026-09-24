import SwiftUI

/// Stand-in for HelpActivity.java. Content copied verbatim from
/// `activity_help.xml`'s section text — this is user-facing
/// documentation, not something to paraphrase.
struct HelpView: View {
    private static let sections: [(heading: String, body: String)] = [
        ("Getting Around", "Use the three tabs at the bottom of the screen — Home, Materials, and Profile — to move between the main areas of the app. On the Home screen, tap the menu icon at the top left to open the list of materials sent to you."),
        ("Adjusting Text Size", "On the Home screen, use the text size slider to make the main text bigger or smaller. You can also say \"recommended font\" for a size based on your assessment, or \"default font\" to return to the standard size."),
        ("Using Your Voice", "The app starts listening on its own right after it finishes speaking — you don't need to press a button. On the Login and Register screens, wait for the short beep before speaking. On Home, Materials, and Profile, it listens continuously in the background, so you can speak anytime without waiting for a cue. If it doesn't understand you, it will ask you to say it again."),
        ("Logging In by Voice", "When you open the Login screen, the app automatically asks whether you're a new or existing user — say \"new\" to go to registration, or \"existing\" to log in. If you've logged in on this device before, it already remembers your email and will go straight to asking for your password. If it's your first time, it will guide you through saying your email piece by piece — the part before the at sign, then the provider like gmail or yahoo, then the ending like com or edu — confirming each part before moving on."),
        ("Voice Commands — Home Screen", "\"Help\" — hear the list of commands. \"Read screen\" — hear this page read aloud. \"Open latest material\" — open your newest lesson. \"Materials\" — go to the Materials screen. \"Profile\" — open your profile. \"Read announcement\" — hear the instructor's message. \"Recommended font\" / \"default font\" — change text size. \"Repeat\" — hear the last message again. \"Stop\" — stop the app from speaking. \"Logout\" — sign out."),
        ("Voice Commands — Materials Screen", "\"Read screen\" — hear this page. \"Read titles\" — hear every material title. \"Open\" followed by a title — open that lesson. \"Open first / second / third\" — open a lesson by its position in the list. \"Open featured\" — open the featured material. \"Home\" or \"profile\" — switch screens. \"Repeat\" — hear the last message again. \"Stop\" — stop the app from speaking."),
        ("Voice Commands — Profile Screen", "\"Read profile\" — hear your details read aloud. \"Turn on/off text to speech\" — control whether the app talks. \"Turn on/off speech to text\" — control whether the app listens. \"Retake assessment\" — take the accessibility test again. \"Logout\" — sign out. \"Home\" or \"materials\" — switch screens."),
        ("Registering by Voice", "On the Register screen, press Voice Registration and the app will ask for each field one at a time. After you answer, it reads back what it heard — letter by letter for names — say \"yes\" to confirm or \"no\" to say it again. Say \"skip\" to leave your middle name blank. You can always switch to typing by tapping any field directly."),
        ("Repeating, Stopping, and Zooming", "Tap anywhere on the screen three times quickly to repeat the last spoken instruction — useful if you missed it or just walked back to the app. You can also just say \"repeat\" or \"stop\" at any time. To zoom in on a screen, pinch with two fingers, the same way you would zoom a photo."),
        ("Accessibility Settings", "Open your Profile screen and look under Accessibility Options. There you can switch Text-to-Speech and Speech-to-Text on or off independently, depending on how you prefer to use the app.")
    ]

    @State private var isSpeaking = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Button {
                    Task { await readAllAloud() }
                } label: {
                    Label(isSpeaking ? "Reading…" : "Read This Guide Aloud", systemImage: "speaker.wave.2.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isSpeaking)

                ForEach(Self.sections, id: \.heading) { section in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(section.heading).font(.headline)
                        Text(section.body).font(.body).foregroundStyle(.secondary)
                    }
                }
            }
            .padding()
        }
        .navigationTitle("Help & User Guide")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func readAllAloud() async {
        isSpeaking = true
        let fullText = Self.sections.map { "\($0.heading). \($0.body)" }.joined(separator: " ")
        await CloudTTSService.shared.speak(fullText)
        isSpeaking = false
    }
}

#Preview {
    NavigationStack { HelpView() }
}
