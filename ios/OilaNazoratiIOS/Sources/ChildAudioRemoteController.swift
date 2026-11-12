import FirebaseFirestore
import Combine
import Foundation

/// Bola oldindan yoqib qo'ygan rozilik doirasida ota-ona so'rovini qabul qiladi.
/// Mikrofon faqat ilova faol oynada va so'rov mavjud bo'lganda ishga tushadi.
@MainActor
final class ChildAudioRemoteController: ObservableObject {
    @Published private(set) var statusText = "Masofaviy ovoz kutish rejimida"
    @Published private(set) var isPublishing = false

    private var requestListener: ListenerRegistration?
    private var requestRef: DocumentReference?
    private let voiceSession = AgoraVoiceSession()
    private var handledRequestID: String?

    func startListening(familyCode: String, childUid: String, consent: ChildAudioConsent) {
        let ref = Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .collection("mic_requests").document("current")
        requestRef = ref
        requestListener?.remove()
        requestListener = ref.addSnapshotListener { [weak self] snapshot, error in
            guard let self else { return }
            Task { @MainActor in
                if let error {
                    self.statusText = "Ovoz so‘rovini tekshirib bo‘lmadi"
                    return
                }
                guard let data = snapshot?.data(),
                      let status = data["status"] as? String else { return }
                let requestID = data["requestId"] as? String ?? ""
                let channel = data["channelName"] as? String ?? ""

                if status == "requested", !requestID.isEmpty, !channel.isEmpty {
                    let expiresAtMs = (data["expiresAtMs"] as? NSNumber)?.int64Value ?? 0
                    if expiresAtMs > 0 && expiresAtMs < Int64(Date().timeIntervalSince1970 * 1000) {
                        self.statusText = "Ovoz so‘rovining muddati tugagan"
                        try? await ref.updateData(["status": "expired"])
                        return
                    }
                    guard consent.isEnabled, consent.hasSystemPermission else {
                        self.statusText = "Masofaviy ovoz uchun bola qurilmasida rozilik yoqilmagan"
                        try? await ref.updateData([
                            "status": "consent_not_enabled",
                            "childUpdatedAtMs": Int64(Date().timeIntervalSince1970 * 1000)
                        ])
                        return
                    }
                    guard self.handledRequestID != requestID else { return }
                    self.handledRequestID = requestID
                    self.statusText = "Ota-ona so‘roviga ulanmoqda…"
                    self.voiceSession.join(channel: channel, role: "publisher", familyCode: familyCode, childUid: childUid, requestID: requestID) { result in
                        switch result {
                        case .success:
                            self.isPublishing = true
                            self.statusText = "Jonli ovoz uzatilmoqda"
                            Task {
                                try? await ref.updateData([
                                    "status": "active",
                                    "childUpdatedAtMs": Int64(Date().timeIntervalSince1970 * 1000)
                                ])
                            }
                        case .failure(let error):
                            self.isPublishing = false
                            self.statusText = error.localizedDescription
                            self.handledRequestID = nil
                            Task {
                                try? await ref.updateData([
                                    "status": "failed",
                                    "error": error.localizedDescription,
                                    "childUpdatedAtMs": Int64(Date().timeIntervalSince1970 * 1000)
                                ])
                            }
                        }
                    }
                } else if ["stopped", "cancelled", "expired"].contains(status) {
                    self.stopPublishing(status: "Ovoz uzatish to‘xtatildi", updateRemote: false)
                }
            }
        }
    }

    func stopPublishing(status: String = "Ovoz uzatish to‘xtatildi", updateRemote: Bool = true) {
        voiceSession.leave()
        isPublishing = false
        statusText = status
        handledRequestID = nil
        if updateRemote, let requestRef {
            Task {
                try? await requestRef.updateData([
                    "status": "stopped",
                    "childUpdatedAtMs": Int64(Date().timeIntervalSince1970 * 1000)
                ])
            }
        }
    }

    func stopListening() {
        requestListener?.remove()
        requestListener = nil
        stopPublishing(status: "Ilova faol emas — ovoz uzatish to‘xtatildi")
    }
}
