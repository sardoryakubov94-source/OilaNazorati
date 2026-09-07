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
    private var statusListener: ListenerRegistration? = null
    private var projectionStatusListener: ListenerRegistration? = null
    private var activeRequestId: String? = null

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
        val oldItems = box.findViewWithTag<LinearLayout>("history_items")
        if (oldItems != null) box.removeView(oldItems)
        val items = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "history_items"
        }
        box.addView(items)

        ScreenshotRepository.fetchHistory { list ->
            runOnUiThread {
                items.removeAllViews()
                if (list.isEmpty()) {
                    items.addView(TextView(this).apply {
                        text = "Hali screenshot mavjud emas."
                        textSize = 15f
                        setPadding(0, 24, 0, 24)
                    })
                } else {
                    list.forEach { addItem(items, it) }
                }
            }
        }
    }

    private fun addItem(parent: LinearLayout, meta: ScreenshotMetadata) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 18, 8, 18)
        }
        card.addView(TextView(this).apply {
            text = "${meta.appLabel} • ${meta.thresholdMinute} daqiqa\n" +
                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(meta.capturedAt))
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
