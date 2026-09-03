package uz.oilanazorati.parentcontrol.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 28, 32, 32) }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(TextView(this).apply { text = "📸 Screenshot nazorati"; textSize = 24f })
        enabled = Switch(this).apply { text = "Screenshot olish" }; root.addView(enabled)
        autoTop3 = Switch(this).apply { text = "Avtomatik TOP 3 ilova" }; root.addView(autoTop3)
        root.addView(TextView(this).apply { text = "Chastota"; textSize = 16f })
        frequency = Spinner(this); frequency.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("15 daqiqa", "30 daqiqa", "60 daqiqa")); root.addView(frequency)
        root.addView(TextView(this).apply { text = "Bugungi ilovalar — qo'lda tanlash"; textSize = 18f })
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; root.addView(appsBox)
        root.addView(Button(this).apply { text = "💾 Saqlash"; setOnClickListener { save() } })
        root.addView(Button(this).apply { text = "🖼 Screenshotlar tarixi"; setOnClickListener { startActivity(android.content.Intent(this@ScreenshotSettingsActivity, ScreenshotHistoryActivity::class.java)) } })
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
