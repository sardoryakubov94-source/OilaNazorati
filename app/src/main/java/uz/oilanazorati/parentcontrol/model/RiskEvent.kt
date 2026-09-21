package uz.oilanazorati.parentcontrol.model

data class RiskEvent(
    val id: String = "",
    val category: String = "",
    val severity: String = "LOW",
    val confidence: Int = 0,
    val packageName: String = "",
    val appName: String = "",
    val source: String = "",
    val summary: String = "",
    val contextText: String = "",
    val mediaType: String = "",
    val mediaState: String = "",
    val capturedAt: Long = 0L,
    val evidenceAvailable: Boolean = false,
    val sensitive: Boolean = false,
    val reviewed: Boolean = false
)
