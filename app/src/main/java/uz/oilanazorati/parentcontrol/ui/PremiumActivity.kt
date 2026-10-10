package uz.oilanazorati.parentcontrol.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.io.ByteArrayOutputStream

/**
 * Premium so'rov yuborish ekrani: izoh, admin kartalari, to'lov
 * skrinshotini biriktirish va so'rov yuborish.
 *
 * MUHIM: skrinshot Firestore hujjatining o'zida (Base64 matn sifatida)
 * saqlanadi — Firebase Storage EMAS, chunki Storage endi bepul (Spark)
 * rejada ishlamaydi. Shu sabab rasm avval KUCHLI siqiladi (max 800px,
 * JPEG sifat 50%) — aks holda Firestore'ning 1 MB/hujjat chegarasidan
 * osonlik bilan oshib ketadi.
 */
class PremiumActivity : AppCompatActivity() {

    private lateinit var premiumStatusText: TextView
    private lateinit var screenshotStatusText: TextView
    private lateinit var cardsList: RecyclerView
    private val cardAdapter = AdminCardAdapter()
    private var screenshotBase64: String = ""

    // So'rov holati: qayta-qayta yuborishning oldini olish va foydalanuvchiga holatni ko'rsatish uchun
    private lateinit var requestStatusText: TextView
    private lateinit var sendButton: Button
    private var latestStatus: String? = null   // eng yangi so'rov: kutilmoqda | tolandi | rad_etildi
    private var statusLoaded = false
    private var sending = false
    private var premiumLoaded = false
    private var isPremiumNow = false
    private var requestsListener: ListenerRegistration? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) compressAndAttach(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_premium)

        premiumStatusText = findViewById(R.id.premiumStatusText)
        val premiumActiveBadge = findViewById<View>(R.id.premiumActiveBadge)
        screenshotStatusText = findViewById(R.id.screenshotStatusText)
        cardsList = findViewById(R.id.cardsList)
        cardsList.layoutManager = LinearLayoutManager(this)
        cardsList.adapter = cardAdapter

        findViewById<View>(R.id.btnAttachScreenshot).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        requestStatusText = findViewById(R.id.requestStatusText)
        sendButton = findViewById(R.id.btnSendPremiumRequest)
        sendButton.setOnClickListener { sendRequest() }
        updateRequestUi()

        // Eng yangi so'rov holati: yuborilgan bo'lsa — "kutilmoqda", admin hal qilsa — o'zi yangilanadi.
        requestsListener = FirebaseRepo.listenMyPremiumRequests { list ->
            latestStatus = list.firstOrNull()?.holati
            statusLoaded = true
            updateRequestUi()
        }
        // Internet sekin bo'lsa tugma abadiy o'chiq qolmasin
        Handler(Looper.getMainLooper()).postDelayed({
            if (!statusLoaded) { statusLoaded = true; updateRequestUi() }
        }, 5000L)

        FirebaseRepo.listenAdminCards { cards -> cardAdapter.setData(cards) }

        FirebaseRepo.checkIsPremium { isPremium ->
            premiumActiveBadge.visibility = if (isPremium) View.VISIBLE else View.GONE
            isPremiumNow = isPremium
            premiumLoaded = true
            updateRequestUi()
            premiumStatusText.text = if (isPremium) {
                ""
            } else {
                "Hozircha Premium faol emas — kartalardan biriga to'lov qilib, so'rov yuboring"
            }
        }
    }

    private fun compressAndAttach(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(input)
            input.close()

            val maxDim = 800
            val scale = maxDim.toFloat() / maxOf(original.width, original.height)
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    original, (original.width * scale).toInt(), (original.height * scale).toInt(), true
                )
            } else original

            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 50, out)
            screenshotBase64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

            val sizeKb = out.size() / 1024
            screenshotStatusText.text = "✅ Skrinshot biriktirildi (~${sizeKb} KB)"
        } catch (e: Exception) {
            Toast.makeText(this, "Rasmni yuklashda xato yuz berdi", Toast.LENGTH_SHORT).show()
        }
    }

    /** Holat kartasi va "yuborish" tugmasining ko'rinishini yangilaydi. */
    private fun updateRequestUi() {
        // Premium allaqachon tasdiqlangan bo'lsa, eski "tolandi" holati bannerga aylanadi;
        // premium bekor qilingan bo'lsa (premiumLoaded && !isPremiumNow) — qayta yuborish mumkin.
        val approvedActive = latestStatus == "tolandi" && isPremiumNow
        val pending = latestStatus == "kutilmoqda"
        val rejected = latestStatus == "rad_etildi"

        when {
            pending -> {
                requestStatusText.text = "⏳ So'rovingiz yuborildi.\nAdmin to'lovingizni ko'rib chiqmoqda — iltimos, biroz kuting. Qayta yuborish shart emas."
                requestStatusText.setTextColor(0xFFF39C12.toInt())
                requestStatusText.visibility = View.VISIBLE
            }
            approvedActive -> {
                requestStatusText.text = "✅ So'rovingiz tasdiqlandi — Premium faollashtirildi."
                requestStatusText.setTextColor(0xFF2ECC71.toInt())
                requestStatusText.visibility = View.VISIBLE
            }
            rejected -> {
                requestStatusText.text = "❌ So'rovingiz rad etildi.\nTo'lov skrinshotini tekshirib, qayta yuborishingiz mumkin."
                requestStatusText.setTextColor(0xFFE74C3C.toInt())
                requestStatusText.visibility = View.VISIBLE
            }
            else -> requestStatusText.visibility = View.GONE
        }

        when {
            sending -> { sendButton.isEnabled = false; sendButton.text = "⏳ Yuborilmoqda..." }
            !statusLoaded -> { sendButton.isEnabled = false; sendButton.text = "Yuklanmoqda..." }
            pending -> { sendButton.isEnabled = false; sendButton.text = "⏳ So'rov ko'rib chiqilmoqda" }
            approvedActive -> { sendButton.isEnabled = false; sendButton.text = "✅ Premium faol" }
            else -> { sendButton.isEnabled = true; sendButton.text = "To'lov qildim — so'rov yuborish" }
        }
    }

    private fun sendRequest() {
        if (sending || latestStatus == "kutilmoqda") {
            Toast.makeText(this, "So'rovingiz allaqachon yuborilgan — admin ko'rib chiqmoqda", Toast.LENGTH_SHORT).show()
            return
        }
        if (screenshotBase64.isBlank()) {
            screenshotStatusText.text = "⚠️ Avval to'lov skrinshotini biriktiring"
            Toast.makeText(this, "Avval to'lov skrinshotini biriktiring", Toast.LENGTH_SHORT).show()
            return
        }
        sending = true
        updateRequestUi()
        FirebaseRepo.sendPremiumRequest("To'lov qildim", screenshotBase64) { success ->
            sending = false
            if (success) {
                latestStatus = "kutilmoqda"   // darrov ko'rsatiladi; keyin kuzatuvchi tasdiqlaydi
                screenshotBase64 = ""
                screenshotStatusText.text = "✅ Skrinshot yuborildi"
                Toast.makeText(this, "So'rov yuborildi — admin tekshirib, tasdiqlaydi", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Xato yuz berdi, qayta urinib ko'ring", Toast.LENGTH_SHORT).show()
            }
            updateRequestUi()
        }
    }

    override fun onDestroy() {
        requestsListener?.remove()
        super.onDestroy()
    }
}
