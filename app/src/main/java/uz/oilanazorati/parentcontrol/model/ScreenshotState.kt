package uz.oilanazorati.parentcontrol.model

data class ScreenshotState(
    val date: String = "",
    val packageName: String = "",
    val thresholdMinute: Int = 0,
    val status: String = "reserved",
    val attemptCount: Int = 0,
    val updatedAt: Long = 0L
)
