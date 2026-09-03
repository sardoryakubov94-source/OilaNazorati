package uz.oilanazorati.parentcontrol.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import uz.oilanazorati.parentcontrol.model.ScreenshotMetadata
import uz.oilanazorati.parentcontrol.screenshot.ScreenshotRepository
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class ScreenshotHistoryActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply { addView(box) }
        setContentView(scroll)
        box.addView(TextView(this).apply {
            text = "🖼 Screenshotlar tarixi"
            textSize = 24f
        })
        load()
    }

    private fun load() {
        ScreenshotRepository.fetchHistory { list ->
            runOnUiThread {
                if (list.isEmpty()) {
                    box.addView(TextView(this).apply {
                        text = "Hali screenshot mavjud emas."
                    })
                } else {
                    list.forEach { addItem(it) }
                }
            }
        }
    }

    private fun addItem(meta: ScreenshotMetadata) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 16, 8, 16)
        }
        val title = TextView(this).apply {
            text = "${meta.appLabel} • ${meta.thresholdMinute} daqiqa\n" +
                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    .format(Date(meta.capturedAt))
            textSize = 16f
        }
        card.addView(title)

        val image = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(-1, 500)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        card.addView(image)
        box.addView(card)

        // Screenshot is stored as bytes in Firestore, so Firebase Storage is not required.
        ScreenshotRepository.loadImageBytes(meta.id) { bytes ->
            if (bytes == null || bytes.isEmpty()) return@loadImageBytes
            executor.execute {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                runOnUiThread {
                    if (bmp != null) image.setImageBitmap(bmp)
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
