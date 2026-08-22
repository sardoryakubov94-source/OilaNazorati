package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import uz.oilanazorati.parentcontrol.databinding.ActivityChildSetupBinding
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.service.MonitorForegroundService
import uz.oilanazorati.parentcontrol.util.ContactSyncHelper

/**
 * Bu ekran BOLA qurilmasida, ota-ona (yoki bola o'zi, ota-onasi ko'magida)
 * tomonidan BIR MARTA sozlanadi. Har bir qadam alohida, foydalanuvchi nima
 * uchun ruxsat so'ralayotganini ko'rib turadi — YASHIRIN o'rnatish yo'q.
 */
class ChildSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildSetupBinding

    private val runtimePermissions = arrayOf(
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.READ_SMS,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            requestBackgroundLocationIfNeeded()
        } else {
            showExplanationDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPair.setOnClickListener { pairWithFamilyCode() }
        binding.btnGrantPermissions.setOnClickListener {
            permissionLauncher.launch(runtimePermissions)
        }
        binding.btnUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        binding.btnDefaultPhone.setOnClickListener { requestDefaultPhoneRole() }
        binding.btnDefaultSms.setOnClickListener { requestDefaultSmsRole() }
        binding.btnSyncContacts.setOnClickListener { syncContactsNow() }
        binding.btnFinish.setOnClickListener { finishSetupAndStartMonitoring() }

        restoreSavedPairingIntoUi()
    }

    /**
     * Bu qurilma avval allaqachon oila kodi bilan ulangan bo'lsa
     * (SharedPreferences'da saqlangan bo'lsa), ekran qayta ochilganda
     * kod va farzand ismi maydonlari BO'SH chiqmasligi uchun ularni
     * shu yerdan tiklaymiz. Aks holda foydalanuvchi "ulanish uzilib
     * qoldimi" deb chalkashishi mumkin, garchi aslida hech narsa
     * o'zgarmagan bo'lsa ham.
     */
    private fun restoreSavedPairingIntoUi() {
        val prefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
        val savedCode = prefs.getString("family_code", null)
        val isChildDevice = prefs.getBoolean("is_child_device", false)

        if (savedCode != null) {
            binding.inputFamilyCode.setText(savedCode)
            prefs.getString("child_name", null)?.let { binding.inputChildName.setText(it) }
            // Xotirada bo'lmasa (masalan jarayon qayta boshlangan), FirebaseRepo'ga ham joylaymiz
            if (FirebaseRepo.familyCode == null) FirebaseRepo.familyCode = savedCode
            if (FirebaseRepo.childId == null) {
                FirebaseRepo.childId = prefs.getString("child_id", null)
                    ?: FirebaseAuth.getInstance().currentUser?.uid
            }
            binding.pairStatusText.text = if (isChildDevice) {
                "✅ Ulandi: $savedCode (nazorat ishga tushirilgan)"
            } else {
                "✅ Ulandi: $savedCode"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateRoleStatusUi()
    }

    // ---------------- Oila kodi bilan bog'lash ----------------

    private fun pairWithFamilyCode() {
        val code = binding.inputFamilyCode.text?.toString()?.trim()?.uppercase()
        if (code.isNullOrBlank() || code.length != 6) {
            binding.inputFamilyCode.error = "6 xonali kodni kiriting (ota-ona ekranidan oling)"
            return
        }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseRepo.familyCode = code
        FirebaseRepo.childId = uid

        val childName = binding.inputChildName.text?.toString()?.trim().orEmpty()
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putString("family_code", code)
            .putString("child_id", uid)
            .putString("child_name", childName)
            .apply()

        FirebaseRepo.saveChildProfile(childName)

        binding.pairStatusText.text = "✅ Ulandi: $code" +
            if (childName.isNotBlank()) " ($childName sifatida)" else ""
    }

    // ---------------- Qo'ng'iroq/SMS kuzatuvi uchun runtime ruxsatlar ----------------
    // MUHIM: qo'ng'iroqlar endi CallLogObserver orqali (tizimning o'z
    // "Qo'ng'iroqlar tarixi" jadvalini o'qish) kuzatiladi — bu oddiy
    // READ_CALL_LOG runtime ruxsati bilan ishlaydi, ROLE_CALL_SCREENING
    // (standart Telefon ilovasi) bo'lish SHART EMAS. Bu qaror bola
    // qurilmasida odatdagi qo'ng'iroq/spam-himoya tajribasini
    // (masalan Samsung'ning o'z "АОН и защита от спама" xizmatini)
    // buzmasligi uchun ataylab tanlangan — xuddi SMS uchun qilinganidek.

    private fun requestDefaultPhoneRole() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.READ_CALL_LOG), 1004
            )
        } else {
            updateRoleStatusUi()
        }
    }

    private fun requestDefaultSmsRole() {
        // MUHIM: bu endi ROLE_SMS (standart SMS ilova) SO'RAMAYDI —
        // chunki bu bolaning butun SMS tajribasini bizning ilovamizga
        // o'tkazib yuborar edi (yozish/o'qish ishlamay qolardi).
        // SMS_RECEIVED_ACTION orqali kuzatuv uchun oddiy RECEIVE_SMS/
        // READ_SMS runtime ruxsatlari YETARLI — ular "Asosiy ruxsatlarni
        // berish" tugmasi bosilganda allaqachon so'raladi. Bu tugma
        // endi faqat o'sha ruxsatlar hali berilmagan bo'lsa, qayta
        // so'rash uchun qoladi.
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECEIVE_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.READ_SMS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS),
                1003
            )
        } else {
            updateRoleStatusUi()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateRoleStatusUi()
    }

    private fun updateRoleStatusUi() {
        val hasPhone = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        binding.btnDefaultPhone.text = if (hasPhone) "✅ Qo'ng'iroq kuzatuvi yoqilgan" else "Qo'ng'iroq kuzatuvini yoqish"

        val hasSms = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECEIVE_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        binding.btnDefaultSms.text = if (hasSms) "✅ SMS kuzatuvi yoqilgan" else "SMS kuzatuvini yoqish"
    }

    // ---------------- Fon lokatsiya ruxsati (alohida so'ralishi shart, Android 10+) ----------------

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                    1001
                )
            }
        }
    }

    private fun showExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Ruxsatlar kerak")
            .setMessage("Statistika yig'ish uchun barcha so'ralgan ruxsatlar zarur. Sozlamalardan qo'lda yoqishingiz mumkin.")
            .setPositiveButton("Sozlamalarga o'tish") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
            .setNegativeButton("Yopish", null)
            .show()
    }

    // ---------------- Saqlangan kontaktlarni sinxronlash ----------------
    // Faqat ISM BILAN saqlangan kontaktlar (masalan "Onam", "Dadam")
    // o'qiladi va nomi + anonim rang-identifikatori Firestore'ga yoziladi.
    // Saqlanmagan raqamlar bu jarayonga umuman kirmaydi.

    private fun syncContactsNow() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.READ_CONTACTS), 1002
            )
            return
        }
        ContactSyncHelper.syncNow(this)
        binding.pairStatusText.text = "✅ Saqlangan kontaktlar sinxronlandi (raqamlarsiz, faqat ism+rang)"
    }

    // ---------------- Sozlashni yakunlash ----------------

    private fun finishSetupAndStartMonitoring() {
        if (FirebaseRepo.familyCode == null) {
            binding.pairStatusText.text = "Avval oila kodini kiriting"
            return
        }
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putBoolean("is_child_device", true)
            .apply()

        ContextCompat.startForegroundService(this, Intent(this, MonitorForegroundService::class.java))
        binding.pairStatusText.text = "✅ Nazorat ishga tushdi"
    }
}
