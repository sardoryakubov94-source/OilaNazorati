import AVFoundation
import Combine
import Foundation

/// Bolaning mikrofon bo'yicha doimiy tanlovi va iOS ruxsat holatini boshqaradi.
/// Bu klass mikrofonni o'zi ishga tushirmaydi va audio yozmaydi.
@MainActor
final class ChildAudioConsent: ObservableObject {
    @Published private(set) var isEnabled: Bool
    @Published private(set) var permissionText: String = "Tekshirilmoqda"

    private let consentKey = "child_live_audio_consent"

    init() {
        isEnabled = UserDefaults.standard.bool(forKey: consentKey)
        refreshPermission()
    }

    var hasSystemPermission: Bool {
        AVAudioSession.sharedInstance().recordPermission == .granted
    }

    func refreshPermission() {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            permissionText = "Mikrofon ruxsati berilgan"
        case .denied:
            permissionText = "Mikrofon ruxsati o‘chirilgan — iPhone sozlamalarini tekshiring"
        case .undetermined:
            permissionText = "Hali so‘ralmagan"
        @unknown default:
            permissionText = "Noma’lum"
        }

        if !hasSystemPermission && isEnabled {
            isEnabled = false
            UserDefaults.standard.set(false, forKey: consentKey)
        }
    }

    /// Birinchi marta iOS tizim ruxsatini so'raydi. Keyingi chaqiriqlarda
    /// iOS allaqachon saqlagan natija ishlatiladi; tizim oynasi qayta chiqarilmaydi.
    func enable(completion: @escaping (Bool) -> Void) {
        let session = AVAudioSession.sharedInstance()
        switch session.recordPermission {
        case .granted:
            saveConsent(true)
            completion(true)
        case .denied:
            refreshPermission()
            completion(false)
        case .undetermined:
            session.requestRecordPermission { [weak self] granted in
                Task { @MainActor in
                    self?.saveConsent(granted)
                    self?.refreshPermission()
                    completion(granted)
                }
            }
        @unknown default:
            completion(false)
        }
    }

    func disable() {
        saveConsent(false)
    }

    private func saveConsent(_ enabled: Bool) {
        isEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: consentKey)
    }
}
