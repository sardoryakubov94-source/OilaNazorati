package uz.oilanazorati.parentcontrol.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import uz.oilanazorati.parentcontrol.databinding.ActivityParentFamilyLinkBinding

/** Ota-ona uchun oila kodi va farzand qurilmasini ulash bo'yicha to'liq ekranli sahifa. */
class ParentFamilyLinkActivity : AppCompatActivity() {
    private lateinit var binding: ActivityParentFamilyLinkBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentFamilyLinkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val code = intent.getStringExtra(EXTRA_FAMILY_CODE)
            ?: getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getString("family_code", null)

        if (code.isNullOrBlank()) {
            Toast.makeText(this, "Oila kodi topilmadi. Qayta kirib ko'ring.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.familyCodeText.text = code
        binding.btnCopyCode.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OilaNazorati oila kodi", code))
            Toast.makeText(this, "Oila kodi nusxalandi", Toast.LENGTH_SHORT).show()
        }
        binding.btnOpenDashboard.setOnClickListener {
            startActivity(Intent(this, ParentDashboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }
    }

    companion object {
        const val EXTRA_FAMILY_CODE = "parent_family_code"
    }
}
