package uz.oilanazorati.parentcontrol.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import uz.oilanazorati.parentcontrol.databinding.ActivitySavedContactsBinding
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.util.Calendar

/**
 * Ota-ona ekrani: "Kontaktlar". Ikki qism:
 *
 * 1) BUGUN FAOL — bosh sahifadagi "N ta Kontaktlar" kartasi bilan AYNAN bir xil
 *    ro'yxat (bugun kimlar bilan gaplashilgani). Qatorga bosilsa — shu kontakt
 *    bilan oxirgi 30 kunda necha marta gaplashilgani ko'rsatiladi.
 * 2) KALENDAR — istalgan sanani tanlab, o'sha kunda nechta kontakt bilan
 *    gaplashilganini ko'rish mumkin. Shu kundagi kontaktlardan birini bosilsa,
 *    oy boshidan tanlangan sanagacha FAQAT o'sha kontakt bilan qaysi kunlari
 *    necha marta gaplashilgani ro'yxati ochiladi.
 *
 * Avval bu ekran bola qurilmasidagi BARCHA saqlangan kontaktlar ro'yxatini
 * (gaplashilgan-gaplashilmaganidan qat'i nazar) ko'rsatardi — bu bosh
 * sahifadagi "N ta" soni bilan mos kelmasdi. Endi ikkalasi bitta ma'lumotga
 * asoslanadi. Raqamning o'zi bu ekranda hech qachon ko'rsatilmaydi — faqat
 * rang orqali va, agar qurilmada ism bilan saqlangan bo'lsa, o'sha ism orqali
 * "shu odam" ekani bilinadi.
 */
class SavedContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySavedContactsBinding
    private val todayAdapter = ContactStatClickableAdapter { showThirtyDaySummary(it) }
    private val dayAdapter = ContactStatClickableAdapter { showMonthBreakdown(it, selectedDayStart) }
    private val allContactsAdapter = SavedContactsAdapter()

    private var names: Map<String, String> = emptyMap()
    private var contactsListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var todayCallsListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var selectedDayStart: Long = 0L

    private val uzMonths = arrayOf(
        "yanvar", "fevral", "mart", "aprel", "may", "iyun",
        "iyul", "avgust", "sentabr", "oktabr", "noyabr", "dekabr"
    )
    private val uzDays = arrayOf(
        "yakshanba", "dushanba", "seshanba", "chorshanba", "payshanba", "juma", "shanba"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySavedContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindBottomNav(NavTab.CALLS)

        binding.todayContactsList.layoutManager = LinearLayoutManager(this)
        binding.todayContactsList.adapter = todayAdapter
        binding.dayContactsList.layoutManager = LinearLayoutManager(this)
        binding.dayContactsList.adapter = dayAdapter
        binding.savedContactsList.layoutManager = LinearLayoutManager(this)
        binding.savedContactsList.adapter = allContactsAdapter

        contactsListener = FirebaseRepo.listenSavedContacts { contacts ->
            names = contacts.associate { it.kontaktHash to it.nomi }
            todayAdapter.setNames(names)
            dayAdapter.setNames(names)
            allContactsAdapter.setContacts(contacts)
        }

        val todayStart = startOfDay(System.currentTimeMillis())
        todayCallsListener = FirebaseRepo.listenCallsForDay(todayStart, todayStart + DAY_MS) { calls ->
            if (isFinishing || isDestroyed) return@listenCallsForDay
            val stats = buildContactStats(calls)
            todayAdapter.setStats(stats)
            binding.todayEmptyText.visibility = if (stats.isEmpty()) View.VISIBLE else View.GONE
            binding.todayContactsList.visibility = if (stats.isEmpty()) View.GONE else View.VISIBLE
        }

        binding.btnPickDate.setOnClickListener { openDatePicker() }
    }

    private fun startOfDay(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun uzDateLabel(ms: Long): String {
        val c = Calendar.getInstance(); c.timeInMillis = ms
        val day = c.get(Calendar.DAY_OF_MONTH)
        val month = uzMonths[c.get(Calendar.MONTH)]
        val weekday = uzDays[c.get(Calendar.DAY_OF_WEEK) - 1]
        return "$day-$month, $weekday"
    }

    private fun openDatePicker() {
        val cal = Calendar.getInstance()
        if (selectedDayStart > 0) cal.timeInMillis = selectedDayStart
        val dlg = DatePickerDialog(this, { _, y, m, d ->
            val picked = Calendar.getInstance()
            picked.set(y, m, d, 0, 0, 0); picked.set(Calendar.MILLISECOND, 0)
            loadDay(picked.timeInMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        dlg.datePicker.maxDate = System.currentTimeMillis()
        dlg.show()
    }

    /** Kalendardan tanlangan kun uchun: o'sha kunda nechta kontakt bilan gaplashilgani. */
    private fun loadDay(dayStart: Long) {
        selectedDayStart = dayStart
        binding.selectedDateLabel.text = uzDateLabel(dayStart)
        binding.dayResultCard.visibility = View.VISIBLE
        binding.dayCountLabel.text = "Yuklanmoqda…"
        FirebaseRepo.fetchCallsInRange(dayStart, dayStart + DAY_MS) { calls ->
            if (isFinishing || isDestroyed) return@fetchCallsInRange
            val stats = buildContactStats(calls)
            binding.dayCountLabel.text = "${stats.size} ta kontakt bilan gaplashilgan"
            dayAdapter.setStats(stats)
            binding.dayEmptyText.visibility = if (stats.isEmpty()) View.VISIBLE else View.GONE
            binding.dayContactsList.visibility = if (stats.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** "Bugun faol" ro'yxatidagi kontaktga bosilganda: oxirgi 30 kunlik umumiy son. */
    private fun showThirtyDaySummary(stat: ContactStat) {
        val end = System.currentTimeMillis(); val start = end - 30L * DAY_MS
        FirebaseRepo.fetchCallsInRange(start, end) { calls ->
            if (isFinishing || isDestroyed) return@fetchCallsInRange
            val mine = calls.filter { it.kontaktHash.ifBlank { "noma_lum" } == stat.kontaktHash }
            val minutes = mine.sumOf { it.davomiylikSoniya } / 60
            AlertDialog.Builder(this)
                .setTitle(labelFor(stat.kontaktHash))
                .setMessage("Oxirgi 30 kunda: ${mine.size} marta qo'ng'iroq, jami $minutes daqiqa.")
                .setPositiveButton("Yopish", null)
                .show()
        }
    }

    /**
     * Kalendar kunidagi kontaktga bosilganda: oy boshidan shu sanagacha,
     * FAQAT o'sha kontakt bilan qaysi kunlari necha marta gaplashilgani.
     */
    private fun showMonthBreakdown(stat: ContactStat, dayStart: Long) {
        val cal = Calendar.getInstance(); cal.timeInMillis = dayStart
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = startOfDay(cal.timeInMillis)
        FirebaseRepo.fetchCallsInRange(monthStart, dayStart + DAY_MS) { calls ->
            if (isFinishing || isDestroyed) return@fetchCallsInRange
            val mine = calls.filter { it.kontaktHash.ifBlank { "noma_lum" } == stat.kontaktHash }
            val perDay = mine.groupBy { startOfDay(it.boshlanishMs) }
                .toSortedMap(compareByDescending { it })

            val scroll = ScrollView(this)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            if (perDay.isEmpty()) {
                box.addView(TextView(this).apply {
                    text = "Bu oyda hali gaplashilmagan."
                    setTextColor(Color.parseColor("#6B7A90"))
                    textSize = 14f
                })
            } else {
                perDay.forEach { (day, list) ->
                    box.addView(TextView(this).apply {
                        text = "${uzDateLabel(day)} — ${list.size} marta"
                        setTextColor(Color.parseColor("#0F1B33"))
                        textSize = 14f
                        setPadding(0, dp(8), 0, dp(8))
                    })
                }
            }
            scroll.addView(box)
            AlertDialog.Builder(this)
                .setTitle(labelFor(stat.kontaktHash))
                .setView(scroll)
                .setPositiveButton("Yopish", null)
                .show()
        }
    }

    private fun labelFor(hash: String): String = when {
        hash == "noma_lum" -> "Noma'lum raqam"
        names.containsKey(hash) -> names[hash]!!
        else -> "Saqlanmagan kontakt"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        contactsListener?.remove()
        todayCallsListener?.remove()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        startActivity(Intent(this, ParentDashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
