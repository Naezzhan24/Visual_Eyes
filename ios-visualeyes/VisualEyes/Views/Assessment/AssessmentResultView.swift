import SwiftUI

/// Port of AssessmentResultActivity.java / `activity_assessment_result.xml`:
/// white result card (level, recommended size, word breakdown), the voice
/// status chip, and Continue.
struct AssessmentResultView: View {
    @State private var viewModel: AssessmentResultViewModel

    init(summary: AssessmentSummary, onContinue: @escaping () -> Void) {
        let model = AssessmentResultViewModel(summary: summary)
        model.onContinue = onContinue
        _viewModel = State(initialValue: model)
    }

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    card

                    HStack(spacing: 8) {
                        if viewModel.isListening {
                            Image(systemName: "waveform")
                                .font(.system(size: 18, weight: .semibold))
                                .symbolEffect(.variableColor.iterative)
                        }
                        Text(viewModel.isListening
                             ? "Say \"repeat\" or \"continue\""
                             : "Continuing shortly...")
                    }
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(VE.textPrimary)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(VE.voiceChip))
                    .padding(.top, 16)
                    .accessibilityElement(children: .combine)

                    Button("Continue") { viewModel.finish() }
                        .buttonStyle(MaroonButtonStyle(height: 52, fontSize: 15))
                        .padding(.top, 12)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 24)
                .frame(minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .maroonBackground()
        .toolbar(.hidden, for: .navigationBar)
        .task { await viewModel.speakAndListen() }
        .onDisappear { viewModel.stopEverything() }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Assessment Complete")
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(VE.textPrimary)
                .frame(maxWidth: .infinity)
                .accessibilityAddTraits(.isHeader)

            divider.padding(.vertical, 16)

            label("Your Visual Impairment Support Level")
            value(viewModel.summary.impairmentLevel.rawValue)

            label("Recommended Text Size")
            value("\(viewModel.sizeSpokenText)pt")

            Text("You could clearly read \(viewModel.summary.yesCount) out of \(viewModel.summary.totalItems) words.")
                .font(.system(size: 15))
                .foregroundStyle(VE.textSecondary)

            if !viewModel.summary.outcomes.isEmpty {
                divider.padding(.vertical, 12)
                label("Words you were asked to read")
                VStack(spacing: 0) {
                    ForEach(viewModel.summary.outcomes) { outcome in
                        HStack {
                            Text(outcome.word)
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(VE.textBody)
                            Spacer()
                            Text("\(Int(outcome.size))pt")
                                .font(.system(size: 15))
                                .foregroundStyle(VE.textSecondary)
                            // Okabe–Ito blue/vermillion, matching the Yes/No buttons.
                            Image(systemName: outcome.wasRead ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundStyle(outcome.wasRead ? Color(hex: 0x0072B2) : Color(hex: 0xD55E00))
                                .accessibilityLabel(outcome.wasRead ? "Read successfully" : "Not read")
                        }
                        .padding(.vertical, 8)
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(.top, 4)
            }
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

    private var divider: some View {
        Rectangle().fill(VE.divider).frame(height: 1)
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 15, weight: .bold))
            .foregroundStyle(VE.textSecondary)
    }

    private func value(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 20, weight: .bold))
            .foregroundStyle(VE.primary)
            .padding(.top, 4)
            .padding(.bottom, 16)
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
