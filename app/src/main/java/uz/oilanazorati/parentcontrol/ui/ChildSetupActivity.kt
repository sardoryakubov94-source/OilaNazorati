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

        // MUHIM: bu ekranga kirish endi MainActivity'dagi UMUMIY PAROL
        // orqali himoyalanadi (ilova ochilishidan boshlab). Shuning uchun
        // bu yerda alohida PIN so'ralmaydi — ikkita alohida parol o'rniga
        // bola telefonida FAQAT bitta umumiy parol bo'lishi uchun ataylab
        // olib tashlangan.

        binding.btnPair.setOnClickListener { pairWithFamilyCode() }
        binding.btnGrantPermissions.setOnClickListener {
            permissionLauncher.launch(runtimePermissions)
        }
        binding.btnUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        binding.btnDefaultPhone.setOnClickListener { requestDefaultPhoneRole() }
        binding.btnDefaultSms.setOnClickListener { requestDefaultSmsRole() }
        binding.btnNotificationAccess.setOnClickListener { requestNotificationAccess() }
        binding.btnBatteryOptimization.setOnClickListener { requestIgnoreBatteryOptimization() }
        binding.btnSyncContacts.setOnClickListener { syncContactsNow() }
        binding.btnDeviceAdmin.setOnClickListener { requestDeviceAdmin() }
        binding.btnFinish.setOnClickListener { finishSetupAndStartMonitoring() }

        restoreSavedPairingIntoUi()
    }

    // ---------------- Ilovani o'chirishdan himoyalash (Device Admin) ----------------
    // MUHIM CHEKLOV: bu FAQAT qo'shimcha to'siq. Agar kimdir qurilmaning
    // Sozlamalar > Xavfsizlik > Qurilma administratorlari bo'limini bilib,
    // shu yerdan admin huquqini o'chirsa — keyin ilovani oddiy usulda
    // o'chirish mumkin bo'lib qoladi (shunda AppDeviceAdminReceiver.
    // onDisableRequested() orqali ogohlantirish ko'rsatiladi). To'liq,
    // aylanib o'tib bo'lmaydigan himoya faqat qurilmani "Device Owner"
    // rejimida (factory reset + QR-kod bilan) sozlashda mumkin.

    private fun requestDeviceAdmin() {
        val compName = ComponentName(this, AppDeviceAdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(compName)) {
            binding.btnDeviceAdmin.text = "✅ O'chirishdan himoyalangan"
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Bu ilovani tasodifan yoki ruxsatsiz o'chirib tashlanishidan himoya qiladi."
            )
        }
        startActivity(intent)
    }

    private fun updateDeviceAdminStatusUi() {
        val compName = ComponentName(this, AppDeviceAdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        binding.btnDeviceAdmin.text = if (dpm.isAdminActive(compName)) {
            "✅ O'chirishdan himoyalangan"
        } else {
            "🔒 Ilovani o'chirishdan himoyalash"
        }
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
        updateDeviceAdminStatusUi()

        // Batareya tejash holati
        val pm = getSystemService(android.os.PowerManager::class.java)
        binding.btnBatteryOptimization.text = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            "✅ Batareya tejashdan chiqarilgan"
        } else {
            "🔋 Batareya tejashdan chiqarish (muhim!)"
        }
    }

    // ---------------- Oila kodi bilan bog'lash ----------------

    private fun pairWithFamilyCode() {
        val code = binding.inputFamilyCode.text?.toString()?.trim()?.uppercase()
        if (code.isNullOrBlank() || code.length != 6) {
            binding.inputFamilyCode.error = "6 xonali kodni kiriting (ota-ona ekranidan oling)"
            return
        }

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null && currentUser.isAnonymous) {
            // Bu qurilma allaqachon o'zining mustaqil (Google'ga bog'liq
            // bo'lmagan) identifikatoriga ega — shu bilan davom etamiz.
            finishPairing(code, currentUser.uid)
        } else {
            // MUHIM: bola qurilmasi uchun Google/Gmail hisobiga BUTUNLAY
            // BOG'LIQ BO'LMAGAN, mustaqil "anonim" Firebase identifikator
            // yaratamiz. Shu tufayli keyinchalik ota-ona shu qurilmada
            // (yoki umuman istalgan qurilmada) Gmail hisobidan chiqib
            // ketsa ham, bola qurilmasining ma'lumot yuborish huquqi
            // (Firestore uchun request.auth) DAVOM ETADI — chunki bu
            // huquq endi Gmail sessiyasiga emas, shu mustaqil
            // identifikatorga bog'langan, va u qurilmada doimiy saqlanadi.
            FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid ?: return@addOnSuccessListener
                    finishPairing(code, uid)
                }
                .addOnFailureListener {
                    binding.pairStatusText.text = "Ulanishda xato yuz berdi, qayta urinib ko'ring"
                }
        }
    }

    private fun finishPairing(code: String, uid: String) {
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

        binding.btnNotificationAccess.text = if (isNotificationAccessGranted()) {
            "✅ Bildirishnoma kuzatuvi yoqilgan"
        } else {
            "Ijtimoiy tarmoq bildirishnomalarini yoqish"
        }
    }

    // ---------------- Ijtimoiy tarmoq bildirishnomalarini o'qish ----------------
    // MUHIM: bu oddiy runtime permission EMAS — Android bunga alohida,
    // qo'lda tasdiqlanadigan tizim sozlamasi orqali ruxsat beradi
    // (Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS). Standart dialog
    // orqali so'rab bo'lmaydi.

    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val pkg = packageName
        if (pm.isIgnoringBatteryOptimizations(pkg)) {
            binding.btnBatteryOptimization.text = "✅ Batareya tejashdan chiqarilgan"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Batareya tejashdan chiqarish")
            .setMessage(
                "Samsung va boshqa telefonlar fon xizmatlarini batareya " +
                    "tejash maqsadida ba'zan o'chirib qo'yadi. Bu narsa " +
                    "joylashuv, qo'ng'iroq va SMS kuzatuvini to'xtatib " +
                    "qo'yishi mumkin.\n\n" +
                    "Keyingi sozlama ekranida bu ilovani 'Cheklanmagan' " +
                    "rejimga o'tkazing."
            )
            .setPositiveButton("Sozlamaga o'tish") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$pkg")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .show()
    }
        if (isNotificationAccessGranted()) {
            binding.btnNotificationAccess.text = "✅ Bildirishnoma kuzatuvi yoqilgan"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Bildirishnoma kirishi")
            .setMessage(
                "Keyingi ekranda \"Oila Nazorati\" (Google cervis)ni toping va " +
                    "yoqib qo'ying — shundan keyin Instagram, Telegram, WhatsApp " +
                    "kabi ilovalardan kelgan xabar bildirishnomalari kuzatiladi."
            )
            .setPositiveButton("Davom etish") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .show()
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabledListeners = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(packageName)
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
