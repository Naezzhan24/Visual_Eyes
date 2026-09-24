import SwiftUI

/// Stand-in for PrivacyPolicyActivity.java. Content copied verbatim from
/// `activity_privacy_policy.xml` — this is a legal/policy document, not
/// something to paraphrase or shorten.
struct PrivacyPolicyView: View {
    private static let lastUpdated = "Last updated: August 2026"

    private static let sections: [(heading: String, body: String)] = [
        ("Information We Collect", "When you register for VisualED, we collect your first, middle, and last name, age, year level, school ID number, email address, and password. During your accessibility assessment, we also record your visual impairment level and your recommended text size, so the app can adjust reading materials for you. If you send feedback about a learning material, we collect the rating and message you write."),
        ("How We Use Your Information", "Your information is used to verify your identity, confirm your enrollment with your school, personalize text size and accessibility settings, and let your school administrator review and approve your account. We do not sell your personal information, and we do not use it for advertising."),
        ("Who Can See Your Information", "Your school administrator can see your registration details and assessment results in order to approve your account and send you appropriate learning materials. Your data is stored on our secure cloud database provider (Supabase) and is only accessible to authorized school staff and the app's developers for support and maintenance purposes. We also use Google Firebase to automatically detect app crashes and understand how the app is used, so we can fix problems and improve accessibility features; Firebase does not receive your name, school ID, or password."),
        ("Data Retention and Your Rights", "We keep your account information for as long as you remain an active student user, or as required by your school's records policy. You may request to view, correct, or delete your personal information at any time by contacting your school administrator or the app support contact below."),
        ("Contact Us", "If you have questions about this Privacy Policy or how your data is handled, please contact your school administrator or reach the VisualED support team at [insert contact email here].")
    ]

    @State private var isSpeaking = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(Self.lastUpdated)
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                Button {
                    Task { await readAllAloud() }
                } label: {
                    Label(isSpeaking ? "Reading…" : "Read This Policy Aloud", systemImage: "speaker.wave.2.fill")
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
        .navigationTitle("Privacy Policy")
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
    NavigationStack { PrivacyPolicyView() }
}
