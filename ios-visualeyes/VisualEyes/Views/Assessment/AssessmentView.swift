import SwiftUI

/// Port of TextSizeTestActivity.java / `activity_text_size_test.xml`: white
/// card with step, title, instruction, the test word and the voice status
/// chip, then Start (maroon) or Yes (blue) / No (yellow) below. Presented
/// either right after first login (no assessment on file yet) or from
/// Profile's "Retake" — `isRetake` picks the word bank and where the caller
/// should route to once `onFinished` fires with a summary (or `nil` if the
/// student cancelled mid-test via the "stop" voice command).
struct AssessmentView: View {
    let isRetake: Bool
    let onFinished: (AssessmentSummary?) -> Void

    @State private var viewModel: AssessmentViewModel
    @FocusState private var typingFocused: Bool

    init(isRetake: Bool, onFinished: @escaping (AssessmentSummary?) -> Void) {
        self.isRetake = isRetake
        self.onFinished = onFinished
        _viewModel = State(initialValue: AssessmentViewModel(isRetake: isRetake))
    }

    var body: some View {
        VStack(spacing: 0) {
            card

            buttons
                .padding(.top, 16)
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 20)
        .maroonScreen(title: isRetake ? "Retake Assessment" : "Reading Assessment")
        .navigationBarBackButtonHidden(true)
        .toolbar {
            // Only a retake can be abandoned; the first assessment is
            // required before the rest of the app opens.
            if isRetake && viewModel.phase != .completing && viewModel.phase != .finished {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { viewModel.cancel() }
                        .foregroundStyle(.white)
                }
            }
        }
        .task { await viewModel.autoStart() }
        .onDisappear { viewModel.stopEverything() }
        .onChange(of: viewModel.phase) { _, newPhase in
            guard newPhase == .finished else { return }
            onFinished(viewModel.wasCancelled ? nil : viewModel.summary)
        }
    }

    private var card: some View {
        VStack(spacing: 0) {
            Text(viewModel.progressText)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(VE.primary)

            Text("Visual Impairment Level Test")
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(VE.textPrimary)
                .multilineTextAlignment(.center)
                .padding(.top, 10)
                .accessibilityAddTraits(.isHeader)

            Text("Look at the word. Say yes/no or tap the Yes/No button.")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(VE.textSecondary)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.top, 12)

            divider.padding(.top, 16)

            ZStack {
                if let word = viewModel.currentWord {
                    Text(word)
                        // Exactly the tier size (18–42), like txtWord's sp
                        // size — enlarging it would skew the result.
                        .font(.system(size: viewModel.currentSize, weight: .bold))
                        .foregroundStyle(VE.textPrimary)
                        .multilineTextAlignment(.center)
                        .accessibilityLabel("The word is \(word)")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .frame(minHeight: 200)

            if viewModel.phase == .verifyingTyped {
                HStack(spacing: 10) {
                    MaroonTextField(systemImage: "character.cursor.ibeam", isFocused: false) {
                        TextField("", text: $viewModel.typedAnswer, prompt: maroonPrompt("Type the word you read"))
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($typingFocused)
                            .submitLabel(.done)
                            .onSubmit { viewModel.submitTypedWord() }
                            .accessibilityLabel("Type the word you read")
                    }
                    Button("Submit") { viewModel.submitTypedWord() }
                        .buttonStyle(MaroonButtonStyle(height: 52, fontSize: 14))
                        .frame(width: 96)
                }
                .padding(.bottom, 14)
                .onAppear { typingFocused = true }
            }

            divider.padding(.bottom, 14)

            Text("Voice Status")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(VE.textSecondary)
                .padding(.bottom, 6)

            HStack(spacing: 8) {
                if viewModel.isListening {
                    Image(systemName: "waveform")
                        .font(.system(size: 18, weight: .semibold))
                        .symbolEffect(.variableColor.iterative)
                }
                Text(statusText)
                    .multilineTextAlignment(.center)
            }
            .font(.system(size: 16, weight: .bold))
            .foregroundStyle(VE.textPrimary)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 12)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(VE.voiceChip))
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.updatesFrequently)
        }
        .padding(20)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.white)
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .stroke(Color(hex: 0xF0E5E8), lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
        )
    }

    private var statusText: String {
        if let error = viewModel.errorMessage { return error }
        if !viewModel.statusMessage.isEmpty { return viewModel.statusMessage }
        if viewModel.isListening { return "Listening..." }
        return "Waiting for voice response..."
    }

    private var divider: some View {
        Rectangle().fill(VE.divider).frame(height: 1)
    }

    /// btnStart above btnYes/btnNo, all always on screen like the Android
    /// layout; Start is disabled once the test is running.
    private var buttons: some View {
        VStack(spacing: 12) {
            Button(viewModel.startButtonTitle) {
                Task { await viewModel.start() }
            }
            .buttonStyle(MaroonButtonStyle(height: 52, fontSize: 15))
            .disabled(!viewModel.canStart)

            HStack(spacing: 12) {
                Button("Yes") { viewModel.tapYesButton() }
                    .buttonStyle(SolidButtonStyle(kind: .blue))
                    .accessibilityHint("Blue button. You will type the word to confirm.")
                Button("No") { viewModel.tapNoButton() }
                    .buttonStyle(SolidButtonStyle(kind: .yellow))
                    .accessibilityHint("Yellow button.")
            }
            .disabled(!viewModel.canAnswerWithButtons)
            .opacity(viewModel.canAnswerWithButtons ? 1 : 0.5)
        }
    }
}

#Preview {
    NavigationStack {
        AssessmentView(isRetake: false, onFinished: { _ in })
    }
}
