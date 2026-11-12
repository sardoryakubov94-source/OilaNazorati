import Foundation

/// Qurilma platformasi. "unknown" — metadata aniqlanmaganda xavfsiz, cheklangan holat.
enum DevicePlatform: String, Codable {
    case android
    case ios
    case unknown
}

/// Ilovadagi qurilma roli. Bir ilova ota-ona yoki bola qurilmasi sifatida ishlashi mumkin.
enum DeviceRole: String, Codable {
    case parent
    case child
    case unselected
}

/// Platformalararo nazorat funksiyalari.
enum MonitoringCapability: String, CaseIterable, Codable {
    case location
    case focusMode = "focus_mode"
    case appUsage = "app_usage"
    case calls
    case sms
    case contacts
    case notifications
    case screenshots
    case ambientAudio = "ambient_audio"
    case riskAlerts = "risk_alerts"
}

/// Ota-ona interfeysining vizual platformasi.
enum ParentInterfaceStyle {
    case ios
    case android
}

/// To'rtta platforma kombinatsiyasi uchun yagona siyosat natijasi.
struct CrossPlatformConfiguration {
    let parentPlatform: DevicePlatform
    let childPlatform: DevicePlatform
    let parentInterfaceStyle: ParentInterfaceStyle
    let capabilities: Set<MonitoringCapability>

    func supports(_ capability: MonitoringCapability) -> Bool {
        capabilities.contains(capability)
    }

    static func resolve(
        parentPlatform: DevicePlatform,
        childPlatform: DevicePlatform
    ) -> CrossPlatformConfiguration {
        let style: ParentInterfaceStyle = parentPlatform == .android ? .android : .ios
        return CrossPlatformConfiguration(
            parentPlatform: parentPlatform,
            childPlatform: childPlatform,
            parentInterfaceStyle: style,
            capabilities: DeviceCapabilityMatrix.capabilities(for: childPlatform)
        )
    }
}

/// Bola qurilmasining operatsion tizimi imkoniyatlari.
/// Noma'lum platformada hech qanday nazorat funksiyasi avtomatik ochilmaydi.
enum DeviceCapabilityMatrix {
    static func capabilities(
        for platform: DevicePlatform,\n        iosMajorVersion: Int = ProcessInfo.processInfo.operatingSystemVersion.majorVersion\n    ) -> Set<MonitoringCapability> {
        switch platform {
        case .ios:
            // iOS: Apple ruxsat bergan va ilovada qisman amalga oshirilgan imkoniyatlar.
            return iosMajorVersion >= 16 ? [.location, .focusMode] : [.location]
        case .android:
            // Android: mavjud Android nazorat modullari. Amaliy foydalanish
            // ota-ona autentifikatsiyasi, ruxsatlar va modul holatiga ham bog'liq.
            return Set(MonitoringCapability.allCases)
        case .unknown:
            return []
        }
    }
}

/// Firestore'dagi eski va yangi platforma maydonlarini bir xil talqin qiladi.
/// Android hozir childPlatform, iOS oldingi versiyasi platform maydonini yozgan.
enum ChildPlatformResolver {
    static func resolve(from data: [String: Any]) -> DevicePlatform {
        let raw = (data["childPlatform"] as? String)
            ?? (data["platform"] as? String)
            ?? ""
        return DevicePlatform(rawValue: raw.lowercased()) ?? .unknown
    }
}
