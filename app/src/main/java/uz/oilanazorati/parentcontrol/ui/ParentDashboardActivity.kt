package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.screenshot.FocusModeRepository
import java.text.SimpleDateFormat
import java.util.*

class ParentDashboardActivity : AppCompatActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // MUHIM: bu ekran "singleTask" rejimida ochiladi. Agar u allaqachon
        // ochiq bo'lsa-yu, "Mening oilalarim"dan BOSHQA oila kodi tanlansa,
        // Android yangi nusxa yaratish o'rniga shu ekranni oldinga
        // chiqaradi — lekin hech narsa qayta yuklanmaydi, eski oilaning
        // ma'lumotlari ko'rinaveradi. setIntent(intent) + recreate() bilan
        // ekranni yangi FirebaseRepo.familyCode uchun to'liq qayta yuklaymiz.
        setIntent(intent)
        recreate()
    }

    private lateinit var binding: uz.oilanazorati.parentcontrol.databinding.ActivityParentDashboardBinding
    private val appUsageAdapter = AppUsageAdapter()
    private val timelineAdapter = TimelineAdapter()
    private val smsAdapter = SmsHistoryAdapter()
    private val contactSummaryAdapter = ContactSummaryAdapter()
    private var isPremiumUser = false
    private var selectedChildPlatform: ChildDevicePlatform = ChildDevicePlatform.UNKNOWN
    private var selectedChildCapabilities: Set<ParentFeature> = emptySet()
    private var platformUiDefaults: Map<View, Int>? = null
    private var simInfoListener: com.google.firebase.firestore.ListenerRegistration? = null
    // MUHIM: bular ilgari saqlanmagani uchun har safar loadTodayStats() chaqirilganda
    // (masalan bola almashtirilganda) ESKI listenerlar o'chirilmay, ustiga yangisi
    // qo'shilib borardi — Firestore o'qish kvotasini keraksiz ko'paytirar edi.
    private var contactsListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var callsListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var smsListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var appUsageListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var locationListener: com.google.firebase.firestore.ListenerRegistration? = null
    private lateinit var simCardText: TextView
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var lastBackPressMs = 0L
    private val doubleBackWindowMs = 2000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = uz.oilanazorati.parentcontrol.databinding.ActivityParentDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (!ensureAuth()) return
        setupLists(); setupHeader(); setupBottomNav(); setupSectionButtons(); installSimInfoCard(); installOvozXavfRow()
        loadLocationRadarGif()
        FirebaseRepo.checkIsPremium { isPremium ->
            isPremiumUser = isPremium
            contactSummaryAdapter.setPremium(isPremium)
            smsAdapter.setPremium(isPremium)
            binding.premiumBannerHome.visibility = if (isPremium || selectedChildPlatform == ChildDevicePlatform.IOS) android.view.View.GONE else android.view.View.VISIBLE
            // O'z-o'zini davolash: premium holati boshqa yo'l bilan (masalan
            // to'g'ridan-to'g'ri Firebase konsolidan) o'zgargan bo'lsa ham,
            // dashboard har ochilganda bola hujjatlariga aktual holat qayta
            // yozib qo'yiladi — bildirishnoma filtri doim yangi ma'lumotga tayanadi.
            FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                FirebaseRepo.syncPremiumFlagToChildren(uid, isPremium) { }
            }
        }
        val parentPrefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
        val savedCode = parentPrefs.getString("parent_family_code", null)
            ?: parentPrefs.getString("family_code", null)
        val savedChild = parentPrefs.getString("parent_selected_child_id", null)
            ?: parentPrefs.getString("child_id", null)
        if (savedCode != null) {
            FirebaseRepo.familyCode = savedCode
            FirebaseRepo.childId = savedChild
            binding.headerFamilyCode.text = savedCode
            loadFamily(savedCode)
        } else {
            startActivity(Intent(this, ParentFamilyLinkActivity::class.java))
            finish()
        }
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
                ?: getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getString("parent_family_code", null)
            if (code.isNullOrBlank()) {
                Toast.makeText(this, "Oila kodi topilmadi. Qayta kiring.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, ParentFamilyLinkActivity::class.java).apply {
                putExtra(ParentFamilyLinkActivity.EXTRA_FAMILY_CODE, code)
            })
        }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnFocusMode.setOnClickListener {
            if (FirebaseRepo.familyCode.isNullOrBlank() || FirebaseRepo.childId.isNullOrBlank()) {
                Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show()
            } else if (selectedChildPlatform == ChildDevicePlatform.IOS) {
                showIosScreenTimeDialog()
            } else if (selectedChildCapabilities.contains(ParentFeature.FOCUS_MODE)) {
                showFocusModeDialog()
            } else {
                Toast.makeText(this, "Bu qurilmada Kontrol rejimi mavjud emas", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBottomNav() { bindBottomNav(NavTab.HOME) }

    private fun setupSectionButtons() {
        binding.btnLocationHistory.setOnClickListener { openIfChildSelected(ParentFeature.LOCATION) { LocationHistoryActivity::class.java } }
        binding.btnCallHistory.setOnClickListener { openIfChildSelected(ParentFeature.CALLS) { CallHistoryActivity::class.java } }
        binding.btnSmsHistory.setOnClickListener { openIfChildSelected(ParentFeature.SMS) { SmsHistoryActivity::class.java } }
        binding.btnSavedContacts.setOnClickListener { openIfChildSelected(ParentFeature.CONTACTS) { SavedContactsActivity::class.java } }
        binding.btnTrends.setOnClickListener { openIfChildSelected(ParentFeature.TRENDS) { TrendsActivity::class.java } }
        binding.cardStatCalls.setOnClickListener { openIfChildSelected(ParentFeature.CALLS) { CallHistoryActivity::class.java } }
        binding.cardStatSms.setOnClickListener { openIfChildSelected(ParentFeature.SMS) { SmsHistoryActivity::class.java } }
        binding.cardStatContacts.setOnClickListener { openIfChildSelected(ParentFeature.CONTACTS) { SavedContactsActivity::class.java } }
        binding.premiumBannerHome.setOnClickListener { startActivity(Intent(this, PremiumActivity::class.java)) }
        binding.btnPremiumCtaHome.setOnClickListener { startActivity(Intent(this, PremiumActivity::class.java)) }
        binding.cardStatScreenshot.setOnClickListener { openIfChildSelected(ParentFeature.SCREENSHOTS) { ScreenshotHistoryActivity::class.java } }
    }


    /**
     * "Ovoz" va "Xavfsizlik signallari" — bitta umumiy fon (karta) ichida yonma-yon,
     * har biri alohida bosiladigan, ekranning yarmini egallaydigan ikkita bo'lak.
     */

    /** So'nggi joylashuv kartasidagi GIF animatsiyasini yuklaydi va ishga tushiradi. */
    private fun loadLocationRadarGif() {
        try {
            val imageView = binding.locationRadarGif ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(resources, R.drawable.location_radar_anim)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source) as? AnimatedImageDrawable
                imageView.setImageDrawable(drawable)
                drawable?.start()
            } else {
                // API 26-27 uchun: statik radar rasmini ko'rsatamiz (GIF animatsiyasi API 28+ da ishlaydi)
                imageView.setImageResource(R.drawable.ic_location_radar)
            }
        } catch (e: Exception) {
            android.util.Log.w("Dashboard", "GIF yuklanmadi: ${e.message}")
        }
    }

    private fun installOvozXavfRow() {
        val scroll = binding.root.getChildAt(0) as? android.widget.ScrollView ?: return
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        if (content.findViewWithTag<View>("ovoz_xavf_row") != null) return

        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            tag = "ovoz_xavf_row"
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(androidx.core.content.ContextCompat.getColor(
                    this@ParentDashboardActivity,
                    uz.oilanazorati.parentcontrol.R.color.color_surface
                ))
            }
            elevation = 3f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (4 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
        }

        val ovozHalf = halfCell(
            uz.oilanazorati.parentcontrol.R.drawable.ic_mic_wave,
            androidx.core.content.ContextCompat.getColor(this, uz.oilanazorati.parentcontrol.R.color.color_primary),
            "Ovoz", "Jonli eshitish"
        ) {
            FirebaseRepo.checkIsPremium { isPremium ->
                if (!isPremium) {
                    AlertDialog.Builder(this@ParentDashboardActivity)
                        .setTitle("⭐ Premium kerak")
                        .setMessage("Jonli ovoz eshitish faqat premium foydalanuvchilar uchun mavjud.")
                        .setPositiveButton("Premiumni ochish") { _, _ -> startActivity(Intent(this@ParentDashboardActivity, PremiumActivity::class.java)) }
                        .setNegativeButton("Bekor qilish", null)
                        .show()
                    return@checkIsPremium
                }
                openIfChildSelected(ParentFeature.AMBIENT_AUDIO) { AmbientListenActivity::class.java }
            }
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), -1).apply {
                topMargin = dp(16); bottomMargin = dp(16)
            }
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(
                this@ParentDashboardActivity,
                uz.oilanazorati.parentcontrol.R.color.color_border
            ))
        }

        val xavfHalf = halfCell(
            uz.oilanazorati.parentcontrol.R.drawable.ic_shield_alert,
            0xFFF59E0B.toInt(),
            "Xavfsizlik", "Muhim signallar"
        ) {
            openIfChildSelected(ParentFeature.RISK_ALERTS) { RiskAlertsActivity::class.java }
        }

        row.addView(ovozHalf); row.addView(divider); row.addView(xavfHalf)

        val simCard = content.findViewWithTag<View>("sim_info_card")
        val idx = content.indexOfChild(simCard)
        content.addView(row, if (idx >= 0) idx + 1 else content.childCount)

        if (content.findViewWithTag<View>("ios_screen_share_card") == null) {
            val screenCard = TextView(this).apply {
                tag = "ios_screen_share_card"
                text = "📺  Jonli ekran ulash\n      iPhone farzand ekranini ko‘rish"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(androidx.core.content.ContextCompat.getColor(this@ParentDashboardActivity, R.color.color_text_primary))
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(androidx.core.content.ContextCompat.getColor(this@ParentDashboardActivity, R.color.color_surface))
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(2); bottomMargin = dp(12)
                }
                setOnClickListener {
                    if (FirebaseRepo.familyCode.isNullOrBlank() || FirebaseRepo.childId.isNullOrBlank()) {
                        Toast.makeText(this@ParentDashboardActivity, "Avval farzandni tanlang", Toast.LENGTH_SHORT).show()
                    } else FirebaseRepo.checkIsPremium { premium ->
                        if (!premium) AlertDialog.Builder(this@ParentDashboardActivity)
                            .setTitle("⭐ Premium kerak")
                            .setMessage("Jonli ekran ko‘rish Premium uchun.")
                            .setPositiveButton("Premiumni ochish") { _, _ -> startActivity(Intent(this@ParentDashboardActivity, PremiumActivity::class.java)) }
                            .setNegativeButton("Bekor qilish", null).show()
                        else startActivity(Intent(this@ParentDashboardActivity, ScreenShareListenActivity::class.java))
                    }
                }
            }
            content.addView(screenCard, content.indexOfChild(row) + 1)
        }
    }

    /** Yonma-yon joylashadigan ikkita bo'lakdan biri: markazlashtirilgan ikonka, sarlavha va kichik izoh. */
    private fun halfCell(iconRes: Int, tint: Int, title: String, subtitle: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { onClick() }
            setOnTouchListener { v, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.6f
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
            addView(iconTileCentered(iconRes, tint))
            addView(TextView(this@ParentDashboardActivity).apply {
                text = title
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(androidx.core.content.ContextCompat.getColor(this@ParentDashboardActivity, uz.oilanazorati.parentcontrol.R.color.color_text_primary))
                setPadding(0, dp(5), 0, 0)
            })
            addView(TextView(this@ParentDashboardActivity).apply {
                text = subtitle
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                setTextColor(androidx.core.content.ContextCompat.getColor(this@ParentDashboardActivity, uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    /** iconTile'ning markazlashtirilgan (chetga suriluvchi marginsiz) varianti — yonma-yon bo'laklar uchun. */
    private fun iconTileCentered(iconRes: Int, tint: Int): View {
        val tile = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 40))
            }
        }
        tile.addView(android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        })
        return tile
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun iconTile(iconRes: Int, tint: Int): View {
        val tile = android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(14) }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 40))
            }
        }
        tile.addView(android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        })
        return tile
    }

    private fun installSimInfoCard() {
        val scroll = binding.root.getChildAt(0) as? android.widget.ScrollView ?: return
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        if (content.findViewWithTag<View>("sim_info_card") != null) return

        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            tag = "sim_info_card"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(androidx.core.content.ContextCompat.getColor(this@ParentDashboardActivity, uz.oilanazorati.parentcontrol.R.color.color_surface))
            }
            elevation = 3f
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (4 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
        }
        val title = TextView(this).apply {
            text = "SIM / telefon raqamlari"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@ParentDashboardActivity, uz.oilanazorati.parentcontrol.R.color.color_text_primary))
        }
        simCardText = TextView(this).apply {
            text = "Raqam aniqlanmadi. Farzand telefonidan SIM ma'lumoti kutilmoqda."
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@ParentDashboardActivity, uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconTile(uz.oilanazorati.parentcontrol.R.drawable.ic_sim_card, 0xFF14B8A6.toInt()))
            addView(title)
        }
        card.addView(titleRow); card.addView(simCardText)
        val premiumIndex = content.indexOfChild(binding.premiumBannerHome)
        content.addView(card, if (premiumIndex >= 0) premiumIndex + 1 else 1)
    }

    private fun refreshSimInfo() {
        simInfoListener?.remove()
        simInfoListener = FirebaseRepo.listenSimInfo { count, cards ->
            if (!::simCardText.isInitialized) return@listenSimInfo
            if (count == 0 || cards.isEmpty()) {
                simCardText.text = "Raqam aniqlanmadi. Farzand telefonidan SIM ma'lumoti kutilmoqda."
                return@listenSimInfo
            }
            simCardText.text = cards.joinToString("\n") { sim ->
                val number = (sim["phoneNumber"] as? String)?.trim().orEmpty().ifBlank { "Aniqlanmadi" }
                val operator = (sim["operator"] as? String) ?: "Noma'lum operator"
                val slotRaw = (sim["slot"] as? Long)?.toInt() ?: 0
                "SIM ${slotRaw + 1}: $number ($operator)"
            }
        }
    }

    private fun openIfChildSelected(requiredFeature: ParentFeature, activityClass: () -> Class<*>) {
        if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) {
            Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show()
            return
        }
        if (!selectedChildCapabilities.contains(requiredFeature)) {
            Toast.makeText(this, "Bu funksiya tanlangan qurilmada qo'llab-quvvatlanmaydi", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, activityClass()))
    }

    private fun ensureAuth(): Boolean {
        if (FirebaseAuth.getInstance().currentUser == null) { startActivity(Intent(this, MainActivity::class.java)); finish(); return false }
        return true
    }

    private fun loadFamily(code: String) {
        FirebaseRepo.familyCode = code
        binding.headerFamilyCode.text = code
        val prefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
        val selectedId = prefs.getString("parent_selected_child_id", null)
            ?: prefs.getString("child_id", null)

        FirebaseRepo.fetchLinkedChildren(code) { children ->
            val selectedChild = children.firstOrNull { it.id == selectedId }
            if (selectedChild != null) {
                val platform = ChildDevicePlatform.from(selectedChild.platform)
                if (platform == ChildDevicePlatform.UNKNOWN) {
                    FirebaseRepo.childId = null
                    Toast.makeText(this, "Bola qurilmasi platformasi aniqlanmadi. Ilovani yangilab qayta ulang.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, ParentFamilyLinkActivity::class.java).apply {
                        putExtra(ParentFamilyLinkActivity.EXTRA_FAMILY_CODE, code)
                    })
                    finish()
                    return@fetchLinkedChildren
                }
                setChildId(selectedChild.id)
                setChildPlatform(platform, selectedChild.supportedCapabilities)
                applyChildPlatformUi()
                loadTodayStats()
            } else {
                // Tanlov yo'qolgan yoki bola oiladan chiqarilgan bo'lsa,
                // dashboard o'rniga umumiy bolalar oynasiga qaytamiz.
                FirebaseRepo.childId = null
                startActivity(Intent(this, ParentFamilyLinkActivity::class.java).apply {
                    putExtra(ParentFamilyLinkActivity.EXTRA_FAMILY_CODE, code)
                })
                finish()
            }
        }
    }

    private fun setChildId(id: String) {
        FirebaseRepo.childId = id
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putString("child_id", id)
            .putString("parent_selected_child_id", id)
            .apply()
    }

    private fun setChildPlatform(platform: ChildDevicePlatform, declaredCapabilities: Set<String>? = null) {
        selectedChildPlatform = platform
        selectedChildCapabilities = ChildPlatformPolicy.capabilities(platform, declaredCapabilities)
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
            .putString("parent_selected_platform", if (platform == ChildDevicePlatform.IOS) "ios" else "android")
            .apply()
    }

    /** Tanlangan bola platformasiga mos ota-ona boshqaruvlarini ko'rsatadi. */
    private fun applyChildPlatformUi() {
        val isIos = selectedChildPlatform == ChildDevicePlatform.IOS
        val hiddenForIos = listOf(
            binding.cardStatCalls.parent as View,
            (binding.btnCallHistory.parent as View).parent as View,
            binding.smsTimelineList.parent as View,
            (binding.btnSavedContacts.parent as View).parent as View,
            binding.notificationsCardHome,
            (binding.btnTrends.parent as View).parent as View,
            binding.cardStatScreenshot.parent as View
        )
        val platformViews = (hiddenForIos + listOfNotNull(
            binding.root.findViewWithTag<View>("sim_info_card"),
            binding.root.findViewWithTag<View>("ovoz_xavf_row")
        )).distinct()
        if (platformUiDefaults == null) {
            platformUiDefaults = platformViews.associateWith { it.visibility }
        }

        if (!isIos) {
            // Android tanlanganda XML'dagi asl ko'rinish holatlarini tiklaymiz.
            platformUiDefaults?.forEach { (view, visibility) -> view.visibility = visibility }
            binding.premiumBannerHome.visibility = if (isPremiumUser) View.GONE else View.VISIBLE
            binding.btnFocusMode.contentDescription = "Kontrol rejimi"
            binding.btnFocusModeIcon.contentDescription = "Kontrol rejimi"
            binding.btnFocusModeIcon.setImageResource(R.drawable.ic_shield)
            binding.btnFocusMode.visibility =
                if (selectedChildCapabilities.contains(ParentFeature.FOCUS_MODE)) View.VISIBLE else View.GONE
            binding.btnLocationHistory.visibility =
                if (selectedChildCapabilities.contains(ParentFeature.LOCATION)) View.VISIBLE else View.GONE
            return
        }

        binding.btnFocusMode.contentDescription = "Ekran vaqti"
        binding.btnFocusModeIcon.contentDescription = "Ekran vaqti"
        binding.btnFocusModeIcon.setImageResource(R.drawable.ic_clock)
        val hasScreenTime = selectedChildCapabilities.any {
            it == ParentFeature.FOCUS_MODE ||
            it == ParentFeature.SCREEN_TIME_LIMITS ||
            it == ParentFeature.SCREEN_TIME_SCHEDULES ||
            it == ParentFeature.WEBSITE_RESTRICTIONS
        }
        binding.btnFocusMode.visibility = if (hasScreenTime) View.VISIBLE else View.GONE
        binding.btnLocationHistory.visibility =
            if (selectedChildCapabilities.contains(ParentFeature.LOCATION)) View.VISIBLE else View.GONE

        platformViews.forEach { it.visibility = View.GONE }
        binding.premiumBannerHome.visibility = View.GONE
    }

    /**
     * Android ota-ona ilovasidan iPhone bolaning Screen Time siyosatini boshqarish.
     * Ilova/site tanlovi iPhone'ning o'zida FamilyActivityPicker orqali qilinadi.
     */
    private fun showIosScreenTimeDialog() {
        val code = FirebaseRepo.familyCode
        val childId = FirebaseRepo.childId
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (code.isNullOrBlank() || childId.isNullOrBlank() || uid.isNullOrBlank()) {
            Toast.makeText(this, "Oila yoki farzand ma'lumoti topilmadi", Toast.LENGTH_SHORT).show()
            return
        }

        val reference = FirebaseFirestore.getInstance()
            .collection("families").document(code)
            .collection("children").document(childId)
            .collection("screen_time_settings").document("current")

        val childReference = reference.parent.parent
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply {
            addView(content)
        }
        val authorizationLabel = TextView(this).apply {
            text = "Apple Screen Time ruxsati: tekshirilmoqda…"
            textSize = 12f
            setTextColor(resources.getColor(R.color.color_text_secondary, theme))
            setPadding(0, dp(4), 0, dp(8))
        }
        content.addView(authorizationLabel)
        val locationPermissionLabel = TextView(this).apply {
            text = "Joylashuv ruxsati: tekshirilmoqda…"
            textSize = 12f
            setTextColor(resources.getColor(R.color.color_text_secondary, theme))
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(locationPermissionLabel)
        val eventsLabel = TextView(this).apply {
            text = "Limit hodisalari: yuklanmoqda…"
            textSize = 12f
            setTextColor(resources.getColor(R.color.color_text_secondary, theme))
            setPadding(0, dp(8), 0, dp(8))
        }
        var eventsListener: com.google.firebase.firestore.ListenerRegistration? = null
        var knownEventIds: Set<String>? = null
        fun addHint(value: String) {
            content.addView(TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(resources.getColor(R.color.color_text_secondary, theme))
                setPadding(0, dp(4), 0, dp(10))
            })
        }
        fun addField(label: String, initial: String, numeric: Boolean = false): EditText {
            content.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(3))
            })
            return EditText(this).apply {
                setSingleLine(true)
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                hint = initial
                setText(initial)
                textSize = 16f
                content.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }

        val enabled = Switch(this).apply { text = "Ota-ona nazorati faol"; isChecked = true }
        content.addView(enabled)
        val dailyLimit = addField("Kunlik limit (daqiqada, 0–600; 0 = o'chirilgan)", "120", true)
        val schoolEnabled = CheckBox(this).apply { text = "Maktab jadvalini yoqish" }
        content.addView(schoolEnabled)
        val schoolStart = addField("Maktab boshlanishi (HH:mm)", "08:00")
        val schoolEnd = addField("Maktab tugashi (HH:mm)", "14:00")
        val sleepEnabled = CheckBox(this).apply { text = "Uyqu jadvalini yoqish" }
        content.addView(sleepEnabled)
        val sleepStart = addField("Uyqu boshlanishi (HH:mm)", "21:30")
        val sleepEnd = addField("Uyqu tugashi (HH:mm)", "07:00")
        val websiteBlocking = CheckBox(this).apply { text = "Tanlangan saytlarni cheklash" }
        content.addView(websiteBlocking)
        addHint("Muhim: qaysi ilova, toifa va saytlar nazorat qilinishi bolaning iPhone qurilmasida Apple Family Controls ruxsati bilan tanlanadi. Bu oynada faqat vaqt siyosati yuboriladi.")
        content.addView(eventsLabel)

        childReference?.get()?.addOnSuccessListener { childSnapshot ->
            val status = childSnapshot.getString("screenTimeAuthorizationStatus") ?: "notDetermined"
            val label = when (status) {
                "approved" -> "Apple Screen Time ruxsati: berilgan"
                "denied" -> "Apple Screen Time ruxsati: rad etilgan"
                "notDetermined" -> "Apple Screen Time ruxsati: hali so'ralmagan"
                else -> "Apple Screen Time ruxsati: noma'lum"
            }
            authorizationLabel.text = label
            val locationStatus = childSnapshot.getString("locationAuthorizationStatus") ?: "unknown"
            val sharingConsent = childSnapshot.getBoolean("locationSharingConsent") == true
            val locationText = when (locationStatus) {
                "authorizedAlways" -> "Doimiy ruxsat"
                "authorizedWhenInUse" -> "Ilova ishlaganda"
                "denied" -> "Rad etilgan"
                "restricted" -> "Cheklangan"
                "notDetermined" -> "So'ralmagan"
                else -> "Noma'lum"
            }
            locationPermissionLabel.text = if (sharingConsent) {
                "Joylashuv: $locationText"
            } else {
                "Joylashuv: bola rozilik bermagan"
            }
        }
        eventsListener = childReference?.collection("screen_time_reports")
            ?.orderBy("timestampMs", com.google.firebase.firestore.Query.Direction.DESCENDING)
            ?.limit(5)?.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    eventsLabel.text = "Limit hodisalari hozircha olinmadi"
                    return@addSnapshotListener
                }
                val documents = snapshot?.documents.orEmpty()
                val currentIds = documents.map { it.id }.toSet()
                val previousIds = knownEventIds
                if (previousIds != null && currentIds.any { it !in previousIds }) {
                    runOnUiThread {
                        Toast.makeText(this, "iPhone'da kunlik ekran vaqti limiti tugadi", Toast.LENGTH_LONG).show()
                    }
                }
                knownEventIds = currentIds
                eventsLabel.text = if (documents.isEmpty()) {
                    "Limit hodisalari: hozircha yo'q"
                } else {
                    documents.joinToString("\n", prefix = "So'nggi limit hodisalari:\n") { doc ->
                        val millis = doc.getLong("timestampMs") ?: 0L
                        val time = java.text.SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(millis))
                        "• $time"
                    }
                }
            }

        val dialog = AlertDialog.Builder(this)
            .setTitle("iPhone — Ekran vaqti")
            .setView(scroll)
            .setPositiveButton("Saqlash", null)
            .setNegativeButton("Bekor qilish", null)
            .create()

        dialog.setOnDismissListener { eventsListener?.remove() }

        dialog.setOnShowListener {
            reference.get().addOnSuccessListener { snapshot ->
                enabled.isChecked = snapshot.getBoolean("enabled") ?: true
                dailyLimit.setText((snapshot.getLong("dailyLimitMinutes") ?: 120L).toString())
                schoolEnabled.isChecked = snapshot.getBoolean("schoolEnabled") ?: false
                sleepEnabled.isChecked = snapshot.getBoolean("sleepEnabled") ?: false
                websiteBlocking.isChecked = snapshot.getBoolean("websiteBlockingEnabled") ?: false
                schoolStart.setText(snapshot.getString("schoolStart") ?: "08:00")
                schoolEnd.setText(snapshot.getString("schoolEnd") ?: "14:00")
                sleepStart.setText(snapshot.getString("sleepStart") ?: "21:30")
                sleepEnd.setText(snapshot.getString("sleepEnd") ?: "07:00")
            }.addOnFailureListener {
                Toast.makeText(this, "Joriy sozlamalar olinmadi: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val minutes = dailyLimit.text.toString().trim().toIntOrNull()
                val schoolStartValue = schoolStart.text.toString().trim()
                val schoolEndValue = schoolEnd.text.toString().trim()
                val sleepStartValue = sleepStart.text.toString().trim()
                val sleepEndValue = sleepEnd.text.toString().trim()
                if (minutes == null || minutes !in 0..600) {
                    dailyLimit.error = "0 dan 600 gacha butun son kiriting"
                    return@setOnClickListener
                }
                if ((schoolEnabled.isChecked && (!isValidClockTime(schoolStartValue) || !isValidClockTime(schoolEndValue))) ||
                    (sleepEnabled.isChecked && (!isValidClockTime(sleepStartValue) || !isValidClockTime(sleepEndValue)))) {
                    Toast.makeText(this, "Vaqtni HH:mm formatida kiriting (masalan 08:30)", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val policy = hashMapOf<String, Any>(
                    "enabled" to enabled.isChecked,
                    "dailyLimitMinutes" to minutes,
                    "schoolEnabled" to schoolEnabled.isChecked,
                    "schoolStart" to schoolStartValue,
                    "schoolEnd" to schoolEndValue,
                    "sleepEnabled" to sleepEnabled.isChecked,
                    "sleepStart" to sleepStartValue,
                    "sleepEnd" to sleepEndValue,
                    "websiteBlockingEnabled" to websiteBlocking.isChecked,
                    "updatedAtMs" to System.currentTimeMillis(),
                    "updatedBy" to "android_parent",
                    "schemaVersion" to 1
                )
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                reference.set(policy, SetOptions.merge())
                    .addOnSuccessListener {
                        Toast.makeText(this, "iPhone uchun Screen Time sozlamalari yuborildi", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }
                    .addOnFailureListener { error ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        Toast.makeText(this, "Saqlashda xato: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
            }
        }
        dialog.show()
    }

    private fun isValidClockTime(value: String): Boolean {
        val parts = value.split(":")
        if (parts.size != 2) return false
        val hour = parts[0].toIntOrNull() ?: return false
        val minute = parts[1].toIntOrNull() ?: return false
        return hour in 0..23 && minute in 0..59
    }

    private fun showIosFocusModeDialog() {
        val switch = Switch(this).apply {
            text = "iPhone'da Kontrol rejimini yoqish"
            textSize = 15f
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("iOS Kontrol rejimi")
            .setMessage("Yoqilganda iPhone'da Apple Screen Time ruxsatlari orqali ilovalar va ayrim saytlar toifalari cheklanadi. iOS'da Android kabi har bir ilovani alohida tanlash imkoniyati yo'q. Bu sozlama bolaning iPhone'ida Family Controls ruxsati berilgan bo'lsagina ishlaydi.")
            .setView(switch)
            .setPositiveButton("Saqlash", null)
            .setNegativeButton("Bekor qilish", null)
            .create()
        dialog.setOnShowListener {
            FocusModeRepository.getSettingsOnce { current ->
                switch.isChecked = current.enabled
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                FocusModeRepository.saveSettings(
                    uz.oilanazorati.parentcontrol.model.FocusModeSettings(
                        enabled = switch.isChecked,
                        allowedPackages = emptyList()
                    )
                ) { ok ->
                    Toast.makeText(
                        this,
                        if (ok) "iOS Kontrol rejimi sozlamasi yuborildi. iPhone ruxsatlari ham talab qilinadi."
                        else "Saqlab bo'lmadi — internetni tekshiring",
                        Toast.LENGTH_LONG
                    ).show()
                    if (ok) dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showChildPickerDialog(code: String, preloaded: List<Pair<String, String>>? = null) {
        val show: (List<Pair<String, String>>) -> Unit = { children ->
            if (children.isEmpty()) Toast.makeText(this, "Bu kodga hali birorta farzand ulanmagan", Toast.LENGTH_SHORT).show() else renderChildPickerDialog(code, children)
        }
        if (preloaded != null) show(preloaded) else FirebaseRepo.fetchChildren(code, show)
    }

    private fun renderChildPickerDialog(code: String, children: List<Pair<String, String>>) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        var dialog: AlertDialog? = null
        fun renderRows(list: List<Pair<String, String>>) {
            container.removeAllViews()
            list.forEach { (childId, name) ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
                val label = TextView(this).apply {
                    text = name; textSize = 17f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        FirebaseRepo.fetchLinkedChildren(code) { devices ->
                            val selected = devices.firstOrNull { it.id == childId }
                            if (selected == null) {
                                Toast.makeText(this@ParentDashboardActivity, "Farzand qurilmasi topilmadi", Toast.LENGTH_SHORT).show()
                            } else {
                                val platform = ChildDevicePlatform.from(selected.platform)
                                if (platform == ChildDevicePlatform.UNKNOWN) {
                                    Toast.makeText(this@ParentDashboardActivity, "Qurilma platformasi aniqlanmadi", Toast.LENGTH_SHORT).show()
                                } else {
                                    setChildId(selected.id)
                                    setChildPlatform(platform, selected.supportedCapabilities)
                                    applyChildPlatformUi()
                                    loadTodayStats()
                                }
                            }
                            dialog?.dismiss()
                        }
                    }
                }
                val deleteBtn = TextView(this).apply {
                    text = "🗑"; textSize = 20f; setPadding(dp(14), dp(6), dp(6), dp(6))
                    setOnClickListener {
                        AlertDialog.Builder(this@ParentDashboardActivity)
                            .setTitle("O'chirish")
                            .setMessage("\"$name\" qurilmasi shu oiladan chiqarilsinmi?\n\nQurilma bloklanmaydi — keyin oila kodi orqali qayta ulash mumkin.")
                            .setPositiveButton("Ha, chiqarish") { _, _ ->
                                FirebaseRepo.unlinkChild(code, childId) { ok ->
                                    if (ok) {
                                        Toast.makeText(this@ParentDashboardActivity, "\"$name\" oiladan chiqarildi", Toast.LENGTH_SHORT).show()
                                        if (FirebaseRepo.childId == childId) FirebaseRepo.childId = null
                                        FirebaseRepo.fetchChildren(code) { updated -> if (updated.isEmpty()) dialog?.dismiss() else renderRows(updated) }
                                    } else Toast.makeText(this@ParentDashboardActivity, "O'chirib bo'lmadi — internetni tekshiring", Toast.LENGTH_SHORT).show()
                                }
                            }.setNegativeButton("Bekor qilish", null).show()
                    }
                }
                row.addView(label); row.addView(deleteBtn); container.addView(row)
            }
        }
        renderRows(children)
        dialog = AlertDialog.Builder(this).setTitle("Qaysi farzand?").setView(container).setNegativeButton("Yopish", null).create()
        dialog.show()
    }

    /**
     * "Kontrol rejim" — dars yoki e'tibor kerak bo'lgan vaqtda ota-ona
     * yoqadi: shu vaqtda farzand telefonida faqat belgilangan (yoki
     * telefon/SMS/Sozlamalar kabi tizim uchun zarur) dasturlar ochiq
     * turadi, qolganlari ochilgan zahoti yopiladi. Yechish ham shu oynadan.
     */
    private fun showFocusModeDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val switchLabel = TextView(this).apply {
            text = "Kontrol rejimini yoqish"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val modeSwitch = Switch(this)
        switchRow.addView(switchLabel)
        switchRow.addView(modeSwitch)
        container.addView(switchRow)

        container.addView(TextView(this).apply {
            text = "Yoqilsa, farzand telefonida faqat pastda belgilangan dasturlar ochiq turadi. Qolganlari ochilgan zahoti yopiladi. Telefon, SMS, Sozlamalar va shu ilova har doim ochiq qoladi."
            textSize = 13f
            setTextColor(resources.getColor(R.color.color_text_secondary, theme))
            setPadding(0, 0, 0, dp(14))
        })

        // Tezroq bajarish: bitta-bitta belgilashning o'rnini bosadi — shu
        // belgi qo'yilsa, pastdagi ro'yxat e'tiborga olinmaydi va faqat
        // tizim uchun zarur (telefon, SMS, Sozlamalar, bosh ekran, shu ilova)
        // dasturlardan boshqasi butunlay blokланади.
        val quickBlockAllCheckbox = CheckBox(this).apply {
            text = "⚡ Tezroq: standart ilovalardan tashqari hammasini blokla"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(4))
        }
        container.addView(quickBlockAllCheckbox)
        container.addView(TextView(this).apply {
            text = "Bu belgilansa, pastdagi ro'yxatdan birortasini tanlash shart emas."
            textSize = 12f
            setTextColor(resources.getColor(R.color.color_text_secondary, theme))
            setPadding(0, 0, 0, dp(10))
        })

        val appsListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280))
        }
        scroll.addView(appsListContainer)
        container.addView(scroll)

        fun setAppsListEnabled(enabled: Boolean) {
            appsListContainer.alpha = if (enabled) 1f else 0.4f
            for (i in 0 until appsListContainer.childCount) {
                appsListContainer.getChildAt(i).isEnabled = enabled
            }
        }
        quickBlockAllCheckbox.setOnCheckedChangeListener { _, checked -> setAppsListEnabled(!checked) }

        val loadingText = TextView(this).apply {
            text = "Dasturlar ro'yxati yuklanmoqda..."
            textSize = 13f
            setPadding(0, dp(12), 0, dp(12))
        }
        appsListContainer.addView(loadingText)

        val checkboxByPackage = HashMap<String, CheckBox>()

        var dialog: AlertDialog? = null
        dialog = AlertDialog.Builder(this)
            .setTitle("Kontrol rejim")
            .setView(container)
            .setPositiveButton("Saqlash") { _, _ ->
                val allowed = if (quickBlockAllCheckbox.isChecked) emptyList()
                    else checkboxByPackage.filterValues { it.isChecked }.keys.toList()
                FocusModeRepository.saveSettings(
                    uz.oilanazorati.parentcontrol.model.FocusModeSettings(enabled = modeSwitch.isChecked, allowedPackages = allowed)
                ) { ok ->
                    Toast.makeText(
                        this,
                        if (ok) (if (modeSwitch.isChecked) "Kontrol rejim yoqildi" else "Kontrol rejim o'chirildi") else "Saqlab bo'lmadi — internetni tekshiring",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Bekor qilish", null)
            .create()
        dialog.show()

        FocusModeRepository.getSettingsOnce { current ->
            modeSwitch.isChecked = current.enabled
            // Avval saqlanganda ro'yxat bo'sh bo'lsa — demak "tezroq" yo'l
            // tanlangan edi (yoki hali hech narsa sozlanmagan); shu holatni
            // tiklaymiz, aks holda alohida belgilangan dasturlarni ko'rsatamiz.
            quickBlockAllCheckbox.isChecked = current.enabled && current.allowedPackages.isEmpty()
            FirebaseRepo.fetchRecentDistinctApps { apps ->
                appsListContainer.removeView(loadingText)
                if (apps.isEmpty()) {
                    appsListContainer.addView(TextView(this).apply {
                        text = "Hali dastur tarixi yo'q. Farzand telefonida birror ilova ishlatilgandan so'ng, u shu yerda ko'rinadi."
                        textSize = 13f
                        setPadding(0, dp(12), 0, dp(12))
                    })
                } else {
                    apps.forEach { (pkg, name) ->
                        val cb = CheckBox(this).apply {
                            text = name
                            isChecked = current.allowedPackages.contains(pkg)
                            textSize = 15f
                        }
                        checkboxByPackage[pkg] = cb
                        appsListContainer.addView(cb)
                    }
                }
                setAppsListEnabled(!quickBlockAllCheckbox.isChecked)
            }
        }
    }

    private fun loadTodayStats() {
        // Bola almashtirilganda avvalgi listenerlar albatta yopiladi.
        contactsListener?.remove(); callsListener?.remove(); smsListener?.remove()
        appUsageListener?.remove(); locationListener?.remove()
        simInfoListener?.remove()
        if (selectedChildCapabilities.contains(ParentFeature.SIM_INFO)) refreshSimInfo()

        val cal = Calendar.getInstance(); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis; val dayEnd = dayStart + 24 * 60 * 60 * 1000
        if (selectedChildCapabilities.contains(ParentFeature.CONTACTS)) {
            contactsListener = FirebaseRepo.listenSavedContacts { contacts -> val names = contacts.associate { it.kontaktHash to it.nomi }; contactSummaryAdapter.setNames(names); smsAdapter.setNames(names) }
        }
        if (selectedChildCapabilities.contains(ParentFeature.CALLS)) {
            callsListener = FirebaseRepo.listenCallsForDay(dayStart, dayEnd) { calls ->
                val incoming = calls.count { it.turi == "kiruvchi" }; val outgoing = calls.count { it.turi == "chiquvchi" }
                binding.statCallCount.text = "${calls.size} ta"; binding.statCallDetail.text = "$incoming kiruvchi\n$outgoing chiquvchi"; timelineAdapter.setCalls(calls, timeFmt)
                val stats = buildContactStats(calls); binding.statContactCount.text = "${stats.count { it.kontaktHash != "noma_lum" }} ta"; contactSummaryAdapter.setStats(stats)
            }
        }
        if (selectedChildCapabilities.contains(ParentFeature.SMS)) {
            smsListener = FirebaseRepo.listenSmsForDay(dayStart, dayEnd) { sms ->
                val sent = sms.count { it.turi == "yuborilgan" }; val received = sms.count { it.turi == "qabul_qilingan" }
                binding.statSmsCount.text = "${sms.size} ta"; binding.statSmsDetail.text = "$sent yuborilgan\n$received qabul"; smsAdapter.setData(sms)
                binding.smsTimelineList.visibility = if (sms.isEmpty()) View.GONE else View.VISIBLE; binding.smsSectionEmpty.visibility = if (sms.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        if (selectedChildCapabilities.contains(ParentFeature.APP_USAGE)) {
            appUsageListener = FirebaseRepo.listenAppUsageForDay(dayStart, dayEnd) { usage -> appUsageAdapter.setData(usage.groupBy { it.ilovaNomi }.mapValues { (_, list) -> list.sumOf { it.davomiylikSoniya } }.toList().sortedByDescending { it.second }) }
        }
        if (selectedChildCapabilities.contains(ParentFeature.LOCATION)) {
            locationListener = FirebaseRepo.listenLatestLocation { loc ->
            if (loc == null) return@listenLatestLocation
            val time = timeFmt.format(Date(loc.vaqtMs)); val minutesAgo = ((System.currentTimeMillis() - loc.vaqtMs) / 60000).coerceAtLeast(0)
            binding.locationTimeAgo.text = "$time • $minutesAgo daqiqa oldin"; binding.locationCoords.text = "${"%.5f".format(Locale.US, loc.lat)}, ${"%.5f".format(Locale.US, loc.lng)}"; binding.headerStatus.text = if (minutesAgo <= 45) "● FAOL" else "● NOFAOL"
            binding.headerStatus.setTextColor(Color.parseColor(if (minutesAgo <= 45) "#2ECC71" else "#8B96A5"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        simInfoListener?.remove()
        contactsListener?.remove(); callsListener?.remove(); smsListener?.remove()
        appUsageListener?.remove(); locationListener?.remove()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMs <= doubleBackWindowMs) { finishAndRemoveTask(); return }
        lastBackPressMs = now
        Toast.makeText(this, "Chiqish uchun yana bir marta 'Ortga' tugmasini bosing", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_CHILD_ID = "selected_child_id"
        const val EXTRA_CHILD_PLATFORM = "selected_child_platform"
    }
}
