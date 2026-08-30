package uz.oilanazorati.parentcontrol.ui

import android.graphics.Color
import android.content.Intent
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
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
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

        bindBottomNav(NavTab.APPS)

        FirebaseRepo.checkIsPremium { isPremium ->
            topContactsAdapter.setPremium(isPremium)
            topSmsContactsAdapter.setPremium(isPremium)
        }

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
        loadDonutCharts()

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

    // ---------------- 0) Bugungi umumiy holat — donut diagrammalar ----------------

    private fun loadDonutCharts() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000

        FirebaseRepo.listenCallsForDay(dayStart, dayEnd) { calls -> drawCallsDonut(calls) }
        FirebaseRepo.listenSmsForDay(dayStart, dayEnd) { smsList -> drawSmsDonut(smsList) }
        FirebaseRepo.listenAppUsageForDay(dayStart, dayEnd) { usage -> drawAppsDonut(usage) }
        FirebaseRepo.listenSavedContacts { contacts -> drawContactsDonut(contacts.size) }
    }

    /** Umumiy donut (pie, teshikli) sozlamalarini bitta joyda ushlab turadi. */
    private fun styleDonut(chart: com.github.mikephil.charting.charts.PieChart, centerText: String) {
        chart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setUsePercentValues(false)
            setDrawEntryLabels(false)
            isRotationEnabled = false
            setHoleColor(android.graphics.Color.TRANSPARENT)
            holeRadius = 62f
            transparentCircleRadius = 64f
            setCenterText(centerText)
            setCenterTextSize(14f)
            setCenterTextColor(Color.parseColor("#FFFFFF"))
            animateY(500)
        }
    }

    private fun donutDataSet(entries: List<PieEntry>, colors: List<Int>): PieDataSet {
        return PieDataSet(entries, "").apply {
            this.colors = colors
            setDrawValues(false)
            sliceSpace = 2f
        }
    }

    private fun drawCallsDonut(calls: List<CallEvent>) {
        val incoming = calls.count { it.turi == "kiruvchi" }
        val outgoing = calls.count { it.turi == "chiquvchi" }
        val missed = calls.size - incoming - outgoing
        val total = calls.size

        styleDonut(binding.donutCalls, "$total ta")
        if (total == 0) {
            binding.donutCalls.data = null
            binding.donutCalls.invalidate()
            binding.legendCalls.text = "Bugun qo'ng'iroq yo'q"
            return
        }
        val entries = mutableListOf<PieEntry>()
        val colors = mutableListOf<Int>()
        val legendParts = mutableListOf<String>()
        if (incoming > 0) { entries.add(PieEntry(incoming.toFloat())); colors.add(Color.parseColor("#2ECC71")) }
        if (outgoing > 0) { entries.add(PieEntry(outgoing.toFloat())); colors.add(Color.parseColor("#3498DB")) }
        if (missed > 0) { entries.add(PieEntry(missed.toFloat())); colors.add(Color.parseColor("#F39C12")) }
        legendParts.add("🟢 Kiruvchi ${percent(incoming, total)}%")
        legendParts.add("🔵 Chiquvchi ${percent(outgoing, total)}%")
        if (missed > 0) legendParts.add("🟠 Javobsiz ${percent(missed, total)}%")

        binding.donutCalls.data = PieData(donutDataSet(entries, colors))
        binding.donutCalls.invalidate()
        binding.legendCalls.text = legendParts.joinToString("\n")
    }

    private fun drawSmsDonut(smsList: List<SmsEvent>) {
        val sent = smsList.count { it.turi == "yuborilgan" }
        val received = smsList.size - sent
        val total = smsList.size

        styleDonut(binding.donutSms, "$total ta")
        if (total == 0) {
            binding.donutSms.data = null
            binding.donutSms.invalidate()
            binding.legendSms.text = "Bugun SMS yo'q"
            return
        }
        val entries = mutableListOf<PieEntry>()
        val colors = mutableListOf<Int>()
        if (sent > 0) { entries.add(PieEntry(sent.toFloat())); colors.add(Color.parseColor("#3498DB")) }
        if (received > 0) { entries.add(PieEntry(received.toFloat())); colors.add(Color.parseColor("#2ECC71")) }

        binding.donutSms.data = PieData(donutDataSet(entries, colors))
        binding.donutSms.invalidate()
        binding.legendSms.text = "🔵 Yuborilgan ${percent(sent, total)}%\n🟢 Qabul qilingan ${percent(received, total)}%"
    }

    private fun drawContactsDonut(totalContacts: Int) {
        styleDonut(binding.donutContacts, "$totalContacts ta")
        if (totalContacts == 0) {
            binding.donutContacts.data = null
            binding.donutContacts.invalidate()
            binding.legendContacts.text = "Hali kontakt saqlanmagan"
            return
        }
        val entries = listOf(PieEntry(totalContacts.toFloat()))
        val colors = listOf(Color.parseColor("#2ECC71"))
        binding.donutContacts.data = PieData(donutDataSet(entries, colors))
        binding.donutContacts.invalidate()
        binding.legendContacts.text = "🟢 Bugun faol 100%"
    }

    private fun drawAppsDonut(usage: List<uz.oilanazorati.parentcontrol.model.AppUsageEvent>) {
        val totals = usage.groupBy { it.ilovaNomi }
            .mapValues { (_, list) -> list.sumOf { it.davomiylikSoniya } }
            .toList()
            .sortedByDescending { it.second }

        styleDonut(binding.donutApps, "${totals.size} ta")
        val totalSeconds = totals.sumOf { it.second }
        if (totals.isEmpty() || totalSeconds == 0L) {
            binding.donutApps.data = null
            binding.donutApps.invalidate()
            binding.legendApps.text = "Bugun faoliyat yo'q"
            return
        }

        // Ilovalarni ishlatilish vaqti bo'yicha 3ta darajaga bo'lamiz: Faol / O'rta / Kam.
        val third = (totals.size + 2) / 3
        val faol = totals.take(third).sumOf { it.second }
        val orta = totals.drop(third).take(third).sumOf { it.second }
        val kam = totals.drop(third * 2).sumOf { it.second }

        val entries = mutableListOf<PieEntry>()
        val colors = mutableListOf<Int>()
        val legendParts = mutableListOf<String>()
        if (faol > 0) { entries.add(PieEntry(faol.toFloat())); colors.add(Color.parseColor("#2ECC71")); legendParts.add("🟢 Faol ${percent(faol, totalSeconds)}%") }
        if (orta > 0) { entries.add(PieEntry(orta.toFloat())); colors.add(Color.parseColor("#F39C12")); legendParts.add("🟠 O'rta ${percent(orta, totalSeconds)}%") }
        if (kam > 0) { entries.add(PieEntry(kam.toFloat())); colors.add(Color.parseColor("#E74C3C")); legendParts.add("🔴 Kam ${percent(kam, totalSeconds)}%") }

        binding.donutApps.data = PieData(donutDataSet(entries, colors))
        binding.donutApps.invalidate()
        binding.legendApps.text = legendParts.joinToString("\n")
    }

    private fun percent(part: Int, total: Int): Int = if (total == 0) 0 else (part * 100) / total
    private fun percent(part: Long, total: Long): Int = if (total == 0L) 0 else (part * 100 / total).toInt()

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
