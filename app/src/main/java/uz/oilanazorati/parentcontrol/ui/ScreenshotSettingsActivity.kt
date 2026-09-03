package uz.oilanazorati.parentcontrol.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.ScreenshotSettings
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.screenshot.ScreenshotRepository
import java.util.*

class ScreenshotSettingsActivity : AppCompatActivity() {
    private val checks = LinkedHashMap<String, CheckBox>()
    private var current = ScreenshotSettings()
    private lateinit var enabled: Switch
    private lateinit var autoTop3: Switch
    private lateinit var frequency: Spinner
    private lateinit var appsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loading = ProgressBar(this)
        setContentView(FrameLayout(this).apply { addView(loading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)) })
        FirebaseRepo.checkIsPremium { isPremium ->
            if (isPremium) buildScreenshotUi() else buildPremiumRequiredUi()
        }
    }

    private fun buildPremiumRequiredUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 28, 32, 32) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@ScreenshotSettingsActivity).apply {
                setImageResource(R.drawable.ic_nav_screenshot)
                setColorFilter(ContextCompat.getColor(this@ScreenshotSettingsActivity, R.color.color_text_primary))
                layoutParams = LinearLayout.LayoutParams(64, 64).apply { marginEnd = 16 }
            })
            addView(TextView(this@ScreenshotSettingsActivity).apply { text = "Screenshot nazorati"; textSize = 24f })
        })

        root.addView(TextView(this).apply {
            text = "Bu funksiya faqat Premium foydalanuvchilar uchun"
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@ScreenshotSettingsActivity, R.color.color_text_secondary))
            setPadding(0, 24, 0, 20)
        })

        // Reklama joyi — haqiqiy reklama tarmog'i (masalan AdMob) hali ulanmagan,
        // shuning uchun bu hozircha statik promo blok.
        root.addView(TextView(this).apply {
            text = "REKLAMA"
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@ScreenshotSettingsActivity, R.color.color_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40)
            setBackgroundColor(0x1AFFFFFF)
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_premium_banner)
            setPadding(32, 32, 32, 32)

            addView(TextView(this@ScreenshotSettingsActivity).apply {
                text = "⭐ Premium imkoniyatlar"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@ScreenshotSettingsActivity, R.color.color_text_primary))
            })
            addView(TextView(this@ScreenshotSettingsActivity).apply {
                text = "• Kontakt va raqamlarni ochiq ko'rish imkoni\n" +
                        "• Kelgan SMS xabarlarni to'liq o'qish imkoni\n" +
                        "• Eng mashhur ilovalardan har 15-30-60 daqiqada 3-5 tagacha ekran screenshotlarini ko'rish imkoni\n" +
                        "• Push bildirishnomalar orqali ilovalardan kelgan xabar va tegishli yangiliklarni ko'rish imkoni"
                textSize = 13f
                setTextColor(0xFFFFF3E0.toInt())
                setPadding(0, 16, 0, 0)
                setLineSpacing(6f, 1f)
            })
            addView(Button(this@ScreenshotSettingsActivity).apply {
                text = "Premium sotib olish"
                setOnClickListener { startActivity(android.content.Intent(this@ScreenshotSettingsActivity, PremiumActivity::class.java)) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 20 }
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })
    }

    private fun buildScreenshotUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 28, 32, 32) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@ScreenshotSettingsActivity).apply {
                setImageResource(R.drawable.ic_nav_screenshot)
                setColorFilter(ContextCompat.getColor(this@ScreenshotSettingsActivity, R.color.color_text_primary))
                layoutParams = LinearLayout.LayoutParams(64, 64).apply { marginEnd = 16 }
            })
            addView(TextView(this@ScreenshotSettingsActivity).apply { text = "Screenshot nazorati"; textSize = 24f })
        })
        enabled = Switch(this).apply { text = "Screenshot olish" }; root.addView(enabled)
        autoTop3 = Switch(this).apply { text = "Avtomatik TOP 3 ilova" }; root.addView(autoTop3)
        root.addView(TextView(this).apply { text = "Chastota"; textSize = 16f })
        frequency = Spinner(this); frequency.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("15 daqiqa", "30 daqiqa", "60 daqiqa")); root.addView(frequency)
        root.addView(TextView(this).apply { text = "Bugungi ilovalar — qo'lda tanlash"; textSize = 18f })
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(appsBox)
        root.addView(Button(this).apply {
            text = "Saqlash"
            val icon = ContextCompat.getDrawable(this@ScreenshotSettingsActivity, R.drawable.ic_save)?.mutate()
            icon?.setColorFilter(currentTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = 16
            setOnClickListener { save() }
        })
        root.addView(Button(this).apply {
            text = "Screenshotlar tarixi"
            val icon = ContextCompat.getDrawable(this@ScreenshotSettingsActivity, R.drawable.ic_gallery)?.mutate()
            icon?.setColorFilter(currentTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
            setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = 16
            setOnClickListener { startActivity(android.content.Intent(this@ScreenshotSettingsActivity, ScreenshotHistoryActivity::class.java)) }
        })
        ScreenshotRepository.listenSettings { s -> current = s; enabled.isChecked = s.enabled; autoTop3.isChecked = s.autoTop3Enabled; frequency.setSelection(listOf(15,30,60).indexOf(s.frequencyMinutes.coerceIn(15,60).toInt()).coerceAtLeast(0)); checks.values.forEach { it.isChecked = s.manualPackageNames.contains(it.tag as String) } }
        loadTodayApps()
    }
    private fun loadTodayApps() {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,0); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
        FirebaseRepo.fetchAppUsageInRange(cal.timeInMillis, cal.timeInMillis + 24*60*60*1000L) { usage ->
            val grouped = usage.groupBy { it.paketNomi }.mapValues { (_, v) -> v.sumOf { it.davomiylikSoniya } }.entries.sortedByDescending { it.value }
            appsBox.removeAllViews(); checks.clear()
            grouped.forEach { e ->
                val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(e.key,0)).toString() } catch (_: Exception) { e.key }
                val cb = CheckBox(this).apply { text = "$label — ${e.value/60} daq"; tag = e.key; isChecked = current.manualPackageNames.contains(e.key) }
                checks[e.key] = cb; appsBox.addView(cb)
            }
            if (grouped.isEmpty()) appsBox.addView(TextView(this).apply { text = "Bugun hali ilova statistikasi kelmagan." })
        }
    }
    private fun save() {
        val selected = checks.filterValues { it.isChecked }.keys.toList()
        val mins = listOf(15,30,60)[frequency.selectedItemPosition.coerceIn(0,2)]
        ScreenshotRepository.saveSettings(ScreenshotSettings(enabled.isChecked, autoTop3.isChecked, mins, selected)) { ok -> Toast.makeText(this, if (ok) "Screenshot sozlamalari saqlandi" else "Saqlashda xato", Toast.LENGTH_SHORT).show() }
    }
}
