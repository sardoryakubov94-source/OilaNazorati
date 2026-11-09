package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
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
            binding.premiumBannerHome.visibility = if (isPremium) android.view.View.GONE else android.view.View.VISIBLE
            // O'z-o'zini davolash: premium holati boshqa yo'l bilan (masalan
            // to'g'ridan-to'g'ri Firebase konsolidan) o'zgargan bo'lsa ham,
            // dashboard har ochilganda bola hujjatlariga aktual holat qayta
            // yozib qo'yiladi — bildirishnoma filtri doim yangi ma'lumotga tayanadi.
            FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                FirebaseRepo.syncPremiumFlagToChildren(uid, isPremium) { }
            }
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
        binding.btnFocusMode.setOnClickListener {
            if (FirebaseRepo.familyCode.isNullOrBlank() || FirebaseRepo.childId.isNullOrBlank()) {
                Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show()
            } else showFocusModeDialog()
        }
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
        binding.cardStatScreenshot.setOnClickListener { openIfChildSelected { ScreenshotHistoryActivity::class.java } }
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
                openIfChildSelected { AmbientListenActivity::class.java }
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
            openIfChildSelected { RiskAlertsActivity::class.java }
        }

        row.addView(ovozHalf); row.addView(divider); row.addView(xavfHalf)

        val simCard = content.findViewWithTag<View>("sim_info_card")
        val idx = content.indexOfChild(simCard)
        content.addView(row, if (idx >= 0) idx + 1 else content.childCount)
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

    private fun openIfChildSelected(activityClass: () -> Class<*>) {
        if (FirebaseRepo.familyCode == null || FirebaseRepo.childId == null) { Toast.makeText(this, "Avval oila kodini yuklab, farzandni tanlang", Toast.LENGTH_SHORT).show(); return }
        startActivity(Intent(this, activityClass()))
    }

    private fun ensureAuth(): Boolean {
        if (FirebaseAuth.getInstance().currentUser == null) { startActivity(Intent(this, MainActivity::class.java)); finish(); return false }
        return true
    }

    private fun loadFamily(code: String) {
        FirebaseRepo.familyCode = code; binding.headerFamilyCode.text = code
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit().putString("family_code", code).apply()
        FirebaseRepo.fetchChildren(code) { children ->
            // MUHIM: oldin allaqachon farzand tanlangan bo'lsa (child_id saqlangan
            // va u hali shu oilada mavjud), "Qaysi farzand?" oynasi HAR safar bosh
            // sahifaga qaytilganda avtomatik ochilmasligi kerak — faqat shu
            // saqlangan farzand yuklanadi. Tanlov oynasi endi faqat: (1) hali
            // birorta ham farzand tanlanmagan bo'lsa, yoki (2) tanlangan farzand
            // oiladan chiqarib yuborilgan bo'lsa, yoki (3) foydalanuvchi
            // sarlavhadagi "farzand tanlash" tugmasini bossa ochiladi.
            val savedChildId = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getString("child_id", null)
            when {
                children.isEmpty() -> Toast.makeText(this, "Bu kodga hali birorta farzand ulanmagan", Toast.LENGTH_SHORT).show()
                children.size == 1 -> { setChildId(children.first().first); loadTodayStats() }
                savedChildId != null && children.any { it.first == savedChildId } -> { setChildId(savedChildId); loadTodayStats() }
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
                    setOnClickListener { setChildId(childId); loadTodayStats(); dialog?.dismiss() }
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

        val appsListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320))
        }
        scroll.addView(appsListContainer)
        container.addView(scroll)

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
                val allowed = checkboxByPackage.filterValues { it.isChecked }.keys.toList()
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
            }
        }
    }

    private fun loadTodayStats() {
        refreshSimInfo()
        // Bola almashtirilganda shu funksiya qayta chaqiriladi — eski
        // listenerlarni o'chirmasdan turib yangisini ulasak, ikkalasi ham
        // ishlab qolib, har o'zgarishda 2x, 3x... o'qish sarflanardi.
        contactsListener?.remove(); callsListener?.remove(); smsListener?.remove()
        appUsageListener?.remove(); locationListener?.remove()

        val cal = Calendar.getInstance(); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis; val dayEnd = dayStart + 24 * 60 * 60 * 1000
        contactsListener = FirebaseRepo.listenSavedContacts { contacts -> val names = contacts.associate { it.kontaktHash to it.nomi }; contactSummaryAdapter.setNames(names); smsAdapter.setNames(names) }
        callsListener = FirebaseRepo.listenCallsForDay(dayStart, dayEnd) { calls ->
            val incoming = calls.count { it.turi == "kiruvchi" }; val outgoing = calls.count { it.turi == "chiquvchi" }
            binding.statCallCount.text = "${calls.size} ta"; binding.statCallDetail.text = "$incoming kiruvchi\n$outgoing chiquvchi"; timelineAdapter.setCalls(calls, timeFmt)
            val stats = buildContactStats(calls); binding.statContactCount.text = "${stats.count { it.kontaktHash != "noma_lum" }} ta"; contactSummaryAdapter.setStats(stats)
        }
        smsListener = FirebaseRepo.listenSmsForDay(dayStart, dayEnd) { sms ->
            val sent = sms.count { it.turi == "yuborilgan" }; val received = sms.count { it.turi == "qabul_qilingan" }
            binding.statSmsCount.text = "${sms.size} ta"; binding.statSmsDetail.text = "$sent yuborilgan\n$received qabul"; smsAdapter.setData(sms)
            binding.smsTimelineList.visibility = if (sms.isEmpty()) View.GONE else View.VISIBLE; binding.smsSectionEmpty.visibility = if (sms.isEmpty()) View.VISIBLE else View.GONE
        }
        appUsageListener = FirebaseRepo.listenAppUsageForDay(dayStart, dayEnd) { usage -> appUsageAdapter.setData(usage.groupBy { it.ilovaNomi }.mapValues { (_, list) -> list.sumOf { it.davomiylikSoniya } }.toList().sortedByDescending { it.second }) }
        locationListener = FirebaseRepo.listenLatestLocation { loc ->
            if (loc == null) return@listenLatestLocation
            val time = timeFmt.format(Date(loc.vaqtMs)); val minutesAgo = ((System.currentTimeMillis() - loc.vaqtMs) / 60000).coerceAtLeast(0)
            binding.locationTimeAgo.text = "$time • $minutesAgo daqiqa oldin"; binding.locationCoords.text = "${"%.5f".format(Locale.US, loc.lat)}, ${"%.5f".format(Locale.US, loc.lng)}"; binding.headerStatus.text = if (minutesAgo <= 45) "● FAOL" else "● NOFAOL"
            binding.headerStatus.setTextColor(Color.parseColor(if (minutesAgo <= 45) "#2ECC71" else "#8B96A5"))
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
}
