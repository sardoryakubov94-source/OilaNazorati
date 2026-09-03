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
    val createdAt: Long = 0L
)
