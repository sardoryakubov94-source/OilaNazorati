package uz.oilanazorati.parentcontrol.model

/**
 * MUHIM PRINSIP: quyidagi barcha modellarda raqam, kontakt nomi yoki
 * xabar/matn mazmuni SAQLANMAYDI. Faqat: turi, vaqti, davomiyligi va
 * (ixtiyoriy) kontaktHash — bu asl raqam EMAS, balki
 * ContactAnonymizer.hash() orqali hosil qilingan, qurilmaga xos tuz
 * bilan qaytarib bo'lmaydigan qilib xashlangan identifikator. U faqat
 * "shu odam bilanmi yoki boshqasi bilanmi" farqini bilish va statistikada
 * rang bilan ajratish uchun ishlatiladi — raqamni ochib bermaydi.
 */

data class CallEvent(
    val turi: String = "",          // "kiruvchi" | "chiquvchi" | "javobsiz"
    val boshlanishMs: Long = 0L,    // epoch millis
    val tugashMs: Long = 0L,        // epoch millis (javobsiz bo'lsa 0)
    val davomiylikSoniya: Long = 0L,
    val kontaktHash: String = ""    // raqam EMAS — anonim, qaytarib bo'lmaydigan identifikator
)

data class SmsEvent(
    val turi: String = "",          // "qabul_qilingan" | "yuborilgan"
    val vaqtMs: Long = 0L,
    val kontaktHash: String = ""    // raqam EMAS — anonim, qaytarib bo'lmaydigan identifikator
)

data class AppUsageEvent(
    val ilovaNomi: String = "",     // masalan "Instagram" (paket nomidan olingan ko'rinadigan nom)
    val paketNomi: String = "",
    val boshlanishMs: Long = 0L,
    val tugashMs: Long = 0L,
    val davomiylikSoniya: Long = 0L
)

data class LocationEvent(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val vaqtMs: Long = 0L
)

/**
 * Faqat qurilmada ISM BILAN SAQLANGAN kontaktlar uchun. Bu "sir" emas —
 * oila a'zolari bir-birini ataylab kontaktga saqlaydi, shuning uchun ism
 * ko'rsatilishi mumkin. Lekin RAQAM baribir hech qachon saqlanmaydi/
 * yuborilmaydi — faqat kontaktHash (ContactAnonymizer.hash natijasi), xuddi
 * qo'ng'iroq/SMS statistikasidagi rang bilan bir xil identifikator. Shu
 * tufayli ota-ona ekranida "bu rang — Onam" deb ko'rsatish mumkin bo'ladi,
 * lekin kontaktga SAQLANMAGAN (notanish) raqamlar bu ro'yxatga umuman
 * kirmaydi va faqat rang bilan (ismsiz, raqamsiz) ko'rinishda qoladi.
 */
data class ContactMapping(
    val nomi: String = "",          // faqat qurilmada saqlangan kontakt ismi
    val kontaktHash: String = ""    // raqam EMAS — anonim identifikator (rang shundan hosil bo'ladi)
)

data class DailySummary(
    val sana: String = "",          // "2026-08-20"
    val smsYuborilgan: Int = 0,
    val smsQabulQilingan: Int = 0,
    val qongiroqKiruvchiSoni: Int = 0,
    val qongiroqKiruvchiDaqiqa: Int = 0,
    val qongiroqChiquvchiSoni: Int = 0,
    val qongiroqChiquvchiDaqiqa: Int = 0,
    val qongiroqJavobsizSoni: Int = 0
)
