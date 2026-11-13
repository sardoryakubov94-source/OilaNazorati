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

/**
 * First child-device disclosure. The guardian's electronic authorization and
 * the child's acknowledgement are stored locally with a version and times.
 */
object ChildLegalConsentGate {
    const val VERSION = "2026-11-13-v1"
    private const val PREFS = "oila_nazorati"

    fun showIfNeeded(activity: AppCompatActivity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("child_legal_consent_version", null) == VERSION) return

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        val explanation = TextView(activity).apply {
            text = """
Bulut servis (OilaNazorati) voyaga yetmagan farzand xavfsizligi, tarbiyasi va qonuniy manfaatlarini himoya qilishga ko‘maklashuvchi ota-ona nazorati ilovasidir.

QANDAY MA’LUMOTLAR ISHLATILISHI MUMKIN?
Qurilma turi va berilgan ruxsatlarga qarab: joylashuv, qo‘ng‘iroqlar tarixi, SMS, kontaktlar, ilovalardan foydalanish statistikasi, bildirishnomalar va ekran tasvirlari. Mikrofon yoki jonli ekran funksiyasi faqat tegishli qurilma imkoniyati, alohida sozlama/ruxsat va ilovada ko‘rsatilgan ishga tushirish tartibi mavjud bo‘lganda ishlaydi. Barcha funksiyalar har bir qurilmada bir xil emas.

MAQSAD VA CHEGARALAR
Ma’lumotlar farzand xavfsizligi va ota-ona nazorati maqsadida ishlatilishi kerak. Ularni boshqa shaxsga noqonuniy uzatish, tarqatish, ta’qib qilish yoki shaxsiy hayotga noqonuniy aralashish taqiqlanadi. Bola nazorat maqsadi va funksiyalari haqida yoshiga mos tarzda xabardor qilinishi lozim.

HUQUQIY ASOS
Oila kodeksining 73-moddasi ota-onaning ta’lim-tarbiya bo‘yicha huquq va majburiyatlarini, 74-moddasi bolaning huquq va manfaatlarini himoya qilish hamda ota-onaning qonuniy vakilligini, 75-moddasi esa ota-onalik huquqi bolaning eng ustun manfaatlariga zid amalga oshirilmasligini belgilaydi.
“Shaxsga doir ma’lumotlar to‘g‘risida”gi Qonunning 21-moddasiga ko‘ra, voyaga yetmagan shaxs ma’lumotlariga ishlov berish uchun ota-onasi yoki qonuniy vakilining yozma, shu jumladan elektron hujjat shaklidagi roziligi olinadi.
MJtK 46¹-moddasi shaxsiy yoki oilaviy sirga oid ma’lumotlarni roziliksiz qonunga xilof yig‘ish yoki tarqatish, 46²-moddasi esa shaxsga doir ma’lumotlar to‘g‘risidagi qonunchilikni buzish uchun javobgarlikni nazarda tutadi.

ROZILIKNI BEKOR QILISH
Qurilma egasi yoki qonuniy vakil tegishli ruxsatlarni qurilma sozlamalarida o‘chirishi, ulanishni to‘xtatishi va shaxsga doir ma’lumotlarga ishlov berishga berilgan rozilikni qonunda belgilangan tartibda chaqirib olishi mumkin. Rozilik chaqirib olinganidan keyin ma’lumotlarni saqlash yoki yo‘q qilish qonuniy majburiyatlar va amaldagi saqlash asoslariga muvofiq hal qilinadi.

MUHIM
Ushbu tasdiq ota-onaning qonuniy vakilligini avtomatik tekshirmaydi va qonunda belgilangan majburiyatlarni bekor qilmaydi. Bulut servis (OilaNazorati) voyaga yetgan shaxslarni kuzatish uchun mo‘ljallanmagan.
            """.trimIndent()
            textSize = 13f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(3).toFloat(), 1.05f)
        }
        content.addView(explanation, LinearLayout.LayoutParams(-1, -2))
        val legalLinks = TextView(activity).apply {
            autoLinkMask = Linkify.WEB_URLS
            linksClickable = true
            text = "Foydalanish shartnomasi: https://oilanazorati.uz/terms.html\nMaxfiylik siyosati: https://oilanazorati.uz/privacy.html"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(legalLinks)

        val guardian = CheckBox(activity).apply {
            text = "Men bolaning ota-onasi yoki qonuniy vakiliman va ushbu ma’lumotlarga ishlov berishga rozilik beraman."
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        val childInformed = CheckBox(activity).apply {
            text = "Nazorat maqsadi va funksiyalari bolaga yoshiga mos tarzda tushuntirildi."
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        val childAcknowledged = CheckBox(activity).apply {
            text = "Men, farzand, nazorat maqsadi va funksiyalari haqida xabardor qilindim hamda ushbu ogohlantirish bilan tanishdim."
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        content.addView(guardian)
        content.addView(childInformed)
        content.addView(childAcknowledged)

        val scroll = ScrollView(activity).apply {
            addView(content, ViewGroup.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Farzand qurilmasi — elektron rozilik")
            .setView(scroll)
            .setCancelable(false)
            .setNegativeButton("Rozilik bermayman") { _, _ -> activity.finish() }
            .setPositiveButton("Rozilik beraman va davom etish", null)
            .create()

        dialog.setOnShowListener {
            val accept = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            accept.isEnabled = false
            val update = {
                accept.isEnabled = guardian.isChecked && childInformed.isChecked && childAcknowledged.isChecked
            }
            guardian.setOnCheckedChangeListener { _, _ -> update() }
            childInformed.setOnCheckedChangeListener { _, _ -> update() }
            childAcknowledged.setOnCheckedChangeListener { _, _ -> update() }
            accept.setOnClickListener {
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putString("child_legal_consent_version", VERSION)
                    .putLong("guardian_legal_consent_at_ms", now)
                    .putLong("child_notice_acknowledged_at_ms", now)
                    .putBoolean("guardian_legal_consent", true)
                    .putBoolean("child_notice_acknowledged", true)
                    .apply()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
