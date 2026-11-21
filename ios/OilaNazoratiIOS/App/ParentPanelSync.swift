import Foundation
import FirebaseFirestore

final class ParentPanelSync {
    static let shared = ParentPanelSync()
    private let db = Firestore.firestore()
    private init() {}

    /// Replace familyId/childId with values from the authenticated app's existing account link.
    /// Security rules must ensure only the linked child device can write this document.
    func sendIOSStatus(authorizationStatus: String, locationSharingEnabled: Bool) async throws {
        guard let familyId = UserDefaults.standard.string(forKey: "linkedFamilyId"),
              let childId = UserDefaults.standard.string(forKey: "linkedChildId") else {
            throw SyncError.missingFamilyLink
        }
        let ref = db.collection("families").document(familyId)
            .collection("children").document(childId)
            .collection("iosStatus").document("current")
        try await ref.setData([
            "devicePlatform": "ios",
            "authorizationStatus": authorizationStatus,
            "locationSharingEnabled": locationSharingEnabled,
            "lastSeenAt": FieldValue.serverTimestamp()
        ], merge: true)
    }

    enum SyncError: LocalizedError {
        case missingFamilyLink
        var errorDescription: String? { "Ota-ona/bola Firebase bog‘lanishi hali sozlanmagan." }
    }
}
