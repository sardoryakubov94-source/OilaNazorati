import SwiftUI
import FirebaseFirestore

/// iOS parent controls. These settings are policy requests; the child iPhone applies
/// them locally only after Family Controls authorization and local app/site selection.
struct IOSScreenTimeParentControlsView: View {
    let familyCode: String
    let childUid: String

    @State private var enabled = true
    @State private var dailyLimitMinutes = 120
    @State private var schoolEnabled = false
    @State private var sleepEnabled = false
    @State private var websiteBlockingEnabled = false
    @State private var schoolStart = Calendar.current.date(from: DateComponents(hour: 8, minute: 0)) ?? Date()
    @State private var schoolEnd = Calendar.current.date(from: DateComponents(hour: 14, minute: 0)) ?? Date()
    @State private var sleepStart = Calendar.current.date(from: DateComponents(hour: 21, minute: 30)) ?? Date()
    @State private var sleepEnd = Calendar.current.date(from: DateComponents(hour: 7, minute: 0)) ?? Date()
    @State private var isSaving = false
    @State private var message: String?
    @State private var error: String?
    @State private var limitEvents: [[String: Any]] = []
    @State private var policyListener: ListenerRegistration?
    @State private var eventsListener: ListenerRegistration?\n    @State private var reportListener: ListenerRegistration?\n    @State private var dailySeconds: Double = 0\n    @State private var weeklySeconds: Double = 0

    private var policyRef: DocumentReference {
        Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("screen_time_settings").document("current")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            heading("Ekran vaqti boshqaruvi", detail: "iPhone / iPad uchun")

            Toggle("Ota-ona nazorati faol", isOn: $enabled)
                .tint(.blue)

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label("Kunlik limit", systemImage: "hourglass")
                    Spacer()
                    Text(dailyLimitMinutes == 0 ? "Cheklanmagan" : "\(dailyLimitMinutes) daqiqa")
                        .font(.subheadline.weight(.semibold)).monospacedDigit()
                }
                Stepper("Limitni o‘zgartirish", value: $dailyLimitMinutes, in: 0...600, step: 15)
                    .labelsHidden()
                Text("0 — kunlik limit o‘chirilgan. Limit faqat bola qurilmasida tanlangan ilova va toifalarga tatbiq qilinadi.")
                    .font(.caption2).foregroundStyle(.secondary)
            }

            Divider()
            scheduleEditor(
                title: "Maktab vaqti",
                symbol: "graduationcap",
                enabled: $schoolEnabled,
                start: $schoolStart,
                end: $schoolEnd
            )
            Divider()
            scheduleEditor(
                title: "Uyqu vaqti",
                symbol: "moon.zzz",
                enabled: $sleepEnabled,
                start: $sleepStart,
                end: $sleepEnd
            )
            Divider()
            Toggle(isOn: $websiteBlockingEnabled) {
                Label("Tanlangan saytlarni cheklash", systemImage: "globe")
            }
            .tint(.blue)

            Button(action: savePolicy) {
                HStack {
                    if isSaving { ProgressView().tint(.white) }
                    else { Image(systemName: "arrow.triangle.2.circlepath") }
                    Text(isSaving ? "Saqlanmoqda…" : "Sozlamalarni bolaga yuborish")
                        .fontWeight(.semibold)
                }
                .frame(maxWidth: .infinity).padding(.vertical, 13)
                .foregroundStyle(.white)
                .background(Color.accentColor, in: RoundedRectangle(cornerRadius: 13))
            }
            .disabled(isSaving)

            if let message {
                Label(message, systemImage: "checkmark.circle.fill")
                    .font(.caption).foregroundStyle(.green)
            }
            if let error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption).foregroundStyle(.red)
            }

            VStack(alignment: .leading, spacing: 8) {
                heading("Ekran vaqti hisoboti", detail: "Firebase orqali kelgan")
                HStack(spacing: 12) {
                    reportMetric(title: "Bugun", seconds: dailySeconds)
                    reportMetric(title: "Hafta", seconds: weeklySeconds)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                heading("Limit hodisalari", detail: "Bola ilovasidan sinxronlangan")
                if limitEvents.isEmpty {
                    Text("Hozircha limit tugagani haqida sinxronlangan hodisa yo‘q.")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    ForEach(Array(limitEvents.enumerated()), id: \.offset) { _, event in
                        HStack {
                            Image(systemName: "bell.badge.fill").foregroundStyle(.orange)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Kunlik limit tugadi").font(.subheadline.weight(.medium))
                                if let millis = event["timestampMs"] as? Int64 {
                                    Text(Date(timeIntervalSince1970: Double(millis) / 1000).formatted(date: .abbreviated, time: .shortened))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                        }
                    }
                }
            }
            Text("Eslatma: Apple hisobot ma’lumotlari va cheklovlar Apple ruxsati, bola qurilmasidagi tanlov va iOS extension targetlariga bog‘liq. Limit hodisasi ota-onaga bola ilovasi keyingi safar ochilib Firebase bilan sinxronlanganda yetkaziladi; hozircha server push xabari yoqilmagan.")
                .font(.caption2).foregroundStyle(.secondary)
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
        .onAppear(perform: startListeners)
        .onDisappear {
            policyListener?.remove()
            eventsListener?.remove()
            reportListener?.remove()
        }
    }

    private func heading(_ title: String, detail: String) -> some View {
        HStack {
            Text(title).font(.headline)
            Spacer()
            Text(detail).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func scheduleEditor(title: String, symbol: String, enabled: Binding<Bool>, start: Binding<Date>, end: Binding<Date>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: enabled) { Label(title, systemImage: symbol) }
                .tint(.blue)
            if enabled.wrappedValue {
                HStack {
                    DatePicker("Boshlanish", selection: start, displayedComponents: .hourAndMinute)
                    DatePicker("Tugash", selection: end, displayedComponents: .hourAndMinute)
                }
                .font(.caption)
            }
        }
    }

    private func startListeners() {
        policyListener?.remove()
        policyListener = policyRef.addSnapshotListener { snapshot, _ in
            guard let data = snapshot?.data() else { return }
            DispatchQueue.main.async {
                enabled = data["enabled"] as? Bool ?? true
                dailyLimitMinutes = data["dailyLimitMinutes"] as? Int ?? 120
                schoolEnabled = data["schoolEnabled"] as? Bool ?? false
                sleepEnabled = data["sleepEnabled"] as? Bool ?? false
                websiteBlockingEnabled = data["websiteBlockingEnabled"] as? Bool ?? false
                schoolStart = Self.date(from: data["schoolStart"] as? String ?? "08:00") ?? schoolStart
                schoolEnd = Self.date(from: data["schoolEnd"] as? String ?? "14:00") ?? schoolEnd
                sleepStart = Self.date(from: data["sleepStart"] as? String ?? "21:30") ?? sleepStart
                sleepEnd = Self.date(from: data["sleepEnd"] as? String ?? "07:00") ?? sleepEnd
            }
        }
        eventsListener?.remove()
        eventsListener = Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("screen_time_reports")
            .order(by: "timestampMs", descending: true)
            .limit(to: 5)
            .addSnapshotListener { snapshot, _ in
                DispatchQueue.main.async {
                    limitEvents = snapshot?.documents.map { $0.data() } ?? []
                }
            }
        reportListener?.remove()
        reportListener = Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("screen_time_reports").document("latest")
            .addSnapshotListener { snapshot, _ in
                let data = snapshot?.data() ?? [:]
                DispatchQueue.main.async {
                    dailySeconds = data["dailySeconds"] as? Double ?? 0
                    weeklySeconds = data["weeklySeconds"] as? Double ?? 0
                }
            }
    }

    private func reportMetric(title: String, seconds: Double) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(seconds > 0 ? seconds.formatted(.time(pattern: .hourMinute)) : "—")
                .font(.title3.bold().monospacedDigit())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(uiColor: .tertiarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }

    private func savePolicy() {
        isSaving = true
        error = nil
        message = nil
        let data: [String: Any] = [
            "enabled": enabled,
            "dailyLimitMinutes": dailyLimitMinutes,
            "schoolEnabled": schoolEnabled,
            "schoolStart": Self.string(from: schoolStart),
            "schoolEnd": Self.string(from: schoolEnd),
            "sleepEnabled": sleepEnabled,
            "sleepStart": Self.string(from: sleepStart),
            "sleepEnd": Self.string(from: sleepEnd),
            "websiteBlockingEnabled": websiteBlockingEnabled,
            "updatedAtMs": Int64(Date().timeIntervalSince1970 * 1000),
            "updatedBy": "ios_parent",
            "schemaVersion": 1
        ]
        policyRef.setData(data, merge: true) { writeError in
            DispatchQueue.main.async {
                isSaving = false
                if let writeError {
                    error = "Saqlashda xato: \(writeError.localizedDescription)"
                } else {
                    message = "Sozlamalar Firebase orqali bolaga yuborildi."
                }
            }
        }
    }

    private static func string(from date: Date) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    private static func date(from value: String) -> Date? {
        let parts = value.split(separator: ":")
        guard parts.count == 2, let hour = Int(parts[0]), let minute = Int(parts[1]) else { return nil }
        return Calendar.current.date(from: DateComponents(hour: hour, minute: minute))
    }
}
