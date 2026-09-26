import Foundation
import Network

/// Whether the phone is online right now, so speech can go straight to
/// the on-device engines (Apple's offline recognizer, then Whisper)
/// instead of waiting for a cloud call that can't succeed — the
/// iOS side of Android's NetworkUtils.isOnline checks.
@MainActor
final class NetworkMonitor {
    static let shared = NetworkMonitor()

    private(set) var isOnline = true
    private let monitor = NWPathMonitor()

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in self?.isOnline = online }
        }
        monitor.start(queue: DispatchQueue(label: "VisualEyes.NetworkMonitor"))
    }
}
