package uz.oilanazorati.parentcontrol.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import uz.oilanazorati.parentcontrol.model.SmsEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.ContactAnonymizer

/**
 * Kiruvchi SMS xabarlarni ushlaydi. Ilova qurilmaning standart SMS
 * ilovasi sifatida tanlangandagina ishlaydi.
 *
 * MUHIM: xabar matni HECH QACHON o'qilmaydi/saqlanmaydi — faqat
 * "SMS keldi" hodisasi, vaqti va jo'natuvchi raqamning anonim xashi
 * qayd etiladi. Raqamning o'zi (originatingAddress) faqat shu metod
 * ichida, xotirada, ContactAnonymizer.hash() ga uzatish uchun
 * ishlatiladi — hech qayerga saqlanmaydi/yuborilmaydi.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

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
                kontaktHash = kontaktHash
            )
        )

        // Ilova standart SMS handler bo'lgani uchun, xabarni tizim
        // manzil kitobiga/SMS ilovasiga o'zi ham yozib qo'yishi kerak.
        // Bu yerda faqat statistika yig'ish maqsad qilingani uchun,
        // xabarni saqlash logikasi ataylab qo'shilmagan.
    }
}

/**
 * Chiquvchi SMS (foydalanuvchi o'zi yuborgan) — SmsManager.sendTextMessage
 * chaqirilganda PendingIntent orqali shu receiver ishga tushadi (natija:
 * yuborildimi/yo'qmi). Faqat "yuborildi" hodisasi hisoblanadi.
 *
 * Qabul qiluvchi raqamning o'zi bu yerga hech qachon kelmaydi — faqat
 * ComposeSmsActivity xabarni jo'natishdan OLDIN raqamni hash'lab,
 * natijani ("kontakt_hash" extra) shu PendingIntent orqali uzatadi.
 * Shu tufayli chiquvchi SMS'lar ham anonim rang bilan statistikaga
 * kiradi, lekin raqam hech qayerda saqlanmaydi.
 */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (resultCode != android.app.Activity.RESULT_OK) return
        val kontaktHash = intent.getStringExtra(
            uz.oilanazorati.parentcontrol.ui.ComposeSmsActivity.EXTRA_KONTAKT_HASH
        ) ?: ""
        FirebaseRepo.logSms(
            SmsEvent(
                turi = "yuborilgan",
                vaqtMs = System.currentTimeMillis(),
                kontaktHash = kontaktHash
            )
        )
    }
}
