import SwiftUI
import FirebaseFirestore

/// Guardian authorization plus child-facing acknowledgement, shown before child setup.
struct ChildLegalConsentView: View {
    static let version = "2026-11-13-v1"

    let familyCode: String
    let childUid: String
    let onAccepted: () -> Void

    @State private var guardianAccepted = false
    @State private var childInformed = false
    @State private var childAcknowledged = false
    @State private var isSaving = false
    @State private var errorText: String?

    private let bodyText = """
OilaNazorati voyaga yetmagan farzand xavfsizligi, tarbiyasi va qonuniy manfaatlarini himoya qilishga ko‘maklashuvchi ota-ona nazorati ilovasidir.

QANDAY MA’LUMOTLAR ISHLATILISHI MUMKIN?
Qurilma turi va berilgan ruxsatlarga qarab: joylashuv, ilovalardan foydalanish statistikasi, ekran vaqti, bildirishnomalar hamda qurilma imkoniyatlariga qarab ekran uzatish yoki mikrofon funksiyalari. iOS’da Android’ga xos SMS va qo‘ng‘iroqlar jurnalini o‘qish imkoniyati mavjud emas. Har bir funksiya faqat platforma ruxsat bergan doirada ishlaydi.

MAQSAD VA CHEGARALAR
Ma’lumotlar farzand xavfsizligi va ota-ona nazorati maqsadida ishlatiladi. Ularni noqonuniy uzatish, tarqatish, ta’qib qilish yoki shaxsiy hayotga noqonuniy aralashish taqiqlanadi. Bola nazorat maqsadi va funksiyalari haqida yoshiga mos tarzda xabardor qilinishi lozim.

HUQUQIY ASOS
Oila kodeksining 73-moddasi ota-onaning ta’lim-tarbiya bo‘yicha huquq va majburiyatlarini, 74-moddasi bolaning huquq va manfaatlarini himoya qilish hamda ota-onaning qonuniy vakilligini, 75-moddasi esa ota-onalik huquqi bolaning eng ustun manfaatlariga zid amalga oshirilmasligini belgilaydi.
“Shaxsga doir ma’lumotlar to‘g‘risida”gi Qonunning 21-moddasiga ko‘ra, voyaga yetmagan shaxs ma’lumotlariga ishlov berish uchun ota-onasi yoki qonuniy vakilining yozma, shu jumladan elektron hujjat shaklidagi roziligi olinadi.
MJtK 46¹-moddasi shaxsiy yoki oilaviy sirga oid ma’lumotlarni roziliksiz qonunga xilof yig‘ish yoki tarqatish, 46²-moddasi esa shaxsga doir ma’lumotlar to‘g‘risidagi qonunchilikni buzish uchun javobgarlikni nazarda tutadi.

ROZILIKNI BEKOR QILISH
Qurilma egasi yoki qonuniy vakil tegishli ruxsatlarni qurilma sozlamalarida o‘chirishi, ulanishni to‘xtatishi va shaxsga doir ma’lumotlarga ishlov berishga berilgan rozilikni qonunda belgilangan tartibda chaqirib olishi mumkin. Ma’lumotlarni saqlash yoki yo‘q qilish qonuniy majburiyatlar va amaldagi saqlash asoslariga muvofiq hal qilinadi.

MUHIM
Ushbu tasdiq ota-onaning qonuniy vakilligini avtomatik tekshirmaydi va qonunda belgilangan majburiyatlarni bekor qilmaydi. OilaNazorati voyaga yetgan shaxslarni kuzatish uchun mo‘ljallanmagan.
"""

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Label("Farzand qurilmasi uchun elektron rozilik", systemImage: "hand.raised.square")
                            .font(.title2.bold())
                        Text(bodyText)
                            .font(.footnote)
                            .foregroundStyle(.primary)
                            .fixedSize(horizontal: false, vertical: true)
                        Divider()
                        Toggle("Men bolaning ota-onasi yoki qonuniy vakiliman va ushbu ma’lumotlarga ishlov berishga rozilik beraman.", isOn: $guardianAccepted)
                        Toggle("Nazorat maqsadi va funksiyalari bolaga yoshiga mos tarzda tushuntirildi.", isOn: $childInformed)
                        Toggle("Bola ushbu ogohlantirish bilan tanishdi.", isOn: $childAcknowledged)
                        if let errorText {
                            Text(errorText).font(.footnote).foregroundStyle(.red)
                        }
                    }
                    .padding()
                }
                Button(action: accept) {
                    if isSaving {
                        ProgressView()
                    } else {
                        Text("Rozilik beraman va davom etish")
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(!guardianAccepted || !childInformed || !childAcknowledged || isSaving)
                .padding(.horizontal)
                .padding(.bottom, 8)
            }
            .navigationTitle("Elektron rozilik")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func accept() {
        guard guardianAccepted && childInformed && childAcknowledged else { return }
        isSaving = true
        errorText = nil
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let defaults = UserDefaults.standard
        let record: [String: Any] = [
            "legalConsentVersion": Self.version,
            "guardianLegalConsent": true,
            "guardianLegalConsentAtMs": now,
            "childInformed": true,
            "childNoticeAcknowledged": true,
            "childNoticeAcknowledgedAtMs": now
        ]

        guard !familyCode.isEmpty, !childUid.isEmpty else {
            defaults.set(Self.version, forKey: "child_legal_consent_version")
            defaults.set(now, forKey: "guardian_legal_consent_at_ms")
            defaults.set(now, forKey: "child_notice_acknowledged_at_ms")
            isSaving = false
            onAccepted()
            return
        }

        Firestore.firestore().collection("families").document(familyCode)
            .collection("children").document(childUid)
            .setData(record, merge: true) { error in
                DispatchQueue.main.async {
                    isSaving = false
                    if let error {
                        errorText = "Rozilik qaydini saqlab bo‘lmadi: \(error.localizedDescription). Internetni tekshirib qayta urinib ko‘ring."
                        return
                    }
                    defaults.set(Self.version, forKey: "child_legal_consent_version")
                    defaults.set(now, forKey: "guardian_legal_consent_at_ms")
                    defaults.set(now, forKey: "child_notice_acknowledged_at_ms")
                    onAccepted()
                }
            }
    }
}
