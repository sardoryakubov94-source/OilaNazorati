import DeviceActivity
import ManagedSettings
import FamilyControls
import UserNotifications

/// Xcode'da alohida Device Activity Monitor Extension targetiga qo'shiladi.
/// Targetga Family Controls capability va App Group entitlement ham beriladi.
@available(iOS 16.0, *)
final class OilaNazoratiActivityMonitor: DeviceActivityMonitor {
    private let store = ManagedSettingsStore()

    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        if activity.rawValue.contains("school") || activity.rawValue.contains("sleep") {
            addActiveReason(activity.rawValue)
            applyCurrentShields()
            notify(title: "Nazorat jadvali boshlandi", body: "Belgilangan vaqt cheklovlari faollashtirildi.")
        }
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        if activity.rawValue.contains("school") || activity.rawValue.contains("sleep") {
            removeActiveReason(activity.rawValue)
            applyCurrentShields()
            notify(title: "Nazorat jadvali tugadi", body: "Vaqt cheklovi yakunlandi.")
        }
        if activity.rawValue == "uz.oilanazorati.daily" {
            removeActiveReason("daily_limit")
            applyCurrentShields()
        }
    }

    override func eventDidReachThreshold(
        _ event: DeviceActivityEvent.Name,
        activity: DeviceActivityName
    ) {
        super.eventDidReachThreshold(event, activity: activity)
        guard event == DeviceActivityEvent.Name("daily_limit") else { return }
        addActiveReason("daily_limit")
        applyCurrentShields()
        let payload: [String: Any] = [
            "type": "daily_limit_reached",
            "activity": activity.rawValue,
            "event": event.rawValue,
            "timestampMs": Int64(Date().timeIntervalSince1970 * 1000),
            "status": "queued_on_child_device"
        ]
        let defaults = UserDefaults(suiteName: "group.uz.oilanazorati.shared") ?? .standard
        var queue = defaults.array(forKey: "screenTimePendingEvents") as? [[String: Any]] ?? []
        queue.append(payload)
        defaults.set(queue, forKey: "screenTimePendingEvents")
        notify(title: "Kunlik limit tugadi", body: "Tanlangan ilova va saytlar uchun vaqt limiti yakunlandi.")
    }

    private func applyCurrentShields() {
        let defaults = UserDefaults(suiteName: "group.uz.oilanazorati.shared") ?? .standard
        guard let data = defaults.data(forKey: "familyActivitySelection"),
              let selection = try? PropertyListDecoder().decode(FamilyActivitySelection.self, from: data) else { return }
        let reasons = defaults.stringArray(forKey: "activeScreenTimeReasons") ?? []
        let shouldShieldApps = !reasons.isEmpty
        store.shield.applications = shouldShieldApps && !selection.applicationTokens.isEmpty ? selection.applicationTokens : nil
        store.shield.applicationCategories = shouldShieldApps && !selection.categoryTokens.isEmpty ? .specific(selection.categoryTokens) : nil
        let blockWeb = defaults.bool(forKey: "websiteBlockingEnabled")
        store.webContent.blockedByFilter = blockWeb && !selection.webDomainTokens.isEmpty ? .specific(selection.webDomainTokens) : nil
    }

    private func addActiveReason(_ reason: String) {
        let defaults = UserDefaults(suiteName: "group.uz.oilanazorati.shared") ?? .standard
        var reasons = defaults.stringArray(forKey: "activeScreenTimeReasons") ?? []
        if !reasons.contains(reason) { reasons.append(reason) }
        defaults.set(reasons, forKey: "activeScreenTimeReasons")
    }

    private func removeActiveReason(_ reason: String) {
        let defaults = UserDefaults(suiteName: "group.uz.oilanazorati.shared") ?? .standard
        let reasons = (defaults.stringArray(forKey: "activeScreenTimeReasons") ?? []).filter { $0 != reason }
        defaults.set(reasons, forKey: "activeScreenTimeReasons")
    }

    private func notify(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: UUID().uuidString,
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}
