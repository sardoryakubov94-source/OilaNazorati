import Foundation
import CoreLocation
import FirebaseFirestore

/// Android'dagi MonitorForegroundService.logLocation() bilan BIR XIL yozuv
/// joyiga (families/{code}/children/{childId}/locations, maydonlar: lat,
/// lng, vaqtMs) yozadi — shuning uchun Ota-ona panelidagi "So'nggi joylashuv"
/// kartasi iOS farzandi uchun ham, hech qanday Android-tomon o'zgarishsiz,
/// ishlaydi.
///
/// MUHIM CHEKLOV: iOS fon rejimida joylashuvni Android kabi "istalganda"
/// yubormaydi — Apple batareya tejash uchun buni qattiq cheklaydi. Bu yerda
/// CLLocationManager'ning "Always" ruxsati va background location rejimi
/// ishlatiladi, lekin yangilanish chastotasi tizim tomonidan boshqariladi
/// (odatda bir necha daqiqada bir, harakatlanish tezligiga qarab).
final class LocationSync: NSObject, CLLocationManagerDelegate {
    static let shared = LocationSync()

    private let manager = CLLocationManager()
    private var familyCode: String?
    private var childUid: String?
    private var lastSentAt: Date = .distantPast
    private let minInterval: TimeInterval = 120 // batareya tejash uchun — 2 daqiqadan tezroq yubormaydi

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = 100 // metr
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false
    }

    func start(familyCode: String, childUid: String) {
        self.familyCode = familyCode
        self.childUid = childUid
        manager.requestAlwaysAuthorization()
        manager.startUpdatingLocation()
        // Ilova butunlay yopilgan bo'lsa ham qurilma sezilarli joy
        // o'zgartirganda uyg'onib, qisqa vaqt yangilanish olishi uchun.
        manager.startMonitoringSignificantLocationChanges()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last, let code = familyCode, let uid = childUid else { return }
        let now = Date()
        guard now.timeIntervalSince(lastSentAt) >= minInterval else { return }
        lastSentAt = now

        let db = Firestore.firestore()
        db.collection("families").document(code).collection("children").document(uid)
            .collection("locations").addDocument(data: [
                "lat": loc.coordinate.latitude,
                "lng": loc.coordinate.longitude,
                "vaqtMs": Int64(now.timeIntervalSince1970 * 1000)
            ])
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Jim o'tamiz — keyingi urinish o'zi keladi (significant change yoki harakat).
    }
}
