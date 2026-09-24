import SwiftUI

/// Placeholder splash screen — Android's IntroActivity hand-draws an
/// animated book+eye logo (`IntroLogoView.java`) with a letter-by-letter
/// "VisualED" pop-in. This is a simplified stand-in (SF Symbol + fade/scale)
/// so the app has a working launch screen from Phase 1; revisit with a
/// closer visual match to the Android animation during the Phase 6 polish
/// pass, not before — it's cosmetic, not load-bearing.
struct IntroView: View {
    let onFinished: () -> Void

    @State private var logoScale: CGFloat = 0.6
    @State private var logoOpacity: Double = 0
    @State private var titleOpacity: Double = 0

    var body: some View {
        ZStack {
            Color(uiColor: .systemBackground).ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "eye.fill")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 96, height: 96)
                    .foregroundStyle(.tint)
                    .scaleEffect(logoScale)
                    .opacity(logoOpacity)
                Text("VisualED")
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .opacity(titleOpacity)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { onFinished() }
        .task {
            withAnimation(.spring(response: 0.6, dampingFraction: 0.7)) {
                logoScale = 1.0
                logoOpacity = 1.0
            }
            try? await Task.sleep(for: .milliseconds(500))
            withAnimation(.easeIn(duration: 0.4)) {
                titleOpacity = 1.0
            }
            try? await Task.sleep(for: .milliseconds(1200))
            onFinished()
        }
    }
}

#Preview {
    IntroView(onFinished: {})
}
