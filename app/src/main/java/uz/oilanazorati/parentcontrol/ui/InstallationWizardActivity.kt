package uz.oilanazorati.parentcontrol.ui

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import uz.oilanazorati.parentcontrol.screenshot.AccessibilityScreenshotService
import uz.oilanazorati.parentcontrol.service.AppDeviceAdminReceiver

/** Guided child-device setup. Android-protected switches remain user-confirmed. */
class InstallationWizardActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var next: Button
    private val prefs by lazy { getSharedPreferences("installation_wizard", Context.MODE_PRIVATE) }

    // Same runtime permissions as the existing child setup screen.
    private val permissions = arrayOf(
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.READ_PHONE_NUMBERS,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.READ_SMS,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.CALL_PHONE
    )
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "OilaNazorati — sozlash"
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refresh()
    }

    private fun buildUi() {
        root.addView(TextView(this).apply {
            text = "Bolalar telefonini sozlash"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Bitta tugma bilan keyingi kerakli sozlamaga o'ting. Android sahifasidan qaytsangiz, ruxsatlar avtomatik tekshiriladi."
            textSize = 15f
            setPadding(0, dp(8), 0, dp(14))
        })
        status = TextView(this).apply {
            textSize = 14f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFEAF4FF.toInt())
        }
        root.addView(status)
        next = Button(this).apply {
            text = "Keyingi kerakli qadam"
            setOnClickListener { openNextStep() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) }
        }
        root.addView(next)
        addNote("Muhim: Accessibility Screenshot",
            "Android 13+ da avval xizmatni bosib ogohlantirishdagi OK ni tasdiqlang. Keyin ilova ma'lumotlari sahifasidagi ⋮ → Allow restricted settings ni tanlang. So'ng Accessibility'ga qaytib xizmatni yoqing.")
        root.addView(Button(this).apply {
            text = "Holatni qayta tekshirish"
            setOnClickListener { refresh() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) }
        })
        root.addView(Button(this).apply {
            text = "Oila kodi bilan ulanish / monitoring"
            setOnClickListener { startActivity(Intent(this@InstallationWizardActivity, ChildSetupActivity::class.java)) }
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
        })
        refresh()
    }

    private fun addNote(heading: String, body: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(0xFFF4F6F8.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        box.addView(TextView(this).apply {
            text = heading
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = body
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(box)
    }

    private fun openNextStep() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }

        if (!accessibilityEnabled()) {
            when (prefs.getInt("access_stage", 0)) {
                0 -> AlertDialog.Builder(this)
                    .setTitle("Accessibility — 1-qadam")
                    .setMessage("Accessibility sozlamasi ochiladi. Ro'yxatdan OilaNazorati Screenshot xizmatini bosing. Agar Restricted setting ogohlantirishi chiqsa, OK ni bosing. Keyin shu yordamchiga qayting.")
                    .setPositiveButton("Accessibility'ni ochish") { _, _ ->
                        prefs.edit().putInt("access_stage", 1).apply()
                        openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }.setNegativeButton("Keyinroq", null).show()
                1 -> AlertDialog.Builder(this)
                    .setTitle("Accessibility — 2-qadam")
                    .setMessage("Ilova ma'lumotlari sahifasida ⋮ menyuni bosing → Allow restricted settings / Cheklangan sozlamalarga ruxsat berish → Android tasdig'ini bering. So'ng yordamchiga qayting.")
                    .setPositiveButton("Ilova ma'lumotlarini ochish") { _, _ ->
                        prefs.edit().putInt("access_stage", 2).apply()
                        openSystem(appDetailsIntent())
                    }.setNegativeButton("Keyinroq", null).show()
                else -> AlertDialog.Builder(this)
                    .setTitle("Accessibility — 3-qadam")
                    .setMessage("Accessibility sahifasida OilaNazorati Screenshot xizmatini ON qiling va Android tasdig'ini bering. Qaytganingizda ilova o'zi tekshiradi.")
                    .setPositiveButton("Accessibility'ni ochish") { _, _ ->
                        openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }.setNegativeButton("Keyinroq", null).show()
            }
            return
        }

        if (!usageEnabled()) {
            explain("Ilova ishlatilishi (Usage Access)",
                "Ro'yxatdan OilaNazorati'ni tanlang va foydalanish statistikasiga ruxsat bering.",
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); return
        }
        if (!notificationsEnabled()) {
            explain("Bildirishnomalarga kirish",
                "Ro'yxatdan OilaNazorati'ni tanlang va bildirishnomalarga ruxsat bering.",
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            explain("Fon joylashuvi",
                "Ilova ma'lumotlari → Ruxsatlar → Joylashuv bo'limida, agar mavjud bo'lsa, 'Har doim ruxsat berish'ni tanlang.",
                appDetailsIntent()); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !batteryExempt()) {
            val batteryIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:" + packageName))
            explain("Batareya cheklovi",
                "OilaNazorati uchun batareya optimallashtirishni o'chirishga ruxsat bering. Ayrim telefonlarda buni ishlab chiqaruvchi sozlamalaridan ham tanlash kerak bo'ladi.",
                batteryIntent); return
        }
        if (!deviceAdminEnabled()) {
            val component = ComponentName(this, AppDeviceAdminReceiver::class.java)
            val adminIntent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "OilaNazorati xizmatini ruxsatsiz o'chirib yuborishdan himoya qilish uchun.")
            }
            explain("Ilovani o'chirishdan himoya", "Android oynasida OilaNazorati administratoriga ruxsat bering.", adminIntent)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Ruxsatlar tayyor ✓")
            .setMessage("Ruxsatlar tekshirildi. Endi oila kodi bilan ulanish va monitoringni ishga tushirish mumkin.")
            .setPositiveButton("Ulanish sahifasini ochish") { _, _ ->
                startActivity(Intent(this, ChildSetupActivity::class.java))
            }.setNegativeButton("Yopish", null).show()
    }

    private fun explain(titleText: String, message: String, intent: Intent) {
        AlertDialog.Builder(this).setTitle(titleText).setMessage(message)
            .setPositiveButton("Sozlamani ochish") { _, _ -> openSystem(intent) }
            .setNegativeButton("Keyinroq", null).show()
    }

    private fun refresh() {
        val granted = permissions.count {
            ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val access = accessibilityEnabled()
        if (access) prefs.edit().putInt("access_stage", 3).apply()
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val battery = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || batteryExempt()
        status.text = listOf(
            "Oddiy ruxsatlar: " + granted + "/" + permissions.size,
            "Accessibility Screenshot: " + if (access) "YOQILGAN ✓" else "YOQILMAGAN",
            "Usage Access: " + if (usageEnabled()) "BERILGAN ✓" else "BERILMAGAN",
            "Bildirishnoma access: " + if (notificationsEnabled()) "BERILGAN ✓" else "BERILMAGAN",
            "Fon joylashuvi: " + if (background) "BERILGAN ✓" else "BERILMAGAN",
            "Batareya: " + if (battery) "CHEKLOVSIZ ✓" else "CHEKLANGAN",
            "Device Admin: " + if (deviceAdminEnabled()) "FAOL ✓" else "FAOL EMAS"
        ).joinToString("\n")
        next.text = if (granted == permissions.size && access && usageEnabled() &&
            notificationsEnabled() && background && battery && deviceAdminEnabled())
            "Ruxsatlar tayyor ✓" else "Keyingi kerakli qadam"
    }

    private fun accessibilityEnabled(): Boolean = try {
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == packageName && info.name == AccessibilityScreenshotService::class.java.name
            }
    } catch (_: Exception) { false }

    @Suppress("DEPRECATION")
    private fun usageEnabled(): Boolean = try {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    private fun notificationsEnabled(): Boolean = try {
        val raw = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        raw.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    } catch (_: Exception) { false }

    private fun batteryExempt(): Boolean = try {
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) { false }

    private fun deviceAdminEnabled(): Boolean = try {
        val manager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        manager.isAdminActive(ComponentName(this, AppDeviceAdminReceiver::class.java))
    } catch (_: Exception) { false }

    private fun appDetailsIntent() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))

    private fun openSystem(intent: Intent) {
        try { startActivity(intent) }
        catch (_: Exception) { startActivity(appDetailsIntent()) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
