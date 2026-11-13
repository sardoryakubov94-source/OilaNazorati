import SwiftUI

struct IOSParentLegalNoticeView: View {
    static let version = "2026-11-13-parent-v1"
    let onAccept: () -> Void
    @State private var acknowledged = false

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("BULUT SERVIS (OILANAZORATI) — OTA-ONA UCHUN HUQUQIY ESLATMA")
                            .font(.title3.bold())
                        Text("Ilovadan foydalanishdan oldin quyidagi ma’lumotlar bilan tanishing. Ushbu eslatma ota-ona paneliga birinchi kirishda, Google hisobiga kirish va oila kodi yaratishdan oldin ko‘rsatiladi.")
                            .font(.subheadline)
                        section("1. OTA-ONANING HUQUQ VA MAJBURIYATLARI", """
O‘zbekiston Respublikasi Oila kodeksining 73-moddasi ota-onaning bolani tarbiyalash va uning kamoloti haqida g‘amxo‘rlik qilish huquq hamda majburiyatlarini belgilaydi.
74-modda ota-onaning bolaning qonuniy vakili sifatida uning huquq va manfaatlarini himoya qilishini nazarda tutadi.
75-modda ota-onalik huquqi bolaning manfaatlariga zid amalga oshirilmasligi, bolaning jismoniy va ruhiy sog‘lig‘i hamda kamolotiga zarar yetkazmasligi kerakligini belgilaydi.
""")
                        section("2. SHAXSGA DOIR MA’LUMOTLAR", """
“Shaxsga doir ma’lumotlar to‘g‘risida”gi Qonun talablariga muvofiq, bolaning shaxsga doir ma’lumotlarini yig‘ish va ularga ishlov berish qonuniy asosda, belgilangan maqsad doirasida amalga oshirilishi kerak. Voyaga yetmagan shaxs nomidan rozilik masalasi uning ota-onasi yoki qonuniy vakili tomonidan qonunchilik talablariga muvofiq hal qilinadi.
""")
                        section("3. SHAXSIY HAYOT VA JAVOBGARLIK", """
MJtKning 46¹-moddasi shaxsiy yoki oilaviy sirga oid ma’lumotlarni qonunga xilof ravishda yig‘ish yoki tarqatish bilan bog‘liq javobgarlikni, 46²-moddasi esa shaxsga doir ma’lumotlar to‘g‘risidagi qonunchilikni buzganlik uchun javobgarlikni nazarda tutadi.
Ilovadan ta’qib qilish, noqonuniy kuzatish, ma’lumotlarni ruxsatsiz tarqatish yoki boshqa noqonuniy maqsadlarda foydalanish mumkin emas.
""")
                        section("4. FUNKSIYALAR VA CHEKLOVLAR", """
Kuzatuv imkoniyatlari qurilma turi, operatsion tizim va foydalanuvchi bergan ruxsatlarga bog‘liq. Android va iOS funksiyalari bir xil emas. Ota-ona nazorat maqsadi va ishlatiladigan funksiyalarni bolaga yoshiga mos tarzda tushuntirishi lozim.
""")
                        section("5. MA’LUMOTLAR VA ROZILIK", """
Ma’lumotlarni qayta ishlash, saqlash va o‘chirish tartibi ilovaning Maxfiylik siyosatida bayon etilgan. Rozilikni chaqirib olish yoki funksiyalarni o‘chirish masalalari amaldagi qonunchilik va xizmat ko‘rsatish shartlariga muvofiq amalga oshiriladi.
""")
                        Link("Foydalanish shartnomasi", destination: URL(string: "https://oilanazorati.uz/terms.html")!)
                            .font(.footnote)
                        Link("Maxfiylik siyosati", destination: URL(string: "https://oilanazorati.uz/privacy.html")!)
                            .font(.footnote)
                        Text("Ushbu eslatma qonun matnining to‘liq nusxasi emas, balki foydalanuvchiga mo‘ljallangan qisqacha izohdir. Batafsil ma’lumot uchun ilovaning Foydalanish shartlari va Maxfiylik siyosatini o‘qing.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
                }
                Divider()
                VStack(spacing: 12) {
                    Toggle("Men huquqiy eslatmani o‘qidim, tushundim va ilovadan qonuniy maqsadlarda foydalanishga roziman.", isOn: $acknowledged)
                        .font(.footnote)
                    Button(action: onAccept) {
                        Text("Qabul qilaman va davom etish")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                            .padding()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(!acknowledged)
                }
                .padding()
                .background(.regularMaterial)
            }
            .navigationTitle("Huquqiy eslatma")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(true)
        }
    }

    private func section(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.headline)
            Text(body).font(.subheadline).fixedSize(horizontal: false, vertical: true)
        }
    }
}
