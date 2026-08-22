package uz.oilanazorati.parentcontrol.service

import android.content.Context
import android.database.ContentObserver
import android.provider.CallLog
import androidx.core.content.ContextCompat
import uz.oilanazorati.parentcontrol.model.CallEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.ContactAnonymizer

/**
 * MUHIM ARXITEKTURA QARORI: qo'ng'iroqlarni `CallScreeningService` +
 * `TelephonyCallback` kombinatsiyasi orqali emas, tizimning o'z
 * "Qo'ng'iroqlar tarixi" (Call Log) jadvalini kuzatish orqali yig'amiz.
 *
 * Sabab: `CallScreeningService.onScreenCall()` faqat qo'ng'iroq
 * BOSHLANGANDA chaqiriladi, tugash vaqti va davomiylikni bilish uchun
 * alohida `TelephonyCallback` orqali holat o'zgarishini kuzatish kerak
 * edi — bu ikki alohida mexanizm ba'zi qurilmalarda (masalan dual-SIM,
 * ba'zi Samsung modellari) ishonchli sinxron ishlamasligi mumkin.
 *
 * Call Log esa — qo'ng'iroq TUGAGANDAN keyin tizimning o'zi tomonidan
 * to'ldiriladigan, RAQAM + TUR (kiruvchi/chiquvchi/javobsiz) +
 * DAVOMIYLIKni allaqachon tayyor beradigan yagona, ishonchli manba.
 * Bunga faqat oddiy READ_CALL_LOG runtime ruxsati kifoya — standart
 * Telefon ilovasi (ROLE_CALL_SCREENING) bo'lish SHART EMAS.
 */
class CallLogObserver(
    private val context: Context,
    handler: android.os.Handler
) : ContentObserver(handler) {

    companion object {
        private const val PREFS = "oila_nazorati"
        private const val KEY_LAST_CALL_MS = "last_call_log_check_ms"
    }

    override fun onChange(selfChange: Boolean) {
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastCheckedMs = prefs.getLong(KEY_LAST_CALL_MS, System.currentTimeMillis() - 60_000)
        var newestMs = lastCheckedMs

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} > ?",
                arrayOf(lastCheckedMs.toString()),
                "${CallLog.Calls.DATE} ASC"
            )
            cursor?.use {
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)

                while (it.moveToNext()) {
                    val number = it.getString(numberIdx)
                    val startMs = it.getLong(dateIdx)
                    val durationSec = it.getLong(durationIdx)
                    val type = it.getInt(typeIdx)

                    val turi = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "kiruvchi"
                        CallLog.Calls.OUTGOING_TYPE -> "chiquvchi"
                        CallLog.Calls.MISSED_TYPE,
                        CallLog.Calls.REJECTED_TYPE -> "javobsiz"
                        else -> "javobsiz" // BLOCKED_TYPE, VOICEMAIL_TYPE va h.k. — ehtiyot uchun
                    }

                    val kontaktHash = ContactAnonymizer.hash(context, number)

                    FirebaseRepo.logCall(
                        CallEvent(
                            turi = turi,
                            boshlanishMs = startMs,
                            tugashMs = startMs + durationSec * 1000,
                            davomiylikSoniya = if (turi == "javobsiz") 0 else durationSec,
                            kontaktHash = kontaktHash
                        )
                    )

                    if (startMs > newestMs) newestMs = startMs
                }
            }
        } catch (e: SecurityException) {
            // Ruxsat berilmagan — jim o'tkazib yuboramiz
        }

        if (newestMs > lastCheckedMs) {
            prefs.edit().putLong(KEY_LAST_CALL_MS, newestMs).apply()
        }
    }
}
