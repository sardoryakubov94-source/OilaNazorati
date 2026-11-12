import SwiftUI
import FirebaseAuth
import FirebaseFirestore

/// Android ilovasidagi "Bu farzandimning telefoni" bosqichining iOS versiyasi.
/// Xuddi shu Firestore sxemasiga yozadi: families/{code}/children/{childUid}
/// — shuning uchun Android'dagi Ota-ona paneli hech qanday o'zgarishsiz bu
/// farzandni ham ro'yxatda ko'radi.
struct LinkView: View {
    @AppStorage("family_code") private var savedFamilyCode: String = ""
    @AppStorage("child_uid") private var savedChildUid: String = ""

    @State private var code: String = ""
    @State private var name: String = ""
    @State private var isLinking = false
    @State private var errorText: String?

    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                Image(systemName: "shield.lefthalf.filled")
                    .font(.system(size: 56))
                    .foregroundColor(.teal)

                Text("Oila kodi bilan bog'lash")
                    .font(.title2).bold()

                Text("Ota-ona ilovasida (Android) yaratilgan Oila kodini va farzand ismini kiriting.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                TextField("Farzand ismi", text: $name)
                    .textFieldStyle(.roundedBorder)
                    .padding(.horizontal)

                TextField("Oila kodi (masalan: 38EEJK)", text: $code)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .padding(.horizontal)

                if let errorText {
                    Text(errorText).foregroundColor(.red).font(.footnote)
                }

                Button(action: link) {
                    if isLinking {
                        ProgressView()
                    } else {
                        Text("Bog'lash").bold().frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(code.trimmingCharacters(in: .whitespaces).count != 6 || name.trimmingCharacters(in: .whitespaces).isEmpty || isLinking)
                .padding(.horizontal)

                Spacer()
            }
            .padding(.top, 60)
        }
    }

    private func link() {
        isLinking = true
        errorText = nil
        let normalizedCode = code.trimmingCharacters(in: .whitespaces).uppercased()
        let childName = name.trimmingCharacters(in: .whitespaces)

        Auth.auth().signInAnonymously { result, error in
            if let error {
                isLinking = false
                errorText = "Kirishda xato: \(error.localizedDescription)"
                return
            }
            guard let uid = result?.user.uid else {
                isLinking = false
                errorText = "Foydalanuvchi identifikatori olinmadi"
                return
            }

            let db = Firestore.firestore()
            let childDoc = db.collection("families").document(normalizedCode)
                .collection("children").document(uid)

            childDoc.setData([
                "nomi": childName,
                "childUid": uid,
                "platform": "ios",
                "childPlatform": "ios",
                "deviceRole": "child",
                "capabilitySchemaVersion": 1,
                "supportedCapabilities": DeviceCapabilityMatrix.capabilities(for: .ios).map(\.rawValue).sorted(),
                "yaratilganMs": Int64(Date().timeIntervalSince1970 * 1000)
            ], merge: true) { error in
                isLinking = false
                if let error {
                    errorText = "Saqlashda xato: \(error.localizedDescription)"
                    return
                }
                savedFamilyCode = normalizedCode
                savedChildUid = uid
                UserDefaults.standard.set(DeviceRole.child.rawValue, forKey: "device_role")
            }
        }
    }
}
