package uz.oilanazorati.parentcontrol.ui

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.model.RiskEvent
import uz.oilanazorati.parentcontrol.risk.RiskAnalysisEngine
import uz.oilanazorati.parentcontrol.model.ScreenshotMetadata
import uz.oilanazorati.parentcontrol.model.effectiveKind
import uz.oilanazorati.parentcontrol.screenshot.ScreenshotRepository
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class RiskAlertsActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var tabAlerts: Button
    private lateinit var tabShots: Button
    private var listener: ListenerRegistration? = null
    private val executor = Executors.newSingleThreadExecutor()

    // Ikki bo'lim: "alerts" — Ogohlantirish (rangli signallar), "shots" — xavf bo'yicha olingan screenshotlar
    private var activeTab = "alerts"
    private var lastEvents: List<RiskEvent> = emptyList()
    private var riskShots: List<ScreenshotMetadata> = emptyList()
    private var shotsLoadedAt = 0L
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), 0)
            setBackgroundColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(android.widget.FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) }
            background = getDrawable(uz.oilanazorati.parentcontrol.R.drawable.bg_badge_orange)
            addView(ImageView(this@RiskAlertsActivity).apply {
                setImageResource(uz.oilanazorati.parentcontrol.R.drawable.ic_shield)
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(16), dp(16)).apply { gravity = Gravity.CENTER }
                setColorFilter(Color.WHITE)
            })
        })
        header.addView(TextView(this).apply {
            text = "Xavfsizlik signallari"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(Button(this).apply {
            text = "Orqaga"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        // Sarlavha (Xavfsizlik signallari + Orqaga) doim yuqorida turadi.
        root.addView(header)

        // Izoh matni ro'yxat bilan birga yuqoriga siljiydi va sarlavha ostida yo'qoladi;
        // "Ogohlantirish" / "Screenshot" bo'limlari esa sarlavha tagida qotib qoladi.
        // Shunda ro'yxatga ko'proq ekran joyi qoladi.
        val description = TextView(this).apply {
            text = "Faqat muhim xavf signallari ko'rsatiladi: 18+ video yoki rasmlar bola tomonidan izlansa yoki ko'rilsa, intim suhbatlar olib borilsa, intim video yoki rasmlar yuborilsa yoki qabul qilinsa, 1XBET va boshqa qimor o'yinlari ochilsa yoki qidirilsa, o'z joniga qasd qilish yoki giyohvand moddalari bo'yicha bola telefonida aktiv qidiruv yoki suhbat olib borilganda — ushbu bo'limda barchasi qayd etiladi. Skrinshotlar va suhbatlarni ota-onalar shu yerda ko'rishlari mumkin."
            textSize = 12f
            setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
            setPadding(0, dp(6), 0, dp(12))
        }
        val tabsHeight = dp(58)
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
            setBackgroundColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_bg))
        }
        tabAlerts = tabButton("⚠️ Ogohlantirish") { selectTab("alerts") }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
        }
        tabShots = tabButton("📷 Screenshot") { selectTab("shots") }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        tabRow.addView(tabAlerts)
        tabRow.addView(tabShots)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(description)
        // Bo'limlar tugmalari uchun joy (haqiqiy tugmalar ustida suzib turadi)
        val tabSpacer = View(this)
        content.addView(tabSpacer, LinearLayout.LayoutParams(-1, tabsHeight))
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(20))
        }
        content.addView(list)
        scroll.addView(content)

        val frame = FrameLayout(this)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        frame.addView(tabRow, FrameLayout.LayoutParams(-1, tabsHeight))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val stickTabs = { tabRow.translationY = maxOf(0, tabSpacer.top - scroll.scrollY).toFloat() }
        scroll.viewTreeObserver.addOnScrollChangedListener { stickTabs() }
        tabSpacer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> stickTabs() }
        setContentView(root)
        updateTabStyles()
        listener = FirebaseRepo.listenRiskEvents { events ->
            runOnUiThread {
                lastEvents = events
                if (activeTab == "alerts") render(events)
            }
        }
        loadRiskShots()
    }

    private fun rounded(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun tabButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        maxLines = 1
        setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
        setOnClickListener { onClick() }
    }

    private fun updateTabStyles() {
        val active = getColor(uz.oilanazorati.parentcontrol.R.color.color_surface_alt)
        val inactive = getColor(uz.oilanazorati.parentcontrol.R.color.color_surface)
        val border = getColor(uz.oilanazorati.parentcontrol.R.color.color_border)
        tabAlerts.background = rounded(if (activeTab == "alerts") active else inactive, border)
        tabShots.background = rounded(if (activeTab == "shots") active else inactive, border)
    }

    private fun selectTab(tab: String) {
        if (activeTab == tab) return
        activeTab = tab
        updateTabStyles()
        if (tab == "alerts") render(lastEvents) else {
            renderShots()
            if (System.currentTimeMillis() - shotsLoadedAt > 20_000L) loadRiskShots()
        }
    }

    /** Xavf signali bo'yicha olingan screenshotlar (qo'lda/avtomatik screenshotlardan alohida). */
    private fun loadRiskShots() {
        ScreenshotRepository.fetchHistory { all ->
            runOnUiThread {
                shotsLoadedAt = System.currentTimeMillis()
                riskShots = all.filter { it.effectiveKind() == "risk" }.sortedByDescending { it.capturedAt }
                tabShots.text = "📷 Screenshot (${riskShots.size})"
                if (activeTab == "shots") renderShots()
            }
        }
    }

    private fun emptyText(msg: String) = TextView(this).apply {
        text = msg
        textSize = 14f
        setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(40), dp(16), dp(40))
    }

    private fun renderShots() {
        list.removeAllViews()
        if (riskShots.isEmpty()) {
            list.addView(emptyText("Xavf signali bo'yicha screenshot hali yo'q."))
            return
        }
        list.addView(TextView(this).apply {
            text = "Screenshotni o'chirish uchun uni bosib turing."
            textSize = 11f
            setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
            setPadding(dp(2), 0, 0, dp(8))
        })
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        riskShots.forEach { meta ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(
                    getColor(uz.oilanazorati.parentcontrol.R.color.color_surface),
                    getColor(uz.oilanazorati.parentcontrol.R.color.color_border)
                )
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnLongClickListener { confirmDelete(meta); true }
            }
            card.addView(TextView(this).apply {
                text = "🟠 ${meta.appLabel} • ${fmt.format(Date(meta.capturedAt))}"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            })
            if (meta.riskCategory.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "Kategoriya: ${meta.riskCategory}"
                    textSize = 12f
                    setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
                    setPadding(0, dp(3), 0, dp(2))
                })
            }
            if (meta.sensitiveEvidence) {
                card.addView(TextView(this).apply {
                    text = "🔒 Sezgir xavf dalili yashirilgan — media mazmuni ko'rsatilmaydi"
                    textSize = 12f
                    setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
                    setPadding(0, dp(8), 0, dp(4))
                })
            } else {
                val image = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(8) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setOnClickListener { (tag as? Bitmap)?.let { showFullScreenImage(it) } }
                    setOnLongClickListener { confirmDelete(meta); true }
                }
                card.addView(image)
                ScreenshotRepository.loadImageBytes(meta.id) { bytes ->
                    if (bytes == null || bytes.isEmpty()) return@loadImageBytes
                    executor.execute {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        runOnUiThread {
                            if (bmp != null && !isFinishing) { image.tag = bmp; image.setImageBitmap(bmp) }
                        }
                    }
                }
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    private fun confirmDelete(meta: ScreenshotMetadata) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Screenshotni o'chirish")
            .setMessage("Bu screenshot o'chiriladi. Davom etilsinmi?")
            .setNegativeButton("Bekor qilish", null)
            .setPositiveButton("O'chirish") { _, _ ->
                ScreenshotRepository.deleteScreenshot(meta.id) { ok ->
                    runOnUiThread {
                        if (ok) loadRiskShots() else Toast.makeText(this, "O'chirib bo'lmadi", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showFullScreenImage(bitmap: Bitmap) {
        val dialog = Dialog(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
        })
        root.addView(Button(this).apply {
            text = "Yopish"
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(dp(90), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12); rightMargin = dp(12)
        })
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
    }

    private fun render(allEvents: List<RiskEvent>) {
        list.removeAllViews()
        val events = allEvents.filterNot { RiskAnalysisEngine.isLikelyFalseAlarm(it.severity, it.summary, it.packageName) }
        if (events.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Hozircha jiddiy xavf signali aniqlanmadi."
                textSize = 14f
                setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(40), dp(16), dp(40))
            })
            return
        }
        events.sortedByDescending { it.capturedAt }.forEach { event ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_surface))
                elevation = dp(2).toFloat()
            }
            val dot = if (event.severity == "HIGH") "🔴" else "🟠"
            // Eski yozuvlarda sarlavha oxirida "(aniqlangan so'z: ...)" va juda uzun matn bor —
            // ularni ham qisqa va tushunarli ko'rinishga keltiramiz.
            val shortTitle = event.summary.substringBefore(" (aniqlangan so'z:").trim()
            val term = Regex("aniqlangan so'z: (.*)\\)").find(event.summary)?.groupValues?.get(1)?.trim().orEmpty()
            card.addView(TextView(this).apply {
                text = "$dot $shortTitle"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (event.severity == "HIGH") 0xFFE74C3C.toInt() else 0xFFF39C12.toInt())
            })
            card.addView(TextView(this).apply {
                val time = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(event.capturedAt))
                text = event.appName + " • " + time + (if (term.isNotBlank()) "\nAniqlangan so'z: $term" else "")
                textSize = 12f
                setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
                setPadding(0, dp(5), 0, dp(5))
            })
            if (event.mediaType.isNotBlank()) {
                card.addView(TextView(this).apply {
                    val mediaLabel = if (event.mediaType == "VIDEO") "Video" else "Rasm"
                    text = if (event.mediaState == "HIDDEN_SENSITIVE") {
                        "🔒 $mediaLabel yuborilgan/ko'rilgan — sezgir mazmun yashirilgan"
                    } else {
                        "📎 $mediaLabel media signali mavjud"
                    }
                    textSize = 12f
                    setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
                })
            }
            val fullText = event.contextText
                .replace(Regex("\\b(?:android|androidx)\\.[\\w.$]+"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (fullText.isNotBlank()) {
                val shortText = RiskAnalysisEngine.snippet(fullText, listOf(term).filter { it.isNotBlank() })
                val expandable = shortText != fullText
                var expanded = false
                val ctx = TextView(this).apply {
                    text = "Matn: $shortText"
                    textSize = 12f
                    setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
                    setPadding(0, dp(5), 0, 0)
                }
                card.addView(ctx)
                if (expandable) {
                    val toggle = TextView(this).apply {
                        text = "▼ To'liq matnni ko'rish"
                        textSize = 11f
                        setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
                        setPadding(0, dp(6), 0, 0)
                    }
                    card.addView(toggle)
                    val flip = View.OnClickListener {
                        expanded = !expanded
                        ctx.text = "Matn: " + (if (expanded) fullText else shortText)
                        toggle.text = if (expanded) "▲ Yig'ish" else "▼ To'liq matnni ko'rish"
                    }
                    card.setOnClickListener(flip)
                }
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        listener?.remove()
        super.onDestroy()
    }
}
