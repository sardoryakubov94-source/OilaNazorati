package uz.oilanazorati.parentcontrol.ui

import android.app.AppOpsManager
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import uz.oilanazorati.parentcontrol.screenshot.AccessibilityScreenshotService

/** Guides the child-device setup; Android-protected toggles remain user-confirmed. */
class InstallationWizardActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var summary: TextView
    private val runtimePermissions = arrayOf(
        android.Manifest.permission.READ_PHONE_STATE, android.Manifest.permission.READ_PHONE_NUMBERS,
        android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.CALL_PHONE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "O'rnatish yordamchisi"
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(28)) }
        scroll.addView(root); setContentView(scroll); buildUi()
    }
    override fun onResume() { super.onResume(); if (::summary.isInitialized) refreshStatus() }

    private fun buildUi() {
        root.addView(TextView(this).apply { text = "Bola telefonini tayyorlash"; textSize = 25f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "Har bir ruxsatni shu yerdan oching. Sozlamadan qaytsangiz, holat avtomatik tekshiriladi."; textSize = 15f; setPadding(0, dp(8), 0, dp(16)) })
        summary = TextView(this).apply { textSize = 15f; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(0xFFEAF4FF.toInt()) }
        root.addView(summary)
        addStep("1", "Asosiy ruxsatlar", "SMS, qo'ng'iroq, kontakt, joylashuv va mikrofon ruxsatlarini bir oynada bering.") {
            val missing = runtimePermissions.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) openAppDetails() else requestPermissions(missing.toTypedArray(), 101)
        }
        addStep("2", "Accessibility cheklovini ochish", "Xizmat xira bo'lsa: Ilova ma'lumotlari → ⋮ → “Cheklangan sozlamalarga ruxsat berish”.") {
            AlertDialog.Builder(this).setTitle("Accessibility cheklovi")
                .setMessage("Android bu ruxsatni foydalanuvchi tasdig'isiz ochmaydi. OilaNazorati ilova ma'lumotlariga kiring, ⋮ menyuni oching va “Cheklangan sozlamalarga ruxsat berish”ni tanlang. Keyin Accessibility xizmatini yoqing.")
                .setPositiveButton("Ilova ma'lumotlariga o'tish") { _, _ -> openAppDetails() }
                .setNeutralButton("Accessibility'ga o'tish") { _, _ -> openAccessibility() }
                .setNegativeButton("Bekor", null).show()
        }
        addStep("3", "Screenshot Accessibility", "Oila Nazorati — Accessibility Screenshot xizmatini ON qiling.") { openAccessibility() }
        addStep("4", "Ilova ishlatilishi (Usage Access)", "Ilovalar statistikasi va screenshot kuzatuvi uchun kerak.") { openUsageAccess() }
        addStep("5", "Bildirishnomalarga ruxsat", "Telegram/WhatsApp va boshqa ilovalardan bildirishnomalarni ko'rish uchun.") { openNotificationAccess() }
        addStep("6", "Joylashuv va fon ishlashi", "Joylashuv ruxsatlarini tekshiring; Android sozlamasida fon joylashuviga ruxsat bering (agar so'ralsa).") { openAppDetails() }
        addStep("7", "Batareya cheklovini olib tashlash", "Monitoring fonda uzilmasdan ishlashi uchun.") { requestBatteryExemption() }
        addStep("8", "Ilovani o'chirishdan himoya", "Device Admin ruxsatini sozlash uchun eski sozlash sahifasidan foydalaning.") { startActivity(Intent(this, ChildSetupActivity::class.java)) }
        root.addView(Button(this).apply { text = "Holatni qayta tekshirish"; setOnClickListener { refreshStatus() }; layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) } })
        root.addView(Button(this).apply { text = "Sozlash sahifasiga qaytish"; setOnClickListener { finish() }; layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) } })
        refreshStatus()
    }

    private fun addStep(number: String, title: String, detail: String, action: () -> Unit) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(0xFFF4F6F8.toInt()); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } }
        card.addView(TextView(this).apply { text = "$number. $title"; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        card.addView(TextView(this).apply { text = detail; textSize = 14f; setPadding(0, dp(5), 0, dp(8)) })
        card.addView(Button(this).apply { text = "Ochish / sozlash"; setOnClickListener { action() } })
        root.addView(card)
    }

    private fun refreshStatus() {
        val granted = runtimePermissions.count { ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        val accessibility = try {
            val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == packageName && it.resolveInfo.serviceInfo.name == AccessibilityScreenshotService::class.java.name }
        } catch (_: Exception) { false }
        val usage = checkUsageAccess()
        val notification = try { (Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: "").split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName } } catch (_: Exception) { false }
        val battery = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        summary.text = "Ruxsatlar: $granted/${runtimePermissions.size}\nAccessibility Screenshot: ${if (accessibility) "YOQILGAN ✓" else "YOQILMAGAN"}\nUsage Access: ${if (usage) "BERILGAN ✓" else "BERILMAGAN"}\nBildirishnoma access: ${if (notification) "BERILGAN ✓" else "BERILMAGAN"}\nBatareya cheklovi: ${if (battery) "O'CHIRILGAN ✓" else "FAOL"}"
    }
    @Suppress("DEPRECATION")
    private fun checkUsageAccess(): Boolean = try { (getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED } catch (_: Exception) { false }\n\n    private fun openAppDetails() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    private fun openAccessibility() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    private fun openUsageAccess() = startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    private fun openNotificationAccess() = startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (_: Exception) { openAppDetails() } else openAppDetails()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
