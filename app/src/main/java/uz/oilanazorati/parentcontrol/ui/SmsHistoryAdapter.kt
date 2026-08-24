package uz.oilanazorati.parentcontrol.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.SmsEvent
import uz.oilanazorati.parentcontrol.util.ContactAnonymizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tanlangan kun uchun HAR BIR SMS'ni alohida qator qilib ko'rsatadi.
 * CallHistoryAdapter bilan bir xil g'oya: rang orqali "shu odam",
 * saqlangan bo'lsa ism bilan, aks holda faqat rang bilan.
 */
class SmsHistoryAdapter : RecyclerView.Adapter<SmsHistoryAdapter.VH>() {

    private var items: List<SmsEvent> = emptyList()
    private var names: Map<String, String> = emptyMap()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setData(newItems: List<SmsEvent>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setNames(newNames: Map<String, String>) {
        names = newNames
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val colorDot: View = view.findViewById(R.id.contactColorDot)
        val contactLabel: TextView = view.findViewById(R.id.smsContactLabel)
        val detailLabel: TextView = view.findViewById(R.id.smsDetailLabel)
        val timeLabel: TextView = view.findViewById(R.id.smsTimeLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_sms_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val hash = item.kontaktHash.ifBlank { "noma_lum" }

        holder.contactLabel.text = when {
            hash == "noma_lum" -> "Noma'lum raqam"
            names.containsKey(hash) -> names[hash]
            item.raqam.isNotBlank() -> item.raqam
            else -> "Saqlanmagan kontakt"
        }

        holder.detailLabel.text = when (item.turi) {
            "yuborilgan" -> "📤 Yuborilgan"
            else -> "📥 Qabul qilingan"
        }

        holder.timeLabel.text = timeFmt.format(Date(item.vaqtMs))

        val color = ContactAnonymizer.colorFor(hash)
        (holder.colorDot.background as? GradientDrawable)?.mutate()?.let {
            (it as GradientDrawable).setColor(color)
        }
    }

    override fun getItemCount() = items.size
}
