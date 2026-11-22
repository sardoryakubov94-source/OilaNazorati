package uz.oilanazorati.parentcontrol.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
 * "Video yo'riqnomalar" — bola telefonida ilovani sozlash bo'yicha bitta video.
 * Unda kerakli ruxsatlarni berish va nazoratni boshlash ko'rsatiladi.
 *
 * Video saytdan (https://oilanazorati.uz/videos/) oqim (stream) qilib ko'rsatiladi —
 * shu sabab ilova hajmi oshmaydi. Ko'rish uchun internet kerak.
 */
class VideoGuideActivity : AppCompatActivity() {

    private data class Guide(
        val title: String, val body: String, val duration: String, val file: String,
        val topColor: String, val bottomColor: String, val accent: String
    )

    private val guides = listOf(
        Guide(
            "Bola telefoniga ruxsatlarni berish va nazoratni boshlash",
            "Bola telefonida oila kodini kiritish, kerakli ruxsatlarni berish va nazoratni ishga tushirish.",
            "1:05", "sozlash-nazoratni-boshlash", "#46337E", "#31245A", "#BCA3FF"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0B1220"), Color.parseColor("#111D30"), Color.parseColor("#0B1422"))
            )
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(this).apply {
            text = "Video yo'riqnomalar"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(this).apply {
            text = "Yopish  ✕"
            setTextColor(Color.parseColor("#8993A2"))
            textSize = 12f
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(Color.parseColor("#1C293B"), dp(12).toFloat(), Color.parseColor("#34445A"))
            setOnClickListener { finish() }
        })
        content.addView(topRow)
        content.addView(TextView(this).apply {
            text = "Bola telefonida kerakli ruxsatlarni berish va nazoratni boshlash bo'yicha videoqo'llanma. Ko'rish uchun internet kerak."
            setTextColor(Color.parseColor("#9FB3CE"))
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(10), 0, dp(6))
        })

        for (g in guides) {
            val accent = Color.parseColor(g.accent)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor(g.topColor), Color.parseColor(g.bottomColor))
                ).apply {
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(1), Color.argb(110, Color.red(accent), Color.green(accent), Color.blue(accent)))
                }
                setPadding(dp(18), dp(18), dp(18), dp(18))
                isClickable = true
                isFocusable = true
                setOnClickListener { openVideo(g.file, g.title) }
            }
            card.addView(TextView(this).apply {
                text = "▶  ${g.duration} daqiqa"
                setTextColor(Color.parseColor("#0B1220"))
                textSize = 11.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(11), dp(5), dp(11), dp(5))
                background = rounded(accent, dp(20).toFloat(), accent)
            })
            card.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
            card.addView(TextView(this).apply {
                text = g.title
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            })
            card.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
            card.addView(TextView(this).apply {
                text = g.body
                setTextColor(Color.parseColor("#D3DEEE"))
                textSize = 13.5f
                setLineSpacing(dp(4).toFloat(), 1f)
            })
            card.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
            card.addView(TextView(this).apply {
                text = "Videoni ko'rish  →"
                setTextColor(Color.parseColor("#0B1220"))
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(13), 0, dp(13))
                background = rounded(accent, dp(14).toFloat(), accent)
                setOnClickListener { openVideo(g.file, g.title) }
            })
            content.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
        }

        scroll.addView(content)
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun openVideo(file: String, title: String) {
        startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_URL, "$BASE_URL$file.mp4")
            putExtra(VideoPlayerActivity.EXTRA_TITLE, title)
        })
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            setStroke((1.2f * resources.displayMetrics.density).toInt(), stroke)
        }

    companion object {
        private const val BASE_URL = "https://oilanazorati.uz/videos/"
    }
}
