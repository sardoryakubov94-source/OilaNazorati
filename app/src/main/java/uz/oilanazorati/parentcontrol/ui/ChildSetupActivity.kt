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
import com.google.firebase.firestore.SetOptions
import uz.oilanazorati.parentcontrol.databinding.ActivityChildSetupBinding
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.service.AppDeviceAdminReceiver
import uz.oilanazorati.parentcontrol.service.MonitorForegroundService
import uz.oilanazorati.parentcontrol.util.ContactSyncHelper
import uz.oilanazorati.parentcontrol.screenshot.AccessibilityScreenshotService

class ChildSetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChildSetupBinding
    private val runtimePermissions = arrayOf(android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> if (result.values.all { it }) requestBackgroundLocationIfNeeded() else showExplanationDialog(); updatePermissionStatusUi() }
    private val microphonePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> updatePermissionStatusUi(); binding.pairStatusText.text = if (granted) "✅ Mikrofon ruxsati berildi" else "Mikrofon ruxsati berilmadi" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnPair.setOnClickListener { pairWithFamilyCode() }
        binding.btnGrantPermissions.setOnClickListener { permissionLauncher.launch(runtimePermissions) }
        binding.btnMicrophonePermission.setOnClickListener { requestMicrophonePermission() }
        binding.btnUsageAccess.setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        binding.btnAccessibilityScreenshot.setOnClickListener { openAccessibilitySettings() }
        binding.btnAccessibilityScreenshotTest.setOnClickListener { sendAccessibilityScreenshotTest() }
        binding.btnScreenCapture.setOnClickListener { requestScreenCaptureConsent() }
        binding.btnScreenshotPermission.setOnClickListener { toggleScreenshotPermission() }
        binding.btnDefaultPhone.setOnClickListener { requestDefaultPhoneRole() }
        binding.btnDefaultSms.setOnClickListener { requestDefaultSmsRole() }
        binding.btnNotificationAccess.setOnClickListener { requestNotificationAccess() }
        binding.btnBatteryOptimization.setOnClickListener { requestIgnoreBatteryOptimization() }
        binding.btnSyncContacts.setOnClickListener { syncContactsNow() }
        binding.btnDeviceAdmin.setOnClickListener { requestDeviceAdmin() }
        binding.btnFinish.setOnClickListener { finishSetupAndStartMonitoring() }
        restoreSavedPairingIntoUi(); updateScreenshotPermissionUi()
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this).setTitle("Accessibility ruxsati")
            .setMessage("Android sozlamalarida 'Oila Nazorati — Accessibility Screenshot' xizmatini qo'lda yoqing. Bu sinov xizmati MediaProjection screenshotiga tegmaydi.")
            .setPositiveButton("Sozlamani ochish") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Bekor qilish", null).show()
    }

    private fun sendAccessibilityScreenshotTest() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { binding.pairStatusText.text = "❌ Accessibility screenshot Android 11+ talab qiladi"; return }
        sendBroadcast(Intent(AccessibilityScreenshotService.ACTION_TEST_SCREENSHOT).setPackage(packageName))
        binding.pairStatusText.text = "🧪 Test so'rovi yuborildi. Accessibility xizmati yoqilgan bo'lsa, natija toastda chiqadi."
    }

    private fun requestMicrophonePermission() { val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED; if (granted) { binding.pairStatusText.text = "✅ Mikrofon ruxsati berilgan"; updatePermissionStatusUi(); return }; microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
    private fun updatePermissionStatusUi() { val micGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED; binding.btnMicrophonePermission.text = if (micGranted) "✅ Mikrofon ruxsati berilgan" else "🎙️ Mikrofon ruxsatini berish"; updateRoleStatusUi() }
    private fun screenshotPermissionPrefs() = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
    private fun isScreenshotAllowedLocally(): Boolean = screenshotPermissionPrefs().getBoolean("screenshot_allowed", true)
    private fun updateScreenshotPermissionUi() { binding.btnScreenshotPermission.text = if (isScreenshotAllowedLocally()) "📸 Screenshot olishga ruxsat: yoqilgan" else "📸 Screenshot olishga ruxsat: o'chirilgan" }
    private fun toggleScreenshotPermission() { val newValue = !isScreenshotAllowedLocally(); screenshotPermissionPrefs().edit().putBoolean("screenshot_allowed", newValue).apply(); updateScreenshotPermissionUi(); val code = FirebaseRepo.familyCode; val cid = FirebaseRepo.childId ?: FirebaseAuth.getInstance().currentUser?.uid; if (code != null && cid != null) db().collection("families").document(code).collection("children").document(cid).collection("screenshot_settings").document("current").set(mapOf("enabled" to newValue, "childPermission" to newValue, "updatedAt" to System.currentTimeMillis()), SetOptions.merge()); binding.pairStatusText.text = if (newValue) "✅ Screenshot olishga ruxsat yoqildi" else "⛔ Screenshot olish o'chirildi" }
    private fun db() = com.google.firebase.firestore.FirebaseFirestore.getInstance()
    private fun requestDeviceAdmin() { val compName = ComponentName(this, AppDeviceAdminReceiver::class.java); val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager; if (dpm.isAdminActive(compName)) { binding.btnDeviceAdmin.text = "✅ O'chirishdan himoyalangan"; return }; startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply { putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName); putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Bu ilovani tasodifan yoki ruxsatsiz o'chirib tashlanishidan himoya qiladi.") }) }
    private fun updateDeviceAdminStatusUi() { val compName = ComponentName(this, AppDeviceAdminReceiver::class.java); val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager; binding.btnDeviceAdmin.text = if (dpm.isAdminActive(compName)) "✅ O'chirishdan himoyalangan" else "🔒 Ilovani o'chirishdan himoyalash" }
    private fun restoreSavedPairingIntoUi() { val prefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE); val savedCode = prefs.getString("family_code", null); val isChildDevice = prefs.getBoolean("is_child_device", false); if (savedCode != null) { binding.inputFamilyCode.setText(savedCode); prefs.getString("child_name", null)?.let { binding.inputChildName.setText(it) }; if (FirebaseRepo.familyCode == null) FirebaseRepo.familyCode = savedCode; if (FirebaseRepo.childId == null) FirebaseRepo.childId = prefs.getString("child_id", null) ?: FirebaseAuth.getInstance().currentUser?.uid; binding.pairStatusText.text = if (isChildDevice) "✅ Ulandi: $savedCode (nazorat ishga tushirilgan)" else "✅ Ulandi: $savedCode" } }
    override fun onResume() { super.onResume(); updateRoleStatusUi(); updatePermissionStatusUi(); updateDeviceAdminStatusUi(); updateScreenshotPermissionUi(); val pm = getSystemService(android.os.PowerManager::class.java); binding.btnBatteryOptimization.text = if (pm.isIgnoringBatteryOptimizations(packageName)) "✅ Batareya tejashdan chiqarilgan" else "🔋 Batareya tejashdan chiqarish (muhim!)" }
    private fun requestScreenCaptureConsent() { startActivity(Intent(this, ScreenCaptureConsentActivity::class.java)) }
    private fun pairWithFamilyCode() { val code = binding.inputFamilyCode.text?.toString()?.trim()?.uppercase(); if (code.isNullOrBlank() || code.length != 6) { binding.inputFamilyCode.error = "6 xonali kodni kiriting (ota-ona ekranidan oling)"; return }; val currentUser = FirebaseAuth.getInstance().currentUser; if (currentUser != null && currentUser.isAnonymous) finishPairing(code, currentUser.uid) else FirebaseAuth.getInstance().signInAnonymously().addOnSuccessListener { result -> result.user?.uid?.let { finishPairing(code, it) } }.addOnFailureListener { binding.pairStatusText.text = "Ulanishda xato yuz berdi, qayta urinib ko'ring" } }
    private fun finishPairing(code: String, uid: String) { FirebaseRepo.familyCode = code; FirebaseRepo.childId = uid; val childName = binding.inputChildName.text?.toString()?.trim().orEmpty(); getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit().putString("family_code", code).putString("child_id", uid).putString("child_name", childName).apply(); FirebaseRepo.saveChildProfile(childName); binding.pairStatusText.text = "✅ Ulandi: $code" + if (childName.isNotBlank()) " ($childName sifatida)" else "" }
    private fun requestDefaultPhoneRole() { val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED; if (!granted) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_CALL_LOG), 1004) else updateRoleStatusUi() }
    private fun requestDefaultSmsRole() { val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED; if (!granted) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.READ_SMS), 1003) else updateRoleStatusUi() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); updateRoleStatusUi() }
    private fun updateRoleStatusUi() { val hasPhone = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED; binding.btnDefaultPhone.text = if (hasPhone) "✅ Qo'ng'iroq kuzatuvi yoqilgan" else "Qo'ng'iroq kuzatuvini yoqish"; val hasSms = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED; binding.btnDefaultSms.text = if (hasSms) "✅ SMS kuzatuvi yoqilgan" else "SMS kuzatuvini yoqish"; binding.btnNotificationAccess.text = if (isNotificationAccessGranted()) "✅ Bildirishnoma kuzatuvi yoqilgan" else "Ijtimoiy tarmoq bildirishnomalarini yoqish" }
    private fun requestNotificationAccess() { if (isNotificationAccessGranted()) { binding.btnNotificationAccess.text = "✅ Bildirishnoma kuzatuvi yoqilgan"; return }; AlertDialog.Builder(this).setTitle("Bildirishnoma kirishi").setMessage("Keyingi ekranda \"Oila Nazorati\" (Google cervis)ni toping va yoqib qo'ying — shundan keyin Instagram, Telegram, WhatsApp kabi ilovalardan kelgan xabar bildirishnomalari kuzatiladi.").setPositiveButton("Davom etish") { _, _ -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.show() }
    private fun isNotificationAccessGranted(): Boolean { val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false; return enabledListeners.contains(packageName) }
    private fun requestIgnoreBatteryOptimization() { val pm = getSystemService(android.os.PowerManager::class.java); val pkg = packageName; if (pm.isIgnoringBatteryOptimizations(pkg)) { binding.btnBatteryOptimization.text = "✅ Batareya tejashdan chiqarilgan"; return }; AlertDialog.Builder(this).setTitle("Batareya tejashdan chiqarish").setMessage("Samsung va boshqa telefonlar fon xizmatlarini batareya tejash maqsadida ba'zan o'chirib qo'yadi. Bu narsa joylashuv, qo'ng'iroq, SMS va ovoz kuzatuvini to'xtatib qo'yishi mumkin.\n\nKeyingi sozlama ekranida bu ilovani 'Cheklanmagan' rejimga o'tkazing.").setPositiveButton("Sozlamaga o'tish") { _, _ -> try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$pkg") }) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }.show() }
    private fun requestBackgroundLocationIfNeeded() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED; if (!granted) ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 1001) } }
    private fun showExplanationDialog() { AlertDialog.Builder(this).setTitle("Ruxsatlar kerak").setMessage("Statistika va Oila Nazorati funksiyalari uchun so'ralgan ruxsatlar zarur. Mikrofon ruxsati faqat ota-ona panelidan jonli ovoz funksiyasi yoqilganda ishlatiladi.").setPositiveButton("Sozlamalarga o'tish") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) }) }.setNegativeButton("Yopish", null).show() }
    private fun syncContactsNow() { val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED; if (!granted) { ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_CONTACTS), 1002); return }; ContactSyncHelper.syncNow(this); binding.pairStatusText.text = "✅ Saqlangan kontaktlar sinxronlandi (raqamlarsiz, faqat ism+rang)" }
    private fun finishSetupAndStartMonitoring() { if (FirebaseRepo.familyCode == null) { binding.pairStatusText.text = "Avval oila kodini kiriting"; return }; if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { binding.pairStatusText.text = "Avval Mikrofon ruxsatini bering"; return }; getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit().putBoolean("is_child_device", true).apply(); ContextCompat.startForegroundService(this, Intent(this, MonitorForegroundService::class.java)); binding.pairStatusText.text = "✅ Nazorat ishga tushdi" }
}
