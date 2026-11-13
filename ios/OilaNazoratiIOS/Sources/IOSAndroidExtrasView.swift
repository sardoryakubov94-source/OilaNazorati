import SwiftUI
import FirebaseFirestore

/// Android bolaning bildirishnomalari, SIM ma'lumoti va trendlari iOS ota-ona ilovasida.
/// Android bola tomoni o'zgarmaydi — faqat u yozgan ma'lumotlar o'qiladi.

private func androidChildRef(_ familyCode: String, _ childID: String) -> DocumentReference {
    Firestore.firestore()
        .collection("families").document(familyCode)
        .collection("children").document(childID)
}

private func shortDate(_ millis: Double) -> String {
    let formatter = DateFormatter()
    formatter.dateStyle = .short
    formatter.timeStyle = .short
    return formatter.string(from: Date(timeIntervalSince1970: millis / 1000))
}

private let extrasCardBackground = Color(uiColor: .secondarySystemGroupedBackground)

// MARK: - Bildirishnomalar

private struct IOSAndroidNotificationRow: Identifiable {
    let id: String
    let appName: String
    let title: String
    let text: String
    let millis: Double
}

struct IOSAndroidNotificationsView: View {
    let familyCode: String
    let childID: String

    @State private var rows: [IOSAndroidNotificationRow] = []
    @State private var loading = false
    @State private var errorText: String?
    @State private var listener: ListenerRegistration?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Bildirishnomalar", systemImage: "bell.badge")
                .font(.headline)
            if let errorText {
                Text(errorText).font(.caption).foregroundStyle(.red)
            }
            if loading && rows.isEmpty {
                ProgressView().frame(maxWidth: .infinity)
            } else if rows.isEmpty {
                Text("Hozircha bildirishnoma yo'q.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(rows) { row in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(row.appName.isEmpty ? "Ilova" : row.appName)
                                .font(.subheadline.weight(.semibold))
                            Spacer()
                            Text(shortDate(row.millis))
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        if !row.title.isEmpty {
                            Text(row.title).font(.footnote)
                        }
                        if !row.text.isEmpty {
                            Text(row.text)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(3)
                        }
                    }
                    .padding(.vertical, 2)
                    Divider()
                }
            }
        }
        .padding(16)
        .background(extrasCardBackground, in: RoundedRectangle(cornerRadius: 18))
        .onAppear { start() }
        .onDisappear {
            listener?.remove()
            listener = nil
        }
    }

    private func start() {
        listener?.remove()
        loading = true
        listener = androidChildRef(familyCode, childID).collection("notifications")
            .order(by: "vaqtMs", descending: true)
            .limit(to: 30)
            .addSnapshotListener { snapshot, error in
                DispatchQueue.main.async {
                    loading = false
                    if let error {
                        errorText = "Bildirishnomalar yuklanmadi: \(error.localizedDescription)"
                        return
                    }
                    errorText = nil
                    rows = (snapshot?.documents ?? []).map { document -> IOSAndroidNotificationRow in
                        let data = document.data()
                        return IOSAndroidNotificationRow(
                            id: document.documentID,
                            appName: data["ilovaNomi"] as? String ?? "",
                            title: data["sarlavha"] as? String ?? "",
                            text: data["matn"] as? String ?? "",
                            millis: (data["vaqtMs"] as? NSNumber)?.doubleValue ?? 0
                        )
                    }
                }
            }
    }
}

// MARK: - SIM ma'lumoti

private struct IOSAndroidSimCard: Identifiable {
    let id: Int
    let slot: Int
    let operatorName: String
    let phoneNumber: String
}

struct IOSAndroidSimInfoView: View {
    let familyCode: String
    let childID: String

    @State private var cards: [IOSAndroidSimCard] = []
    @State private var listener: ListenerRegistration?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("SIM ma'lumoti", systemImage: "simcard")
                .font(.headline)
            if cards.isEmpty {
                Text("SIM ma'lumoti hali kelmagan.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(cards) { card in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("SIM \(card.slot + 1) • \(card.operatorName)")
                            .font(.subheadline.weight(.semibold))
                        Text(card.phoneNumber.isEmpty ? "Raqam aniqlanmagan" : card.phoneNumber)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .padding(16)
        .background(extrasCardBackground, in: RoundedRectangle(cornerRadius: 18))
        .onAppear { start() }
        .onDisappear {
            listener?.remove()
            listener = nil
        }
    }

    private func start() {
        listener?.remove()
        listener = androidChildRef(familyCode, childID).addSnapshotListener { snapshot, _ in
            let raw = snapshot?.data()?["simCards"] as? [[String: Any]] ?? []
            let parsed = raw.enumerated().map { index, item -> IOSAndroidSimCard in
                IOSAndroidSimCard(
                    id: index,
                    slot: (item["slot"] as? NSNumber)?.intValue ?? index,
                    operatorName: item["operator"] as? String ?? "Noma'lum operator",
                    phoneNumber: item["phoneNumber"] as? String ?? ""
                )
            }
            DispatchQueue.main.async { cards = parsed }
        }
    }
}

// MARK: - Trendlar (oxirgi 7 kun)

private struct IOSAndroidTrendDay: Identifiable {
    let id: Date
    var calls = 0
    var sms = 0
    var usageSeconds = 0
}

struct IOSAndroidTrendsView: View {
    let familyCode: String
    let childID: String

    @State private var days: [IOSAndroidTrendDay] = []
    @State private var loading = false
    @State private var errorText: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Trendlar (7 kun)", systemImage: "chart.bar")
                    .font(.headline)
                Spacer()
                Button {
                    load()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(loading)
            }
            if let errorText {
                Text(errorText).font(.caption).foregroundStyle(.red)
            }
            if loading && days.isEmpty {
                ProgressView().frame(maxWidth: .infinity)
            } else {
                trendSection("Ilova vaqti (daqiqa)", values: days.map { $0.usageSeconds / 60 })
                trendSection("Qo'ng'iroqlar", values: days.map { $0.calls })
                trendSection("SMS", values: days.map { $0.sms })
            }
        }
        .padding(16)
        .background(extrasCardBackground, in: RoundedRectangle(cornerRadius: 18))
        .onAppear { load() }
    }

    private func trendSection(_ title: String, values: [Int]) -> some View {
        let maxValue = max(values.max() ?? 0, 1)
        return VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.subheadline.weight(.semibold))
            ForEach(Array(days.enumerated()), id: \.offset) { index, day in
                let value = index < values.count ? values[index] : 0
                HStack(spacing: 8) {
                    Text(dayLabel(day.id))
                        .font(.caption2)
                        .frame(width: 44, alignment: .leading)
                    ProgressView(value: Double(value), total: Double(maxValue))
                    Text("\(value)")
                        .font(.caption2)
                        .frame(width: 36, alignment: .trailing)
                }
            }
        }
    }

    private func dayLabel(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "dd.MM"
        return formatter.string(from: date)
    }

    private func load() {
        loading = true
        errorText = nil
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        var template: [IOSAndroidTrendDay] = []
        for offset in stride(from: 6, through: 0, by: -1) {
            if let date = calendar.date(byAdding: .day, value: -offset, to: today) {
                template.append(IOSAndroidTrendDay(id: date))
            }
        }
        guard let first = template.first else {
            loading = false
            return
        }
        let startMillis = first.id.timeIntervalSince1970 * 1000
        let ref = androidChildRef(familyCode, childID)
        let group = DispatchGroup()
        var result = template

        func index(forMillis millis: Double) -> Int? {
            let day = calendar.startOfDay(for: Date(timeIntervalSince1970: millis / 1000))
            return result.firstIndex(where: { $0.id == day })
        }

        group.enter()
        ref.collection("app_usage")
            .whereField("boshlanishMs", isGreaterThanOrEqualTo: startMillis)
            .order(by: "boshlanishMs")
            .getDocuments { snapshot, error in
                DispatchQueue.main.async {
                    if let error { errorText = error.localizedDescription }
                    for document in snapshot?.documents ?? [] {
                        let data = document.data()
                        let millis = (data["boshlanishMs"] as? NSNumber)?.doubleValue ?? 0
                        let seconds = (data["davomiylikSoniya"] as? NSNumber)?.intValue ?? 0
                        if let i = index(forMillis: millis) { result[i].usageSeconds += max(0, seconds) }
                    }
                    group.leave()
                }
            }

        group.enter()
        ref.collection("calls")
            .whereField("boshlanishMs", isGreaterThanOrEqualTo: startMillis)
            .order(by: "boshlanishMs")
            .getDocuments { snapshot, error in
                DispatchQueue.main.async {
                    if let error { errorText = error.localizedDescription }
                    for document in snapshot?.documents ?? [] {
                        let millis = (document.data()["boshlanishMs"] as? NSNumber)?.doubleValue ?? 0
                        if let i = index(forMillis: millis) { result[i].calls += 1 }
                    }
                    group.leave()
                }
            }

        group.enter()
        ref.collection("sms")
            .whereField("vaqtMs", isGreaterThanOrEqualTo: startMillis)
            .order(by: "vaqtMs")
            .getDocuments { snapshot, error in
                DispatchQueue.main.async {
                    if let error { errorText = error.localizedDescription }
                    for document in snapshot?.documents ?? [] {
                        let millis = (document.data()["vaqtMs"] as? NSNumber)?.doubleValue ?? 0
                        if let i = index(forMillis: millis) { result[i].sms += 1 }
                    }
                    group.leave()
                }
            }

        group.notify(queue: .main) {
            days = result
            loading = false
        }
    }
}
