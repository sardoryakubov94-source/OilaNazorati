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
            applySelectedShields()
            notify(title: "Nazorat jadvali boshlandi", body: "Belgilangan vaqt cheklovlari faollashtirildi.")
        }
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        if activity.rawValue.contains("school") || activity.rawValue.contains("sleep") {
            clearSelectedShields()
            notify(title: "Nazorat jadvali tugadi", body: "Vaqt cheklovi yakunlandi.")
        }
    }

    override func eventDidReachThreshold(
        _ event: DeviceActivityEvent.Name,
        activity: DeviceActivityName
    ) {
        super.eventDidReachThreshold(event, activity: activity)
        guard event == DeviceActivityEvent.Name("daily_limit") else { return }
        applySelectedShields()
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

    private func applySelectedShields() {
        let defaults = UserDefaults(suiteName: "group.uz.oilanazorati.shared") ?? .standard
        guard let data = defaults.data(forKey: "familyActivitySelection"),
              let selection = try? PropertyListDecoder().decode(FamilyActivitySelection.self, from: data) else { return }
        store.shield.applications = selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
        store.shield.applicationCategories = selection.categoryTokens.isEmpty ? nil : .specific(selection.categoryTokens)
        store.webContent.blockedByFilter = selection.webDomainTokens.isEmpty ? nil : .specific(selection.webDomainTokens)
    }

    private func clearSelectedShields() {
        store.shield.applications = nil
        store.shield.applicationCategories = nil
        store.webContent.blockedByFilter = nil
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
