package uz.oilanazorati.parentcontrol.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
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

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) compressAndAttach(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_premium)

        premiumStatusText = findViewById(R.id.premiumStatusText)
        screenshotStatusText = findViewById(R.id.screenshotStatusText)
        cardsList = findViewById(R.id.cardsList)
        cardsList.layoutManager = LinearLayoutManager(this)
        cardsList.adapter = cardAdapter

        findViewById<View>(R.id.btnAttachScreenshot).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        findViewById<View>(R.id.btnSendPremiumRequest).setOnClickListener { sendRequest() }

        FirebaseRepo.listenAdminCards { cards -> cardAdapter.setData(cards) }

        FirebaseRepo.checkIsPremium { isPremium ->
            premiumStatusText.text = if (isPremium) {
                "✅ Sizda Premium allaqachon faol"
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

    private fun sendRequest() {
        FirebaseRepo.sendPremiumRequest("To'lov qildim", screenshotBase64) { success ->
            if (success) {
                Toast.makeText(
                    this, "So'rov yuborildi — admin tekshirib, tasdiqlaydi", Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                Toast.makeText(this, "Xato yuz berdi, qayta urinib ko'ring", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
