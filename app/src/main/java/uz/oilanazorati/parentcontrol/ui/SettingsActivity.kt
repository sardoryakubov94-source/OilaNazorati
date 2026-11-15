package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
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

    private var pendingShortcutFamilyCode: String? = null
    private var pendingShortcutChildId: String? = null
    private var pendingShortcutLabel: String? = null

    private val shortcutImagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Rasm formati qo'llab-quvvatlanmaydi")
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 512 || bounds.outHeight / sampleSize > 512) sampleSize *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val source = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
                ?: throw IllegalStateException("Rasmni ochib bo'lmadi")
            val scaled = Bitmap.createScaledBitmap(source, 128, 128, true)
            val output = java.io.ByteArrayOutputStream()
            var quality = 82
            do {
                output.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                quality -= 8
            } while (output.size() > 90 * 1024 && quality >= 50)
            if (output.size() > 100 * 1024) throw IllegalStateException("Rasm hajmi juda katta")
            val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            sendChildShortcutRequest(
                pendingShortcutFamilyCode,
                pendingShortcutChildId,
                pendingShortcutLabel,
                "photo",
                encoded
            )
            if (source !== scaled) source.recycle()
            scaled.recycle()
        } catch (e: Exception) {
            Toast.makeText(this, "Rasmni tayyorlashda xato: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

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
        addSosNumbersRow()
        addChildAppearanceRow()
        addChildShortcutRow()
    }



    // ---------------- Bola qurilmasi launcher ko'rinishi ----------------
    private fun addChildAppearanceRow() {
        val anchor = findViewById<android.view.View>(R.id.rowTheme)
        val container = anchor.parent as? LinearLayout ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = androidx.core.content.ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_card_clickable_theme)
            isClickable = true
            isFocusable = true
        }
        row.addView(TextView(this).apply {
            text = "Bola qurilmasi ikonkasini tanlash"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.color_text_primary))
        })
        row.addView(TextView(this).apply {
            text = "Bulut, kalkulyator, bloknot va neytral shakllar"
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.color_text_secondary))
        })
        row.setOnClickListener { showChildAppearancePicker() }
        container.addView(row, container.indexOfChild(anchor) + 1)
    }

    private fun showChildAppearancePicker() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val code = FirebaseRepo.familyCode ?: prefs.getString("parent_family_code", null)
            ?: prefs.getString("family_code", null)
        val child = FirebaseRepo.childId ?: prefs.getString("parent_selected_child_id", null)
            ?: prefs.getString("child_id", null)
        if (code.isNullOrBlank() || child.isNullOrBlank()) {
            Toast.makeText(this, "Avval ota-ona panelida bolani tanlang.", Toast.LENGTH_LONG).show()
            return
        }
        val options = arrayOf(
            "Asl ikonka — Bulut servis",
            "Bulut belgisi",
            "Geometrik belgi"
        )
        val keys = arrayOf("default", "cloud", "geometric")
        val ref = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("families").document(code).collection("children").document(child)
        ref.get().addOnSuccessListener { doc ->
            val current = doc.getString("launcherAppearance") ?: "default"
            val selected = keys.indexOf(current).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Bola qurilmasi ikonkasini tanlang")
                .setMessage("Ilovaning haqiqiy nomi «Bulut servis» bo'lib qoladi. Tanlangan ikonka bola qurilmasidagi bosh ekranda ko'rinadi.")
                .setSingleChoiceItems(options, selected) { dialog, which ->
                    val key = keys[which]
                    ref.set(
                        mapOf(
                            "launcherAppearance" to key,
                            "launcherAppearanceUpdatedAtMs" to System.currentTimeMillis()
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    ).addOnSuccessListener {
                        Toast.makeText(this, "Tanlov bola qurilmasiga yuborildi.", Toast.LENGTH_SHORT).show()
                    }.addOnFailureListener {
                        Toast.makeText(this, "Saqlashda xato: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Bekor qilish", null)
                .show()
        }.addOnFailureListener {
            Toast.makeText(this, "Bola qurilmasi ma'lumotini olishda xato.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Maxsus bosh ekran yorlig'i ----------------
    private fun addChildShortcutRow() {
        val anchor = findViewById<android.view.View>(R.id.rowTheme)
        val container = anchor.parent as? LinearLayout ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = androidx.core.content.ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_card_clickable_theme)
            isClickable = true
            isFocusable = true
        }
        row.addView(TextView(this).apply {
            text = "Bosh ekran yorlig'i: nom va rasm"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.color_text_primary))
        })
        row.addView(TextView(this).apply {
            text = "O'zingiz nom yozing yoki galereyadan rasm tanlang. Bola telefonida qo'shish tasdiqlanadi."
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.color_text_secondary))
        })
        row.setOnClickListener { showChildShortcutEditor() }
        container.addView(row, container.indexOfChild(anchor) + 2)
    }

    private fun showChildShortcutEditor() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val code = FirebaseRepo.familyCode ?: prefs.getString("parent_family_code", null)
            ?: prefs.getString("family_code", null)
        val child = FirebaseRepo.childId ?: prefs.getString("parent_selected_child_id", null)
            ?: prefs.getString("child_id", null)
        if (code.isNullOrBlank() || child.isNullOrBlank()) {
            Toast.makeText(this, "Avval ota-ona panelida bolani tanlang.", Toast.LENGTH_LONG).show()
            return
        }

        val nameInput = EditText(this).apply {
            hint = "Masalan: Oila nazorati"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(18))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val iconPicker = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Bulut belgisi", "Geometrik belgi", "Ilova belgisi")
            )
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(this@SettingsActivity).apply {
                text = "Yorliq nomini yozing. Yorliq Bulut servis ilovasini ochishini bola tasdiqlash oynasida ko'radi."
                textSize = 13f
            })
            addView(nameInput)
            addView(TextView(this@SettingsActivity).apply {
                text = "Ikonka"
                setPadding(0, dp(12), 0, dp(4))
                textSize = 13f
            })
            addView(iconPicker)
        }
        AlertDialog.Builder(this)
            .setTitle("Bosh ekran yorlig'i")
            .setView(layout)
            .setPositiveButton("Yuborish") { _, _ ->
                val label = nameInput.text?.toString()?.trim().orEmpty()
                if (label.isBlank()) {
                    Toast.makeText(this, "Yorliq nomini kiriting.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val key = when (iconPicker.selectedItemPosition) {
                    1 -> "geometric"
                    2 -> "default"
                    else -> "cloud"
                }
                sendChildShortcutRequest(code, child, label, key, null)
            }
            .setNeutralButton("Galereyadan rasm") { _, _ ->
                val label = nameInput.text?.toString()?.trim().orEmpty()
                if (label.isBlank()) {
                    Toast.makeText(this, "Avval yorliq nomini kiriting.", Toast.LENGTH_SHORT).show()
                    return@setNeutralButton
                }
                pendingShortcutFamilyCode = code
                pendingShortcutChildId = child
                pendingShortcutLabel = label
                shortcutImagePicker.launch("image/*")
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun sendChildShortcutRequest(
        familyCode: String?,
        childId: String?,
        label: String?,
        iconKey: String,
        iconBase64: String?
    ) {
        if (familyCode.isNullOrBlank() || childId.isNullOrBlank() || label.isNullOrBlank()) return
        val fields = mutableMapOf<String, Any>(
            "launcherShortcutLabel" to label.take(18),
            "launcherShortcutIconKey" to iconKey,
            "launcherShortcutRequestId" to java.util.UUID.randomUUID().toString(),
            "launcherShortcutUpdatedAtMs" to System.currentTimeMillis()
        )
        if (!iconBase64.isNullOrBlank()) fields["launcherShortcutIconBase64"] = iconBase64
        else fields["launcherShortcutIconBase64"] = ""
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("families").document(familyCode)
            .collection("children").document(childId)
            .set(fields, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(this, "So'rov bola qurilmasiga yuborildi. U yerda tasdiqlash talab qilinadi.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Yuborishda xato: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
            }
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

    // ---------------- SOS raqamlari (bola uchun) ----------------
    private fun addSosNumbersRow() {
        val anchor = findViewById<android.view.View>(R.id.rowTheme)
        val container = anchor.parent as? android.widget.LinearLayout ?: return
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = androidx.core.content.ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_card_clickable_theme)
            isClickable = true
            isFocusable = true
        }
        row.addView(TextView(this).apply {
            text = "SOS raqamlari (bola uchun)"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.color_text_primary))
        })
        row.addView(TextView(this).apply {
            text = "Bola SOS tugmasini bossa, joylashuvi shu raqamlarga SMS qilinadi (2 tagacha)"
            textSize = 12f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.color_text_secondary))
        })
        row.setOnClickListener { showSosNumbersDialog() }
        container.addView(row, container.indexOfChild(anchor) + 1)
    }

    private fun showSosNumbersDialog() {
        val code = FirebaseRepo.familyCode ?: getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString("family_code", null)
        val childId = FirebaseRepo.childId
        if (code.isNullOrBlank() || childId.isNullOrBlank()) {
            android.widget.Toast.makeText(this, "Avval panelda bolani tanlang.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val ref = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("families").document(code).collection("children").document(childId)
        fun field(hint: String) = EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        val first = field("1-raqam (masalan +998901234567)")
        val second = field("2-raqam (ixtiyoriy)")
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(first)
            addView(second)
        }
        ref.get().addOnSuccessListener { doc ->
            val list = (doc.get("sosNumbers") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            first.setText(list.getOrNull(0).orEmpty())
            second.setText(list.getOrNull(1).orEmpty())
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("SOS raqamlari")
            .setMessage("Bola SOS tugmasini bossa, oxirgi joylashuvi shu raqamlarga SMS qilinadi, SMS ketmasa 1-raqamga qo'ng'iroq qilinadi.")
            .setView(layout)
            .setPositiveButton("Saqlash") { _, _ ->
                val numbers = listOf(first.text.toString(), second.text.toString())
                    .filter { it.isNotBlank() }
                    .map { uz.oilanazorati.parentcontrol.util.SosHelper.normalize(it) }
                if (numbers.any { it == null }) {
                    android.widget.Toast.makeText(this, "Raqam noto'g'ri. Masalan: +998901234567", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    ref.set(
                        mapOf("sosNumbers" to numbers.filterNotNull(), "sosUpdatedAtMs" to System.currentTimeMillis()),
                        com.google.firebase.firestore.SetOptions.merge()
                    ).addOnSuccessListener {
                        android.widget.Toast.makeText(this, "SOS raqamlari saqlandi.", android.widget.Toast.LENGTH_SHORT).show()
                    }.addOnFailureListener {
                        android.widget.Toast.makeText(this, "Saqlanmadi: ${it.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

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
