import SwiftUI

/// The Android app's look, ported 1:1 from `res/values/colors.xml` and the
/// `bg_*.xml` drawables so both apps read as the same product. Names note
/// the drawable each piece comes from.
enum VE {
    // colors.xml
    static let background = Color(hex: 0xFAF7F8)
    static let surface = Color.white
    static let primary = Color(hex: 0x8C4356)
    static let primaryDark = Color(hex: 0x6E3142)
    static let textPrimary = Color(hex: 0x6E3142)
    static let textSecondary = Color(hex: 0x7E6E73)
    static let textBody = Color(hex: 0x2F2A2C)
    static let textOnPrimary = Color.white
    static let textOnPrimaryMuted = Color(hex: 0xF6EDEF)
    static let divider = Color(hex: 0xE7D9DE)

    /// The soft pink used for subtitles on the maroon background (#F2DDE3).
    static let subtitleOnMaroon = Color(hex: 0xF2DDE3)
    /// `#33FFFFFF` — the translucent cards on the maroon background.
    static let translucentCard = Color.white.opacity(0.2)
    /// `bg_voice_chip_soft`
    static let voiceChip = Color(hex: 0xF2E3E8)

    /// `bg_viewer_gradient_bg` — angle 90 runs bottom → top.
    static let maroonGradient = LinearGradient(
        colors: [Color(hex: 0xB85C72), Color(hex: 0x7B3043), Color(hex: 0x3D0A14)],
        startPoint: .top,
        endPoint: .bottom
    )

    /// `bg_btn_login_maroon` / `bg_nav_active_maroon` — angle 90, so the
    /// darker end color sits on top.
    static let buttonGradient = LinearGradient(
        colors: [Color(hex: 0x7C3850), Color(hex: 0xA8506A)],
        startPoint: .top,
        endPoint: .bottom
    )

    /// `bg_feedback_header_gradient` — angle 0 runs left → right.
    static let headerGradient = LinearGradient(
        colors: [Color(hex: 0x6E3142), Color(hex: 0x7B3043), Color(hex: 0x8C4356)],
        startPoint: .leading,
        endPoint: .trailing
    )
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

// MARK: - Backgrounds and cards

/// Full-screen maroon gradient every Android screen sits on.
struct MaroonBackground: View {
    var body: some View {
        VE.maroonGradient.ignoresSafeArea()
    }
}

extension View {
    /// Places the view on the app's maroon gradient.
    func maroonBackground() -> some View {
        background(MaroonBackground())
    }

    /// `bg_glass_card` — frosted translucent panel with a faint light edge.
    func glassCard(cornerRadius: CGFloat = 28) -> some View {
        background(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(LinearGradient(
                    colors: [Color.white.opacity(0.10), Color.white.opacity(0.25)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ))
                .overlay(
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .stroke(Color.white.opacity(0.35), lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.18), radius: 8, y: 4)
        )
    }

    /// `CardView` with `cardBackgroundColor="#33FFFFFF"`, no elevation.
    func translucentCard(cornerRadius: CGFloat = 24) -> some View {
        background(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(VE.translucentCard)
        )
    }

    /// White `CardView` with elevation.
    func surfaceCard(cornerRadius: CGFloat = 24, elevation: CGFloat = 4) -> some View {
        background(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(VE.surface)
                .shadow(color: .black.opacity(0.15), radius: elevation, y: elevation / 2)
        )
    }
}

// MARK: - Inputs

/// `bg_input_maroon_soft` — soft off-white field, maroon ring when focused,
/// with the leading icon Android draws via `drawableStart`.
struct MaroonTextField<Field: View, Trailing: View>: View {
    let systemImage: String
    var isFocused: Bool
    @ViewBuilder var field: () -> Field
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 18))
                .foregroundStyle(VE.textSecondary)
                .frame(width: 22)
                .accessibilityHidden(true)
            field()
                .font(.system(size: 20))
                .foregroundStyle(VE.textBody)
                .tint(VE.primary)
            trailing()
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .frame(height: 52)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(isFocused ? Color.white : Color(hex: 0xFCFAFB))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(isFocused ? VE.primary : Color(hex: 0xD9C5CB), lineWidth: isFocused ? 2 : 1.2)
        )
    }
}

extension MaroonTextField where Trailing == EmptyView {
    init(systemImage: String, isFocused: Bool, @ViewBuilder field: @escaping () -> Field) {
        self.init(systemImage: systemImage, isFocused: isFocused, field: field, trailing: { EmptyView() })
    }
}

/// Placeholder text in Android's `textColorHint` gray, which a plain
/// `TextField` prompt can't be recolored to otherwise.
func maroonPrompt(_ text: String) -> Text {
    Text(text).foregroundColor(VE.textSecondary)
}

// MARK: - Buttons

/// `bg_btn_login_maroon` — the main filled maroon button.
struct MaroonButtonStyle: ButtonStyle {
    var height: CGFloat = 50
    var fontSize: CGFloat = 18

    func makeBody(configuration: Configuration) -> some View {
        MaroonButtonBody(configuration: configuration, height: height, fontSize: fontSize)
    }

    private struct MaroonButtonBody: View {
        let configuration: Configuration
        let height: CGFloat
        let fontSize: CGFloat
        @Environment(\.isEnabled) private var isEnabled

        var body: some View {
            configuration.label
                .font(.system(size: fontSize, weight: .bold))
                .foregroundStyle(VE.textOnPrimary)
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(VE.buttonGradient)
                        .shadow(color: .black.opacity(0.2), radius: 3, y: 2)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.white.opacity(configuration.isPressed ? 0.25 : 0))
                )
                .opacity(isEnabled ? 1 : 0.6)
        }
    }
}

/// `bg_btn_voice_soft` — light pink button with a maroon outline.
struct SoftMaroonButtonStyle: ButtonStyle {
    var height: CGFloat = 50
    var fontSize: CGFloat = 18

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: fontSize, weight: .bold))
            .foregroundStyle(VE.primary)
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color(hex: 0xF7E9EC))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Color(hex: 0xC97B90), lineWidth: 1.5)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(VE.primary.opacity(configuration.isPressed ? 0.2 : 0))
            )
    }
}

// MARK: - Shared pieces

/// The transparent 64pt top bar on Home/Materials/Profile: hamburger,
/// "VisualED", optional help button.
struct VETopBar: View {
    var onMenu: (() -> Void)?
    var onHelp: (() -> Void)?
    /// Supplied by MainTabView, so every tab's hamburger opens the
    /// Sent Materials drawer without wiring it through each tab.
    @Environment(\.openMaterialsDrawer) private var openDrawer

    var body: some View {
        HStack(spacing: 2) {
            if let onMenu = onMenu ?? openDrawer {
                Button(action: onMenu) {
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 48, height: 48)
                }
                .accessibilityLabel("Sent Materials")
            } else {
                Spacer().frame(width: 8)
            }

            Text("VisualED")
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(.white)
                .accessibilityAddTraits(.isHeader)

            Spacer()

            if let onHelp {
                Button(action: onHelp) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 22))
                        .foregroundStyle(.white)
                        .frame(width: 48, height: 48)
                }
                .accessibilityLabel("Help")
            }
        }
        .padding(.leading, 10)
        .padding(.trailing, 18)
        .frame(height: 64)
    }
}

/// `partial_voice_status_card` inside its white card, plus the per-screen
/// "Available voice commands" hint.
struct VoiceStatusCard: View {
    var status: String
    var recognized: String
    var commandsHint: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Voice Navigation Status")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(VE.textPrimary)

            Text("Voice status: \(status)")
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(VE.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(VE.voiceChip))
                .padding(.top, 12)

            Text("Recognized: \(recognized)")
                .font(.system(size: 13))
                .foregroundStyle(VE.textSecondary)
                .padding(.top, 10)

            Text(commandsHint)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(VE.primary)
                .padding(.top, 10)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .surfaceCard()
        .accessibilityElement(children: .combine)
    }
}

/// Screen header used by the pushed screens (Help, Privacy, Voice
/// Settings, …): back chevron + white bold title on the maroon background.
struct VEBackHeader: View {
    let title: String
    var onBack: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onBack) {
                Image(systemName: "chevron.left")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 48, height: 48)
            }
            .accessibilityLabel("Back")

            Text(title)
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(.white)
                .accessibilityAddTraits(.isHeader)
            Spacer()
        }
        .padding(.horizontal, 8)
        .frame(height: 60)
    }
}

// MARK: - Pushed screens

extension View {
    /// Pushed screens sit on the maroon gradient with a transparent nav bar
    /// and white title/back button, matching Android's 64dp white-on-maroon
    /// header while keeping the system back button and swipe-to-go-back.
    func maroonScreen(title: String) -> some View {
        maroonBackground()
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
    }
}

/// One `#33FFFFFF` 20pt-radius section card from `activity_help.xml` /
/// `activity_privacy_policy.xml`.
struct InfoSectionCard: View {
    let heading: String
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(heading)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
                .accessibilityAddTraits(.isHeader)
            Text(text)
                .font(.system(size: 14))
                .foregroundStyle(VE.textOnPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .translucentCard(cornerRadius: 20)
    }
}

/// `bg_btn_yes_blue` / `bg_btn_no_yellow` — the high-contrast confirm and
/// cancel buttons (Okabe–Ito blue and yellow, colorblind-safe).
struct SolidButtonStyle: ButtonStyle {
    enum Kind { case blue, yellow }
    var kind: Kind
    var height: CGFloat = 52
    var fontSize: CGFloat = 15

    func makeBody(configuration: Configuration) -> some View {
        let fill = kind == .blue ? Color(hex: 0x0072B2) : Color(hex: 0xF0E442)
        let stroke = kind == .blue ? Color(hex: 0x00588A) : Color(hex: 0xC9B800)
        return configuration.label
            .font(.system(size: fontSize, weight: .bold))
            // White on the Android yellow is unreadable; use dark text there.
            .foregroundStyle(kind == .blue ? Color.white : Color(hex: 0x2F2A2C))
            .frame(maxWidth: .infinity)
            .frame(minHeight: height)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(stroke, lineWidth: 1.5))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

// MARK: - Low-vision gestures

/// Pinch with two fingers to zoom the screen 1×–3×, snapping back to 100%
/// near the bottom — the ScaleGestureDetector every Android screen has.
private struct PinchZoomModifier: ViewModifier {
    @State private var zoom: CGFloat = 1
    @GestureState private var pinch: CGFloat = 1

    func body(content: Content) -> some View {
        content
            .scaleEffect(min(max(zoom * pinch, 1), 3))
            .simultaneousGesture(
                MagnificationGesture()
                    .updating($pinch) { value, state, _ in state = value }
                    .onEnded { value in
                        let next = min(max(zoom * value, 1), 3)
                        zoom = next < 1.05 ? 1 : next
                    }
            )
    }
}

extension View {
    func pinchToZoom() -> some View {
        modifier(PinchZoomModifier())
    }

    /// Triple-tap anywhere repeats the last spoken instruction.
    func tripleTapToRepeat(_ action: @escaping () -> Void) -> some View {
        simultaneousGesture(TapGesture(count: 3).onEnded(action))
            .accessibilityAction(named: "Repeat last instruction", action)
    }
}
