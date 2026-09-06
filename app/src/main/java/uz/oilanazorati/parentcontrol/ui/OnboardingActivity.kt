package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity


/**
 * Ilova birinchi marta ochilganda ko'rsatiladigan tanishtiruv slaydlari.
 * Faqat bir marta ko'rsatiladi (SharedPreferences bayrog'i orqali).
 */
class OnboardingActivity : AppCompatActivity() {

    private data class Slide(val emoji: String, val accent: String, val title: String, val body: String)

    private val slides = listOf(
        Slide("🛡️", "#FF5A75", "Xush kelibsiz — Oila Nazorati",
            "Farzandingiz qurilmasini bitta oila kodi orqali kuzatib boring: SMS, qo‘ng‘iroqlar, joylashuv, ilovalar va bildirishnomalar — barchasi ota-ona panelida."),
        Slide("✉️", "#4D9DFF", "SMS nazorati",
            "Kiruvchi va chiquvchi xabarlarni, raqamni va vaqtni kuzating. To‘liq matnni o‘qish Premium foydalanuvchilar uchun ochiq."),
        Slide("☎️", "#39C98C", "Qo‘ng‘iroqlar tarixi",
            "Kiruvchi, chiquvchi va javobsiz qo‘ng‘iroqlar, davomiyligi va vaqti bilan ro‘yxatga olinadi."),
        Slide("📍", "#F2C94C", "Joylashuv va Jonli kuzatish",
            "Farzand qurilmasining so‘nggi joylashuvini xaritada ko‘ring, tarixni kuzating yoki «Jonli kuzatish»ni yoqib real vaqtda harakatni kuzating."),
        Slide("▣", "#9B6BFF", "Ilovalar, Screenshot va Bildirishnoma",
            "Eng ko‘p ishlatiladigan ilovalar statistikasi, vaqti-vaqti bilan olinadigan ekran screenshotlari va ijtimoiy tarmoq bildirishnomalarini kuzating."),
        Slide("⭐", "#F2C94C", "Premium bilan to‘liq imkoniyat",
            "Bir martalik to‘lov orqali saqlanmagan raqamlarni, to‘liq SMS matnini va bir nechta oila kodini boshqarish imkoniyatiga ega bo‘ling.")
    )

    private var index = 0
    private lateinit var iconBox: FrameLayout
    private lateinit var iconText: TextView
    private lateinit var titleText: TextView
    private lateinit var bodyText: TextView
    private lateinit var dotsRow: LinearLayout
    private lateinit var nextBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bg = Color.parseColor("#0B1220")
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(28))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val skip = TextView(this).apply {
            text = "Otkazib yuborish"
            setTextColor(Color.parseColor("#8993A2"))
            textSize = 14f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finishOnboarding() }
        }
        topRow.addView(skip)
        content.addView(topRow)

        val spacerTop = View(this)
        content.addView(spacerTop, LinearLayout.LayoutParams(0, dp(80)))

        iconBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        iconText = TextView(this).apply {
            textSize = 40f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        iconBox.addView(iconText)
        content.addView(iconBox)

        val spacerMid = View(this)
        content.addView(spacerMid, LinearLayout.LayoutParams(0, dp(28)))

        titleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 25f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        content.addView(titleText)

        val spacerSmall = View(this)
        content.addView(spacerSmall, LinearLayout.LayoutParams(0, dp(14)))

        bodyText = TextView(this).apply {
            setTextColor(Color.parseColor("#AEB8C7"))
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        content.addView(bodyText)

        val spacerBottom = View(this)
        content.addView(spacerBottom, LinearLayout.LayoutParams(0, 0, 1f))

        dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        content.addView(dotsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)))

        nextBtn = TextView(this).apply {
            textColor(Color.BLACK)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
        }
        content.addView(nextBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        render()
        nextBtn.setOnClickListener {
            if (index < slides.size - 1) {
                index++
                render()
            } else {
                finishOnboarding()
            }
        }
    }

    private fun TextView.textColor(color: Int) = setTextColor(color)

    private fun render() {
        val s = slides[index]
        val accent = Color.parseColor(s.accent)

        val boxBg = GradientDrawable().apply {
            cornerRadius = 24f * resources.displayMetrics.density
            setColor(Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke((1.5f * resources.displayMetrics.density).toInt(), accent)
        }
        iconBox.background = boxBg
        iconText.text = s.emoji
        titleText.text = s.title
        bodyText.text = s.body

        val nextBg = GradientDrawable().apply {
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(accent)
        }
        nextBtn.background = nextBg
        nextBtn.text = if (index == slides.size - 1) "Boshlash →" else "Keyingi →"

        dotsRow.removeAllViews()
        val density = resources.displayMetrics.density
        slides.forEachIndexed { i, _ ->
            val dot = View(this)
            val size = (7 * density).toInt()
            val params = LinearLayout.LayoutParams(size, size).apply {
                marginStart = (4 * density).toInt()
                marginEnd = (4 * density).toInt()
            }
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == index) accent else Color.parseColor("#2B3748"))
            }
            dot.background = drawable
            dotsRow.addView(dot, params)
        }
    }

    private fun finishOnboarding() {
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_shown", true).apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onBackPressed() {
        if (index > 0) {
            index--
            render()
        } else {
            super.onBackPressed()
        }
    }
}
