import SwiftUI
import FamilyControls

@available(iOS 16.0, *)
struct ScreenTimeChildDashboardView: View {
    let familyCode: String
    let childUid: String

    @StateObject private var controller = ScreenTimeController.shared
    @State private var showingPicker = false
    @State private var authorizationBusy = false

    var body: some View {
        List {
            Section {
                HStack(spacing: 12) {
                    Image(systemName: controller.authorizationStatus == .approved ? "checkmark.shield.fill" : "lock.shield")
                        .font(.title2)
                        .foregroundStyle(controller.authorizationStatus == .approved ? .green : .orange)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Screen Time ruxsati")
                            .font(.headline)
                        Text(authorizationText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                if controller.authorizationStatus != .approved {
                    Button {
                        authorizationBusy = true
                        Task {
                            await controller.requestChildAuthorization()
                            authorizationBusy = false
                        }
                    } label: {
                        if authorizationBusy { ProgressView() }
                        else { Label("Apple ruxsatini berish", systemImage: "hand.raised.fill") }
                    }
                    .disabled(authorizationBusy)
                }
            } header: {
                Text("Qurilma ruxsatlari")
            } footer: {
                Text("Bu ruxsat bolaning iPhone qurilmasida beriladi. Ota-ona masofadan turib Apple ruxsatini chetlab o‘ta olmaydi.")
            }

            Section("Nazorat qilinadigan ilovalar va saytlar") {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Tanlangan elementlar")
                        Text("\(controller.selection.applicationTokens.count) ta ilova • \(controller.selection.categoryTokens.count) ta toifa • \(controller.selection.webDomainTokens.count) ta sayt")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Tanlash") { showingPicker = true }
                        .buttonStyle(.borderedProminent)
                }
                .disabled(controller.authorizationStatus != .approved)
            }

            Section("Jadval va cheklovlar") {
                Label("Kunlik vaqt limiti", systemImage: "hourglass")
                Label("Maktab vaqti jadvali", systemImage: "graduationcap")
                Label("Uyqu vaqti jadvali", systemImage: "moon.zzz")
                Label("Veb-sayt cheklovlari", systemImage: "globe")
                Text("Jadvallar ota-ona panelidan yuboriladi va Apple ruxsati tasdiqlangandan keyin shu qurilmada qo‘llanadi.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if let error = controller.lastError {
                Section("Holat") {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
            }
        }
        .navigationTitle("Ekran vaqti")
        .familyActivityPicker(isPresented: $showingPicker, selection: Binding(
            get: { controller.selection },
            set: { controller.updateSelection($0) }
        ))
        .onAppear {
            controller.refreshAuthorization()
            controller.start(familyCode: familyCode, childUid: childUid)
        }
        .onDisappear { controller.stop() }
    }

    private var authorizationText: String {
        switch controller.authorizationStatus {
        case .approved: return "Apple Screen Time ruxsati berilgan"
        case .denied: return "Ruxsat rad etilgan"
        case .notDetermined: return "Hali ruxsat so‘ralmagan"
        @unknown default: return "Ruxsat holati noma’lum"
        }
    }
}
