import Foundation
import FirebaseFirestore
import FamilyControls
import ManagedSettings

/// Android'dagi "Kontrol rejim" bilan BIR XIL hujjatni tinglaydi:
/// families/{code}/children/{childId}/focus_mode_settings/current
/// (maydon: enabled: Bool). Ota-ona Android ilovasidagi mavjud tugmani
/// bossa — bu yerda ham, hech qanday qo'shimcha ish qilmasdan, iPhone'da
/// ilovalar bloklanadi/ochiladi.
///
/// MUHIM CHEKLOV: Android'dagi kabi "faqat tanlangan dasturlarni ochiq
/// qoldirish" (ro'yxatdan bitta-bitta belgilash) iOS'da ishlamaydi, chunki
/// Apple uchinchi tomon ilovaga boshqa ilovalarning NOMINI/paket
/// identifikatorini ko'rishga ruxsat bermaydi. Shu sabab bu yerda FAQAT
/// "tezroq" rejim ishlaydi: yoqilganda deyarli BARCHA ilovalar (ijtimoiy
/// tarmoq, o'yin, messenjer...) bloklanadi. Qo'ng'iroq va Favqulodda
/// chaqiruv — Apple tizim darajasida ularni bloklashga umuman yo'l
/// qo'ymaydi, shuning uchun alohida "ruxsat etilgan" deb belgilash shart
/// emas.
@available(iOS 16.0, *)
final class FocusModeSync {
    static let shared = FocusModeSync()
    private let store = ManagedSettingsStore()
    private var listener: ListenerRegistration?

    /// Ilova birinchi marta ishga tushganda, oila kodiga bog'langandan keyin
    /// bir marta chaqiriladi. Foydalanuvchidan (yoki ota-onadan, shu
    /// qurilmada) Screen Time ruxsatini so'raydi — bu SO'ROVNI faqat
    /// qurilmaning o'zida, bir marta berish mumkin, masofadan emas.
    func requestAuthorizationIfNeeded() {
        Task {
            do {
                try await AuthorizationCenter.shared.requestAuthorization(for: .child)
            } catch {
                // Ruxsat berilmasa, bloklash funksiyasi shunchaki ishlamaydi —
                // joylashuv kuzatuvi bunga bog'liq emas, u baribir ishlayveradi.
            }
        }
    }

    func start(familyCode: String, childUid: String) {
        requestAuthorizationIfNeeded()
        let doc = Firestore.firestore()
            .collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("focus_mode_settings").document("current")

        listener = doc.addSnapshotListener { [weak self] snapshot, _ in
            let enabled = (snapshot?.data()?["enabled"] as? Bool) ?? false
            self?.apply(enabled: enabled)
        }
    }

    private func apply(enabled: Bool) {
        if enabled {
            // Deyarli barcha ilova toifalarini bloklaydi. Telefon, SMS va
            // Sozlamalar kabi tizim ilovalarini Apple o'zi himoyalagan —
            // ular har qanday holatda ham ishlab turadi.
            store.shield.applicationCategories = .all()
            store.shield.webDomainCategories = .all()
        } else {
            store.shield.applicationCategories = nil
            store.shield.webDomainCategories = nil
        }
    }

    func stop() {
        listener?.remove()
    }
}
