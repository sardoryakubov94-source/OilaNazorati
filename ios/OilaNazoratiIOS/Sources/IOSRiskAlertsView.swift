import SwiftUI
import FirebaseFirestore

/// Android bilan bir xil Firestore yo'li: families/{family}/children/{child}/risk_events
struct IOSRiskAlertsView: View {
    let familyCode: String
    let childID: String

    @State private var events: [RiskAlertItem] = []
    @State private var listener: ListenerRegistration?
    @State private var loadError: String?

    var body: some View {
        List {
            Section {
                Label("Android qurilmasida ekran matni va media tahlili; iOS'da esa Apple ruxsat bergan cheklangan kuzatuv ishlaydi.",
                      systemImage: "info.circle")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let loadError {
                Section { Text(loadError).foregroundStyle(.red).font(.footnote) }
            }
            if events.isEmpty {
                ContentUnavailableView("Hozircha signal yo'q",
                    systemImage: "checkmark.shield",
                    description: Text("Xavf signallari aniqlansa shu yerda ko'rinadi."))
            } else {
                ForEach(events) { event in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(event.categoryTitle).font(.headline)
                            Spacer()
                            Text(event.severity)
                                .font(.caption.bold())
                                .padding(.horizontal, 8).padding(.vertical, 4)
                                .background(event.severity == "HIGH" ? Color.red.opacity(0.14) : Color.orange.opacity(0.14), in: Capsule())
                        }
                        Text(event.summary.isEmpty ? "Xavf signali qayd etildi" : event.summary)
                            .font(.subheadline)
                        HStack {
                            Label(event.appName.isEmpty ? event.source : event.appName, systemImage: "app")
                            Spacer()
                            Text(Date(timeIntervalSince1970: Double(event.capturedAt) / 1000)
                                .formatted(date: .abbreviated, time: .shortened))
                        }.font(.caption).foregroundStyle(.secondary)
                        if event.reviewed {
                            Label("Ko'rib chiqilgan", systemImage: "checkmark.circle.fill")
                                .font(.caption).foregroundStyle(.green)
                        }
                    }.padding(.vertical, 5)
                }
            }
        }
        .navigationTitle("Xavfsizlik signallari")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: subscribe)
        .onDisappear { listener?.remove(); listener = nil }
    }

    private func subscribe() {
        listener?.remove()
        listener = Firestore.firestore()
            .collection("families").document(familyCode)
            .collection("children").document(childID)
            .collection("risk_events")
            .order(by: "capturedAt", descending: true).limit(to: 100)
            .addSnapshotListener { snapshot, error in
                if let error { loadError = "Signallar yuklanmadi: \(error.localizedDescription)"; return }
                events = snapshot?.documents.compactMap { doc in
                    let d = doc.data()
                    return RiskAlertItem(id: doc.documentID, category: d["category"] as? String ?? "",
                        severity: d["severity"] as? String ?? "LOW",
                        summary: d["summary"] as? String ?? "",
                        appName: d["appName"] as? String ?? "",
                        source: d["source"] as? String ?? "",
                        capturedAt: d["capturedAt"] as? Int64 ?? (d["capturedAt"] as? Int ?? 0),
                        reviewed: d["reviewed"] as? Bool ?? false)
                } ?? []
            }
    }
}

private struct RiskAlertItem: Identifiable {
    let id: String
    let category: String
    let severity: String
    let summary: String
    let appName: String
    let source: String
    let capturedAt: Int64
    let reviewed: Bool

    var categoryTitle: String {
        switch category {
        case "ADULT_SEXUAL": return "18+ kontent"
        case "SEXUAL_IMAGE_REQUEST": return "Intim rasm so'rovi"
        case "GROOMING_OR_COERCION": return "Shantaj / xavfli aloqa"
        case "GAMBLING": return "Qimor"
        case "DRUGS": return "Giyohvand moddalar"
        case "SELF_HARM": return "O'ziga zarar"
        case "VIOLENCE": return "Zo'ravonlik"
        case "DANGEROUS_CHALLENGE": return "Xavfli challenge"
        default: return category.isEmpty ? "Xavfsizlik signali" : category
        }
    }
}
