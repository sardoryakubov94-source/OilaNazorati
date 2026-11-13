import SwiftUI
import FirebaseFirestore

/// Android risk_events and Apple-approved Screen Time reports shown in one parent feed.
struct IOSRiskAlertsView: View {
    let familyCode: String
    let childID: String

    @State private var events: [RiskAlertItem] = []
    @State private var screenTimeEvents: [RiskAlertItem] = []
    @State private var riskListener: ListenerRegistration?
    @State private var screenTimeListener: ListenerRegistration?
    @State private var loadError: String?

    private var allEvents: [RiskAlertItem] {
        (events + screenTimeEvents).sorted { $0.capturedAt > $1.capturedAt }
    }

    var body: some View {
        List {
            Section {
                Label(
                    "iOS'da Apple Screen Time orqali tanlangan ilova, kategoriya yoki saytlar bo'yicha foydalanish limitlari qayd etiladi. iPhone boshqa ilovalardagi barcha video va yozishmalar mazmunini OilaNazorati'ga ochmaydi.",
                    systemImage: "info.circle"
                )
                .font(.footnote).foregroundStyle(.secondary)
            }
            Section("iOS'dagi kontent aniqlash cheklovlari") {
                Label {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("18+ video kontent")
                            .font(.subheadline.weight(.medium))
                        Text("Boshqa ilovalardagi video tasvirini avtomatik tekshirish iOS tomonidan ochilmagan. Tanlangan ilova yoki saytning foydalanish vaqti kuzatilishi mumkin.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "video.slash")
                        .foregroundStyle(.secondary)
                }
                Label {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Intim suhbatlar va xabarlar")
                            .font(.subheadline.weight(.medium))
                        Text("Telegram, WhatsApp, iMessage va boshqa ilovalarning shaxsiy yozishmalarini OilaNazorati fonda o'qiy olmaydi. Shu sababli intim suhbat, rasm so'rovi yoki shantajni iOS'da avtomatik aniqlash yoqilmagan.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "bubble.left.and.bubble.right")
                        .foregroundStyle(.secondary)
                }
            }
            if let loadError {
                Section { Text(loadError).foregroundStyle(.red).font(.footnote) }
            }
            if allEvents.isEmpty {
                ContentUnavailableView(
                    "Hozircha signal yo'q",
                    systemImage: "checkmark.shield",
                    description: Text("Qo'llab-quvvatlanadigan xavf yoki nazorat hodisasi qayd etilsa shu yerda ko'rinadi.")
                )
            } else {
                ForEach(allEvents) { event in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(event.categoryTitle).font(.headline)
                            Spacer()
                            Text(event.severityTitle)
                                .font(.caption.bold())
                                .padding(.horizontal, 8).padding(.vertical, 4)
                                .background(event.severityColor.opacity(0.14), in: Capsule())
                                .foregroundStyle(event.severityColor)
                        }
                        Text(event.summary.isEmpty ? "Xavf signali qayd etildi" : event.summary)
                            .font(.subheadline)
                        HStack {
                            Label(event.appName.isEmpty ? event.source : event.appName, systemImage: "app")
                            Spacer()
                            Text(Date(timeIntervalSince1970: Double(event.capturedAt) / 1000)
                                .formatted(date: .abbreviated, time: .shortened))
                        }
                        .font(.caption).foregroundStyle(.secondary)
                        if event.confidence > 0 {
                            Text("Tahlil ishonchliligi: \(event.confidence)%")
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                        if event.reviewed {
                            Label("Ko'rib chiqilgan", systemImage: "checkmark.circle.fill")
                                .font(.caption).foregroundStyle(.green)
                        }
                    }
                    .padding(.vertical, 5)
                }
            }
        }
        .navigationTitle("Xavfsizlik signallari")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: subscribe)
        .onDisappear {
            riskListener?.remove()
            riskListener = nil
            screenTimeListener?.remove()
            screenTimeListener = nil
        }
    }

    private func subscribe() {
        riskListener?.remove()
        screenTimeListener?.remove()
        let child = Firestore.firestore()
            .collection("families").document(familyCode)
            .collection("children").document(childID)

        riskListener = child.collection("risk_events")
            .order(by: "capturedAt", descending: true).limit(to: 100)
            .addSnapshotListener { snapshot, error in
                if let error {
                    loadError = "Xavf signallari yuklanmadi: \(error.localizedDescription)"
                    return
                }
                events = snapshot?.documents.compactMap { doc in
                    let d = doc.data()
                    return RiskAlertItem(
                        id: "risk-\(doc.documentID)",
                        category: d["category"] as? String ?? "",
                        severity: d["severity"] as? String ?? "LOW",
                        summary: d["summary"] as? String ?? "",
                        appName: d["appName"] as? String ?? "",
                        source: d["source"] as? String ?? "",
                        capturedAt: Self.integer(d["capturedAt"]),
                        confidence: Self.integer(d["confidence"]),
                        reviewed: d["reviewed"] as? Bool ?? false
                    )
                } ?? []
            }

        // DeviceActivityMonitor extension records only the supported daily-limit event.
        // Apple does not expose arbitrary app/chat/search content through this feed.
        screenTimeListener = child.collection("screen_time_reports")
            .order(by: "timestampMs", descending: true).limit(to: 50)
            .addSnapshotListener { snapshot, error in
                if let error {
                    loadError = "iOS Screen Time hodisalari yuklanmadi: \(error.localizedDescription)"
                    return
                }
                screenTimeEvents = snapshot?.documents.compactMap { doc in
                    let d = doc.data()
                    guard (d["type"] as? String) == "daily_limit_reached" else { return nil }
                    let timestamp = Self.integer(d["timestampMs"])
                    return RiskAlertItem(
                        id: "screen-time-\(doc.documentID)",
                        category: "SCREEN_TIME_LIMIT",
                        severity: "MEDIUM",
                        summary: "Ota-ona tanlagan ilovalar, kategoriyalar yoki saytlar uchun belgilangan kunlik foydalanish limiti tugadi.",
                        appName: "Tanlangan ilovalar / saytlar",
                        source: "Apple Screen Time",
                        capturedAt: timestamp,
                        confidence: 0,
                        reviewed: false
                    )
                } ?? []
            }
    }

    private static func integer(_ value: Any?) -> Int64 {
        if let value = value as? Int64 { return value }
        if let value = value as? Int { return Int64(value) }
        if let value = value as? NSNumber { return value.int64Value }
        return 0
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
    let confidence: Int64
    let reviewed: Bool

    var categoryTitle: String {
        switch category {
        case "ADULT_SEXUAL", "ADULT_MEDIA": return "18+ kontent"
        case "SUGGESTIVE_MEDIA": return "Ochiq / intim tasvir"
        case "SEXUAL_IMAGE_REQUEST": return "Intim rasm so'rovi"
        case "GROOMING_OR_COERCION": return "Shantaj / xavfli aloqa"
        case "GAMBLING": return "Qimor"
        case "DRUGS": return "Giyohvand moddalar"
        case "SELF_HARM": return "O'ziga zarar"
        case "VIOLENCE": return "Zo'ravonlik"
        case "DANGEROUS_CHALLENGE": return "Xavfli chaqiriq"
        case "SCREEN_TIME_LIMIT": return "Ekran vaqti limiti"
        default: return category.isEmpty ? "Xavfsizlik signali" : category
        }
    }

    var severityTitle: String {
        switch severity.uppercased() {
        case "HIGH": return "YUQORI"
        case "MEDIUM": return "O'RTA"
        default: return "PAST"
        }
    }

    var severityColor: Color {
        switch severity.uppercased() {
        case "HIGH": return .red
        case "MEDIUM": return .orange
        default: return .blue
        }
    }
}
