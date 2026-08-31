package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.AdminConfig

/**
 * To'liq, alohida "Sozlamalar" ekrani (avvalgi kichik AlertDialog o'rniga) —
 * NurMakon dasturidagi Sozlamalar sahifasiga o'xshash professional
 * ko'rinish: orqaga qaytish tugmasi + sarlavha, Premium reklama banneri
 * bo'lim boshida, so'ngra "OILA" / "BOSHQARUV" / (adminlar uchun) "ADMIN"
 * bo'limlari, va pastki navigatsiya.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        bindBottomNav(NavTab.SETTINGS)

        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        val currentCode = FirebaseRepo.familyCode
            ?: getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getString("family_code", null)
        if (currentCode != null) {
            findViewById<TextView>(R.id.currentFamilyCodeSubtitle).text = "Joriy kod: $currentCode"
        }

        val isLight = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getBoolean("light_theme", false)
        findViewById<TextView>(R.id.currentThemeSubtitle).text =
            if (isLight) "Kunduzgi rejim" else "Tungi rejim (standart)"

        findViewById<android.view.View>(R.id.rowEnterCode).setOnClickListener { showEnterCodeDialog() }
        findViewById<android.view.View>(R.id.rowNewCode).setOnClickListener { createNewFamilyCode() }
        findViewById<android.view.View>(R.id.rowTheme).setOnClickListener { showThemePickerDialog() }
        findViewById<android.view.View>(R.id.rowNotifications).setOnClickListener {
            if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) {
                Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, NotificationHistoryActivity::class.java))
            }
        }

        findViewById<android.view.View>(R.id.premiumBanner).setOnClickListener {
            startActivity(Intent(this, PremiumActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btnPremiumCta).setOnClickListener {
            startActivity(Intent(this, PremiumActivity::class.java))
        }

        if (AdminConfig.isCurrentUserAdmin()) {
            findViewById<android.view.View>(R.id.adminSection).visibility = android.view.View.VISIBLE
            findViewById<android.view.View>(R.id.rowAdminPanel).setOnClickListener {
                startActivity(Intent(this, AdminPanelActivity::class.java))
            }
        }
    }

    private fun showThemePickerDialog() {
        val options = arrayOf("Tungi rejim (standart)", "Kunduzgi rejim")
        val current = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getBoolean("light_theme", false)
        AlertDialog.Builder(this)
            .setTitle("Mavzu")
            .setSingleChoiceItems(options, if (current) 1 else 0) { dialog, which ->
                val isLight = which == 1
                getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
                    .putBoolean("light_theme", isLight).apply()
                AppCompatDelegate.setDefaultNightMode(
                    if (isLight) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
                )
                dialog.dismiss()
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun showEnterCodeDialog() {
        val input = EditText(this).apply {
            hint = "Oila kodi"
            setText(FirebaseRepo.familyCode.orEmpty())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Oila kodi")
            .setView(container)
            .setPositiveButton("Yuklash") { _, _ ->
                val code = input.text?.toString()?.trim()?.uppercase()
                if (!code.isNullOrBlank()) saveFamilyCodeAndReturn(code)
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun createNewFamilyCode() {
        val newCode = generateFamilyCode()
        FirebaseRepo.createFamily(newCode) { success, errorMsg ->
            if (success) {
                showGeneratedCodeDialog(newCode)
            } else {
                Toast.makeText(this, "Kod yaratishda xato: ${errorMsg ?: "noma'lum"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun generateFamilyCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun showGeneratedCodeDialog(code: String) {
        AlertDialog.Builder(this)
            .setTitle("Yangi oila kodi yaratildi")
            .setMessage(
                "$code\n\nBu kodni bola qurilmasidagi \"Bu farzandimning telefoni\" " +
                "ekranidagi \"Ota-onaning kodini kiriting\" maydoniga kiriting."
            )
            .setPositiveButton("Tushunarli") { _, _ -> saveFamilyCodeAndReturn(code) }
            .setCancelable(false)
            .show()
    }

    /** Oila kodini saqlaydi va o'zgarishni aks ettirish uchun Bosh sahifani qayta ochadi. */
    private fun saveFamilyCodeAndReturn(code: String) {
        FirebaseRepo.familyCode = code
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putString("family_code", code).apply()
        startActivity(Intent(this, ParentDashboardActivity::class.java))
        finish()
    }
}
