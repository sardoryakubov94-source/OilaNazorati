import Foundation
import CoreLocation
import FirebaseFirestore
import Combine

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
final class LocationSync: NSObject, CLLocationManagerDelegate, ObservableObject {
    static let shared = LocationSync()

    private let manager = CLLocationManager()
    @Published private(set) var authorizationStatus: CLAuthorizationStatus = .notDetermined
    private var familyCode: String?
    private var childUid: String?
    private var lastSentAt: Date = .distantPast
    private let minInterval: TimeInterval = 120 // batareya tejash uchun — 2 daqiqadan tezroq yubormaydi

    private override init() {
        super.init()
        manager.delegate = self
        authorizationStatus = manager.authorizationStatus
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        manager.distanceFilter = 100 // metr
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false
    }

    func start(familyCode: String, childUid: String) {
        self.familyCode = familyCode
        self.childUid = childUid
        manager.requestAlwaysAuthorization()
        authorizationStatus = manager.authorizationStatus
        guard manager.authorizationStatus == .authorizedAlways ||
              manager.authorizationStatus == .authorizedWhenInUse else { return }
        manager.startUpdatingLocation()
        // Background updates still depend on the user's system permission and iOS policy.
        if manager.authorizationStatus == .authorizedAlways {
            manager.startMonitoringSignificantLocationChanges()
        }
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        familyCode = nil
        childUid = nil
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationStatus = manager.authorizationStatus
        guard familyCode != nil, childUid != nil else { return }
        if manager.authorizationStatus == .authorizedAlways ||
           manager.authorizationStatus == .authorizedWhenInUse {
            manager.startUpdatingLocation()
            if manager.authorizationStatus == .authorizedAlways {
                manager.startMonitoringSignificantLocationChanges()
            }
        }
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
