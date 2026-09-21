package uz.oilanazorati.parentcontrol.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ListenerRegistration
import uz.oilanazorati.parentcontrol.model.RiskEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.text.SimpleDateFormat
import java.util.*

class RiskAlertsActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private var listener: ListenerRegistration? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
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
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Faqat muhim xavf signallari ko'rsatiladi: 18+ video yoki rasmlar bola tomonidan izlansa yoki ko'rilsa, intim suhbatlar olib borilsa, intim video yoki rasmlar yuborilsa yoki qabul qilinsa, o'z joniga qasd qilish yoki giyohvand moddalari bo'yicha bola telefonida aktiv qidiruv yoki suhbat olib borilganda — ushbu bo'limda barchasi qayd etiladi. Skrinshotlar va suhbatlarni ota-onalar shu yerda ko'rishlari mumkin."
            textSize = 12f
            setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
            setPadding(0, dp(6), 0, dp(12))
        })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        listener = FirebaseRepo.listenRiskEvents { events ->
            runOnUiThread { render(events) }
        }
    }

    private fun render(events: List<RiskEvent>) {
        list.removeAllViews()
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
            val title = if (event.severity == "HIGH") "🔴" else "🟠"
            card.addView(TextView(this).apply {
                text = title + " " + event.summary
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (event.severity == "HIGH") 0xFFE74C3C.toInt() else 0xFFF39C12.toInt())
            })
            card.addView(TextView(this).apply {
                val time = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(event.capturedAt))
                text = event.appName + " • " + time + "\nIshonchlilik: " + event.confidence + "%\nKategoriya: " + event.category
                textSize = 12f
                setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
                setPadding(0, dp(5), 0, dp(5))
            })
            if (event.mediaType.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = if (event.mediaState == "HIDDEN_SENSITIVE") {
                        "🔒 " + if (event.mediaType == "VIDEO") "Video" else "Rasm" + " yuborilgan/ko'rilgan — sezgir mazmun yashirilgan"
                    } else {
                        "📎 " + if (event.mediaType == "VIDEO") "Video" else "Rasm" + " media signali mavjud"
                    }
                    textSize = 12f
                    setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_primary))
                })
            }
            if (event.contextText.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = if (event.sensitive) "Dalil konteksti: " + event.contextText else event.contextText
                    textSize = 12f
                    setTextColor(getColor(uz.oilanazorati.parentcontrol.R.color.color_text_secondary))
                    setPadding(0, dp(5), 0, 0)
                })
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }

    override fun onDestroy() {
        listener?.remove()
        super.onDestroy()
    }
}
