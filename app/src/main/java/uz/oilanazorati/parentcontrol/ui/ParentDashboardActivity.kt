package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import uz.oilanazorati.parentcontrol.databinding.ActivityParentDashboardBinding
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.text.SimpleDateFormat
import java.util.*

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
        binding.btnGenerateCode.setOnClickListener {
            val newCode = generateFamilyCode()
            FirebaseRepo.familyCode = newCode
            FirebaseRepo.createFamily(newCode) { success, errorMsg ->
                if (success) {
                    binding.inputFamilyCode.setText(newCode)
                    loadFamily(newCode)
                    showGeneratedCodeDialog(newCode)
                } else {
                    android.widget.Toast.makeText(
                        this,
                        "Kod yaratishda xato: ${errorMsg ?: "noma'lum"}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        binding.btnChooseChild.setOnClickListener {
            val code = FirebaseRepo.familyCode
                ?: binding.inputFamilyCode.text?.toString()?.trim()?.uppercase()
            if (code.isNullOrBlank()) {
                android.widget.Toast.makeText(
                    this, "Avval oila kodini kiriting yoki yarating", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            showChildPickerDialog(code)
        }
        binding.btnTrends.setOnClickListener {
            startActivity(Intent(this, TrendsActivity::class.java))
        }
        binding.btnSavedContacts.setOnClickListener {
            startActivity(Intent(this, SavedContactsActivity::class.java))
        }
        binding.btnLocationHistory.setOnClickListener {
            if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) {
                android.widget.Toast.makeText(
                    this, "Avval oila kodini yuklab, farzandni tanlang", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, LocationHistoryActivity::class.java))
        }
        binding.btnCallHistory.setOnClickListener {
            if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) {
                android.widget.Toast.makeText(
                    this, "Avval oila kodini yuklab, farzandni tanlang", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, CallHistoryActivity::class.java))
        }
        binding.btnSmsHistory.setOnClickListener {
            if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) {
                android.widget.Toast.makeText(
                    this, "Avval oila kodini yuklab, farzandni tanlang", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, SmsHistoryActivity::class.java))
        }
    }

    private fun ensureAuth() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) auth.signInAnonymously()
    }

    private fun generateFamilyCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun showGeneratedCodeDialog(code: String) {
        AlertDialog.Builder(this)
            .setTitle("Yangi oila kodi yaratildi")
            .setMessage(
                "$code\n\nBu kodni bola qurilmasidagi \"Bu farzandimning telefoni\" " +
                "ekranidagi \"Ota-onaning kodini kiriting\" maydoniga kiriting."
            )
            .setPositiveButton("Tushunarli", null)
            .show()
    }

    private fun loadFamily(code: String) {
        FirebaseRepo.familyCode = code
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putString("family_code", code).apply()

        FirebaseRepo.fetchChildren(code) { children ->
            when {
                children.isEmpty() -> {
                    android.widget.Toast.makeText(
                        this, "Bu kodga hali birorta farzand ulanmagan", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                children.size == 1 -> {
                    FirebaseRepo.childId = children.first().first
                    loadTodayStats()
                }
                else -> showChildPickerDialog(code, children)
            }
        }
    }

    private fun showChildPickerDialog(code: String, preloaded: List<Pair<String, String>>? = null) {
        val show: (List<Pair<String, String>>) -> Unit = { children ->
            if (children.isEmpty()) {
                android.widget.Toast.makeText(
                    this, "Bu kodga hali birorta farzand ulanmagan", android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                val names = children.map { it.second }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Qaysi farzand?")
                    .setItems(names) { _, index ->
                        FirebaseRepo.childId = children[index].first
                        loadTodayStats()
                    }
                    .show()
            }
        }
        if (preloaded != null) show(preloaded) else FirebaseRepo.fetchChildren(code, show)
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
                "Kiruvchi: $incoming ta ($incomingMin daq)\n" +
                "Chiquvchi: $outgoing ta ($outgoingMin daq)\n" +
                "Javobsiz: $missed ta"

            timelineAdapter.setCalls(calls, timeFmt)

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
            binding.smsSummary.text = "Yuborilgan: $sent ta\nQabul qilingan: $received ta"
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
