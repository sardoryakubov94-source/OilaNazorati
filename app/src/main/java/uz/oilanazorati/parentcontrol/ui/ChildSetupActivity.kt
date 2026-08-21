package uz.oilanazorati.parentcontrol.ui

import android.app.role.RoleManager
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

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { updateRoleStatusUi() }

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
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putString("family_code", code)
            .putString("child_id", uid)
            .apply()

        val childName = binding.inputChildName.text?.toString()?.trim().orEmpty()
        FirebaseRepo.saveChildProfile(childName)

        binding.pairStatusText.text = "✅ Ulandi: $code" +
            if (childName.isNotBlank()) " ($childName sifatida)" else ""
    }

    // ---------------- Standart Telefon/SMS ilovasi rolini so'rash ----------------
    // Android'da READ_CALL_LOG/READ_SMS to'liq ishlashi uchun ilova shu
    // rollarni egallashi SHART — bu Google Play siyosati talabi.

    private fun requestDefaultPhoneRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            }
        }
    }

    private fun requestDefaultSmsRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        } else {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            startActivity(intent)
        }
    }

    private fun updateRoleStatusUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            val hasPhone = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
            val hasSms = roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            binding.btnDefaultPhone.text = if (hasPhone) "✅ Qo'ng'iroq kuzatuvi yoqilgan" else "Qo'ng'iroq kuzatuvini yoqish"
            binding.btnDefaultSms.text = if (hasSms) "✅ SMS kuzatuvi yoqilgan" else "SMS kuzatuvini yoqish"
        }
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
