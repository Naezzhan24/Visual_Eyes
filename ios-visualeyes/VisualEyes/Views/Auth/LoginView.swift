import SwiftUI

struct LoginView: View {
    @Environment(SessionStore.self) private var sessionStore
    @State private var viewModel = LoginViewModel()
    @State private var showRegister = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if let message = sessionStore.sessionExpiredMessage {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.orange)
                            .padding(.horizontal)
                            .onAppear {
                                // Show once, then clear.
                                sessionStore.sessionExpiredMessage = nil
                            }
                    }

                    VStack(spacing: 8) {
                        Text("Welcome back")
                            .font(.title.bold())
                        Text("Log in with your School ID to continue.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.top, 24)
                    .multilineTextAlignment(.center)

                    VStack(spacing: 14) {
                        HStack(spacing: 8) {
                            TextField("School ID", text: $viewModel.schoolId)
                                .textFieldStyle(.roundedBorder)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .accessibilityLabel("School ID")

                            // Phase 2 smoke test for Tier-1 voice input —
                            // full voice-driven login lands in Phase 5.
                            Button {
                                Task { await viewModel.toggleSchoolIdDictation() }
                            } label: {
                                Image(systemName: viewModel.isListeningForSchoolId ? "mic.fill" : "mic")
                                    .font(.title3)
                                    .foregroundStyle(viewModel.isListeningForSchoolId ? .red : .accentColor)
                                    .frame(width: 44, height: 36)
                            }
                            .accessibilityLabel(viewModel.isListeningForSchoolId ? "Stop listening" : "Say your School ID")
                        }

                        SecureField("Password (birthdate MM-DD-YYYY)", text: $viewModel.password)
                            .textFieldStyle(.roundedBorder)
                            .accessibilityLabel("Password, your birthdate in month, day, year format")
                    }
                    .padding(.horizontal)

                    if let error = viewModel.errorMessage {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .padding(.horizontal)
                    }

                    Button {
                        Task { await viewModel.login() }
                    } label: {
                        if viewModel.isLoading {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        } else {
                            Text("Log In")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!viewModel.canSubmit)
                    .padding(.horizontal)

                    Button("Don't have an account? Register") {
                        showRegister = true
                    }
                    .font(.footnote)
                }
                .padding(.bottom, 32)
            }
            .navigationDestination(isPresented: $showRegister) {
                RegisterView()
            }
        }
        .task {
            // Phase 2 smoke test for the Cloud TTS path. Doesn't repeat
            // on re-appearance (e.g. popping back from Register) since
            // this view's identity persists as the NavigationStack root.
            await viewModel.speakWelcomePrompt()
        }
    }
}

#Preview {
    LoginView()
        .environment(SessionStore.shared)
}
