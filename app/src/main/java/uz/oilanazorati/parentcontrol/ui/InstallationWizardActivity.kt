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
    private companion object {
        const val BG = 0xFF0B1220.toInt()
        const val CARD = 0xFF16223A.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
        const val MUTED = 0xFFC9D4E5.toInt()
        const val OK = 0xFF4ADE80.toInt()
        const val BAD = 0xFFFF6B6B.toInt()
        const val BTN = 0xFF2F6BFF.toInt()
    }
    private val prefs by lazy { getSharedPreferences("installation_wizard", Context.MODE_PRIVATE) }

    // Same runtime permissions as the existing child setup screen.
    // POST_NOTIFICATIONS exists only on Android 13+; do not block older devices on it.
    private val permissions: Array<String>
        get() = arrayOf(
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
        ).filter {
            it != android.Manifest.permission.POST_NOTIFICATIONS ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result.filterValues { !it }.keys.forEach { permission ->
            val key = "permission_request_count_$permission"
            prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "OilaNazorati — sozlash"
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.setBackgroundColor(BG)
        root.setBackgroundColor(BG)
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
            setTextColor(TEXT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Bitta tugma bilan keyingi kerakli sozlamaga o'ting. Android sahifasidan qaytsangiz, ruxsatlar avtomatik tekshiriladi."
            textSize = 15f
            setTextColor(MUTED)
            setPadding(0, dp(8), 0, dp(14))
        })
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(TEXT)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(CARD)
        }
        root.addView(status)
        next = Button(this).apply {
            text = "Keyingi kerakli qadam"
            setOnClickListener { openNextStep() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) }
        }
        styleButton(next)
        root.addView(next)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addNote("Muhim: Accessibility Screenshot",
                "Avval xizmatni bosib ogohlantirishdagi OK ni tasdiqlang. Keyin ilova ma'lumotlari sahifasidagi ⋮ → Allow restricted settings ni tanlang. So'ng Accessibility'ga qaytib xizmatni yoqing.")
        }
        manufacturerNote()?.let { addNote("Telefoningiz uchun qo'shimcha", it) }
        root.addView(Button(this).apply {
            styleButton(this)
            text = "Holatni qayta tekshirish"
            setOnClickListener { refresh() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) }
        })
        root.addView(Button(this).apply {
            styleButton(this)
            text = "Oila kodi bilan ulanish / monitoring"
            setOnClickListener { startActivity(Intent(this@InstallationWizardActivity, ChildSetupActivity::class.java)) }
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
        })
        refresh()
    }

    private fun styleButton(b: Button) {
        b.setTextColor(TEXT)
        b.setBackgroundColor(BTN)
    }

    private fun addNote(heading: String, body: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(CARD)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        box.addView(TextView(this).apply {
            text = heading
            textSize = 16f
            setTextColor(0xFFFFD166.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = body
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(box)
    }

    private fun openNextStep() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // A first denial can also return false here. Only route to App Info
            // after the permission has actually been requested repeatedly.
            val permanentlyDenied = missing.any {
                prefs.getInt("permission_request_count_$it", 0) >= 2 &&
                    !shouldShowRequestPermissionRationale(it)
            }
            if (permanentlyDenied) {
                AlertDialog.Builder(this)
                    .setTitle("Ruxsatni sozlamadan yoqing")
                    .setMessage("Ayrim ruxsatlar avval rad etilgan. Ilova ma'lumotlari → Ruxsatlar bo'limiga kirib, OilaNazorati uchun kerakli ruxsatlarni yoqing.")
                    .setPositiveButton("Ilova sozlamalarini ochish") { _, _ -> openSystem(appDetailsIntent()) }
                    .setNegativeButton("Keyinroq", null).show()
            } else {
                permissionLauncher.launch(missing.toTypedArray())
            }
            return
        }

        if (!accessibilityEnabled()) {
            // "Allow restricted settings" exists only on Android 13+ (sideloaded apps).
            val stage = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) 3
                else prefs.getInt("access_stage", 0)
            when (stage) {
                0 -> AlertDialog.Builder(this)
                    .setTitle("1-qadam / 3: xizmatni bosing")
                    .setMessage("Ochiladigan ro'yxatdan \"Oila Nazorati — Accessibility Screenshot\" qatorini TOPIB, uning USTIGA bosing (tugmani emas, butun qatorni).\n\nShunda ekranga \"Qurilmangizni himoya qilish uchun bu sozlama hozircha mavjud emas\" (yoki shunga o'xshash) oynacha chiqadi. O'sha oynadagi OK tugmasini albatta bosing.\n\nOK bosilgandan keyingina keyingi qadam ishlaydi — shuning uchun avval shu qatorga bosishni unutmang.")
                    .setPositiveButton("Accessibility'ni ochish") { _, _ ->
                        prefs.edit().putInt("access_stage", 1).apply()
                        openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }.setNegativeButton("Keyinroq", null).show()
                1 -> AlertDialog.Builder(this)
                    .setTitle("2-qadam / 3: barcha ruxsatni yoqish")
                    .setMessage("MUHIM: bu safar ilova o'zi \"Ilova ma'lumotlari\" sahifasini TO'G'RIDAN-TO'G'RI OCHMAYDI — buning o'rniga butun ilovalar RO'YXATI ochiladi. Shu ro'yxatdan QO'LDA \\\"Bulut servis\\\" degan qatorni toping va unga bosing.\n\n(To'g'ridan-to'g'ri ochilsa, Android uch nuqta menyusini ataylab ishlamaydigan qilib qo'yadi — shuning uchun ro'yxatdan qo'lda kirish shart.)\n\nOchilgan sahifada yuqori o'ng burchakdagi uch nuqta (⋮) ni bosing: \"Allow restricted settings\" / \"Cheklangan sozlamalarga ruxsat berish\" ni tanlang, so'ng barmoq izi yoki PIN bilan tasdiqlang.\n\nUch nuqta hali ham hech narsa qilmasa — \"Orqaga: 1-qadam\"ni bosib, Accessibility'dagi xizmat qatoriga qayta bosing va OK'ni tasdiqlang.")
                    .setPositiveButton("Ilovalar ro'yxatini ochish") { _, _ ->
                        prefs.edit().putInt("access_stage", 2).apply()
                        openSystem(appsListIntent())
                    }
                    .setNeutralButton("Orqaga: 1-qadam") { _, _ ->
                        prefs.edit().putInt("access_stage", 0).apply()
                        openNextStep()
                    }
                    .setNegativeButton("Keyinroq", null).show()
                else -> AlertDialog.Builder(this)
                    .setTitle("3-qadam / 3: xizmatni yoqish")
                    .setMessage("Endi Accessibility sahifasida \"Oila Nazorati — Accessibility Screenshot\" qatoriga qayta bosing. Endi tugma xira emas — uni ON holatiga torting va Android tasdig'ini bering.\n\nQaytganingizda bu yordamchi holatni o'zi tekshiradi.")
                    .setPositiveButton("Accessibility'ni ochish") { _, _ ->
                        openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    .setNeutralButton("Hali ham xira / ishlamayapti") { _, _ ->
                        prefs.edit().putInt("access_stage", 0).apply()
                        openNextStep()
                    }
                    .setNegativeButton("Keyinroq", null).show()
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
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                // Android 10: can be requested directly.
                permissionLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            } else {
                // Android 11+: only grantable from the system settings page.
                explain("Fon joylashuvi",
                    "Ochiladigan sahifada Ruxsatlar → Joylashuv ni tanlang va 'Har doim ruxsat berish' (Allow all the time) ni yoqing. Aniq joylashuv (Precise) ham yoniq tursin.",
                    appDetailsIntent())
            }
            return
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
        val rows = listOf(
            "Oddiy ruxsatlar: $granted/${permissions.size}" to (granted == permissions.size),
            "Accessibility Screenshot: " + (if (access) "YOQILGAN ✓" else "YOQILMAGAN") to access,
            "Usage Access: " + (if (usageEnabled()) "BERILGAN ✓" else "BERILMAGAN") to usageEnabled(),
            "Bildirishnoma access: " + (if (notificationsEnabled()) "BERILGAN ✓" else "BERILMAGAN") to notificationsEnabled(),
            "Fon joylashuvi: " + (if (background) "BERILGAN ✓" else "BERILMAGAN") to background,
            "Batareya: " + (if (battery) "CHEKLOVSIZ ✓" else "CHEKLANGAN") to battery,
            "Device Admin: " + (if (deviceAdminEnabled()) "FAOL ✓" else "FAOL EMAS") to deviceAdminEnabled()
        )
        val sb = android.text.SpannableStringBuilder()
        rows.forEachIndexed { i, (line, ok) ->
            val start = sb.length
            sb.append(line)
            sb.setSpan(android.text.style.ForegroundColorSpan(if (ok) OK else BAD),
                start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i < rows.lastIndex) sb.append("\n")
        }
        status.text = sb
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

    /**
     * Android 13+'da "Allow restricted settings" menyusi (⋮ ichida) FAQAT
     * foydalanuvchi Sozlamalar ichida O'ZI qo'lda qadam bosib kirganda
     * ishlaydi — boshqa ilovadan to'g'ridan-to'g'ri (deep-link) ochilgan
     * "Ilova ma'lumotlari" sahifasida bu menyu band/ko'rinmas bo'lib qoladi
     * (bu — Android'ning ataylab qo'yilgan xavfsizlik cheklovi, soxta
     * ilovalar foydalanuvchini aldab shu menyuni bosdirib yubormasligi
     * uchun). Shuning uchun to'g'ridan-to'g'ri ochish o'rniga, butun
     * ilovalar RO'YXATI ochiladi — foydalanuvchi o'zi qo'lda bosib kiradi.
     */
    private fun appsListIntent(): Intent {
        val intent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
        return if (intent.resolveActivity(packageManager) != null) intent
        else Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
    }
        .setData(Uri.fromParts("package", packageName, null))

    private fun openSystem(intent: Intent) {
        try { startActivity(intent) }
        catch (_: Exception) { startActivity(appDetailsIntent()) }
    }

    private fun manufacturerNote(): String? = when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> "Samsung: Sozlamalar → Batareya → Fon rejimidagi cheklovlar → 'Hech qachon uxlamaydigan ilovalar' ga OilaNazorati'ni qo'shing va 'Uxlayotgan ilovalar'dan olib tashlang."
        "xiaomi", "redmi", "poco" -> "Xiaomi: Ilova ma'lumotlari → Avtoishga tushirish (Autostart) ni yoqing va Batareya tejash → 'Cheklovsiz' ni tanlang."
        "huawei", "honor" -> "Huawei/Honor: Ilova ishga tushirish → OilaNazorati uchun 'Qo'lda boshqarish' va uchala ruxsatni yoqing."
        "oppo", "realme", "oneplus", "vivo" -> "Ilova ma'lumotlari → Batareya → 'Fonda ishlashga ruxsat' va Avtoishga tushirishni yoqing."
        else -> null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
