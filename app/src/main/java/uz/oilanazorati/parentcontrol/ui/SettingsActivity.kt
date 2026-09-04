package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.AdminConfig

class SettingsActivity : AppCompatActivity() {
    private val prefsName = "oila_nazorati"
    private val familyCodesKey = "family_codes"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindBottomNav(NavTab.SETTINGS)
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val currentCode = FirebaseRepo.familyCode ?: prefs.getString("family_code", null)
        if (!currentCode.isNullOrBlank()) {
            addFamilyCode(currentCode)
            findViewById<TextView>(R.id.currentFamilyCodeSubtitle).text = "Joriy kod: $currentCode"
        }

        val isLight = prefs.getBoolean("light_theme", false)
        findViewById<TextView>(R.id.currentThemeSubtitle).text = if (isLight) "Kunduzgi rejim" else "Tungi rejim (standart)"
        findViewById<android.view.View>(R.id.rowEnterCode).setOnClickListener { showFamilyManager() }
        findViewById<android.view.View>(R.id.rowNewCode).setOnClickListener { createNewFamilyCode() }
        findViewById<android.view.View>(R.id.rowTheme).setOnClickListener { showThemePickerDialog() }
        findViewById<android.view.View>(R.id.premiumBanner).setOnClickListener { startActivity(Intent(this, PremiumActivity::class.java)) }
        findViewById<android.view.View>(R.id.btnPremiumCta).setOnClickListener { startActivity(Intent(this, PremiumActivity::class.java)) }

        if (AdminConfig.isCurrentUserAdmin()) {
            findViewById<android.view.View>(R.id.adminSection).visibility = android.view.View.VISIBLE
            findViewById<android.view.View>(R.id.rowAdminPanel).setOnClickListener { startActivity(Intent(this, AdminPanelActivity::class.java)) }
        }
    }

    private fun getFamilyCodes(): MutableList<String> {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        return prefs.getStringSet(familyCodesKey, emptySet())?.toMutableList()?.sorted()?.toMutableList() ?: mutableListOf()
    }

    private fun addFamilyCode(code: String) {
        val clean = code.trim().uppercase()
        if (clean.isBlank()) return
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val codes = prefs.getStringSet(familyCodesKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        codes.add(clean)
        prefs.edit().putStringSet(familyCodesKey, codes).apply()
    }

    private fun showFamilyManager() {
        val codes = getFamilyCodes()
        val labels = (codes.map { "🏠 $it" } + "➕ Boshqa oila kodini kiritish").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mening oilalarim")
            .setItems(labels) { _, which ->
                if (which < codes.size) {
                    saveFamilyCodeAndReturn(codes[which])
                } else {
                    showManualCodeDialog()
                }
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun showManualCodeDialog() {
        val input = EditText(this).apply {
            hint = "Oila kodi"
            setText(FirebaseRepo.familyCode.orEmpty())
            selectAll()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, 0, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Oila kodi")
            .setMessage("Saqlangan oilalardan birini tanlang yoki yangi berilgan kodni kiriting.")
            .setView(container)
            .setPositiveButton("Yuklash") { _, _ ->
                input.text?.toString()?.trim()?.uppercase()?.takeIf { it.isNotBlank() }?.let { saveFamilyCodeAndReturn(it) }
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun showThemePickerDialog() {
        val options = arrayOf("Tungi rejim (standart)", "Kunduzgi rejim")
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val current = prefs.getBoolean("light_theme", false)
        AlertDialog.Builder(this)
            .setTitle("Mavzu")
            .setSingleChoiceItems(options, if (current) 1 else 0) { dialog, which ->
                val light = which == 1
                val code = FirebaseRepo.familyCode
                val childId = FirebaseRepo.childId
                prefs.edit().putBoolean("light_theme", light).apply()
                if (!code.isNullOrBlank()) prefs.edit().putString("family_code", code).apply()
                if (!childId.isNullOrBlank()) prefs.edit().putString("child_id", childId).apply()
                FirebaseRepo.familyCode = code ?: prefs.getString("family_code", null)
                FirebaseRepo.childId = childId ?: prefs.getString("child_id", null)
                AppCompatDelegate.setDefaultNightMode(if (light) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
                dialog.dismiss()
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun createNewFamilyCode() {
        FirebaseRepo.checkIsPremium { isPremium ->
            if (!isPremium) {
                AlertDialog.Builder(this)
                    .setTitle("⭐ Premium kerak")
                    .setMessage("Free tarifda 1 ta oila kodi saqlanadi. Premium orqali yangi oila kodlarini yaratish va barcha oilalarni bitta paneldan boshqarish mumkin.")
                    .setPositiveButton("Premiumni ochish") { _, _ -> startActivity(Intent(this, PremiumActivity::class.java)) }
                    .setNegativeButton("Bekor qilish", null)
                    .show()
                return@checkIsPremium
            }
            AlertDialog.Builder(this)
                .setTitle("Yangi oila kodi yaratish")
                .setMessage("Bu yangi, alohida oila yaratadi. Eski oila kodi o‘chmaydi — u 'Mening oilalarim' ro‘yxatida saqlanadi.")
                .setPositiveButton("Yangi kod yaratish") { _, _ -> generateAndCreateFamily() }
                .setNegativeButton("Bekor qilish", null)
                .show()
        }
    }

    private fun generateAndCreateFamily() {
        val code = generateFamilyCode()
        FirebaseRepo.createFamily(code) { ok, err ->
            if (ok) showGeneratedCodeDialog(code)
            else Toast.makeText(this, "Kod yaratishda xato: ${err ?: "noma'lum"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun generateFamilyCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun showGeneratedCodeDialog(code: String) {
        AlertDialog.Builder(this)
            .setTitle("Yangi oila kodi yaratildi ⭐")
            .setMessage("$code\n\nBu kodni yangi bolaning Android qurilmasiga kiriting. Eski oilalar va ularning kodlari saqlanib qoladi.")
            .setPositiveButton("Tushunarli") { _, _ -> saveFamilyCodeAndReturn(code) }
            .setCancelable(false)
            .show()
    }

    private fun saveFamilyCodeAndReturn(code: String) {
        val clean = code.trim().uppercase()
        addFamilyCode(clean)
        FirebaseRepo.familyCode = clean
        FirebaseRepo.childId = null
        getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString("family_code", clean)
            .remove("child_id")
            .apply()
        startActivity(Intent(this, ParentDashboardActivity::class.java))
        finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { moveTaskToBack(true) }
}
