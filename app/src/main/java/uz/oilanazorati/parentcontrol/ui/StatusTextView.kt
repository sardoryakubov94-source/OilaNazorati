package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.graphics.Color
import android.widget.TextView
import uz.oilanazorati.parentcontrol.R

/**
 * Matn boshidagi emoji (⏳ 🔴 ❌ ⚠️ ♾️ ⏱) o'rniga zamonaviy vektor ikonni
 * chap tomonda ko'rsatadi. Matn qiymatlarini o'zgartirish shart emas.
 */
class StatusTextView(context: Context) : TextView(context) {

    private data class Ico(val prefix: String, val res: Int, val color: Int)

    private val icons: List<Ico>
        get() = listOf(
            Ico("⏳", R.drawable.ic_status_wait, 0xFF8FA3C4.toInt()),
            Ico("🔴", R.drawable.ic_status_live, 0xFFEF4444.toInt()),
            Ico("❌", R.drawable.ic_status_error, 0xFFEF4444.toInt()),
            Ico("⚠️", R.drawable.ic_status_warn, 0xFFF59E0B.toInt()),
            Ico("⚠", R.drawable.ic_status_warn, 0xFFF59E0B.toInt()),
            Ico("♾️", R.drawable.ic_status_infinity, 0xFF4DA3FF.toInt()),
            Ico("♾", R.drawable.ic_status_infinity, 0xFF4DA3FF.toInt()),
            Ico("⏱️", R.drawable.ic_status_timer, 0xFF8FA3C4.toInt()),
            Ico("⏱", R.drawable.ic_status_timer, 0xFF8FA3C4.toInt())
        )

    override fun setText(text: CharSequence?, type: TextView.BufferType?) {
        var clean = text?.toString()
        var icon: Ico? = null
        if (clean != null) {
            for (i in icons) {
                if (clean.startsWith(i.prefix)) { icon = i; clean = clean.removePrefix(i.prefix).trimStart(' ', '\uFE0F'); break }
            }
            clean = clean.replace("⏱️ ", "").replace("⏱ ", "").replace("♾️ ", "").replace("♾ ", "")
        }
        super.setText(clean ?: text, type)
        try {
            val d = icon?.let { androidx.core.content.ContextCompat.getDrawable(context, it.res)?.mutate()?.also { dr -> dr.setTint(it.color) } }
            setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
            compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
        } catch (_: Throwable) { }
    }
}
