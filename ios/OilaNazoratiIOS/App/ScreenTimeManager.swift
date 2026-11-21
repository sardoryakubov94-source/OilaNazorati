import Foundation
import FamilyControls
import ManagedSettings
import DeviceActivity
import SwiftUI

@MainActor
final class ScreenTimeManager: ObservableObject {
    @Published var selection = FamilyActivitySelection()
    @Published var message = "Avval Family Controls ruxsatini oling."
    @Published private(set) var authorizationLabel = "Tekshirilmagan"

    private let store = ManagedSettingsStore()
    private let center = AuthorizationCenter.shared

    func requestAuthorization() async {
        do {
            try await center.requestAuthorization(for: .child)
            authorizationLabel = String(describing: center.authorizationStatus)
            message = "Ruxsat holati: \(authorizationLabel)"
            await syncStatus()
        } catch {
            authorizationLabel = String(describing: center.authorizationStatus)
            message = "Ruxsat berilmadi: \(error.localizedDescription)"
        }
    }

    func applyOneHourLimit() {
        // Opaque tokens remain on-device; no app names are extracted or uploaded.
        store.shield.applications = selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
        store.shield.applicationCategories = selection.categoryTokens.isEmpty
            ? nil : .specific(selection.categoryTokens)
        store.shield.webDomains = selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
        message = "Tanlangan ilova/kategoriya/saytlar himoyalandi. 1 soatlik monitoring jadvali uchun DeviceActivity extension sozlanishi kerak."
    }

    func clearRestrictions() {
        store.clearAllSettings()
        message = "Cheklovlar ushbu ManagedSettingsStore’dan olib tashlandi."
    }

    func syncStatus() async {
        do {
            try await ParentPanelSync.shared.sendIOSStatus(
                authorizationStatus: String(describing: center.authorizationStatus),
                locationSharingEnabled: false
            )
            message = "Qurilma holati Firebase’ga yuborildi."
        } catch {
            message = "Firebase yuborish xatosi: \(error.localizedDescription)"
        }
    }
}
