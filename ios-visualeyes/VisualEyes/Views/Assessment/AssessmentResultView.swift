import SwiftUI

/// Stand-in for AssessmentResultActivity.java.
struct AssessmentResultView: View {
    @State private var viewModel: AssessmentResultViewModel

    init(summary: AssessmentSummary, onContinue: @escaping () -> Void) {
        let model = AssessmentResultViewModel(summary: summary)
        model.onContinue = onContinue
        _viewModel = State(initialValue: model)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(viewModel.summary.impairmentLevel.rawValue)
                        .font(.title2.bold())
                    Text("Recommended text size: \(viewModel.sizeSpokenText)pt")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text("You could clearly read \(viewModel.summary.yesCount) out of \(viewModel.summary.totalItems) words.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if viewModel.isListening {
                    Label("Listening for \"repeat\" or \"continue\"…", systemImage: "mic.fill")
                        .font(.footnote)
                        .foregroundStyle(.red)
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Word breakdown")
                        .font(.headline)
                    ForEach(viewModel.summary.outcomes) { outcome in
                        HStack {
                            Text(outcome.word)
                            Spacer()
                            Text("\(Int(outcome.size))pt")
                                .foregroundStyle(.secondary)
                            Image(systemName: outcome.wasRead ? "checkmark.circle.fill" : "xmark.circle")
                                .foregroundStyle(outcome.wasRead ? .green : .red)
                                .accessibilityLabel(outcome.wasRead ? "Read successfully" : "Not read")
                        }
                        .font(.subheadline)
                        .padding(.vertical, 4)
                        Divider()
                    }
                }

                Button("Continue") {
                    viewModel.finish()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
            }
            .padding()
        }
        .navigationTitle("Assessment Result")
        .navigationBarBackButtonHidden(true)
        .task { await viewModel.speakAndListen() }
    }
}

#Preview {
    NavigationStack {
        AssessmentResultView(
            summary: AssessmentSummary(
                outcomes: [
                    AssessmentWordOutcome(word: "cat", size: 18, wasRead: true),
                    AssessmentWordOutcome(word: "apple", size: 24, wasRead: true),
                    AssessmentWordOutcome(word: "garden", size: 30, wasRead: false),
                    AssessmentWordOutcome(word: "bicycle", size: 36, wasRead: false),
                    AssessmentWordOutcome(word: "beautiful", size: 42, wasRead: false)
                ],
                impairmentLevel: .moderate,
                recommendedSize: 24,
                yesCount: 2,
                totalItems: 5,
                isRetake: false
            ),
            onContinue: {}
        )
    }
}
