package uz.oilanazorati.parentcontrol.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import uz.oilanazorati.parentcontrol.R
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
        val density = resources.displayMetrics.density
        val scroll = android.widget.ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
        }
        val intro = TextView(this).apply {
            text = "Skrinshot olish uchun quyidagi 3 qadamni bajaring. Rasmni kattalashtirib ko'rish uchun ustiga bosing:"
            textSize = 14f
            setPadding(0, 0, 0, (14 * density).toInt())
        }
        root.addView(intro)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f }
        val steps = listOf(
            R.drawable.access_step1_installed_apps to "1. Accessibility → Installed apps",
            R.drawable.access_step2_select_app to "2. Oila Nazorati'ni tanlang",
            R.drawable.access_step3_toggle_on to "3. Yoqing (ON)"
        )
        steps.forEach { (resId, caption) ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (6 * density).toInt(); marginStart = (6 * density).toInt()
                }
            }
            val img = ImageView(this).apply {
                setImageResource(resId)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (140 * density).toInt())
                setOnClickListener { showFullScreenImage(resId) }
            }
            val cap = TextView(this).apply { text = caption; textSize = 10f; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, (4 * density).toInt(), 0, 0) }
            col.addView(img); col.addView(cap)
            row.addView(col)
        }
        root.addView(row)

        val note = TextView(this).apply {
            text = "\nEslatma: agar \"Oila Nazorati\" ro'yxatda xira (bosib bo'lmaydigan) ko'rinsa — ilova sahifasidagi ⋮ menyudan \"Cheklangan sozlamalarga ruxsat berish\"ni tanlang, so'ng shu ekranga qaytib qadamlarni takrorlang."
            textSize = 12f
            setTextColor(getColor(android.R.color.darker_gray))
            setPadding(0, (16 * density).toInt(), 0, (8 * density).toInt())
        }
        root.addView(note)
        scroll.addView(root)

        AlertDialog.Builder(this)
            .setTitle("♿ Ekran skrinshoti uchun Accessibility")
            .setView(scroll)
            .setPositiveButton("♿ Sozlamalarga o'tish") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Yopish", null)
            .show()
    }

    private fun showFullScreenImage(resId: Int) {
        val img = ImageView(this).apply {
            setImageResource(resId)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(img)
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

    private fun pairWithFamilyCode() {
        val code = binding.inputFamilyCode.text?.toString()?.trim()?.uppercase()
        if (code.isNullOrBlank() || code.length != 6) { binding.inputFamilyCode.error = "6 xonali kodni kiriting (ota-ona ekranidan oling)"; return }
        binding.btnPair.isEnabled = false
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null && currentUser.isAnonymous) finishPairing(code, currentUser.uid)
        else {
            binding.pairStatusText.text = "Ulanish tekshirilmoqda..."
            FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener { result -> result.user?.uid?.let { finishPairing(code, it) } ?: run { binding.btnPair.isEnabled = true; binding.pairStatusText.text = "❌ Firebase foydalanuvchisi yaratilmadi" } }
                .addOnFailureListener { binding.btnPair.isEnabled = true; binding.pairStatusText.text = "Ulanishda xato yuz berdi, qayta urinib ko'ring" }
        }
    }
    private fun finishPairing(code: String, uid: String) {
        val childName = binding.inputChildName.text?.toString()?.trim().orEmpty()
        binding.pairStatusText.text = "Oila kodi tekshirilmoqda..."
        // MUHIM: kod mavjudligini alohida o'qish (get) bilan tekshirmaymiz — bola
        // anonim foydalanuvchi bo'lgani uchun Firestore qoidalari unga families/{code}
        // hujjatini o'qishga ruxsat bermaydi (faqat ota-ona o'qiy oladi). Shu sabab
        // to'g'ridan-to'g'ri yozishga urinamiz: agar kod noto'g'ri/mavjud bo'lmasa,
        // qoidaning o'zi (exists() tekshiruvi) yozishni PERMISSION_DENIED bilan
        // rad etadi va biz buni pastda ushlab, aniq xato ko'rsatamiz.
        //
        // TIMEOUT: internet yo'q yoki juda sekin bo'lsa, Firestore'ning o'zi
        // hech qachon (na muvaffaqiyat, na xato) javob bermay, cheksiz
        // "tekshirilmoqda..." holatida qolib ketishi mumkin edi. Shu sabab
        // 15 soniyadan keyin javob kelmasa, aniq xato ko'rsatamiz.
        var answered = false
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (!answered) {
                answered = true
                binding.btnPair.isEnabled = true
                binding.pairStatusText.text = "❌ Internet aloqasi yo'q yoki juda sekin. Qurilmani internetga ulab, qayta urinib ko'ring."
            }
        }
        timeoutHandler.postDelayed(timeoutRunnable, 15_000L)
        FirebaseRepo.joinFamily(code, childName) { ok, error ->
            if (answered) return@joinFamily
            answered = true
            timeoutHandler.removeCallbacks(timeoutRunnable)
            binding.btnPair.isEnabled = true
            if (ok) {
                getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
                    .putString("family_code", code).putString("child_id", uid).putString("child_name", childName).apply()
                SimInfoSync.syncNow(this)
                binding.pairStatusText.text = "✅ Ulandi: $code" + if (childName.isNotBlank()) " ($childName sifatida)" else ""
            } else {
                binding.pairStatusText.text = "❌ Ulanmadi: ${error ?: "noma'lum xato"}"
            }
        }
    }
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
