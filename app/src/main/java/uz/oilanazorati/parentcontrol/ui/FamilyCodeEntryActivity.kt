package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import uz.oilanazorati.parentcontrol.databinding.ActivityFamilyCodeEntryBinding
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

/**
 * Bola qurilmasini oilaga ulash uchun mustaqil, to'liq ekranli sahifa.
 * Kod tekshirilmaguncha ruxsatlar sozlash sahifasi ochilmaydi.
 */
class FamilyCodeEntryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFamilyCodeEntryBinding
    private val handler = Handler(Looper.getMainLooper())
    private var requestInProgress = false
    private var requestAnswered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFamilyCodeEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.inputFamilyCode.filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPair.setOnClickListener { connectWithCode() }
    }

    private fun connectWithCode() {
        if (requestInProgress) return
        val code = binding.inputFamilyCode.text?.toString()?.trim()?.uppercase().orEmpty()
        val childName = binding.inputChildName.text?.toString()?.trim().orEmpty()

        if (code.length != 6 || !code.all { it.isLetterOrDigit() }) {
            binding.inputFamilyCode.error = "Ota-ona bergan 6 belgili kodni kiriting"
            return
        }

        requestInProgress = true
        requestAnswered = false
        binding.btnPair.isEnabled = false
        binding.btnPair.text = "Ulanmoqda..."
        binding.pairStatusText.text = "Oila kodi tekshirilmoqda..."

        val timeout = Runnable {
            if (!requestAnswered) {
                requestAnswered = true
                showFailure("Internet aloqasi yo'q yoki javob kechikyapti. Qayta urinib ko'ring.")
            }
        }
        handler.postDelayed(timeout, 15_000L)

        val auth = FirebaseAuth.getInstance()
        val current = auth.currentUser
        if (current != null && current.isAnonymous) {
            submitPairing(code, childName, current.uid, timeout)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid
                    if (uid.isNullOrBlank()) {
                        handler.removeCallbacks(timeout)
                        requestAnswered = true
                        showFailure("Bola qurilmasi uchun Firebase hisobi yaratilmadi.")
                    } else {
                        submitPairing(code, childName, uid, timeout)
                    }
                }
                .addOnFailureListener { error ->
                    handler.removeCallbacks(timeout)
                    requestAnswered = true
                    showFailure(error.localizedMessage ?: "Firebase bilan bog'lanib bo'lmadi.")
                }
        }
    }

    private fun submitPairing(code: String, childName: String, uid: String, timeout: Runnable) {
        FirebaseRepo.joinFamily(code, childName) { ok, error ->
            if (requestAnswered) return@joinFamily
            requestAnswered = true
            handler.removeCallbacks(timeout)
            if (!ok) {
                showFailure(error ?: "Oila kodiga ulanish amalga oshmadi.")
                return@joinFamily
            }

            getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
                .putString("family_code", code)
                .putString("child_id", uid)
                .putString("child_name", childName)
                .putString("child_platform", "android")
                .apply()

            binding.pairStatusText.text = "✅ Oila bilan bog'landi. Keyingi sozlash bosqichiga o'tilmoqda..."
            binding.btnPair.text = "Ulandi"
            binding.btnPair.isEnabled = false

            binding.root.postDelayed({
                startActivity(Intent(this, ChildSetupActivity::class.java).apply {
                    putExtra(EXTRA_FAMILY_LINKED, true)
                })
                finish()
            }, 450L)
        }
    }

    private fun showFailure(message: String) {
        requestInProgress = false
        binding.btnPair.isEnabled = true
        binding.btnPair.text = "Oilaga ulanish"
        binding.pairStatusText.text = "❌ $message"
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FAMILY_LINKED = "family_linked_from_full_screen"
    }
}
