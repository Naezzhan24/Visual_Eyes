import SwiftUI

/// Stand-in for TextSizeTestActivity.java. Presented either right after
/// first login (no assessment on file yet) or from Profile's "Retake
/// Assessment" — `isRetake` picks the word bank and where the caller
/// should route to once `onFinished` fires with a summary (or `nil` if
/// the student cancelled mid-test via the "stop" voice command).
struct AssessmentView: View {
    let isRetake: Bool
    let onFinished: (AssessmentSummary?) -> Void

    @State private var viewModel: AssessmentViewModel

    init(isRetake: Bool, onFinished: @escaping (AssessmentSummary?) -> Void) {
        self.isRetake = isRetake
        self.onFinished = onFinished
        _viewModel = State(initialValue: AssessmentViewModel(isRetake: isRetake))
    }

    var body: some View {
        VStack(spacing: 24) {
            Text(viewModel.progressText)
                .font(.footnote)
                .foregroundStyle(.secondary)

            Spacer()

            if let word = viewModel.currentWord {
                Text(word)
                    .font(.system(size: viewModel.currentSize * 2, weight: .semibold))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
                    .accessibilityLabel("The word is \(word)")
            }

            if viewModel.isListening {
                Label("Listening…", systemImage: "mic.fill")
                    .foregroundStyle(.red)
            }

            if !viewModel.statusMessage.isEmpty {
                Text(viewModel.statusMessage)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }

            if let error = viewModel.errorMessage {
                Text(error).foregroundStyle(.red).font(.footnote)
            }

            Spacer()

            switch viewModel.phase {
            case .notStarted:
                Button("Start") {
                    Task { await viewModel.start() }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)

            case .awaitingAnswer:
                HStack(spacing: 16) {
                    Button("No") { viewModel.tapNoButton() }
                        .buttonStyle(.bordered)
                        .tint(.yellow)
                        .controlSize(.large)
                    Button("Yes") { viewModel.tapYesButton() }
                        .buttonStyle(.borderedProminent)
                        .tint(.blue)
                        .controlSize(.large)
                }

            case .verifyingReadAloud:
                EmptyView() // listening — no buttons needed mid-verification

            case .verifyingTyped:
                VStack(spacing: 12) {
                    TextField("Type the word", text: $viewModel.typedAnswer)
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onSubmit { viewModel.submitTypedWord() }
                    Button("Submit") { viewModel.submitTypedWord() }
                        .buttonStyle(.borderedProminent)
                        .disabled(viewModel.typedAnswer.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(.horizontal)

            case .finished:
                EmptyView()
            }
        }
        .padding()
        .navigationTitle(isRetake ? "Retake Assessment" : "Reading Assessment")
        .navigationBarBackButtonHidden(viewModel.phase != .notStarted && viewModel.phase != .finished)
        .toolbar {
            if viewModel.phase != .notStarted && viewModel.phase != .finished {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { viewModel.cancel() }
                }
            }
        }
        .onChange(of: viewModel.phase) { _, newPhase in
            guard newPhase == .finished else { return }
            onFinished(viewModel.wasCancelled ? nil : viewModel.summary)
        }
    }
}

#Preview {
    NavigationStack {
        AssessmentView(isRetake: false, onFinished: { _ in })
    }
}
