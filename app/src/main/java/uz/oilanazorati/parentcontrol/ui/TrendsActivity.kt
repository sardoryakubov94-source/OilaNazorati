package uz.oilanazorati.parentcontrol.ui

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import uz.oilanazorati.parentcontrol.databinding.ActivityTrendsBinding
import uz.oilanazorati.parentcontrol.model.CallEvent
import uz.oilanazorati.parentcontrol.model.SmsEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.text.SimpleDateFormat
import java.util.*

/**
 * Grafiklar:
 *  1) Haftalik "qachon eng ko'p gaplashadi" — 24 soatlik taqsimot
 *     (bar balandligi = shu soatda boshlangan qo'ng'iroqlar soni, hafta bo'yicha jamlangan)
 *  2) Haftalik "eng ko'p mashg'ul bo'lgan ilovalar" — top 5 ilova, umumiy daqiqa bo'yicha
 *  3) 30 kunlik DAVOMIY (chiziqli) grafik: har kuni necha XIL kontakt bilan
 *     gaplashgan (raqamsiz — faqat son)
 *  4) 30 kunlik DAVOMIY (chiziqli) grafik: har kuni jami necha daqiqa
 *     gaplashgan
 *  5) 30 kun ichida eng ko'p gaplashilgan kontaktlar reytingi (rang bilan,
 *     ismi bo'lsa — ism bilan, saqlanmagan bo'lsa faqat rang bilan)
 *
 * SMS uchun bir xil to'plam (qo'ng'iroqdagi bilan aynan bir xil mantiq,
 * faqat "daqiqa" o'rniga "xabar soni"):
 *  1b) Haftalik "qachon eng ko'p SMS yozishadi" — 24 soatlik taqsimot
 *  3b) 30 kunlik: har kuni necha XIL kontakt bilan SMS yozishilgan
 *  4b) 30 kunlik: har kuni jami necha SMS yozilgan
 *  5b) 30 kun ichida eng ko'p SMS yozishilgan kontaktlar reytingi
 *
 * Faqat vaqt/davomiylik/tur/anonim-hash asosida — raqamning o'zi yo'q,
 * avvalgi kelishuvga mos.
 */
class TrendsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrendsBinding
    private val topContactsAdapter = ContactSummaryAdapter()
    private val topSmsContactsAdapter = SmsContactSummaryAdapter()
    private var savedContactNames: Map<String, String> = emptyMap() // kontaktHash -> nomi

    companion object {
        const val TREND_DAYS = 30
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrendsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) {
            binding.trendsStatus.text = "Avval bosh sahifada oila kodini yuklang"
            return
        }

        binding.topContactsList.layoutManager = LinearLayoutManager(this)
        binding.topContactsList.adapter = topContactsAdapter

        binding.topSmsContactsList.layoutManager = LinearLayoutManager(this)
        binding.topSmsContactsList.adapter = topSmsContactsAdapter

        val cal = Calendar.getInstance()
        val rangeEnd = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, -7)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val weekRangeStart = cal.timeInMillis

        val cal30 = Calendar.getInstance()
        cal30.add(Calendar.DAY_OF_YEAR, -(TREND_DAYS - 1))
        cal30.set(Calendar.HOUR_OF_DAY, 0); cal30.set(Calendar.MINUTE, 0)
        cal30.set(Calendar.SECOND, 0); cal30.set(Calendar.MILLISECOND, 0)
        val monthRangeStart = cal30.timeInMillis

        loadHourlyCallChart(weekRangeStart, rangeEnd)
        loadHourlySmsChart(weekRangeStart, rangeEnd)
        loadTopAppsChart(weekRangeStart, rangeEnd)

        // Ismlarni oldindan yuklab olamiz — reytingda "raqamsiz, lekin
        // saqlangan bo'lsa ismi bilan" ko'rsatish uchun.
        FirebaseRepo.listenSavedContacts { contacts ->
            savedContactNames = contacts.associate { it.kontaktHash to it.nomi }
            topContactsAdapter.setNames(savedContactNames)
            topSmsContactsAdapter.setNames(savedContactNames)
        }

        loadContactTrends(monthRangeStart, rangeEnd)
        loadSmsContactTrends(monthRangeStart, rangeEnd)
    }

    // ---------------- 1) Soat bo'yicha qo'ng'iroq faolligi ----------------

    private fun loadHourlyCallChart(rangeStart: Long, rangeEnd: Long) {
        FirebaseRepo.fetchCallsInRange(rangeStart, rangeEnd) { calls ->
            val hourCounts = IntArray(24)
            val cal = Calendar.getInstance()
            calls.forEach {
                cal.timeInMillis = it.boshlanishMs
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                hourCounts[hour]++
            }

            val entries = hourCounts.mapIndexed { hour, count -> BarEntry(hour.toFloat(), count.toFloat()) }
            val dataSet = BarDataSet(entries, "Qo'ng'iroqlar soni (soat bo'yicha, 7 kunlik)")
            dataSet.color = Color.parseColor("#2E7D32")
            dataSet.valueTextSize = 9f

            binding.hourlyCallChart.apply {
                data = BarData(dataSet)
                description.isEnabled = false
                legend.isEnabled = false
                xAxis.granularity = 1f
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.valueFormatter = IndexAxisValueFormatter((0..23).map { "$it" })
                axisRight.isEnabled = false
                animateY(600)
                invalidate()
            }

            val busiestHour = hourCounts.indices.maxByOrNull { hourCounts[it] } ?: -1
            if (busiestHour >= 0 && hourCounts[busiestHour] > 0) {
                binding.trendsStatus.text =
                    "📊 Eng faol vaqt: soat $busiestHour:00 atrofida (${hourCounts[busiestHour]} ta qo'ng'iroq, 7 kunda)"
            } else {
                binding.trendsStatus.text = "Bu hafta qo'ng'iroq qayd etilmagan"
            }
        }
    }

    // ---------------- 1b) Soat bo'yicha SMS faolligi ----------------

    private fun loadHourlySmsChart(rangeStart: Long, rangeEnd: Long) {
        FirebaseRepo.fetchSmsInRange(rangeStart, rangeEnd) { smsList ->
            val hourCounts = IntArray(24)
            val cal = Calendar.getInstance()
            smsList.forEach {
                cal.timeInMillis = it.vaqtMs
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                hourCounts[hour]++
            }

            val entries = hourCounts.mapIndexed { hour, count -> BarEntry(hour.toFloat(), count.toFloat()) }
            val dataSet = BarDataSet(entries, "SMS soni (soat bo'yicha, 7 kunlik)")
            dataSet.color = Color.parseColor("#AD1457")
            dataSet.valueTextSize = 9f

            binding.hourlySmsChart.apply {
                data = BarData(dataSet)
                description.isEnabled = false
                legend.isEnabled = false
                xAxis.granularity = 1f
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.valueFormatter = IndexAxisValueFormatter((0..23).map { "$it" })
                axisRight.isEnabled = false
                animateY(600)
                invalidate()
            }
        }
    }

    // ---------------- 2) Eng ko'p mashg'ul bo'lgan ilovalar ----------------

    private fun loadTopAppsChart(rangeStart: Long, rangeEnd: Long) {
        FirebaseRepo.fetchAppUsageInRange(rangeStart, rangeEnd) { usage ->
            val totals = usage.groupBy { it.ilovaNomi }
                .mapValues { (_, list) -> list.sumOf { it.davomiylikSoniya } / 60 } // daqiqada
                .toList()
                .sortedByDescending { it.second }
                .take(5)

            val entries = totals.mapIndexed { i, (_, minutes) -> BarEntry(i.toFloat(), minutes.toFloat()) }
            val dataSet = BarDataSet(entries, "Daqiqa (7 kunlik jami)")
            dataSet.color = Color.parseColor("#1565C0")
            dataSet.valueTextSize = 9f

            binding.topAppsChart.apply {
                data = BarData(dataSet)
                description.isEnabled = false
                legend.isEnabled = false
                xAxis.granularity = 1f
                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.valueFormatter = IndexAxisValueFormatter(totals.map { it.first })
                xAxis.labelRotationAngle = -30f
                axisRight.isEnabled = false
                animateY(600)
                invalidate()
            }
        }
    }

    // ---------------- 3-5) Kontaktlar bo'yicha 30 kunlik davomiy statistika ----------------

    private fun loadContactTrends(rangeStart: Long, rangeEnd: Long) {
        FirebaseRepo.fetchCallsInRange(rangeStart, rangeEnd) { calls ->
            drawDailyContactCountChart(calls, rangeStart)
            drawDailyMinutesChart(calls, rangeStart)
            drawTopContactsForRange(calls)
        }
    }

    /** Har kun uchun: shu kuni gaplashilgan XIL kontaktlar soni (chiziqli grafik). */
    private fun drawDailyContactCountChart(calls: List<CallEvent>, rangeStart: Long) {
        val byDay = groupByDayIndex(calls, rangeStart)
        val entries = (0 until TREND_DAYS).map { dayIdx ->
            val dayCalls = byDay[dayIdx] ?: emptyList()
            val distinct = dayCalls.map { it.kontaktHash.ifBlank { "noma_lum" } }.toSet().size
            Entry(dayIdx.toFloat(), distinct.toFloat())
        }
        val dataSet = LineDataSet(entries, "Kunlik xil kontaktlar soni").apply {
            color = Color.parseColor("#6A1B9A")
            setDrawCircles(true)
            circleRadius = 2.5f
            setDrawValues(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillAlpha = 40
            fillColor = Color.parseColor("#6A1B9A")
        }

        binding.dailyContactCountChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 5f
            xAxis.valueFormatter = dayAxisFormatter(rangeStart)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            animateX(600)
            invalidate()
        }
    }

    /** Har kun uchun: shu kuni jami necha daqiqa gaplashilgani (chiziqli grafik). */
    private fun drawDailyMinutesChart(calls: List<CallEvent>, rangeStart: Long) {
        val byDay = groupByDayIndex(calls, rangeStart)
        val entries = (0 until TREND_DAYS).map { dayIdx ->
            val dayCalls = byDay[dayIdx] ?: emptyList()
            val minutes = dayCalls.sumOf { it.davomiylikSoniya } / 60
            Entry(dayIdx.toFloat(), minutes.toFloat())
        }
        val dataSet = LineDataSet(entries, "Kunlik jami daqiqa").apply {
            color = Color.parseColor("#EF6C00")
            setDrawCircles(true)
            circleRadius = 2.5f
            setDrawValues(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillAlpha = 40
            fillColor = Color.parseColor("#EF6C00")
        }

        binding.dailyCallMinutesChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 5f
            xAxis.valueFormatter = dayAxisFormatter(rangeStart)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            animateX(600)
            invalidate()
        }
    }

    /** 30 kun ichida eng ko'p gaplashilgan kontaktlar — rang (va bo'lsa ism) bilan reyting. */
    private fun drawTopContactsForRange(calls: List<CallEvent>) {
        val stats = buildContactStats(calls)
        val distinctCount = stats.count { it.kontaktHash != "noma_lum" }
        val totalMinutes = calls.sumOf { it.davomiylikSoniya } / 60
        val top = stats.firstOrNull()

        val topLabel = when {
            top == null -> "Ma'lumot yo'q"
            top.kontaktHash == "noma_lum" -> "Noma'lum raqam(lar) — ${top.qongiroqSoni} marta, ${top.jamiDaqiqa} daq"
            else -> {
                val name = savedContactNames[top.kontaktHash]
                val who = name ?: "saqlanmagan kontakt"
                "$who — ${top.qongiroqSoni} marta, ${top.jamiDaqiqa} daq"
            }
        }

        binding.topContactSummary.text =
            "$TREND_DAYS kunda: $distinctCount xil kontakt bilan, jami $totalMinutes daqiqa\n" +
            "Eng ko'p gaplashgani: $topLabel"

        topContactsAdapter.setStats(stats)
    }

    // ---------------- 3-5b) Kontaktlar bo'yicha 30 kunlik SMS statistikasi ----------------

    private fun loadSmsContactTrends(rangeStart: Long, rangeEnd: Long) {
        FirebaseRepo.fetchSmsInRange(rangeStart, rangeEnd) { smsList ->
            drawDailySmsContactCountChart(smsList, rangeStart)
            drawDailySmsCountChart(smsList, rangeStart)
            drawTopSmsContactsForRange(smsList)
        }
    }

    /** Har kun uchun: shu kuni SMS yozishilgan XIL kontaktlar soni (chiziqli grafik). */
    private fun drawDailySmsContactCountChart(smsList: List<SmsEvent>, rangeStart: Long) {
        val byDay = groupSmsByDayIndex(smsList, rangeStart)
        val entries = (0 until TREND_DAYS).map { dayIdx ->
            val daySms = byDay[dayIdx] ?: emptyList()
            val distinct = daySms.map { it.kontaktHash.ifBlank { "noma_lum" } }.toSet().size
            Entry(dayIdx.toFloat(), distinct.toFloat())
        }
        val dataSet = LineDataSet(entries, "Kunlik xil SMS kontaktlar soni").apply {
            color = Color.parseColor("#00838F")
            setDrawCircles(true)
            circleRadius = 2.5f
            setDrawValues(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillAlpha = 40
            fillColor = Color.parseColor("#00838F")
        }

        binding.dailySmsContactCountChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 5f
            xAxis.valueFormatter = dayAxisFormatter(rangeStart)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            animateX(600)
            invalidate()
        }
    }

    /** Har kun uchun: shu kuni jami necha SMS yozilgani (chiziqli grafik). */
    private fun drawDailySmsCountChart(smsList: List<SmsEvent>, rangeStart: Long) {
        val byDay = groupSmsByDayIndex(smsList, rangeStart)
        val entries = (0 until TREND_DAYS).map { dayIdx ->
            val daySms = byDay[dayIdx] ?: emptyList()
            Entry(dayIdx.toFloat(), daySms.size.toFloat())
        }
        val dataSet = LineDataSet(entries, "Kunlik jami SMS soni").apply {
            color = Color.parseColor("#C62828")
            setDrawCircles(true)
            circleRadius = 2.5f
            setDrawValues(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillAlpha = 40
            fillColor = Color.parseColor("#C62828")
        }

        binding.dailySmsCountChart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 5f
            xAxis.valueFormatter = dayAxisFormatter(rangeStart)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            animateX(600)
            invalidate()
        }
    }

    /** 30 kun ichida eng ko'p SMS yozishilgan kontaktlar — rang (va bo'lsa ism) bilan reyting. */
    private fun drawTopSmsContactsForRange(smsList: List<SmsEvent>) {
        val stats = buildSmsContactStats(smsList)
        val distinctCount = stats.count { it.kontaktHash != "noma_lum" }
        val totalSms = smsList.size
        val top = stats.firstOrNull()

        val topLabel = when {
            top == null -> "Ma'lumot yo'q"
            top.kontaktHash == "noma_lum" -> "Noma'lum raqam(lar) — ${top.jamiSoni} ta SMS"
            else -> {
                val name = savedContactNames[top.kontaktHash]
                val who = name ?: "saqlanmagan kontakt"
                "$who — ${top.jamiSoni} ta SMS"
            }
        }

        binding.topSmsContactSummary.text =
            "$TREND_DAYS kunda: $distinctCount xil kontakt bilan, jami $totalSms ta SMS\n" +
            "Eng ko'p yozishgani: $topLabel"

        topSmsContactsAdapter.setStats(stats)
    }

    private fun groupSmsByDayIndex(smsList: List<SmsEvent>, rangeStart: Long): Map<Int, List<SmsEvent>> {
        val cal = Calendar.getInstance()
        return smsList.groupBy {
            cal.timeInMillis = it.vaqtMs
            val diffMs = cal.timeInMillis - rangeStart
            (diffMs / (24 * 60 * 60 * 1000L)).toInt().coerceIn(0, TREND_DAYS - 1)
        }
    }

    // ---------------- Yordamchi funksiyalar ----------------

    private fun groupByDayIndex(calls: List<CallEvent>, rangeStart: Long): Map<Int, List<CallEvent>> {
        val cal = Calendar.getInstance()
        return calls.groupBy {
            cal.timeInMillis = it.boshlanishMs
            val diffMs = cal.timeInMillis - rangeStart
            (diffMs / (24 * 60 * 60 * 1000L)).toInt().coerceIn(0, TREND_DAYS - 1)
        }
    }

    private fun dayAxisFormatter(rangeStart: Long): IndexAxisValueFormatter {
        val fmt = SimpleDateFormat("dd/MM", Locale.getDefault())
        val labels = (0 until TREND_DAYS).map { dayIdx ->
            fmt.format(Date(rangeStart + dayIdx * 24 * 60 * 60 * 1000L))
        }
        return IndexAxisValueFormatter(labels)
    }
}
