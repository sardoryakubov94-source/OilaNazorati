package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.graphics.Color
import android.text.util.Linkify
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** One-time legal notice shown to a parent before Google sign-in / family creation. */
object ParentLegalNoticeGate {
    const val VERSION = "2026-11-14-parent-v2"
    private const val PREFS = "oila_nazorati"

    fun showIfNeeded(activity: AppCompatActivity, onAccepted: () -> Unit) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("parent_legal_notice_version", null) == VERSION) {
            onAccepted()
            return
        }

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        val notice = TextView(activity).apply {
            text = """
BULUT SERVIS (OILANAZORATI) — OTA-ONA UCHUN HUQUQIY ESLATMA

Ilovadan foydalanishdan oldin quyidagi ma’lumotlar bilan tanishing. Ushbu eslatma ota-ona paneliga birinchi kirishda, Google hisobiga kirish va oila kodi yaratishdan oldin ko‘rsatiladi.

1. OTA-ONANING HUQUQ VA MAJBURIYATLARI
O‘zbekiston Respublikasi Oila kodeksining 73-moddasi ota-onaning bolani tarbiyalash va uning kamoloti haqida g‘amxo‘rlik qilish huquq hamda majburiyatlarini belgilaydi.
74-modda ota-onaning bolaning qonuniy vakili sifatida uning huquq va manfaatlarini himoya qilishini nazarda tutadi.
75-modda ota-onalik huquqi bolaning manfaatlariga zid amalga oshirilmasligi, bolaning jismoniy va ruhiy sog‘lig‘i hamda kamolotiga zarar yetkazmasligi kerakligini belgilaydi.

2. SHAXSGA DOIR MA’LUMOTLAR
“Shaxsga doir ma’lumotlar to‘g‘risida”gi Qonun talablariga muvofiq, bolaning shaxsga doir ma’lumotlarini yig‘ish va ularga ishlov berish qonuniy asosda, belgilangan maqsad doirasida amalga oshirilishi kerak. Voyaga yetmagan shaxs nomidan rozilik masalasi uning ota-onasi yoki qonuniy vakili tomonidan qonunchilik talablariga muvofiq hal qilinadi.

3. SHAXSIY HAYOT VA JAVOBGARLIK
MJtKning 46¹-moddasi shaxsiy yoki oilaviy sirga oid ma’lumotlarni qonunga xilof ravishda yig‘ish yoki tarqatish bilan bog‘liq javobgarlikni, 46²-moddasi esa shaxsga doir ma’lumotlar to‘g‘risidagi qonunchilikni buzganlik uchun javobgarlikni nazarda tutadi.
Ilovadan ta’qib qilish, noqonuniy kuzatish, ma’lumotlarni ruxsatsiz tarqatish yoki boshqa noqonuniy maqsadlarda foydalanish mumkin emas.

4. FUNKSIYALAR VA CHEKLOVLAR
Kuzatuv imkoniyatlari qurilma turi, operatsion tizim va foydalanuvchi bergan ruxsatlarga bog‘liq. Android va iOS funksiyalari bir xil emas. Ota-ona nazorat maqsadi va ishlatiladigan funksiyalarni bolaga yoshiga mos tarzda tushuntirishi lozim.

5. MA’LUMOTLAR VA ROZILIK
Ma’lumotlarni qayta ishlash, saqlash va o‘chirish tartibi ilovaning Maxfiylik siyosatida bayon etilgan. Rozilikni chaqirib olish yoki funksiyalarni o‘chirish masalalari amaldagi qonunchilik va xizmat ko‘rsatish shartlariga muvofiq amalga oshiriladi.

Ushbu eslatma qonun matnining to‘liq nusxasi emas, balki foydalanuvchiga mo‘ljallangan qisqacha izohdir. Batafsil ma’lumot uchun ilovaning Foydalanish shartlari va Maxfiylik siyosatini o‘qing.
            """.trimIndent()
            textSize = 13f
            setTextColor(Color.BLACK)
            setLineSpacing(dp(3).toFloat(), 1.04f)
        }
        content.addView(notice, LinearLayout.LayoutParams(-1, -2))
        val legalLinks = TextView(activity).apply {
            autoLinkMask = Linkify.WEB_URLS
            linksClickable = true
            text = "Foydalanish shartnomasi: https://oilanazorati.uz/terms.html\nMaxfiylik siyosati: https://oilanazorati.uz/privacy.html"
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(legalLinks)
        val acknowledgement = CheckBox(activity).apply {
            text = "Men ushbu huquqiy eslatmani o‘qidim, mazmunini tushundim va ilovadan qonuniy maqsadlarda foydalanishga roziman."
            textSize = 13f
            setTextColor(Color.BLACK)
        }
        content.addView(acknowledgement)
        val scroll = ScrollView(activity).apply {
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Ota-ona uchun muhim eslatma")
            .setView(scroll)
            .setCancelable(false)
            .setNegativeButton("Hozircha chiqish") { _, _ -> }
            .setPositiveButton("Qabul qilaman", null)
            .create()
        dialog.setOnShowListener {
            val accept = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            accept.isEnabled = false
            acknowledgement.setOnCheckedChangeListener { _, checked -> accept.isEnabled = checked }
            accept.setOnClickListener {
                prefs.edit()
                    .putString("parent_legal_notice_version", VERSION)
                    .putLong("parent_legal_notice_accepted_at_ms", System.currentTimeMillis())
                    .apply()
                dialog.dismiss()
                onAccepted()
            }
        }
        dialog.show()
    }
}
