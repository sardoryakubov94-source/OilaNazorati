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
import uz.oilanazorati.parentcontrol.util.AppPasswordUtil

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
        findViewById<android.view.View>(R.id.rowChangePassword).setOnClickListener { showChangePasswordDialog() }
        findViewById<android.view.View>(R.id.premiumBanner).setOnClickListener { startActivity(Intent(this, PremiumActivity::class.java)) }
        findViewById<android.view.View>(R.id.btnPremiumCta).setOnClickListener { startActivity(Intent(this, PremiumActivity::class.java)) }

        AdminConfig.checkCurrentUserAdmin { isAdmin ->
            if (isAdmin) {
                findViewById<android.view.View>(R.id.adminSection).visibility = android.view.View.VISIBLE
                findViewById<android.view.View>(R.id.rowAdminPanel).setOnClickListener { startActivity(Intent(this, AdminPanelActivity::class.java)) }
            }
        }

        findViewById<android.view.View>(R.id.rowSignOut).setOnClickListener { confirmSignOut() }
    }


    // ---------------- Ilova (kirish) parolini o'zgartirish ----------------

    private fun showChangePasswordDialog() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val savedHash = prefs.getString("app_password_hash", null)

        fun pwField(hint: String): EditText = EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val oldField = pwField("Joriy parol")
        val newField = pwField("Yangi parol (kamida 4 ta raqam)")
        val confirmField = pwField("Yangi parolni takrorlang")

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            if (savedHash != null) addView(oldField)
            addView(newField)
            addView(confirmField)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Ilova parolini o'zgartirish")
            .setView(form)
            .setPositiveButton("Saqlash", null)
            .setNegativeButton("Bekor qilish", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val old = oldField.text?.toString().orEmpty()
                val fresh = newField.text?.toString().orEmpty()
                val confirm = confirmField.text?.toString().orEmpty()

                if (savedHash != null && AppPasswordUtil.hash(old) != savedHash) {
                    Toast.makeText(this, "Joriy parol noto'g'ri", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (fresh.length < 4) {
                    Toast.makeText(this, "Yangi parol kamida 4 ta raqamdan iborat bo'lsin", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (fresh != confirm) {
                    Toast.makeText(this, "Yangi parollar mos kelmadi", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("app_password_hash", AppPasswordUtil.hash(fresh)).apply()
                Toast.makeText(this, "Parol yangilandi", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Hisobdan chiqish")
            .setMessage("Joriy Google hisobidan chiqasiz. Keyingi safar kirganda istalgan Gmail hisobini tanlashingiz mumkin bo'ladi.")
            .setPositiveButton("Chiqish") { _, _ -> performSignOut() }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun performSignOut() {
        val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions
            .Builder(com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        val googleClient = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, gso)
        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
        googleClient.signOut().addOnCompleteListener {
            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
                .remove("family_code").remove("child_id").remove(familyCodesKey).apply()
            FirebaseRepo.familyCode = null
            FirebaseRepo.childId = null
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
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
    override fun onBackPressed() {
        startActivity(Intent(this, ParentDashboardActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP })
        finish()
    }
}
