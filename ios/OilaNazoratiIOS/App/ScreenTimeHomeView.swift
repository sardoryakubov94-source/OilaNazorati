import SwiftUI
import FamilyControls

struct ScreenTimeHomeView: View {
    @StateObject private var screenTime = ScreenTimeManager()
    @State private var showPicker = false

    var body: some View {
        NavigationStack {
            List {
                Section("Qurilma holati") {
                    LabeledContent("Screen Time ruxsati", value: screenTime.authorizationLabel)
                    Button("Ruxsatni so‘rash") { Task { await screenTime.requestAuthorization() } }
                    Button("Ilova / kategoriya / sayt tanlash") { showPicker = true }
                }
                Section("Limitlar") {
                    Button("Tanlanganlarga 1 soatlik limit") { screenTime.applyOneHourLimit() }
                    Button("Barcha cheklovlarni olib tashlash") { screenTime.clearRestrictions() }
                }
                Section("Ota-ona paneli") {
                    Button("Holatni Firebase’ga yuborish") { Task { await screenTime.syncStatus() } }
                }
                Section {
                    Text(screenTime.message).font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("OilaNazorati")
            .familyActivityPicker(isPresented: $showPicker, selection: $screenTime.selection)
            .onChange(of: screenTime.selection) { _, _ in screenTime.applyOneHourLimit() }
        }
    }
}
