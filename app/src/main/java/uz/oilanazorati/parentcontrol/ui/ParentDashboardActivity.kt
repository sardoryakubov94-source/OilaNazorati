package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.AdminConfig
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ota-ona uchun bosh ekran — professional dashboard ko'rinishi:
 * header (oila kodi + FAOL holati), so'nggi joylashuv kartasi (mini-xarita),
 * 4 ta statistik karta, so'ngra Qo'ng'iroqlar / SMS / Muloqotlar / Ilovalar
 * bo'limlari va pastki 5 tabli navigatsiya.
 *
 * Oila kodini kiritish/yaratish, Premium va Admin panel kabi kamdan-kam
 * ishlatiladigan amallar endi header'dagi ⚙️ (Sozlamalar) tugmasi va pastki
 * navigatsiyadagi "Sozlama" tabi orqali ochiladigan menyuga ko'chirildi —
 * yangi dizaynda header'da ularga alohida joy yo'q.
 */
class ParentDashboardActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: uz.oilanazorati.parentcontrol.databinding.ActivityParentDashboardBinding
    private val appUsageAdapter = AppUsageAdapter()
    private val timelineAdapter = TimelineAdapter()
    private val smsAdapter = SmsHistoryAdapter()
    private val contactSummaryAdapter = ContactSummaryAdapter()

    private var googleMap: GoogleMap? = null
    private var isPremiumUser = false
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = uz.oilanazorati.parentcontrol.databinding.ActivityParentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureAuth()
        setupLists()
        setupHeader()
        setupMiniMap()
        setupBottomNav()
        setupSectionButtons()

        FirebaseRepo.checkIsPremium { isPremium ->
            isPremiumUser = isPremium
            contactSummaryAdapter.setPremium(isPremium)
            smsAdapter.setPremium(isPremium)
        }

        val savedCode = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            .getString("family_code", null)
        if (savedCode != null) {
            binding.headerFamilyCode.text = savedCode
            loadFamily(savedCode)
        }
    }

    private fun setupLists() {
        binding.appUsageList.layoutManager = LinearLayoutManager(this)
        binding.appUsageList.adapter = appUsageAdapter
        binding.timelineList.layoutManager = LinearLayoutManager(this)
        binding.timelineList.adapter = timelineAdapter
        binding.smsTimelineList.layoutManager = LinearLayoutManager(this)
        binding.smsTimelineList.adapter = smsAdapter
        binding.contactSummaryList.layoutManager = LinearLayoutManager(this)
        binding.contactSummaryList.adapter = contactSummaryAdapter
    }

    private fun setupHeader() {
        binding.btnChooseChild.setOnClickListener {
            val code = FirebaseRepo.familyCode
            if (code.isNullOrBlank()) {
                Toast.makeText(this, "Avval oila kodini kiriting yoki yarating", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showChildPickerDialog(code)
        }
        binding.btnSettings.setOnClickListener { showSettingsSheet() }
    }

    private fun setupMiniMap() {
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.miniMapContainer, mapFragment)
            .commit()
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.apply {
            setAllGesturesEnabled(false)
            isMapToolbarEnabled = false
            isZoomControlsEnabled = false
        }
    }

    private fun setupBottomNav() {
        binding.navHome.setOnClickListener { /* Allaqachon shu ekranda */ }
        binding.navCalls.setOnClickListener { openIfChildSelected { CallHistoryActivity::class.java } }
        binding.navLocation.setOnClickListener { openIfChildSelected { LocationHistoryActivity::class.java } }
        binding.navApps.setOnClickListener { openIfChildSelected { TrendsActivity::class.java } }
        binding.navSettings.setOnClickListener { showSettingsSheet() }
    }

    private fun setupSectionButtons() {
        binding.btnLocationHistory.setOnClickListener { openIfChildSelected { LocationHistoryActivity::class.java } }
        binding.btnCallHistory.setOnClickListener { openIfChildSelected { CallHistoryActivity::class.java } }
        binding.btnSmsHistory.setOnClickListener { openIfChildSelected { SmsHistoryActivity::class.java } }
        binding.btnSavedContacts.setOnClickListener { startActivity(Intent(this, SavedContactsActivity::class.java)) }
        binding.btnTrends.setOnClickListener { startActivity(Intent(this, TrendsActivity::class.java)) }
    }

    private fun openIfChildSelected(activityClass: () -> Class<*>) {
        if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) {
            Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, activityClass()))
    }

    /**
     * Header'dan olib tashlangan, kamdan-kam ishlatiladigan amallar —
     * oila kodini kiritish/yuklash, yangi kod yaratish, Premium,
     * Bildirishnomalar tarixi, (adminlar uchun) Admin panel — shu yerda.
     */
    private fun showSettingsSheet() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add("🔑 Oila kodini kiritish / o'zgartirish" to { showEnterCodeDialog() })
        actions.add("🆕 Yangi oila kodi yaratish" to { createNewFamilyCode() })
        actions.add("🔔 Bildirishnomalar tarixi" to { openIfChildSelected { NotificationHistoryActivity::class.java } })
        actions.add("⭐ Premium" to { startActivity(Intent(this, PremiumActivity::class.java)) })
        if (AdminConfig.isCurrentUserAdmin()) {
            actions.add("🛠️ Admin panel" to { startActivity(Intent(this, AdminPanelActivity::class.java)) })
        }
        AlertDialog.Builder(this)
            .setTitle("Sozlamalar")
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .show()
    }

    private fun showEnterCodeDialog() {
        val input = EditText(this).apply {
            hint = "Oila kodi"
            setText(FirebaseRepo.familyCode.orEmpty())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Oila kodi")
            .setView(container)
            .setPositiveButton("Yuklash") { _, _ ->
                val code = input.text?.toString()?.trim()?.uppercase()
                if (!code.isNullOrBlank()) loadFamily(code)
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }

    private fun createNewFamilyCode() {
        val newCode = generateFamilyCode()
        FirebaseRepo.familyCode = newCode
        FirebaseRepo.createFamily(newCode) { success, errorMsg ->
            if (success) {
                loadFamily(newCode)
                showGeneratedCodeDialog(newCode)
            } else {
                Toast.makeText(this, "Kod yaratishda xato: ${errorMsg ?: "noma'lum"}", Toast.LENGTH_LONG).show()
            }
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
        binding.headerFamilyCode.text = code
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putString("family_code", code).apply()

        FirebaseRepo.fetchChildren(code) { children ->
            when {
                children.isEmpty() -> {
                    Toast.makeText(this, "Bu kodga hali birorta farzand ulanmagan", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Bu kodga hali birorta farzand ulanmagan", Toast.LENGTH_SHORT).show()
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

        FirebaseRepo.listenSavedContacts { contacts ->
            val names = contacts.associate { it.kontaktHash to it.nomi }
            contactSummaryAdapter.setNames(names)
            smsAdapter.setNames(names)
        }

        FirebaseRepo.listenCallsForDay(dayStart, dayEnd) { calls ->
            val incoming = calls.count { it.turi == "kiruvchi" }
            val outgoing = calls.count { it.turi == "chiquvchi" }

            binding.statCallCount.text = "${calls.size} ta"
            binding.statCallDetail.text = "$incoming kiruvchi\n$outgoing chiquvchi"

            timelineAdapter.setCalls(calls, timeFmt)

            val stats = buildContactStats(calls)
            binding.statContactCount.text = "${stats.count { it.kontaktHash != "noma_lum" }} ta"
            contactSummaryAdapter.setStats(stats)
        }

        FirebaseRepo.listenSmsForDay(dayStart, dayEnd) { sms ->
            val sent = sms.count { it.turi == "yuborilgan" }
            val received = sms.count { it.turi == "qabul_qilingan" }

            binding.statSmsCount.text = "${sms.size} ta"
            binding.statSmsDetail.text = "$sent yuborilgan\n$received qabul"

            smsAdapter.setData(sms)
            binding.smsTimelineList.visibility = if (sms.isEmpty()) View.GONE else View.VISIBLE
            binding.smsSectionEmpty.visibility = if (sms.isEmpty()) View.VISIBLE else View.GONE
        }

        FirebaseRepo.listenAppUsageForDay(dayStart, dayEnd) { usage ->
            val grouped = usage.groupBy { it.ilovaNomi }
                .mapValues { (_, list) -> list.sumOf { it.davomiylikSoniya } }
                .toList()
                .sortedByDescending { it.second }
            appUsageAdapter.setData(grouped)
            binding.statAppCount.text = "${grouped.size} ta"
        }

        FirebaseRepo.listenLatestLocation { loc ->
            if (loc == null) return@listenLatestLocation
            val time = timeFmt.format(Date(loc.vaqtMs))
            val minutesAgo = ((System.currentTimeMillis() - loc.vaqtMs) / 60000).coerceAtLeast(0)
            binding.locationTimeAgo.text = "🕐  $time • $minutesAgo daqiqa oldin"
            binding.locationCoords.text = "${"%.7f".format(loc.lat)}, ${"%.7f".format(loc.lng)}"
            binding.headerStatus.text = if (minutesAgo <= 45) "● FAOL" else "● NOFAOL"
            binding.headerStatus.setTextColor(
                android.graphics.Color.parseColor(if (minutesAgo <= 45) "#2ECC71" else "#8B96A5")
            )

            val position = LatLng(loc.lat, loc.lng)
            googleMap?.let { map ->
                map.clear()
                map.addMarker(MarkerOptions().position(position))
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(position, 14f))
            }
        }
    }
}
