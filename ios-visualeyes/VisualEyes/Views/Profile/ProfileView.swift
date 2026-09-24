import SwiftUI

/// Stand-in for ProfileFragment.java. Student info card, navigation to
/// Assistant Voice/Help/Privacy Policy/Retake Assessment, and logout —
/// all wired to live session data. The TTS/STT on-off toggles land later
/// once there's a shared preferences layer to back them (see the note on
/// `FeedbackViewModel`'s base-value simplification).
struct ProfileView: View {
    @Environment(SessionStore.self) private var sessionStore
    @State private var coordinator = AssessmentCoordinator.shared

    var body: some View {
        NavigationStack {
            List {
                if let student = sessionStore.student {
                    Section("Student Info") {
                        LabeledContent("Name", value: "\(student.firstName) \(student.lastName)")
                        LabeledContent("School ID", value: student.schoolId)
                        if let year = student.yearLevel, !year.isEmpty {
                            LabeledContent("Year level", value: year)
                        }
                        if let section = student.section, !section.isEmpty {
                            LabeledContent("Section", value: section)
                        }
                        if let level = student.impairmentLevel, !level.isEmpty {
                            LabeledContent("Impairment level", value: level)
                        }
                    }
                }

                Section {
                    NavigationLink("Assistant Voice") {
                        VoiceSettingsView()
                    }
                    NavigationLink("Help") {
                        HelpView()
                    }
                    NavigationLink("Privacy Policy") {
                        PrivacyPolicyView()
                    }
                }

                Section {
                    Button("Retake Assessment") {
                        coordinator.pendingRetake = true
                    }
                }

                Section {
                    Button("Log Out", role: .destructive) {
                        sessionStore.logout()
                    }
                }
            }
            .navigationTitle("Profile")
        }
    }
}

#Preview {
    ProfileView()
        .environment(SessionStore.shared)
}
