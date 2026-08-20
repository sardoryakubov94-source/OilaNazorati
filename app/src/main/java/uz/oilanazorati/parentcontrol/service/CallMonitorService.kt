package uz.oilanazorati.parentcontrol.service

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import uz.oilanazorati.parentcontrol.model.CallEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.ContactAnonymizer

/**
 * Bu ilova qurilmaning STANDART Telefon ilovasi (default dialer/
 * call-screening handler) sifatida tanlanganda ishga tushadi.
 *
 * MUHIM: raqamning o'zi HECH QACHON saqlanmaydi yoki Firebase'ga
 * yuborilmaydi. `callDetails.handle` orqali kelgan raqam faqat shu
 * metod ichida, xotirada, ContactAnonymizer.hash() ga uzatish uchun
 * ishlatiladi — natijada asl raqamni tiklab bo'lmaydigan anonim
 * identifikator chiqadi. Qo'ng'iroq YO'NALISHI, DAVOMIYLIGI va shu
 * anonim identifikator qayd etiladi — statistika va rang bilan
 * ajratish uchun.
 */
class CallMonitorService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val isIncoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING

        // Qo'ng'iroqni bloklamaymiz — faqat kuzatamiz, shuning uchun
        // hech qanday cheklovsiz javob qaytaramiz.
        val response = CallResponse.Builder().build()
        respondToCall(callDetails, response)

        // Raqamni faqat shu yerda, vaqtincha, hash olish uchun o'qiymiz —
        // qayerda ham saqlanmaydi.
        val rawNumber = callDetails.handle?.schemeSpecificPart
        val kontaktHash = ContactAnonymizer.hash(applicationContext, rawNumber)

        val startMs = System.currentTimeMillis()
        CallSessionTracker.onCallStart(
            direction = if (isIncoming) "kiruvchi" else "chiquvchi",
            startMs = startMs,
            kontaktHash = kontaktHash
        )
    }
}

/**
 * Qo'ng'iroq tugagan vaqtni CallScreeningService orqali bilib bo'lmaydi
 * (u faqat boshlanishda chaqiriladi), shuning uchun davomiylikni
 * PhoneStateListener/TelephonyCallback orqali MonitorForegroundService
 * ichida kuzatamiz. Bu obyekt ikkalasi orasidagi vaqtinchalik holatni
 * ushlab turadi.
 */
object CallSessionTracker {
    private var currentDirection: String? = null
    private var currentStartMs: Long = 0L
    private var wasAnswered: Boolean = false
    private var currentKontaktHash: String = ""

    fun onCallStart(direction: String, startMs: Long, kontaktHash: String = "") {
        currentDirection = direction
        currentStartMs = startMs
        wasAnswered = false
        currentKontaktHash = kontaktHash
    }

    fun onCallAnswered() {
        wasAnswered = true
    }

    fun onCallEnded() {
        val direction = currentDirection ?: return
        val endMs = System.currentTimeMillis()
        val durationSec = ((endMs - currentStartMs) / 1000).coerceAtLeast(0)

        val turi = if (!wasAnswered && direction == "kiruvchi") "javobsiz" else direction

        FirebaseRepo.logCall(
            CallEvent(
                turi = turi,
                boshlanishMs = currentStartMs,
                tugashMs = endMs,
                davomiylikSoniya = if (turi == "javobsiz") 0 else durationSec,
                kontaktHash = currentKontaktHash
            )
        )
        currentDirection = null
        currentKontaktHash = ""
    }
}
