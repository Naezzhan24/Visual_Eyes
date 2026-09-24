import SwiftUI

/// Stand-in for MaterialsFragment.java. List of materials wired to live
/// data, opening into the reader on tap. The Sent Materials drawer and
/// voice commands ("open [title]", "read titles", "leave feedback", ...)
/// land in later phases.
struct MaterialsView: View {
    @State private var viewModel = MaterialsViewModel()

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.isLoading && viewModel.materials.isEmpty {
                    ProgressView()
                } else if viewModel.materials.isEmpty {
                    ContentUnavailableView(
                        "No materials yet",
                        systemImage: "books.vertical",
                        description: Text(viewModel.errorMessage ?? "Materials sent to you will show up here.")
                    )
                } else {
                    List(viewModel.materials) { material in
                        NavigationLink(value: material) {
                            MaterialCard(material: material)
                        }
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Materials")
            .navigationDestination(for: LearningMaterial.self) { material in
                MaterialReaderView(material: material)
            }
            .task { await viewModel.loadMaterials() }
            .refreshable { await viewModel.loadMaterials() }
        }
    }
}

#Preview {
    MaterialsView()
        .environment(SessionStore.shared)
}
