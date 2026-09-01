package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
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
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ota-ona uchun bosh ekran — professional dashboard ko'rinishi:
 * header (oila kodi + FAOL holati), so'nggi joylashuv kartasi (mini-xarita),
 * 4 ta statistik karta, so'ngra Qo'ng'iroqlar / SMS / Muloqotlar / Ilovalar
 * bo'limlari va pastki 5 tabli navigatsiya.
 *
 * Oila kodini kiritish/yaratish, Premium va Admin panel kabi kamdan-kam
 * ishlatiladigan amallar endi to'liq alohida SettingsActivity ekranida —
 * header'dagi ⚙️ tugmasi va pastki navigatsiyadagi "Sozlama" tabi o'sha
 * ekranga olib boradi.
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

        if (!ensureAuth()) return

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
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
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
        bindBottomNav(NavTab.HOME)
    }

    private fun setupSectionButtons() {
        binding.btnLocationHistory.setOnClickListener { openIfChildSelected { LocationHistoryActivity::class.java } }
        binding.btnCallHistory.setOnClickListener { openIfChildSelected { CallHistoryActivity::class.java } }
        binding.btnSmsHistory.setOnClickListener { openIfChildSelected { SmsHistoryActivity::class.java } }
        binding.btnSavedContacts.setOnClickListener { startActivity(Intent(this, SavedContactsActivity::class.java)) }
        binding.btnTrends.setOnClickListener { startActivity(Intent(this, TrendsActivity::class.java)) }

        // "Bugungi nazorat" statistik kartalari ham bosilganda tegishli
        // to'liq ekranga o'tkazadi — endi faqat ko'rsatib turadigan
        // dekorativ katakcha emas.
        binding.cardStatCalls.setOnClickListener { openIfChildSelected { CallHistoryActivity::class.java } }
        binding.cardStatSms.setOnClickListener { openIfChildSelected { SmsHistoryActivity::class.java } }
        binding.cardStatContacts.setOnClickListener { startActivity(Intent(this, SavedContactsActivity::class.java)) }
        binding.cardStatApps.setOnClickListener { startActivity(Intent(this, TrendsActivity::class.java)) }
    }

    private fun openIfChildSelected(activityClass: () -> Class<*>) {
        if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) {
            Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, activityClass()))
    }

    /**
     * MUHIM TUZATISH: bu yerda avval `auth.currentUser == null` bo'lsa
     * `signInAnonymously()` chaqirilar edi — bu esa ota-onaning HAQIQIY
     * Google identifikatorini ANONIM identifikator bilan almashtirib
     * qo'yardi (masalan process qayta ishga tushganda, tungi/kunduzgi
     * temani almashtirganda ham shu holat yuz berishi mumkin edi).
     * Anonim identifikator FAQAT bola qurilmasi uchun mo'ljallangan
     * (ChildSetupActivity) — ota-ona tomonida ishlatilsa, Firestore
     * qoidalari (isRealOwner) farzandlar ro'yxatini ko'rsatishni rad
     * etadi va "Bu kodga hali birorta farzand ulanmagan" degan noto'g'ri
     * xabar chiqadi, garchi farzand aslida ulangan bo'lsa ham.
     * Endi bunday holatda anonim kirish o'rniga foydalanuvchi qayta
     * Google bilan kirishi uchun kirish ekraniga qaytariladi.
     */
    private fun ensureAuth(): Boolean {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return false
        }
        return true
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
                    setChildId(children.first().first)
                    loadTodayStats()
                }
                else -> showChildPickerDialog(code, children)
            }
        }
    }

    /**
     * FirebaseRepo.childId'ni to'g'ridan-to'g'ri o'rnatish YETARLI EMAS —
     * u faqat xotirada (RAM) saqlanadi. Agar tizim ilova jarayonini
     * keyinroq tozalab qo'ysa (masalan xotira yetishmasa, yoki mavzuni
     * almashtirganda ba'zi qurilmalarda bo'lgani kabi), jarayon qaytadan
     * boshlanganda bu qiymat yo'qolib, "hech qanday qurilma ulanmagan"
     * holatiga qaytib qolar edi — garchi oila kodi to'g'ri saqlangan
     * bo'lsa ham. Shu funksiya childId'ni SharedPreferences'ga ham
     * yozadi, App.kt esa jarayon boshlanganda uni qaytadan tiklaydi.
     */
    private fun setChildId(id: String) {
        FirebaseRepo.childId = id
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putString("child_id", id).apply()
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
                        setChildId(children[index].first)
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
            binding.locationCoords.text = "${"%.5f".format(Locale.US, loc.lat)}, ${"%.5f".format(Locale.US, loc.lng)}"
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

    // Tab-ekranlar endi finish() qilinmaydi (tez almashish uchun tirik
    // qoladi) — shuning uchun orqaga tugmasi eski ekranga qaytish
    // o'rniga to'g'ridan-to'g'ri ilovadan chiqishi kerak.
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
