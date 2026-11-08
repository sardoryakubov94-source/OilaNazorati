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
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * "Qanday ulanadi?" — ota-ona va bolani ulashning 4 qadamli, oddiy tilda
 * yozilgan yo'riqnomasi. Ilova birinchi marta ochilganda (feature-tur
 * slaydlaridan keyin) avtomatik ko'rsatiladi, va istalgan vaqt rol
 * tanlash ekranidagi "❓ Qanday ulanadi?" havolasidan qayta ochiladi.
 *
 * FIRST-launch zanjirida ("from_onboarding" = true) "Tushunarli, boshlayman"
 * tugmasi MainActivity'ga o'tkazadi. Yordam havolasidan ochilganda esa
 * shunchaki finish() qiladi — orqaga, MainActivity allaqachon ostida turadi.
 */
class SetupGuideActivity : AppCompatActivity() {

    private data class Step(val number: Int, val accent: String, val emoji: String, val title: String, val body: String)

    private val steps = listOf(
        Step(
            1, "#4D9DFF", "👨\u200d👩\u200d👧",
            "Ota-ona: Oila kodini yarating",
            "Bu qadamni O'ZINGIZNING telefoningizda — ya'ni kuzatadigan telefonda qilasiz.\n\n" +
                "\"Ota-ona panelini ochish\" tugmasini bosing va Google hisobingiz bilan kiring. " +
                "Ilova sizga maxsus Oila kodi beradi.\n\n" +
                "📌 Bu kod sizning Gmail hisobingizga bog'langan va har doim boshqacha (unikal) bo'lib yaratiladi. " +
                "Undan faqat shu Gmail'ning egasi, ya'ni siz, foydalana olasiz — boshqa hech kim emas."
        ),
        Step(
            2, "#39C98C", "📱",
            "Bola: ilovani o'rnating va bog'laning",
            "Kuzatmoqchi bo'lgan farzandingizning telefoniga shu ilovani o'rnating.\n\n" +
                "Ochilgach \"Bu farzandimning telefoni\" tugmasini bosing. Keyin ochilgan oynaga " +
                "farzand ismini va 1-qadamda olgan Oila kodini kiriting, so'ng \"Bog'lash\" tugmasini bosing."
        ),
        Step(
            3, "#F2C94C", "✅",
            "Ruxsatlarni birma-bir yoqing",
            "Bog'langandan so'ng \"OilaNazorati o'rnatish yordamchisi\" ochiladi.\n\n" +
                "Yordamchi ko'rsatgan har bir ruxsatni tartib bilan yoqib chiqing. Hammasi tayyor bo'lgach, " +
                "pastdagi \"✅ Sozlashni yakunlash va boshlash\" tugmasini bosing.\n\n" +
                "⚠️ Bu eng muhim qadam — biror ruxsat berilmasa, o'sha qism (masalan, joylashuv yoki SMS) kuzatilmaydi."
        ),
        Step(
            4, "#9B6BFF", "👆",
            "Ota-ona panelida farzandni tanlang",
            "Endi o'z telefoningizga, Ota-ona paneliga qayting.\n\n" +
                "Yuqori o'ng burchakdagi kichkina 👤 belgisini bosing, chiqqan ro'yxatdan farzandingizning " +
                "ismini toping va unga bosing.\n\n" +
                "Shu bilan tugadi — bugungi qo'ng'iroqlar, SMS va joylashuv panelda ko'rina boshlaydi."
        )
    )

    private var index = 0
    private var fromOnboarding = false

    private lateinit var iconBox: FrameLayout
    private lateinit var iconText: TextView
    private lateinit var numberBadge: TextView
    private lateinit var titleText: TextView
    private lateinit var bodyText: TextView
    private lateinit var dotsRow: LinearLayout
    private lateinit var nextBtn: TextView
    private lateinit var backBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fromOnboarding = intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false)

        val bg = Color.parseColor("#0B1220")
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(28))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitle = TextView(this).apply {
            text = "Qanday ulanadi?"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val close = TextView(this).apply {
            text = "Yopish ✕"
            setTextColor(Color.parseColor("#8993A2"))
            textSize = 14f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finishGuide() }
        }
        topRow.addView(headerTitle)
        topRow.addView(close)
        content.addView(topRow)

        val spacerTop = View(this)
        content.addView(spacerTop, LinearLayout.LayoutParams(0, dp(36)))

        val iconWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        iconBox = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(96), dp(96))
        }
        iconText = TextView(this).apply {
            textSize = 38f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        iconBox.addView(iconText)
        iconWrap.addView(iconBox)
        numberBadge = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
        }
        iconWrap.addView(numberBadge)
        content.addView(iconWrap)

        val spacerMid = View(this)
        content.addView(spacerMid, LinearLayout.LayoutParams(0, dp(24)))

        titleText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        content.addView(titleText)

        val spacerSmall = View(this)
        content.addView(spacerSmall, LinearLayout.LayoutParams(0, dp(16)))

        bodyText = TextView(this).apply {
            setTextColor(Color.parseColor("#C9D4E5"))
            textSize = 16f
            gravity = Gravity.CENTER
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        content.addView(bodyText)

        val spacerBottom = View(this)
        content.addView(spacerBottom, LinearLayout.LayoutParams(0, dp(32)))

        dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        content.addView(dotsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)))

        val spacerBtn = View(this)
        content.addView(spacerBtn, LinearLayout.LayoutParams(0, dp(20)))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        backBtn = TextView(this).apply {
            text = "← Orqaga"
            textSize = 15f
            setTextColor(Color.parseColor("#AEB8C7"))
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
            setOnClickListener {
                if (index > 0) { index--; render() }
            }
        }
        nextBtn = TextView(this).apply {
            setTextColor(Color.BLACK)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(16))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            setOnClickListener {
                if (index < steps.size - 1) { index++; render() } else finishGuide()
            }
        }
        btnRow.addView(backBtn)
        btnRow.addView(nextBtn)
        content.addView(btnRow)

        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        render()
    }

    private fun render() {
        val s = steps[index]
        val accent = Color.parseColor(s.accent)

        val boxBg = GradientDrawable().apply {
            cornerRadius = 24f * resources.displayMetrics.density
            setColor(Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke((1.5f * resources.displayMetrics.density).toInt(), accent)
        }
        iconBox.background = boxBg
        iconText.text = s.emoji

        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
            setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.parseColor("#0B1220"))
        }
        numberBadge.background = badgeBg
        numberBadge.text = s.number.toString()

        titleText.text = s.title
        bodyText.text = s.body

        backBtn.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE

        val nextBg = GradientDrawable().apply {
            cornerRadius = 16f * resources.displayMetrics.density
            setColor(accent)
        }
        nextBtn.background = nextBg
        nextBtn.text = if (index == steps.size - 1) "Tushunarli, boshlayman ✓" else "Keyingi qadam →"

        dotsRow.removeAllViews()
        val density = resources.displayMetrics.density
        steps.forEachIndexed { i, _ ->
            val dot = View(this)
            val size = (8 * density).toInt()
            val params = LinearLayout.LayoutParams(size, size).apply {
                marginStart = (5 * density).toInt()
                marginEnd = (5 * density).toInt()
            }
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == index) accent else Color.parseColor("#2B3748"))
            }
            dot.background = drawable
            dotsRow.addView(dot, params)
        }
    }

    private fun finishGuide() {
        getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_SETUP_GUIDE_SHOWN, true).apply()
        if (fromOnboarding) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    override fun onBackPressed() {
        if (index > 0) { index--; render() } else finishGuide()
    }

    companion object {
        const val EXTRA_FROM_ONBOARDING = "from_onboarding"
        const val PREF_SETUP_GUIDE_SHOWN = "setup_guide_shown"
    }
}
