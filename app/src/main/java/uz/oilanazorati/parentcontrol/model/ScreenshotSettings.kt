package uz.oilanazorati.parentcontrol.model

data class ScreenshotSettings(
    val enabled: Boolean = false,
    val autoTop3Enabled: Boolean = true,
    val frequencyMinutes: Int = 15,
    val manualPackageNames: List<String> = emptyList(),
    val updatedAt: Long = 0L,
    val updatedByUid: String = ""
)
