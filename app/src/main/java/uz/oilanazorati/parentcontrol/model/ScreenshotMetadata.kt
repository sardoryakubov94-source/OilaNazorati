package uz.oilanazorati.parentcontrol.model

data class ScreenshotMetadata(
    val id: String = "",
    val childId: String = "",
    val familyId: String = "",
    val packageName: String = "",
    val appLabel: String = "",
    val capturedAt: Long = 0L,
    val date: String = "",
    val dailyUsageSeconds: Long = 0L,
    val thresholdMinute: Int = 0,
    val storagePath: String = "",
    val status: String = "completed",
    val contentType: String = "image/jpeg",
    val byteSize: Long = 0L,
    val riskCategory: String = "",
    val sensitiveEvidence: Boolean = false,
    val createdAt: Long = 0L,
    /** "manual" (Hozir screenshot olish) | "auto" (eng ko'p ishlatilgan ilovalar) | "risk" (xavf signali). */
    val kind: String = ""
)

/**
 * Screenshot turi. Yangi yozuvlarda `kind` bor; eski yozuvlarda (kind yo'q) avvalgi
 * qoidalar bo'yicha aniqlanadi: threshold > 0 — avtomatik, xavf toifasi/sezgir dalil
 * bor — xavf signali, qolgani — qo'lda olingan.
 */
fun ScreenshotMetadata.effectiveKind(): String = when {
    kind.isNotBlank() -> kind
    thresholdMinute > 0 -> "auto"
    riskCategory.isNotBlank() || sensitiveEvidence -> "risk"
    else -> "manual"
}
