package uz.oilanazorati.parentcontrol.model

/** "Bizga yozing" orqali yuborilgan xabar. */
data class SupportMessage(
    val fromUid: String = "",
    val fromEmail: String = "",
    val matn: String = "",
    val aloqaRaqami: String = "",
    val holati: String = "kutilmoqda", // kutilmoqda | javob_berildi
    val createdAtMs: Long = 0L,
    val adminJavobi: String = "",
    val javobVaqtiMs: Long = 0L
)

/** Premium (kengaytirilgan funksiyalar) uchun so'rov. */
data class PremiumRequest(
    val fromUid: String = "",
    val fromEmail: String = "",
    val holati: String = "kutilmoqda", // kutilmoqda | tolandi | rad_etildi
    val tolovIzohi: String = "",
    val tolovSkrinshotiBase64: String = "",
    val createdAtMs: Long = 0L,
    val halQilinganMs: Long = 0L
)

/** Admin tomonidan qo'shilgan to'lov kartasi (UZCARD/HUMO va h.k.). */
data class AdminCard(
    val id: String = "",
    val turi: String = "", // masalan "UZCARD", "HUMO"
    val raqam: String = "",
    val egasi: String = ""
)
