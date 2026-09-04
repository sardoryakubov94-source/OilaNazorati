package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.text.SimpleDateFormat
import java.util.*

class ParentDashboardActivity : AppCompatActivity() {
    private lateinit var binding: uz.oilanazorati.parentcontrol.databinding.ActivityParentDashboardBinding
    private val appUsageAdapter = AppUsageAdapter()
    private val timelineAdapter = TimelineAdapter()
    private val smsAdapter = SmsHistoryAdapter()
    private val contactSummaryAdapter = ContactSummaryAdapter()
    private var isPremiumUser = false
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var lastBackPressMs = 0L
    private val doubleBackWindowMs = 2000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = uz.oilanazorati.parentcontrol.databinding.ActivityParentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (!ensureAuth()) return
        setupLists(); setupHeader(); setupBottomNav(); setupSectionButtons()
        FirebaseRepo.checkIsPremium { isPremium ->
            isPremiumUser = isPremium
            contactSummaryAdapter.setPremium(isPremium)
            smsAdapter.setPremium(isPremium)
        }
        val savedCode = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getString("family_code", null)
        if (savedCode != null) { binding.headerFamilyCode.text = savedCode; loadFamily(savedCode) }
    }

    private fun setupLists() {
        binding.appUsageList.layoutManager = LinearLayoutManager(this); binding.appUsageList.adapter = appUsageAdapter
        binding.timelineList.layoutManager = LinearLayoutManager(this); binding.timelineList.adapter = timelineAdapter
        binding.smsTimelineList.layoutManager = LinearLayoutManager(this); binding.smsTimelineList.adapter = smsAdapter
        binding.contactSummaryList.layoutManager = LinearLayoutManager(this); binding.contactSummaryList.adapter = contactSummaryAdapter
    }

    private fun setupHeader() {
        binding.btnChooseChild.setOnClickListener {
            val code = FirebaseRepo.familyCode
            if (code.isNullOrBlank()) { Toast.makeText(this, "Avval oila kodini kiriting yoki yarating", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            showChildPickerDialog(code)
        }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    private fun setupBottomNav() { bindBottomNav(NavTab.HOME) }

    private fun setupSectionButtons() {
        binding.btnLocationHistory.setOnClickListener { openIfChildSelected { LocationHistoryActivity::class.java } }
        binding.btnCallHistory.setOnClickListener { openIfChildSelected { CallHistoryActivity::class.java } }
        binding.btnSmsHistory.setOnClickListener { openIfChildSelected { SmsHistoryActivity::class.java } }
        binding.btnSavedContacts.setOnClickListener { startActivity(Intent(this, SavedContactsActivity::class.java)) }
        binding.btnTrends.setOnClickListener { startActivity(Intent(this, TrendsActivity::class.java)) }
        binding.cardStatCalls.setOnClickListener { openIfChildSelected { CallHistoryActivity::class.java } }
        binding.cardStatSms.setOnClickListener { openIfChildSelected { SmsHistoryActivity::class.java } }
        binding.cardStatContacts.setOnClickListener { startActivity(Intent(this, SavedContactsActivity::class.java)) }
        binding.premiumBannerHome.setOnClickListener { startActivity(Intent(this, PremiumActivity::class.java)) }
        binding.btnPremiumCtaHome.setOnClickListener { startActivity(Intent(this, PremiumActivity::class.java)) }
        binding.cardStatScreenshot.setOnClickListener {
            if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show()
            else startActivity(Intent(this, ScreenshotSettingsActivity::class.java))
        }
    }

    private fun openIfChildSelected(activityClass: () -> Class<*>) {
        if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) { Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent(this, activityClass()))
    }

    private fun ensureAuth(): Boolean {
        if (FirebaseAuth.getInstance().currentUser == null) { startActivity(Intent(this, MainActivity::class.java)); finish(); return false }
        return true
    }

    private fun loadFamily(code: String) {
        FirebaseRepo.familyCode = code
        binding.headerFamilyCode.text = code
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit().putString("family_code", code).apply()
        FirebaseRepo.fetchChildren(code) { children ->
            when {
                children.isEmpty() -> Toast.makeText(this, "Bu kodga hali birorta farzand ulanmagan", Toast.LENGTH_SHORT).show()
                children.size == 1 -> { setChildId(children.first().first); loadTodayStats() }
                else -> showChildPickerDialog(code, children)
            }
        }
    }

    private fun setChildId(id: String) {
        FirebaseRepo.childId = id
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit().putString("child_id", id).apply()
    }

    private fun showChildPickerDialog(code: String, preloaded: List<Pair<String, String>>? = null) {
        val show: (List<Pair<String, String>>) -> Unit = { children ->
            if (children.isEmpty()) Toast.makeText(this, "Bu kodga hali birorta farzand ulanmagan", Toast.LENGTH_SHORT).show()
            else AlertDialog.Builder(this).setTitle("Qaysi farzand?").setItems(children.map { it.second }.toTypedArray()) { _, index -> setChildId(children[index].first); loadTodayStats() }.show()
        }
        if (preloaded != null) show(preloaded) else FirebaseRepo.fetchChildren(code, show)
    }

    private fun loadTodayStats() {
        val cal = Calendar.getInstance(); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis; val dayEnd = dayStart + 24 * 60 * 60 * 1000
        FirebaseRepo.listenSavedContacts { contacts -> val names = contacts.associate { it.kontaktHash to it.nomi }; contactSummaryAdapter.setNames(names); smsAdapter.setNames(names) }
        FirebaseRepo.listenCallsForDay(dayStart, dayEnd) { calls ->
            val incoming = calls.count { it.turi == "kiruvchi" }; val outgoing = calls.count { it.turi == "chiquvchi" }
            binding.statCallCount.text = "${calls.size} ta"; binding.statCallDetail.text = "$incoming kiruvchi\n$outgoing chiquvchi"; timelineAdapter.setCalls(calls, timeFmt)
            val stats = buildContactStats(calls); binding.statContactCount.text = "${stats.count { it.kontaktHash != "noma_lum" }} ta"; contactSummaryAdapter.setStats(stats)
        }
        FirebaseRepo.listenSmsForDay(dayStart, dayEnd) { sms ->
            val sent = sms.count { it.turi == "yuborilgan" }; val received = sms.count { it.turi == "qabul_qilingan" }
            binding.statSmsCount.text = "${sms.size} ta"; binding.statSmsDetail.text = "$sent yuborilgan\n$received qabul"; smsAdapter.setData(sms)
            binding.smsTimelineList.visibility = if (sms.isEmpty()) View.GONE else View.VISIBLE; binding.smsSectionEmpty.visibility = if (sms.isEmpty()) View.VISIBLE else View.GONE
        }
        FirebaseRepo.listenAppUsageForDay(dayStart, dayEnd) { usage -> appUsageAdapter.setData(usage.groupBy { it.ilovaNomi }.mapValues { (_, list) -> list.sumOf { it.davomiylikSoniya } }.toList().sortedByDescending { it.second }) }
        FirebaseRepo.listenLatestLocation { loc ->
            if (loc == null) return@listenLatestLocation
            val time = timeFmt.format(Date(loc.vaqtMs)); val minutesAgo = ((System.currentTimeMillis() - loc.vaqtMs) / 60000).coerceAtLeast(0)
            binding.locationTimeAgo.text = "$time • $minutesAgo daqiqa oldin"; binding.locationCoords.text = "${"%.5f".format(Locale.US, loc.lat)}, ${"%.5f".format(Locale.US, loc.lng)}"; binding.headerStatus.text = if (minutesAgo <= 45) "● FAOL" else "● NOFAOL"
            binding.headerStatus.setTextColor(android.graphics.Color.parseColor(if (minutesAgo <= 45) "#2ECC71" else "#8B96A5"))
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs <= doubleBackWindowMs) {
            finishAndRemoveTask()
            return
        }
        lastBackPressMs = now
        Toast.makeText(this, "Chiqish uchun yana bir marta 'Ortga' tugmasini bosing", Toast.LENGTH_SHORT).show()
    }
}
