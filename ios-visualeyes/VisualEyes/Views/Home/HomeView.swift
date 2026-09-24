import SwiftUI

/// Stand-in for HomeFragment.java. Welcome banner + featured material
/// card + Help shortcut, wired to live data. The quick text-size slider
/// and the Sent Materials drawer are simple UI pieces still deferred;
/// voice commands land alongside the rest of the app's voice-command
/// grammar wiring.
struct HomeView: View {
    @Environment(SessionStore.self) private var sessionStore
    @State private var viewModel = HomeViewModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("Welcome, \(sessionStore.student?.firstName ?? "")!")
                        .font(.title2.bold())
                        .padding(.horizontal)

                    if viewModel.isLoading && viewModel.materials.isEmpty {
                        ProgressView().padding(.horizontal)
                    } else if let featured = viewModel.featuredMaterial {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Latest material")
                                .font(.headline)
                            NavigationLink(value: featured) {
                                MaterialCard(material: featured)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal)
                    } else if let error = viewModel.errorMessage {
                        Text(error).foregroundStyle(.red).padding(.horizontal)
                    } else {
                        Text("No materials yet.")
                            .foregroundStyle(.secondary)
                            .padding(.horizontal)
                    }
                }
                .padding(.vertical)
            }
            .navigationTitle("Home")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    NavigationLink {
                        HelpView()
                    } label: {
                        Image(systemName: "questionmark.circle")
                    }
                    .accessibilityLabel("Help")
                }
            }
            .navigationDestination(for: LearningMaterial.self) { material in
                MaterialReaderView(material: material)
            }
            .task { await viewModel.loadMaterials() }
            .refreshable { await viewModel.loadMaterials() }
        }
    }
}

#Preview {
    HomeView()
        .environment(SessionStore.shared)
}
