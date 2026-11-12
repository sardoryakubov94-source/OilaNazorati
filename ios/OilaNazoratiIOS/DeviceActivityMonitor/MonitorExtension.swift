import DeviceActivity
import ManagedSettings
import FamilyControls

/// Add this file to a separate Device Activity Monitor Extension target in Xcode.
final class MonitorExtension: DeviceActivityMonitor {
    private let store = ManagedSettingsStore()

    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        // Schedule-based school/sleep shielding can be applied here.
    }

    override func eventDidReachThreshold(_ event: DeviceActivityEvent.Name,
                                        activity: DeviceActivityName) {
        super.eventDidReachThreshold(event, activity: activity)
        // Threshold reached: apply shielding. Parent notification delivery is
        // not guaranteed from this extension; use an approved notification flow.
        store.shield.applicationCategories = .all()
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        store.clearAllSettings()
    }
}
