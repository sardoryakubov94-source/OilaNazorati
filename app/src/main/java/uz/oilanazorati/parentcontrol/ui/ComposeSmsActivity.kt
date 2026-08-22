package uz.oilanazorati.parentcontrol.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import androidx.appcompat.app.AppCompatActivity
import uz.oilanazorati.parentcontrol.databinding.ActivityComposeSmsBinding

/**
 * Ixtiyoriy: ota-ona bola qurilmasidan bevosita SMS yozib yuborishni
 * xohlasa ishlatiladigan oddiy ekran.
 *
 * MUHIM: bu yerda alohida kuzatish/hash kodi YO'Q — chunki
 * `SmsSentObserver` (MonitorForegroundService ichida) `content://sms`
 * jadvalini kuzatib turadi va bu orqali yuborilgan xabarni ham,
 * Samsung Messages orqali yuborilganini ham BIR XIL, AVTOMATIK
 * yo'l bilan aniqlab, statistikaga qo'shadi.
 */
class ComposeSmsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComposeSmsBinding
    private var destinationNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposeSmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        destinationNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER)
        binding.composeToLabel.text = "Kimga: ${destinationNumber ?: "?"}"

        binding.btnSend.setOnClickListener { sendSms() }
    }

    private fun sendSms() {
        val number = destinationNumber
        val body = binding.composeBody.text?.toString()
        if (number.isNullOrBlank() || body.isNullOrBlank()) return

        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            getSystemService(SmsManager::class.java)
        else
            @Suppress("DEPRECATION") SmsManager.getDefault()

        val parts = smsManager.divideMessage(body)
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(number, null, body, null, null)
        }

        finish()
    }

    companion object {
        const val EXTRA_PHONE_NUMBER = "phone_number"
    }
}
