package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import uz.oilanazorati.parentcontrol.databinding.ActivityParentDashboardBinding
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ota-ona ekrani: bugungi kun bo'yicha
 *  - qo'ng'iroqlar (kiruvchi/chiquvchi/javobsiz soni va daqiqasi)
 *  - SMS (yuborilgan/qabul qilingan soni)
 *  - ilovalarda o'tkazilgan vaqt (ro'yxat, eng ko'p vaqt sarflangani tepada)
 *  - so'nggi ma'lum joylashuv
 * ko'rsatiladi. Barchasi soat/daqiqa aniqligida, lekin kontakt/matn yo'q.
 */
class ParentDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentDashboardBinding
    private val appUsageAdapter = AppUsageAdapter()
    private val timelineAdapter = TimelineAdapter()
    private val contactSummaryAdapter = ContactSummaryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureAuth()

        binding.appUsageList.layoutManager = LinearLayoutManager(this)
        binding.appUsageList.adapter = appUsageAdapter
        binding.timelineList.layoutManager = LinearLayoutManager(this)
        binding.timelineList.adapter = timelineAdapter
        binding.contactSummaryList.layoutManager = LinearLayoutManager(this)
        binding.contactSummaryList.adapter = contactSummaryAdapter

        val savedCode = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            .getString("family_code", null)
        if (savedCode != null) {
            binding.inputFamilyCode.setText(savedCode)
            loadFamily(savedCode)
        }

        binding.btnLoadFamily.setOnClickListener {
            val code = binding.inputFamilyCode.text?.toString()?.trim()?.uppercase()
            if (!code.isNullOrBlank()) loadFamily(code)
        }
        binding.btnTrends.setOnClickListener {
            startActivity(Intent(this, TrendsActivity::class.java))
        }
        binding.btnSavedContacts.setOnClickListener {
            startActivity(Intent(this, SavedContactsActivity::class.java))
        }
       
    private fun ensureAuth() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) auth.signInAnonymously()
    }

    private fun loadFamily(code: String) {
        FirebaseRepo.familyCode = code
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putString("family_code", code).apply()

        // Oddiy holatda oilada bitta bola bo'ladi; agar bir nechta bo'lsa,
        // bu yerga bolalar ro'yxatini tanlash UI qo'shiladi. Hozircha
        // birinchi topilgan bola avtomatik tanlanadi.
        FirebaseFirestore.getInstance()
            .collection("families").document(code)
            .collection("children")
            .limit(1)
            .get()
            .addOnSuccessListener { snap ->
                val firstChild = snap.documents.firstOrNull()?.id ?: return@addOnSuccessListener
                FirebaseRepo.childId = firstChild
                loadTodayStats()
            }
    }

    private fun loadTodayStats() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        FirebaseRepo.listenSavedContacts { contacts ->
            contactSummaryAdapter.setNames(contacts.associate { it.kontaktHash to it.nomi })
        }
        FirebaseRepo.listenCallsForDay(dayStart, dayEnd) { calls ->
            val incoming = calls.count { it.turi == "kiruvchi" }
            val outgoing = calls.count { it.turi == "chiquvchi" }
            val missed = calls.count { it.turi == "javobsiz" }
            val incomingMin = calls.filter { it.turi == "kiruvchi" }.sumOf { it.davomiylikSoniya } / 60
            val outgoingMin = calls.filter { it.turi == "chiquvchi" }.sumOf { it.davomiylikSoniya } / 60

            binding.callsSummary.text =
                "📞 Kiruvchi: $incoming ta ($incomingMin daq)\n" +
                "📞 Chiquvchi: $outgoing ta ($outgoingMin daq)\n" +
                "📵 Javobsiz: $missed ta"

            timelineAdapter.setCalls(calls, timeFmt)

            // Anonim kontaktlar bo'yicha yig'indi: raqamlar ko'rinmaydi,
            // faqat "necha xil odam bilan gaplashgan" va har birining
            // rangi/soni/daqiqasi ko'rinadi.
            val stats = buildContactStats(calls)
            val distinctCount = stats.count { it.kontaktHash != "noma_lum" }
            binding.contactCountSummary.text =
                "Bugun $distinctCount xil raqam bilan gaplashgan " +
                "(rang qanchalik ko'p qaytarilsa, shuncha ko'p o'sha kontakt bilan gaplashilgan)"
            contactSummaryAdapter.setStats(stats)
        }

        FirebaseRepo.listenSmsForDay(dayStart, dayEnd) { sms ->
            val sent = sms.count { it.turi == "yuborilgan" }
            val received = sms.count { it.turi == "qabul_qilingan" }
            binding.smsSummary.text = "💬 Yuborilgan: $sent ta\n💬 Qabul qilingan: $received ta"
        }

        FirebaseRepo.listenAppUsageForDay(dayStart, dayEnd) { usage ->
            val grouped = usage.groupBy { it.ilovaNomi }
                .mapValues { (_, list) -> list.sumOf { it.davomiylikSoniya } }
                .toList()
                .sortedByDescending { it.second }
            appUsageAdapter.setData(grouped)
        }

        FirebaseRepo.listenLatestLocation { loc ->
            if (loc != null) {
                val time = timeFmt.format(Date(loc.vaqtMs))
                binding.locationSummary.text = "📍 So'nggi joylashuv: ${loc.lat}, ${loc.lng} ($time)"
            }
        }
    }
}
