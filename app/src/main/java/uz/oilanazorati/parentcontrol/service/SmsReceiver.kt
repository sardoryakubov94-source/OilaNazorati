package uz.oilanazorati.parentcontrol.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.provider.Telephony
import uz.oilanazorati.parentcontrol.model.SmsEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.ContactAnonymizer

/**
 * Kiruvchi SMS xabarlarni ushlaydi.
 *
 * MUHIM ARXITEKTURA QARORI: bu `SMS_RECEIVED_ACTION`ni tinglaydi, ya'ni
 * `SMS_DELIVER_ACTION` EMAS. Farqi katta: `SMS_DELIVER` faqat ilova
 * qurilmaning STANDART SMS ilovasi (ROLE_SMS egasi) bo'lgandagina
 * yuboriladi — bu esa bolaning butun SMS tajribasini (yozish, o'qish,
 * bildirishnoma) bizning ilovamizga o'tkazib yuboradi, holbuki bizda
 * to'liq xabarlar qutisi yo'q — natijada SMS "ishlamay qoladi".
 *
 * `SMS_RECEIVED_ACTION` esa RECEIVE_SMS ruxsatiga ega BARCHA ilovalarga
 * yuboriladi — standart ilova bo'lish shart EMAS. Shunday qilib, Samsung
 * Messages (yoki boshqa SMS ilovasi) odatdagidek ishlashda davom etadi,
 * biz esa parallel ravishda faqat statistika uchun xabar kelganini bilib
 * qolamiz — bola tajribasiga hech qanday ta'sir qilmaydi.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        // Bir intentda bir nechta bo'lak (multipart SMS) kelishi mumkin,
        // lekin bu baribir BITTA xabar hisoblanadi — shuning uchun faqat
        // kelgan bo'lsa +1 qilamiz, bo'lak sonini emas.
        if (messages.isNullOrEmpty()) return

        // Barcha bo'laklar bir xil jo'natuvchidan keladi — birinchisidan olamiz.
        val rawSender = messages.firstOrNull()?.originatingAddress
        val kontaktHash = ContactAnonymizer.hash(context.applicationContext, rawSender)

        FirebaseRepo.logSms(
            SmsEvent(
                turi = "qabul_qilingan",
                vaqtMs = System.currentTimeMillis(),
                kontaktHash = kontaktHash,
                raqam = rawSender.orEmpty()
            )
        )

        // Ilova standart SMS handler bo'lgani uchun, xabarni tizim
        // manzil kitobiga/SMS ilovasiga o'zi ham yozib qo'yishi kerak.
        // Bu yerda faqat statistika yig'ish maqsad qilingani uchun,
        // xabarni saqlash logikasi ataylab qo'shilmagan.
    }
}

/**
 * Samsung Messages (yoki boshqa har qanday SMS ilovasi) orqali
 * YUBORILGAN xabarlarni kuzatish uchun. Chiquvchi SMS uchun
 * "yuborildi" degan tizim broadcast'i yo'q (faqat ilovaning o'zi
 * yuborsa, PendingIntent orqali bilib bo'ladi) — shuning uchun
 * `content://sms` jadvalidagi o'zgarishlarni READ_SMS ruxsati orqali
 * kuzatamiz. Bu usul ham standart SMS ilovasi bo'lishni TALAB QILMAYDI.
 */
class SmsSentObserver(
    private val context: Context,
    handler: android.os.Handler
) : ContentObserver(handler) {

    companion object {
        private const val PREFS = "oila_nazorati"
        private const val KEY_LAST_SENT_MS = "last_sms_sent_check_ms"
    }

    override fun onChange(selfChange: Boolean) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastCheckedMs = prefs.getLong(KEY_LAST_SENT_MS, System.currentTimeMillis() - 60_000)
        var newestMs = lastCheckedMs

        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                "${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.DATE} > ?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_SENT.toString(), lastCheckedMs.toString()),
                "${Telephony.Sms.DATE} ASC"
            )
            cursor?.use {
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                while (it.moveToNext()) {
                    val address = it.getString(addressIdx)
                    val dateMs = it.getLong(dateIdx)
                    val kontaktHash = ContactAnonymizer.hash(context, address)
                    FirebaseRepo.logSms(
                        SmsEvent(turi = "yuborilgan", vaqtMs = dateMs, kontaktHash = kontaktHash, raqam = address.orEmpty())
                    )
                    if (dateMs > newestMs) newestMs = dateMs
                }
            }
        } catch (e: SecurityException) {
            // Ruxsat berilmagan — jim o'tkazib yuboramiz
        }

        if (newestMs > lastCheckedMs) {
            prefs.edit().putLong(KEY_LAST_SENT_MS, newestMs).apply()
        }
    }
}
