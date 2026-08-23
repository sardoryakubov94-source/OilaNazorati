package uz.oilanazorati.parentcontrol.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R

/**
 * Kunlik ilova ishlatilishi ro'yxati. Har bir qatorda:
 *  - o'sha ilova uchun barqaror rang (nomi asosida hisoblangan)
 *  - nomi va daqiqasi
 *  - eng ko'p ishlatilgan ilovaga nisbatan foiz bo'yicha progress chiziq
 */
class AppUsageAdapter : RecyclerView.Adapter<AppUsageAdapter.VH>() {

    private var data: List<Pair<String, Long>> = emptyList() // ilova nomi -> soniya
    private var maxSeconds: Long = 1L

    private val palette = intArrayOf(
        0xFF2ECC71.toInt(), // yashil
        0xFF3498DB.toInt(), // ko'k
        0xFF9B59B6.toInt(), // binafsha
        0xFFE91E63.toInt(), // pushti
        0xFFF39C12.toInt(), // to'q sariq
        0xFF1ABC9C.toInt()  // firuza
    )

    fun setData(newData: List<Pair<String, Long>>) {
        data = newData
        maxSeconds = newData.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: 1L
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val colorDot: View = view.findViewById(R.id.appColorDot)
        val name: TextView = view.findViewById(R.id.appName)
        val duration: TextView = view.findViewById(R.id.appDuration)
        val fill: View = view.findViewById(R.id.appProgressFill)
        val spacer: View = view.findViewById(R.id.appProgressSpacer)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_usage, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (name, seconds) = data[position]
        val minutes = seconds / 60
        holder.name.text = name
        holder.duration.text = "${minutes} daqiqa"

        val color = palette[position % palette.size]
        (holder.colorDot.background as? GradientDrawable)?.mutate()?.let {
            (it as GradientDrawable).setColor(color)
        }
        (holder.fill.background as? GradientDrawable)?.mutate()?.let {
            (it as GradientDrawable).setColor(color)
        }

        val percent = ((seconds.toFloat() / maxSeconds.toFloat()) * 100).toInt().coerceIn(2, 100)
        (holder.fill.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = percent.toFloat()
            holder.fill.layoutParams = it
        }
        (holder.spacer.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = (100 - percent).toFloat()
            holder.spacer.layoutParams = it
        }
    }

    override fun getItemCount() = data.size
}
