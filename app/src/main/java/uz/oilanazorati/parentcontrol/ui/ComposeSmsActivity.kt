package uz.oilanazorati.parentcontrol.ui

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import androidx.appcompat.app.AppCompatActivity
import uz.oilanazorati.parentcontrol.databinding.ActivityComposeSmsBinding
import uz.oilanazorati.parentcontrol.util.ContactAnonymizer

/**
 * Minimal SMS yozish ekrani. Bu ekran bolaning O'ZI uchun oddiy SMS
 * ilovasidek ishlaydi — u albatta kimga yozayotganini ko'radi (bu normal,
 * chunki bu bolaning o'z qurilmasi).
 *
 * Statistikaga tegishli qism shu yerda: xabar jo'natilishidan OLDIN,
 * qabul qiluvchi raqam ContactAnonymizer.hash() orqali anonim
 * identifikatorga aylantiriladi va shu identifikator (raqamning o'zi
 * EMAS) SmsSentReceiver'ga PendingIntent extra sifatida uzatiladi —
 * natijada chiquvchi SMS'lar ham timeline'da to'g'ri rang bilan chiqadi,
 * lekin Firestore'ga raqam hech qachon yozilmaydi.
 */
class ComposeSmsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComposeSmsBinding
    private var destinationNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposeSmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        destinationNumber = extractNumber(intent)
        binding.composeToLabel.text = "Kimga: ${destinationNumber ?: "?"}"

        // ACTION_SEND orqali matn ham kelishi mumkin (masalan "ulashish" menyusidan)
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { binding.composeBody.setText(it) }

        binding.btnSend.setOnClickListener { sendSms() }
    }

    private fun extractNumber(intent: Intent): String? {
        val data: Uri? = intent.data
        return data?.schemeSpecificPart?.substringBefore("?")
    }

    private fun sendSms() {
        val number = destinationNumber
        val body = binding.composeBody.text?.toString()
        if (number.isNullOrBlank() || body.isNullOrBlank()) return

        // Raqam faqat shu yerda, xotirada, hash olish uchun ishlatiladi.
        val kontaktHash = ContactAnonymizer.hash(applicationContext, number)

        val sentIntent = Intent(this, uz.oilanazorati.parentcontrol.service.SmsSentReceiver::class.java)
            .setAction("uz.oilanazorati.SMS_SENT")
            .putExtra(EXTRA_KONTAKT_HASH, kontaktHash)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT

        val sentPendingIntent = PendingIntent.getBroadcast(this, 0, sentIntent, flags)

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            getSystemService(SmsManager::class.java)
        else
            @Suppress("DEPRECATION") SmsManager.getDefault()

        val parts = smsManager.divideMessage(body)
        if (parts.size > 1) {
            val sentIntents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(sentPendingIntent) } }
            smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, null)
        } else {
            smsManager.sendTextMessage(number, null, body, sentPendingIntent, null)
        }

        finish()
    }

    companion object {
        const val EXTRA_KONTAKT_HASH = "kontakt_hash"
    }
}
