package uz.oilanazorati.parentcontrol.model

data class CallEvent(
    val turi: String = "",
    val boshlanishMs: Long = 0L,
    val tugashMs: Long = 0L,
    val davomiylikSoniya: Long = 0L,
    val kontaktHash: String = ""
)

data class SmsEvent(
    val turi: String = "",
    val vaqtMs: Long = 0L,
    val kontaktHash: String = ""
)

data class AppUsageEvent(
    val ilovaNomi: String = "",
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

data class ContactMapping(
    val nomi: String = "",
    val kontaktHash: String = ""
)

data class ChildProfile(
    val nomi: String = "",
    val yaratilganMs: Long = 0L
)

data class DailySummary(
    val sana: String = "",
    val smsYuborilgan: Int = 0,
    val smsQabulQilingan: Int = 0,
    val qongiroqKiruvchiSoni: Int = 0,
    val qongiroqKiruvchiDaqiqa: Int = 0,
    val qongiroqChiquvchiSoni: Int = 0,
    val qongiroqChiquvchiDaqiqa: Int = 0,
    val qongiroqJavobsizSoni: Int = 0
)
