package uz.oilanazorati.parentcontrol.model

/**
 * "Kontrol rejim" — yoqilganda farzand telefonida faqat [allowedPackages]
 * ro'yxatidagi dasturlar ochiq turadi; qolganlari ochilgan zahoti yopilib,
 * bosh ekranga qaytariladi. Ro'yxatga kiritilmagan bo'lsa ham, tizim uchun
 * zarur paketlar (dialer, SMS, sozlamalar, ilovaning o'zi) AccessibilityScreenshotService
 * ichida alohida ro'yxat orqali har doim ruxsat etiladi — bu yerda saqlanmaydi.
 */
data class FocusModeSettings(
    val enabled: Boolean = false,
    val allowedPackages: List<String> = emptyList(),
    val updatedAt: Long = 0L,
    val updatedByUid: String = ""
)
