package uz.oilanazorati.parentcontrol.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.model.ScreenshotMetadata
import uz.oilanazorati.parentcontrol.screenshot.ScreenshotRepository
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class ScreenshotHistoryActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val staleRequestHandler = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private lateinit var requestButton: Button
    private lateinit var statusText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var tabAuto: Button
    private lateinit var tabManual: Button
    private lateinit var listContainer: LinearLayout
    private var statusListener: ListenerRegistration? = null
    private var projectionStatusListener: ListenerRegistration? = null
    private var activeRequestId: String? = null

    // "Avtomatik" (chastota chegarasidan o'tganda 3 tadan olinadigan burst
    // screenshotlar) va "Qo'lda olingan" ("Hozir screenshot olish" tugmasi
    // orqali) bir-biriga aralashib ketmasligi uchun alohida saqlanadi.
    // Avtomatik screenshotlarda thresholdMinute >= 15 (chastota), qo'lda
    // olinganlarda esa har doim 0 — bu farq ma'lumotlar bazasida allaqachon
    // mavjud edi, shunchaki UI'da ishlatilmagan edi.
    private var autoItems: List<ScreenshotMetadata> = emptyList()
    private var manualItems: List<ScreenshotMetadata> = emptyList()
    private var activeTab = "auto"
    private val expandedBursts = HashSet<String>()

    private val staleRequestWatchdog = object : Runnable {
        override fun run() {
            ScreenshotRepository.failStaleScreenshotRequest()
            staleRequestHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadHistory()
        ScreenshotRepository.failStaleScreenshotRequest()
        staleRequestHandler.post(staleRequestWatchdog)

        statusListener = ScreenshotRepository.listenScreenshotRequestStatus { requestId, status ->
            runOnUiThread { handleRequestStatus(requestId, status) }
        }
        projectionStatusListener = ScreenshotRepository.listenProjectionStatus { active, updatedAt ->
            runOnUiThread { handleProjectionStatus(active, updatedAt) }
        }
    }

    private fun handleProjectionStatus(active: Boolean, updatedAt: Long) {
        connectionStatusText.text = if (active) {
            "🟢 Ekran nazorati ulangan"
        } else {
            val whenText = if (updatedAt > 0) {
                " (" + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(updatedAt)) + ")"
            } else ""
            "🔴 Ekran nazorati ulanmagan$whenText — farzand qurilmasida ilova ochilishi kerak"
        }
    }

    private fun buildUi() {
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply { addView(box) }
        setContentView(scroll)

        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@ScreenshotHistoryActivity).apply {
                text = "🖼 Screenshotlar tarixi"
                textSize = 24f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(Button(this@ScreenshotHistoryActivity).apply {
                text = "⚙ Sozlamalar"
                setOnClickListener { startActivity(Intent(this@ScreenshotHistoryActivity, ScreenshotSettingsActivity::class.java)) }
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 18 })

        connectionStatusText = TextView(this).apply {
            text = "Ulanish holati tekshirilmoqda..."
            textSize = 13f
            setPadding(0, 0, 0, 14)
        }
        box.addView(connectionStatusText)

        requestButton = Button(this).apply {
            text = "📸 Hozir screenshot olish"
            setOnClickListener { requestScreenshot() }
        }
        box.addView(requestButton)

        statusText = TextView(this).apply {
            text = "Bolaning qurilmasidan yangi screenshot so'rash mumkin."
            textSize = 14f
            setPadding(4, 8, 4, 20)
        }
        box.addView(statusText)

        box.addView(Button(this).apply {
            text = "🔄 Tarixni yangilash"
            setOnClickListener { loadHistory() }
        })

        // Avtomatik va qo'lda olingan screenshotlar aralashib ketmasligi
        // uchun ikkita alohida bo'lim (tab) qilinadi.
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 8)
        }
        tabAuto = Button(this).apply {
            text = "🤖 Avtomatik"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 8 }
            setOnClickListener { selectTab("auto") }
        }
        tabManual = Button(this).apply {
            text = "👆 Qo'lda olingan"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { selectTab("manual") }
        }
        tabRow.addView(tabAuto)
        tabRow.addView(tabManual)
        box.addView(tabRow)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "history_items"
        }
        box.addView(listContainer)
        updateTabStyles()
    }

    private fun selectTab(tab: String) {
        if (activeTab == tab) return
        activeTab = tab
        expandedBursts.clear()
        updateTabStyles()
        renderActiveTab()
    }

    private fun updateTabStyles() {
        val activeColor = androidx.core.content.ContextCompat.getColor(this, uz.oilanazorati.parentcontrol.R.color.color_surface_alt)
        val inactiveColor = androidx.core.content.ContextCompat.getColor(this, uz.oilanazorati.parentcontrol.R.color.color_surface)
        tabAuto.setBackgroundColor(if (activeTab == "auto") activeColor else inactiveColor)
        tabManual.setBackgroundColor(if (activeTab == "manual") activeColor else inactiveColor)
    }

    private fun requestScreenshot() {
        if (activeRequestId != null) return
        requestButton.isEnabled = false
        requestButton.text = "⏳ So'rov yuborilmoqda..."
        statusText.text = "Bola qurilmasiga screenshot so'rovi yuborilmoqda..."

        ScreenshotRepository.requestScreenshot { ok, requestIdOrError ->
            runOnUiThread {
                if (ok && requestIdOrError != null) {
                    activeRequestId = requestIdOrError
                    requestButton.text = "⏳ Screenshot olinmoqda..."
                    statusText.text = "So'rov qabul qilindi. Bola qurilmasi screenshot yuborishini kuting."
                } else {
                    requestButton.isEnabled = true
                    requestButton.text = "📸 Hozir screenshot olish"
                    statusText.text = requestIdOrError ?: "So'rov yuborishda xato."
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleRequestStatus(requestId: String, status: String) {
        if (activeRequestId != null && requestId != activeRequestId) return
        when (status) {
            "requested" -> {
                activeRequestId = requestId
                requestButton.isEnabled = false
                requestButton.text = "⏳ Screenshot kutilmoqda..."
                statusText.text = "So'rov yuborildi."
            }
            "processing" -> {
                activeRequestId = requestId
                requestButton.isEnabled = false
                requestButton.text = "⏳ Screenshot olinmoqda..."
                statusText.text = "Bola qurilmasi screenshotni tayyorlamoqda..."
            }
            "completed" -> {
                activeRequestId = null
                requestButton.isEnabled = true
                requestButton.text = "📸 Hozir screenshot olish"
                statusText.text = "✅ Screenshot tayyor. Tarix yangilandi."
                loadHistory()
            }
            "failed" -> {
                activeRequestId = null
                requestButton.isEnabled = true
                requestButton.text = "📸 Hozir screenshot olish"
                statusText.text = "❌ Screenshot olish o'z vaqtida yakunlanmadi. Bola qurilmasida ekran yozish ruxsati va internetni tekshiring."
                loadHistory()
            }
        }
    }

    private fun loadHistory() {
        ScreenshotRepository.fetchHistory { list ->
            runOnUiThread {
                // thresholdMinute == 0 — "Hozir screenshot olish" orqali qo'lda
                // so'ralgan; thresholdMinute >= 15 — belgilangan chastota
                // chegarasidan o'tganda avtomatik olingan burst screenshot.
                autoItems = list.filter { it.thresholdMinute > 0 }
                manualItems = list.filter { it.thresholdMinute == 0 }
                tabAuto.text = "🤖 Avtomatik (${autoItems.size})"
                tabManual.text = "👆 Qo'lda olingan (${manualItems.size})"
                renderActiveTab()
            }
        }
    }

    private fun renderActiveTab() {
        listContainer.removeAllViews()
        if (activeTab == "manual") renderManualList() else renderAutoList()
    }

    private fun renderManualList() {
        if (manualItems.isEmpty()) {
            listContainer.addView(emptyText("Qo'lda olingan screenshot hali yo'q."))
            return
        }
        manualItems.forEach { addItem(listContainer, it, null) }
    }

    /**
     * Avtomatik screenshotlar "Joylashuv ro'yxati" bo'limiga o'xshab vaqt
     * bo'yicha ro'yxat qilib ko'rsatiladi: har bir qator — bitta "burst"
     * (bitta ilova belgilangan chastotadan o'tganda ketma-ket olingan,
     * odatda 3 tadan iborat screenshotlar to'plami). Qator bosilganda o'sha
     * burst ichidagi barcha kadrlar (har biri o'z vaqti bilan) ochiladi.
     */
    private fun renderAutoList() {
        if (autoItems.isEmpty()) {
            listContainer.addView(emptyText("Avtomatik screenshot hali yo'q."))
            return
        }
        val groups = autoItems.groupBy { Triple(it.date, it.packageName, it.thresholdMinute) }
        val ordered = groups.entries.sortedByDescending { entry -> entry.value.maxOf { it.capturedAt } }
        ordered.forEach { (key, shotsUnsorted) ->
            val shots = shotsUnsorted.sortedBy { it.capturedAt }
            val groupKey = "${key.first}_${key.second}_${key.third}"
            addBurstGroupRow(groupKey, shots)
        }
    }

    private fun emptyText(msg: String) = TextView(this).apply {
        text = msg
        textSize = 15f
        setPadding(0, 24, 0, 24)
    }

    private fun addBurstGroupRow(groupKey: String, shots: List<ScreenshotMetadata>) {
        val first = shots.first()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        val expandContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 4, 0, 8)
            visibility = if (expandedBursts.contains(groupKey)) android.view.View.VISIBLE else android.view.View.GONE
        }

        lateinit var viewLink: TextView
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val nowExpanded = expandContainer.visibility == android.view.View.VISIBLE
                if (nowExpanded) {
                    expandContainer.visibility = android.view.View.GONE
                    expandedBursts.remove(groupKey)
                    viewLink.text = "🖼 Ko'rish"
                } else {
                    expandContainer.visibility = android.view.View.VISIBLE
                    expandedBursts.add(groupKey)
                    viewLink.text = "🔼 Yopish"
                }
            }
        }
        row.addView(TextView(this).apply {
            text = timeFmt.format(Date(first.capturedAt))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@ScreenshotHistoryActivity, uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            setPadding(0, 0, 20, 0)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@ScreenshotHistoryActivity).apply {
                text = "${first.appLabel} • ${dateFmt.format(Date(first.capturedAt))}"
                textSize = 15f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@ScreenshotHistoryActivity, uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
            })
            addView(TextView(this@ScreenshotHistoryActivity).apply {
                text = "${first.thresholdMinute} daqiqadan oshdi • ${shots.size} ta kadr"
                textSize = 13f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@ScreenshotHistoryActivity, uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
            })
        })
        viewLink = TextView(this).apply {
            text = if (expandedBursts.contains(groupKey)) "🔼 Yopish" else "🖼 Ko'rish"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#2ECC71"))
        }
        row.addView(viewLink)

        listContainer.addView(row)
        listContainer.addView(expandContainer)

        shots.forEachIndexed { index, meta ->
            addItem(expandContainer, meta, "${index + 1}-kadr • ${timeFmt.format(Date(meta.capturedAt))}")
        }
    }

    private fun addItem(parent: LinearLayout, meta: ScreenshotMetadata, burstLabel: String?) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 18, 8, 18)
        }
        card.addView(TextView(this).apply {
            text = burstLabel ?: (
                "${meta.appLabel} • ${meta.thresholdMinute} daqiqa\n" +
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(meta.capturedAt))
                )
            textSize = 16f
        })

        val image = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, 500)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Screenshot — to'liq ko'rish uchun bosing"
            setOnClickListener {
                val bmp = tag as? Bitmap
                if (bmp != null) showFullScreenImage(bmp)
            }
        }
        card.addView(image)
        parent.addView(card)

        ScreenshotRepository.loadImageBytes(meta.id) { bytes ->
            if (bytes == null || bytes.isEmpty()) return@loadImageBytes
            executor.execute {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                runOnUiThread {
                    if (bmp != null && !isFinishing) {
                        image.tag = bmp
                        image.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun showFullScreenImage(bitmap: Bitmap) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        val image = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setImageBitmap(bitmap)
            contentDescription = "Screenshot to'liq ko'rinish"
        }
        root.addView(image)

        val close = Button(this).apply {
            text = "✕"
            textSize = 18f
            setOnClickListener { dialog.dismiss() }
        }
        val closeParams = FrameLayout.LayoutParams(56, 56, Gravity.TOP or Gravity.END).apply {
            topMargin = 16
            rightMargin = 16
        }
        root.addView(close, closeParams)

        image.setOnClickListener { /* yopilmaydi */ }
        root.setOnClickListener { /* faqat rasm tashqarisi ham oynani yopmaydi */ }

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setDimAmount(0f)
            }
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
    }

    override fun onDestroy() {
        staleRequestHandler.removeCallbacksAndMessages(null)
        statusListener?.remove()
        projectionStatusListener?.remove()
        executor.shutdownNow()
        super.onDestroy()
    }
}
