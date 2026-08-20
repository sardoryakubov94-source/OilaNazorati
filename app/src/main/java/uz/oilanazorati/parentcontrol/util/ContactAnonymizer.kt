package uz.oilanazorati.parentcontrol.util

import android.content.Context
import android.graphics.Color
import java.security.MessageDigest
import java.util.UUID

/**
 * MUHIM PRINSIP: bu klass raqamni HECH QACHON ochiq holda saqlamaydi yoki
 * uzatmaydi (Firebase'ga ham, logga ham). U faqat bitta narsa qiladi:
 * raqamni bitta yo'nalishli (bir tomonlama) hash'ga aylantiradi, shunda:
 *
 *   - bitta raqamdan kelgan/ketgan barcha qo'ng'iroq va SMS'lar doim
 *     BITTA xash (demak — bitta rang) oladi,
 *   - lekin xash'dan asl raqamni qayta tiklab bo'lmaydi (SHA-256 + har bir
 *     qurilmaga xos "tuz" (salt) tufayli — rainbow-table hujumi ham
 *     ishlamaydi, chunki tuz hech qayerga yuborilmaydi, faqat qurilmada
 *     turadi).
 *
 * Natijada ota-ona panelida: "bugun 5 marta, jami 42 daqiqa — 🟦 ko'k
 * kontakt bilan" ko'rinadi, lekin qaysi raqam ekani ko'rinmaydi.
 */
object ContactAnonymizer {

    private const val PREFS = "oila_nazorati_salt"
    private const val KEY_SALT = "device_salt_v1"

    private var cachedSalt: String? = null

    /** Har bir qurilmada bir marta generatsiya qilinadigan, hech qayerga yuborilmaydigan tuz. */
    private fun salt(context: Context): String {
        cachedSalt?.let { return it }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var s = prefs.getString(KEY_SALT, null)
        if (s == null) {
            s = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_SALT, s).apply()
        }
        cachedSalt = s
        return s
    }

    /** Raqamni taqqoslash uchun me'yorlashtiradi (bo'shliq, tire, +998 vs 998 farqi bo'lmasin). */
    private fun normalize(rawNumber: String): String {
        var n = rawNumber.filter { it.isDigit() || it == '+' }
        n = n.removePrefix("+")
        // 998901234567 va 901234567 bir xil raqam sifatida hisoblansin
        if (n.length > 9) n = n.takeLast(9)
        return n
    }

    /**
     * Raqamdan 10 belgili anonim identifikator hosil qiladi. Bir xil raqam —
     * doim bir xil natija, lekin natijadan raqamni tiklab bo'lmaydi.
     * Noma'lum/yashirin raqam (masalan xususiy chaqiruv) uchun bitta umumiy
     * "noma'lum" guruhga tushadi.
     */
    fun hash(context: Context, rawNumber: String?): String {
        if (rawNumber.isNullOrBlank()) return "noma_lum"
        val normalized = normalize(rawNumber)
        val salted = normalized + salt(context)
        val digest = MessageDigest.getInstance("SHA-256").digest(salted.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(10)
    }

    /**
     * Xash asosida barqaror (deterministik), bir-biridan ajratilgan rang
     * beradi — HSL fazosida tanlangani uchun ranglar bir-biriga o'xshab
     * qolmaydi va yetarlicha to'yingan/o'qish oson bo'ladi.
     */
    fun colorFor(hashValue: String): Int {
        if (hashValue == "noma_lum") return Color.GRAY
        // xashning birinchi 8 hex belgisidan barqaror int hosil qilamiz
        val seed = hashValue.take(8).toLong(16)
        val hue = (seed % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.85f))
    }
}
