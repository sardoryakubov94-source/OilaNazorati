import SwiftUI
import FamilyControls
import DeviceActivity
import ManagedSettings
import FirebaseFirestore
import UserNotifications

/// Firestore contract shared by the iOS parent and child panels:
/// families/{code}/children/{uid}/screen_time_settings/current
/// Parent writes policy fields; child applies them locally after Apple authorization.
@available(iOS 16.0, *)
enum ScreenTimeContract {
    static let collection = "screen_time_settings"
    static let document = "current"
    static let reportCollection = "screen_time_reports"
    static let reportDocument = "latest"
    static let activityName = DeviceActivityName("uz.oilanazorati.daily")
    static let dailyEventName = DeviceActivityEvent.Name("daily_limit")
    static let appGroup = "group.uz.oilanazorati.shared"
}

@available(iOS 16.0, *)
@MainActor
final class ScreenTimeController: ObservableObject {
    static let shared = ScreenTimeController()

    @Published private(set) var authorizationStatus: AuthorizationStatus = AuthorizationCenter.shared.authorizationStatus
    @Published private(set) var selection = FamilyActivitySelection()
    @Published private(set) var lastError: String?
    @Published private(set) var policyActive = false

    private let center = DeviceActivityCenter()
    private let settings = ManagedSettingsStore()
    private var policyListener: ListenerRegistration?
    private var familyCode: String?
    private var childUid: String?
    private var latestPolicy: [String: Any]?

    private init() {
        loadSelection()
    }

    func requestChildAuthorization() async {
        do {
            try await AuthorizationCenter.shared.requestAuthorization(for: .child)
            authorizationStatus = AuthorizationCenter.shared.authorizationStatus
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
            lastError = nil
            if authorizationStatus == .approved, let latestPolicy {
                apply(policy: latestPolicy)
            }
        } catch {
            authorizationStatus = AuthorizationCenter.shared.authorizationStatus
            lastError = "Screen Time ruxsati berilmadi: \(error.localizedDescription)"
        }
    }

    func updateSelection(_ value: FamilyActivitySelection) {
        selection = value
        saveSelection()
        applySelectionIfNeeded()
        if let latestPolicy { apply(policy: latestPolicy) }
    }

    func start(familyCode: String, childUid: String) {
        self.familyCode = familyCode
        self.childUid = childUid
        policyListener?.remove()
        policyListener = Firestore.firestore()
            .collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection(ScreenTimeContract.collection).document(ScreenTimeContract.document)
            .addSnapshotListener { [weak self] snapshot, error in
                Task { @MainActor in
                    guard let self else { return }
                    if let error {
                        self.lastError = "Nazorat sozlamalari sinxronlanmadi: \(error.localizedDescription)"
                        return
                    }
                    guard let data = snapshot?.data() else { return }
                    self.apply(policy: data)
                }
            }
        syncQueuedEvents()
    }

    func stop() {
        policyListener?.remove()
        policyListener = nil
    }

    func refreshAuthorization() {
        authorizationStatus = AuthorizationCenter.shared.authorizationStatus
    }

    private func apply(policy: [String: Any]) {
        latestPolicy = policy
        guard authorizationStatus == .approved else {
            policyActive = false
            return
        }
        let enabled = policy["enabled"] as? Bool ?? false
        let dailyLimit = policy["dailyLimitMinutes"] as? Int ?? 0
        let schoolEnabled = policy["schoolEnabled"] as? Bool ?? false
        let sleepEnabled = policy["sleepEnabled"] as? Bool ?? false
        let websiteBlocking = policy["websiteBlockingEnabled"] as? Bool ?? false
        policyActive = enabled
        let sharedDefaults = UserDefaults(suiteName: ScreenTimeContract.appGroup)
        sharedDefaults?.set(websiteBlocking, forKey: "websiteBlockingEnabled")

        if enabled {
            applySelectionIfNeeded()
            configureDailyLimit(minutes: dailyLimit)
            configureSchedule(
                name: "school",
                enabled: schoolEnabled,
                start: policy["schoolStart"] as? String ?? "08:00",
                end: policy["schoolEnd"] as? String ?? "14:00"
            )
            configureSchedule(
                name: "sleep",
                enabled: sleepEnabled,
                start: policy["sleepStart"] as? String ?? "21:30",
                end: policy["sleepEnd"] as? String ?? "07:00"
            )
            if websiteBlocking && !selection.webDomainTokens.isEmpty {
                settings.webContent.blockedByFilter = .specific(selection.webDomainTokens)
            } else {
                settings.webContent.blockedByFilter = nil
            }
        } else {
            clearAllShields()
        }
    }

    private func applySelectionIfNeeded() {
        guard authorizationStatus == .approved else { return }
        // Selection itself must never block apps. Shields are applied only by
        // DeviceActivityMonitor when a schedule starts or a usage threshold is reached.
    }

    private func configureDailyLimit(minutes: Int) {
        guard minutes > 0,
              !selection.applicationTokens.isEmpty || !selection.categoryTokens.isEmpty || !selection.webDomainTokens.isEmpty else {
            center.stopMonitoring([ScreenTimeContract.activityName])
            return
        }
        let schedule = DeviceActivitySchedule(
            intervalStart: DateComponents(hour: 0, minute: 0),
            intervalEnd: DateComponents(hour: 23, minute: 59),
            repeats: true
        )
        let threshold = DateComponents(minute: minutes)
        let event = DeviceActivityEvent(
            applications: selection.applicationTokens,
            categories: selection.categoryTokens,
            webDomains: selection.webDomainTokens,
            threshold: threshold
        )
        do {
            try center.startMonitoring(
                ScreenTimeContract.activityName,
                during: schedule,
                events: [ScreenTimeContract.dailyEventName: event]
            )
        } catch {
            lastError = "Kunlik limit jadvali ishga tushmadi: \(error.localizedDescription)"
        }
    }

    private func configureSchedule(name: String, enabled: Bool, start: String, end: String) {
        let activity = DeviceActivityName("uz.oilanazorati.\(name)")
        guard enabled, let startTime = Self.hourMinute(start), let endTime = Self.hourMinute(end) else {
            center.stopMonitoring([activity])
            return
        }
        let schedule = DeviceActivitySchedule(
            intervalStart: DateComponents(hour: startTime.0, minute: startTime.1),
            intervalEnd: DateComponents(hour: endTime.0, minute: endTime.1),
            repeats: true
        )
        do {
            try center.startMonitoring(activity, during: schedule)
        } catch {
            lastError = "\(name == "school" ? "Maktab" : "Uyqu") jadvali ishga tushmadi: \(error.localizedDescription)"
        }
    }

    private static func hourMinute(_ value: String) -> (Int, Int)? {
        let parts = value.split(separator: ":")
        guard parts.count == 2, let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        return (hour, minute)
    }

    private func clearAllShields() {
        settings.shield.applications = nil
        settings.shield.applicationCategories = nil
        settings.webContent.blockedByFilter = nil
        UserDefaults(suiteName: ScreenTimeContract.appGroup)?.removeObject(forKey: "activeScreenTimeReasons")
        UserDefaults(suiteName: ScreenTimeContract.appGroup)?.set(false, forKey: "websiteBlockingEnabled")
        center.stopMonitoring()
    }

    private func saveSelection() {
        guard let data = try? PropertyListEncoder().encode(selection) else { return }
        UserDefaults(suiteName: ScreenTimeContract.appGroup)?.set(data, forKey: "familyActivitySelection")
        UserDefaults.standard.set(data, forKey: "familyActivitySelection")
    }

    private func loadSelection() {
        let data = UserDefaults(suiteName: ScreenTimeContract.appGroup)?.data(forKey: "familyActivitySelection")
            ?? UserDefaults.standard.data(forKey: "familyActivitySelection")
        if let data, let decoded = try? PropertyListDecoder().decode(FamilyActivitySelection.self, from: data) {
            selection = decoded
        }
    }

    /// Upload report totals prepared by the DeviceActivityReport extension.
    func syncReportSummary() {
        guard let code = familyCode, let uid = childUid else { return }
        let defaults = UserDefaults(suiteName: ScreenTimeContract.appGroup) ?? .standard
        let daily = defaults.double(forKey: "latestDailyScreenTimeSeconds")
        let weekly = defaults.double(forKey: "latestWeeklyScreenTimeSeconds")
        guard daily > 0 || weekly > 0 else {
            lastError = "Hisobot hali tayyor emas. Kunlik yoki haftalik hisobot ochilgach qayta urinib ko‘ring."
            return
        }
        Firestore.firestore().collection("families").document(code)
            .collection("children").document(uid)
            .collection(ScreenTimeContract.reportCollection).document(ScreenTimeContract.reportDocument)
            .setData([
                "dailySeconds": daily,
                "weeklySeconds": weekly,
                "updatedAtMs": Int64(Date().timeIntervalSince1970 * 1000),
                "source": "apple_device_activity_report"
            ], merge: true) { [weak self] error in
                Task { @MainActor in
                    if let error { self?.lastError = "Hisobot Firebase'ga yuborilmadi: \(error.localizedDescription)" }
                    else { self?.lastError = nil }
                }
            }
    }

    /// DeviceActivityMonitor extension queues threshold events locally. Upload on next app launch.
    func syncQueuedEvents() {
        guard let code = familyCode, let uid = childUid else { return }
        let defaults = UserDefaults(suiteName: ScreenTimeContract.appGroup) ?? .standard
        guard let events = defaults.array(forKey: "screenTimePendingEvents") as? [[String: Any]], !events.isEmpty else { return }
        let batch = Firestore.firestore().collection("families").document(code)
            .collection("children").document(uid).collection(ScreenTimeContract.reportCollection)
        let writes = events
        for event in writes {
            batch.addDocument(data: event)
        }
        defaults.removeObject(forKey: "screenTimePendingEvents")
    }
}
