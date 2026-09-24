import SwiftUI

struct RegisterView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var viewModel = RegisterViewModel()

    var body: some View {
        Form {
            Section("School ID") {
                TextField("School ID", text: $viewModel.schoolId)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button {
                    Task { await viewModel.checkSchoolId() }
                } label: {
                    if viewModel.isCheckingSchoolId {
                        ProgressView()
                    } else {
                        Text("Check School ID")
                    }
                }
                .disabled(viewModel.schoolId.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            Section("Name") {
                TextField("First name", text: $viewModel.firstName)
                TextField("Middle name (optional)", text: $viewModel.middleName)
                TextField("Last name", text: $viewModel.lastName)
            }

            Section("Details") {
                DatePicker("Birthdate", selection: $viewModel.birthdate, displayedComponents: .date)
                TextField("Year level", text: $viewModel.yearLevel)
                TextField("Section (optional)", text: $viewModel.section)
                TextField("Email", text: $viewModel.email)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.emailAddress)
            }

            if let error = viewModel.errorMessage {
                Section {
                    Text(error).foregroundStyle(.red)
                }
            }
            if let success = viewModel.successMessage {
                Section {
                    Text(success).foregroundStyle(.green)
                }
            }

            Section {
                Button {
                    Task {
                        if await viewModel.submit() {
                            dismiss()
                        }
                    }
                } label: {
                    if viewModel.isSubmitting {
                        ProgressView()
                    } else {
                        Text("Register")
                    }
                }
                .disabled(!viewModel.canSubmit)
            }
        }
        .navigationTitle("Register")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack {
        RegisterView()
    }
}
