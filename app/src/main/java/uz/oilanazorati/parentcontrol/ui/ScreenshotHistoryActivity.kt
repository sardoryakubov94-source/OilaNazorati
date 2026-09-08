package uz.oilanazorati.parentcontrol.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.model.ScreenshotMetadata
import uz.oilanazorati.parentcontrol.screenshot.ScreenshotRepository
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ScreenshotHistoryActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val staleRequestHandler = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private lateinit var requestButton: Button
    private lateinit var statusText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCountText: TextView
    private lateinit var deleteSelectedButton: Button
    private lateinit var tabAuto: Button
    private lateinit var tabManual: Button
    private lateinit var listContainer: LinearLayout
    private var statusListener: ListenerRegistration? = null
    private var projectionStatusListener: ListenerRegistration? = null
    private var activeRequestId: String? = null
    private var autoItems: List<ScreenshotMetadata> = emptyList()
    private var manualItems: List<ScreenshotMetadata> = emptyList()
    private var activeTab = "auto"
    private val expandedBursts = HashSet<String>()
    private val selectedIds = LinkedHashSet<String>()

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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun color(id: Int): Int = androidx.core.content.ContextCompat.getColor(this, id)

    private fun roundedBackground(fill: Int, radius: Int = 16, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun styleButton(button: Button, icon: Int? = null) {
        button.textSize = 14f
        button.isAllCaps = false
        button.minHeight = dp(44)
        button.setPadding(dp(12), dp(7), dp(12), dp(7))
        button.background = roundedBackground(color(uz.oilanazorati.parentcontrol.R.color.color_surface_alt), 14)
        button.setTextColor(color(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
        if (icon != null) {
            button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0)
            button.compoundDrawablePadding = dp(7)
        }
    }

    private fun iconButton(textValue: String, icon: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = textValue
        styleButton(this, icon)
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
            setBackgroundColor(color(uz.oilanazorati.parentcontrol.R.color.color_bg))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(color(uz.oilanazorati.parentcontrol.R.color.color_bg))
            addView(box)
        }
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), 0, dp(8))
        }
        header.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_gallery)
            setColorFilter(color(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(9) }
        })
        header.addView(TextView(this).apply {
            text = "Screenshotlar tarixi"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(iconButton("Sozlamalar", android.R.drawable.ic_menu_preferences) {
            startActivity(Intent(this@ScreenshotHistoryActivity, ScreenshotSettingsActivity::class.java))
        }.apply { layoutParams = LinearLayout.LayoutParams(-2, dp(44)) })
        box.addView(header)

        connectionStatusText = TextView(this).apply {
            text = "Ulanish holati tekshirilmoqda..."
            textSize = 12f
            setTextColor(color(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
            setPadding(dp(4), 0, dp(4), dp(9))
        }
        box.addView(connectionStatusText)

        requestButton = iconButton("Hozir screenshot olish", android.R.drawable.ic_menu_camera) { requestScreenshot() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(7) }
        }
        box.addView(requestButton)

        statusText = TextView(this).apply {
            text = "Bolaning qurilmasidan yangi screenshot so'rash mumkin."
            textSize = 12f
            setTextColor(color(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
            setPadding(dp(4), dp(1), dp(4), dp(10))
        }
        box.addView(statusText)

        box.addView(iconButton("Tarixni yangilash", android.R.drawable.ic_popup_sync) { loadHistory() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(10) }
        })

        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = roundedBackground(color(uz.oilanazorati.parentcontrol.R.color.color_surface_alt), 14)
            setPadding(dp(10), dp(5), dp(5), dp(5))
        }
        selectionCountText = TextView(this).apply {
            text = "0 ta tanlangan"
            textSize = 13f
            setTextColor(color(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        selectionBar.addView(selectionCountText)
        deleteSelectedButton = iconButton("O'chirish", android.R.drawable.ic_menu_delete) { confirmDeleteSelected() }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(42))
        }
        selectionBar.addView(deleteSelectedButton)
        box.addView(selectionBar, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(9) })

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabAuto = iconButton("Avtomatik", android.R.drawable.ic_menu_recent_history) { selectTab("auto") }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) }
        }
        tabManual = iconButton("Qo'lda olingan", android.R.drawable.ic_menu_edit) { selectTab("manual") }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        }
        tabRow.addView(tabAuto)
        tabRow.addView(tabManual)
        box.addView(tabRow, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(10) })

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(listContainer)
        updateTabStyles()
        updateSelectionBar()
    }

    private fun handleProjectionStatus(active: Boolean, updatedAt: Long) {
        connectionStatusText.text = if (active) {
            "Ekran nazorati ulangan"
        } else {
            val whenText = if (updatedAt > 0) " (" + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(updatedAt)) + ")" else ""
            "Ekran nazorati ulanmagan$whenText — farzand qurilmasida ilova ochilishi kerak"
        }
    }

    private fun selectTab(tab: String) {
        if (activeTab == tab) return
        activeTab = tab
        expandedBursts.clear()
        updateTabStyles()
        renderActiveTab()
    }

    private fun updateTabStyles() {
        val active = color(uz.oilanazorati.parentcontrol.R.color.color_surface_alt)
        val inactive = color(uz.oilanazorati.parentcontrol.R.color.color_surface)
        val border = color(uz.oilanazorati.parentcontrol.R.color.color_border)
        tabAuto.background = roundedBackground(if (activeTab == "auto") active else inactive, 14, border)
        tabManual.background = roundedBackground(if (activeTab == "manual") active else inactive, 14, border)
    }

    private fun requestScreenshot() {
        if (activeRequestId != null) return
        requestButton.isEnabled = false
        requestButton.text = "So'rov yuborilmoqda..."
        statusText.text = "Bola qurilmasiga screenshot so'rovi yuborilmoqda..."
        ScreenshotRepository.requestScreenshot { ok, requestIdOrError ->
            runOnUiThread {
                if (ok && requestIdOrError != null) {
                    activeRequestId = requestIdOrError
                    requestButton.text = "Screenshot olinmoqda..."
                    statusText.text = "So'rov qabul qilindi. Bola qurilmasi screenshot yuborishini kuting."
                } else {
                    requestButton.isEnabled = true
                    requestButton.text = "Hozir screenshot olish"
                    statusText.text = requestIdOrError ?: "So'rov yuborishda xato."
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleRequestStatus(requestId: String, status: String) {
        if (activeRequestId != null && requestId != activeRequestId) return
        when (status) {
            "requested" -> { activeRequestId = requestId; requestButton.isEnabled = false; requestButton.text = "Screenshot kutilmoqda..."; statusText.text = "So'rov yuborildi." }
            "processing" -> { activeRequestId = requestId; requestButton.isEnabled = false; requestButton.text = "Screenshot olinmoqda..."; statusText.text = "Bola qurilmasi screenshotni tayyorlamoqda..." }
            "completed" -> { activeRequestId = null; requestButton.isEnabled = true; requestButton.text = "Hozir screenshot olish"; statusText.text = "Screenshot tayyor. Tarix yangilandi."; loadHistory() }
            "failed" -> { activeRequestId = null; requestButton.isEnabled = true; requestButton.text = "Hozir screenshot olish"; statusText.text = "Screenshot olish o'z vaqtida yakunlanmadi. Bola qurilmasida ekran ruxsati va internetni tekshiring."; loadHistory() }
        }
    }

    private fun loadHistory() {
        ScreenshotRepository.fetchHistory { list ->
            runOnUiThread {
                autoItems = list.filter { it.thresholdMinute > 0 }
                manualItems = list.filter { it.thresholdMinute == 0 }
                val validIds = (autoItems + manualItems).map { it.id }.toHashSet()
                selectedIds.retainAll(validIds)
                tabAuto.text = "Avtomatik (${autoItems.size})"
                tabManual.text = "Qo'lda olingan (${manualItems.size})"
                updateSelectionBar()
                renderActiveTab()
            }
        }
    }

    private fun renderActiveTab() {
        listContainer.removeAllViews()
        if (activeTab == "manual") renderManualList() else renderAutoList()
    }

    private fun emptyText(msg: String) = TextView(this).apply {
        text = msg
        textSize = 13f
        setTextColor(color(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(32), dp(16), dp(32))
    }

    private fun cardBackground(selected: Boolean): GradientDrawable = roundedBackground(
        color(uz.oilanazorati.parentcontrol.R.color.color_surface),
        16,
        if (selected) color(uz.oilanazorati.parentcontrol.R.color.color_surface_alt) else color(uz.oilanazorati.parentcontrol.R.color.color_border)
    )

    private fun renderManualList() {
        if (manualItems.isEmpty()) {
            listContainer.addView(emptyText("Qo'lda olingan screenshot hali yo'q."))
            return
        }
        manualItems.forEach { addItem(listContainer, it, null) }
    }

    private fun renderAutoList() {
        if (autoItems.isEmpty()) {
            listContainer.addView(emptyText("Avtomatik screenshot hali yo'q."))
            return
        }
        val groups = autoItems.groupBy { Triple(it.date, it.packageName, it.thresholdMinute) }
        val ordered = groups.entries.sortedByDescending { entry -> entry.value.maxOf { it.capturedAt } }
        ordered.forEach { (key, shotsUnsorted) ->
            val shots = shotsUnsorted.sortedBy { it.capturedAt }
            addBurstGroupRow("${key.first}_${key.second}_${key.third}", shots)
        }
    }

    private fun addBurstGroupRow(groupKey: String, shots: List<ScreenshotMetadata>) {
        val first = shots.first()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val selectedInGroup = shots.count { selectedIds.contains(it.id) }
        val expanded = expandedBursts.contains(groupKey)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBackground(selectedInGroup > 0)
            setPadding(dp(12), dp(9), dp(8), dp(9))
            setOnClickListener {
                if (expandedBursts.contains(groupKey)) expandedBursts.remove(groupKey) else expandedBursts.add(groupKey)
                renderActiveTab()
            }
        }
        row.addView(TextView(this).apply {
            text = timeFmt.format(Date(first.capturedAt))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(10) }
        })
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        details.addView(TextView(this).apply {
            text = first.appLabel
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
        })
        details.addView(TextView(this).apply {
            text = "${dateFmt.format(Date(first.capturedAt))} • ${first.thresholdMinute} daqiqa • ${shots.size} kadr"
            textSize = 11f
            setTextColor(color(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(details)
        row.addView(ImageView(this).apply {
            setImageResource(if (expanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
            setColorFilter(color(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
            contentDescription = if (expanded) "Yopish" else "Ochish"
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        })
        listContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })

        if (expanded) {
            val expandContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(2), 0, dp(4))
            }
            shots.forEachIndexed { index, meta -> addItem(expandContainer, meta, "${index + 1}-kadr • ${timeFmt.format(Date(meta.capturedAt))}") }
            listContainer.addView(expandContainer)
        }
    }

    private fun addItem(parent: LinearLayout, meta: ScreenshotMetadata, burstLabel: String?) {
        val selected = selectedIds.contains(meta.id)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(selected)
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = burstLabel ?: "${meta.appLabel} • ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(meta.capturedAt))}"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(color(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val check = CheckBox(this).apply {
            isChecked = selected
            buttonTintList = android.content.res.ColorStateList.valueOf(color(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
            contentDescription = "Screenshotni tanlash"
            setOnClickListener {
                if (isChecked) selectedIds.add(meta.id) else selectedIds.remove(meta.id)
                updateSelectionBar()
                card.background = cardBackground(isChecked)
            }
        }
        top.addView(check)
        card.addView(top)

        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(7) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Screenshotni to'liq ko'rish uchun bosing"
            setOnClickListener {
                val bmp = tag as? Bitmap
                if (bmp != null) showFullScreenImage(bmp)
            }
        }
        card.addView(image)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

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

    private fun updateSelectionBar() {
        val count = selectedIds.size
        selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        selectionCountText.text = "$count ta tanlangan"
        deleteSelectedButton.isEnabled = count > 0
    }

    private fun confirmDeleteSelected() {
        val ids = selectedIds.toList()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Screenshotlarni o'chirish")
            .setMessage("${ids.size} ta tanlangan screenshot o'chiriladi. Davom etilsinmi?")
            .setNegativeButton("Bekor qilish", null)
            .setPositiveButton("O'chirish") { _, _ -> deleteSelected(ids) }
            .show()
    }

    private fun deleteSelected(ids: List<String>) {
        deleteSelectedButton.isEnabled = false
        statusText.text = "${ids.size} ta screenshot o'chirilmoqda..."
        var completed = 0
        var failed = 0
        ids.forEach { id ->
            ScreenshotRepository.deleteScreenshot(id) { ok ->
                runOnUiThread {
                    if (ok) completed++ else failed++
                    if (completed + failed == ids.size) {
                        ids.forEach { selectedIds.remove(it) }
                        statusText.text = if (failed == 0) "$completed ta screenshot o'chirildi." else "$completed ta o'chirildi, $failed tasida xatolik."
                        loadHistory()
                    }
                }
            }
        }
    }

    private fun showFullScreenImage(bitmap: Bitmap) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val image = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setImageBitmap(bitmap)
            contentDescription = "Screenshot to'liq ko'rinish"
        }
        root.addView(image)
        val close = Button(this).apply {
            text = "Yopish"
            textSize = 14f
            isAllCaps = false
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(90), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12)
            rightMargin = dp(12)
        })
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
