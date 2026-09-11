package uz.oilanazorati.parentcontrol.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
import uz.oilanazorati.parentcontrol.service.AppDeviceAdminReceiver
import uz.oilanazorati.parentcontrol.service.MonitorForegroundService
import uz.oilanazorati.parentcontrol.util.ContactSyncHelper
import uz.oilanazorati.parentcontrol.util.SimInfoSync

class ChildSetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChildSetupBinding
    private val runtimePermissions = arrayOf(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.READ_PHONE_NUMBERS, android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> if (result.values.all { it }) { SimInfoSync.syncNow(this); requestBackgroundLocationIfNeeded() } else showExplanationDialog(); updatePermissionStatusUi() }
    private val microphonePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> updatePermissionStatusUi(); binding.pairStatusText.text = if (granted) "✅ Mikrofon ruxsati berildi" else "Mikrofon ruxsati berilmadi" }
    private val phoneInfoPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> updateRoleStatusUi(); if (result.values.all { it }) SimInfoSync.syncNow(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnPair.setOnClickListener { pairWithFamilyCode() }
        binding.btnGrantPermissions.setOnClickListener { requestOrOpenRuntimePermissions() }
        binding.btnMicrophonePermission.setOnClickListener { requestMicrophonePermission() }
        binding.btnUsageAccess.setOnClickListener { requestUsageAccess() }
        binding.btnAccessibilityScreenshot.setOnClickListener { openAccessibilitySettings() }
        binding.btnDefaultPhone.setOnClickListener { requestDefaultPhoneRole() }
        binding.btnDefaultSms.setOnClickListener { requestDefaultSmsRole() }
        binding.btnPhoneInfoPermission.setOnClickListener { requestPhoneInfoPermission() }
        binding.btnNotificationAccess.setOnClickListener { requestNotificationAccess() }
        binding.btnBatteryOptimization.setOnClickListener { requestIgnoreBatteryOptimization() }
        binding.btnSyncContacts.setOnClickListener { syncContactsNow() }
        binding.btnDeviceAdmin.setOnClickListener { requestDeviceAdmin() }
        binding.btnFinish.setOnClickListener { finishSetupAndStartMonitoring() }
        restoreSavedPairingIntoUi()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) })
    }

    private fun requestOrOpenRuntimePermissions() {
        val allGranted = runtimePermissions.all { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            AlertDialog.Builder(this).setTitle("Ruxsatlar berilgan").setMessage("Ruxsatlarni o'chirish uchun Android ilova sozlamalarini oching va kerakli ruxsatni o'chiring.").setPositiveButton("Sozlamani ochish") { _, _ -> openAppSettings() }.setNegativeButton("Bekor qilish", null).show()
        } else permissionLauncher.launch(runtimePermissions)
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("♿ Ekran skrinshoti uchun Accessibility")
            .setMessage("""
                Accessibility ruxsati ekran skrinshotlarini olish uchun kerak.

                Agar Accessibility bo'limida "Oila Nazorati" xira bo'lib, yoqilmasa, Android xavfsizlik sababli ilova uchun cheklangan sozlamalar hali ochilmagan bo'ladi.

                1-qadam — ilova sozlamalarini oching
                "Ilova sozlamalariga o'tish" tugmasini bosing. Ochilgan sahifada aynan "Oila Nazorati" ilovasi bo'ladi.

                2-qadam — cheklangan imkoniyatlarni oching
                Ilova sahifasining yuqori o'ng tomonidagi ⋮ uch nuqtani bosing va "Cheklangan sozlamalarga ruxsat berish"ni tanlang. Bu Accessibility xira bo'lib qolishining oldini oladi.

                3-qadam — Accessibility'ga qayting
                Orqaga qaytib Accessibility bo'limini oching. Endi "Oila Nazorati" xira bo'lmasligi kerak.

                4-qadam — skrinshot ruxsatini yoqing
                "Oila Nazorati" xizmatiga kiring va Accessibility ruxsatini yoqing.

                Agar telefon uch nuqtani ko'rsatmasa, avval ilova sahifasida "Cheklangan sozlamalar" bilan bog'liq bandni qidiring. Android/telefon ishlab chiqaruvchisiga qarab nomi biroz farq qilishi mumkin.
            """.trimIndent())
            .setPositiveButton("📱 Ilova sozlamalariga o'tish") { _, _ -> openAppSettings() }
            .setNeutralButton("♿ Accessibility'ni ochish") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Yopish", null)
            .show()
    }

    private fun requestMicrophonePermission() {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) { openAppSettings(); return }
        microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun requestPhoneInfoPermission() {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) { openAppSettings(); return }
        phoneInfoPermissionLauncher.launch(arrayOf(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.READ_PHONE_NUMBERS))
    }

    private fun requestUsageAccess() {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) else @Suppress("DEPRECATION") appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        if (mode == android.app.AppOpsManager.MODE_ALLOWED) openAppSettings() else startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun requestDeviceAdmin() {
        val compName = ComponentName(this, AppDeviceAdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(compName)) {
            AlertDialog.Builder(this).setTitle("Himoyani o'chirish").setMessage("Ilovani o'chirishdan himoyalash ruxsatini o'chirmoqchimisiz?").setPositiveButton("O'chirish") { _, _ -> dpm.removeActiveAdmin(compName); updateDeviceAdminStatusUi() }.setNegativeButton("Bekor qilish", null).show()
            return
        }
        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply { putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName); putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Bu ilovani tasodifan yoki ruxsatsiz o'chirib tashlanishidan himoya qiladi.") })
    }

    private fun updateDeviceAdminStatusUi() {
        val compName = ComponentName(this, AppDeviceAdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        binding.btnDeviceAdmin.text = if (dpm.isAdminActive(compName)) "✅ O'chirishdan himoyalangan — o'chirish" else "🔒 Ilovani o'chirishdan himoyalash"
    }

    private fun updatePermissionStatusUi() {
        val micGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        binding.btnMicrophonePermission.text = if (micGranted) "✅ Mikrofon ruxsati berilgan — o'chirish" else "🎙️ Mikrofon ruxsatini berish"
        updateRoleStatusUi()
    }

    private fun restoreSavedPairingIntoUi() {
        val prefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE); val savedCode = prefs.getString("family_code", null); val isChildDevice = prefs.getBoolean("is_child_device", false)
        if (savedCode != null) { binding.inputFamilyCode.setText(savedCode); prefs.getString("child_name", null)?.let { binding.inputChildName.setText(it) }; if (FirebaseRepo.familyCode == null) FirebaseRepo.familyCode = savedCode; if (FirebaseRepo.childId == null) FirebaseRepo.childId = prefs.getString("child_id", null) ?: FirebaseAuth.getInstance().currentUser?.uid; binding.pairStatusText.text = if (isChildDevice) "✅ Ulandi: $savedCode (nazorat ishga tushirilgan)" else "✅ Ulandi: $savedCode" }
    }

    override fun onResume() {
        super.onResume(); updateRoleStatusUi(); updatePermissionStatusUi(); updateDeviceAdminStatusUi()
        val pm = getSystemService(android.os.PowerManager::class.java); binding.btnBatteryOptimization.text = if (pm.isIgnoringBatteryOptimizations(packageName)) "✅ Batareya tejashdan chiqarilgan — o'chirish" else "🔋 Batareya tejashdan chiqarish (muhim!)"
    }

    private fun pairWithFamilyCode() { val code = binding.inputFamilyCode.text?.toString()?.trim()?.uppercase(); if (code.isNullOrBlank() || code.length != 6) { binding.inputFamilyCode.error = "6 xonali kodni kiriting (ota-ona ekranidan oling)"; return }; val currentUser = FirebaseAuth.getInstance().currentUser; if (currentUser != null && currentUser.isAnonymous) finishPairing(code, currentUser.uid) else FirebaseAuth.getInstance().signInAnonymously().addOnSuccessListener { result -> result.user?.uid?.let { finishPairing(code, it) } }.addOnFailureListener { binding.pairStatusText.text = "Ulanishda xato yuz berdi, qayta urinib ko'ring" } }
    private fun finishPairing(code: String, uid: String) { FirebaseRepo.familyCode = code; FirebaseRepo.childId = uid; val childName = binding.inputChildName.text?.toString()?.trim().orEmpty(); getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit().putString("family_code", code).putString("child_id", uid).putString("child_name", childName).apply(); FirebaseRepo.saveChildProfile(childName); SimInfoSync.syncNow(this); binding.pairStatusText.text = "✅ Ulandi: $code" + if (childName.isNotBlank()) " ($childName sifatida)" else "" }
    private fun requestDefaultPhoneRole() { val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED; if (!granted) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_CALL_LOG), 1004) else openAppSettings() }
    private fun requestDefaultSmsRole() { val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED; if (!granted) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS), 1003) else openAppSettings() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); updateRoleStatusUi(); if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) SimInfoSync.syncNow(this) }
    private fun updateRoleStatusUi() { val hasPhone = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED; binding.btnDefaultPhone.text = if (hasPhone) "✅ Qo'ng'iroq kuzatuvi yoqilgan — o'chirish" else "Qo'ng'iroq kuzatuvini yoqish"; val hasSms = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED; binding.btnDefaultSms.text = if (hasSms) "✅ SMS kuzatuvi yoqilgan — o'chirish" else "SMS kuzatuvini yoqish"; binding.btnNotificationAccess.text = if (isNotificationAccessGranted()) "✅ Bildirishnoma kuzatuvi yoqilgan — o'chirish" else "Ijtimoiy tarmoq bildirishnomalarini yoqish"; val phoneInfoGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED; binding.btnPhoneInfoPermission.text = if (phoneInfoGranted) "✅ SIM/telefon raqami ruxsati berilgan — o'chirish" else "📱 SIM/telefon raqami ruxsatini berish"; if (phoneInfoGranted && FirebaseRepo.familyCode != null && FirebaseRepo.childId != null) SimInfoSync.syncNow(this) }
    private fun requestNotificationAccess() { if (isNotificationAccessGranted()) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); return }; AlertDialog.Builder(this).setTitle("Bildirishnoma kirishi").setMessage("Keyingi ekranda \"Oila Nazorati\"ni toping va yoqing.").setPositiveButton("Davom etish") { _, _ -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.show() }
    private fun isNotificationAccessGranted(): Boolean { val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false; return enabledListeners.contains(packageName) }
    private fun requestIgnoreBatteryOptimization() { val pm = getSystemService(android.os.PowerManager::class.java); val pkg = packageName; if (pm.isIgnoringBatteryOptimizations(pkg)) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); return }; AlertDialog.Builder(this).setTitle("Batareya tejashdan chiqarish").setMessage("Keyingi sozlama ekranida bu ilovani cheklangan yoki cheklanmagan holatga o'zgartirishingiz mumkin.").setPositiveButton("Sozlamaga o'tish") { _, _ -> try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$pkg") }) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }.show() }
    private fun requestBackgroundLocationIfNeeded() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED; if (!granted) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 1001) } }
    private fun showExplanationDialog() { AlertDialog.Builder(this).setTitle("Ruxsatlar kerak").setMessage("Statistika va Oila Nazorati funksiyalari uchun so'ralgan ruxsatlar zarur. Mikrofon ruxsati faqat ota-ona panelidan jonli ovoz funksiyasi yoqilganda ishlatiladi. Telefon raqami uchun Androidning 'Telefon raqamlariga ruxsat' so'rovi ham bir marta beriladi; operator raqamni taqdim qilmasa panelda 'Aniqlanmadi' ko'rsatiladi.").setPositiveButton("Sozlamalarga o'tish") { _, _ -> openAppSettings() }.setNegativeButton("Yopish", null).show() }
    private fun syncContactsNow() { val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED; if (!granted) { ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_CONTACTS), 1002); return }; ContactSyncHelper.syncNow(this); binding.pairStatusText.text = "✅ Saqlangan kontaktlar sinxronlandi (raqamlarsiz, faqat ism+rang)" }
    private fun finishSetupAndStartMonitoring() { if (FirebaseRepo.familyCode == null) { binding.pairStatusText.text = "Avval oila kodini kiriting"; return }; if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { binding.pairStatusText.text = "Avval Mikrofon ruxsatini bering"; return }; getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit().putBoolean("is_child_device", true).apply(); SimInfoSync.start(this); ContextCompat.startForegroundService(this, Intent(this, MonitorForegroundService::class.java)); binding.pairStatusText.text = "✅ Nazorat ishga tushdi" }
}
