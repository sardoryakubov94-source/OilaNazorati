package uz.oilanazorati.parentcontrol.ui

import android.app.AlertDialog
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
 * Tanlangan kun uchun HAR BIR SMS'ni alohida qator qilib ko'rsatadi —
 * jo'natuvchi/qabul qiluvchi (ism yoki raqam), vaqti va MATN oldindan
 * ko'rinishi (bitta qator, uzun bo'lsa "..."). Qatorga bosilganda —
 * to'liq matn va raqam alohida oynada ko'rsatiladi.
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
        val root: View = view.findViewById(R.id.itemRoot)
        val colorDot: View = view.findViewById(R.id.contactColorDot)
        val contactLabel: TextView = view.findViewById(R.id.smsContactLabel)
        val detailLabel: TextView = view.findViewById(R.id.smsDetailLabel)
        val timeLabel: TextView = view.findViewById(R.id.smsTimeLabel)
        val bodyPreview: TextView = view.findViewById(R.id.smsBodyPreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_sms_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val hash = item.kontaktHash.ifBlank { "noma_lum" }
        val savedName = names[hash]

        holder.contactLabel.text = when {
            hash == "noma_lum" -> "Noma'lum raqam"
            savedName != null -> savedName
            item.raqam.isNotBlank() -> item.raqam
            else -> "Saqlanmagan kontakt"
        }

        holder.detailLabel.text = when (item.turi) {
            "yuborilgan" -> "📤 Yuborilgan"
            else -> "📥 Qabul qilingan"
        }

        holder.timeLabel.text = timeFmt.format(Date(item.vaqtMs))

        if (item.matn.isNotBlank()) {
            holder.bodyPreview.visibility = View.VISIBLE
            holder.bodyPreview.text = item.matn
        } else {
            holder.bodyPreview.visibility = View.GONE
        }

        val color = ContactAnonymizer.colorFor(hash)
        (holder.colorDot.background as? GradientDrawable)?.mutate()?.let {
            (it as GradientDrawable).setColor(color)
        }

        // Qatorga bosilganda — to'liq matn va raqam (agar saqlangan
        // kontakt bo'lsa, ism ustiga qo'shimcha) alohida oynada ko'rinadi.
        holder.root.setOnClickListener {
            val who = if (savedName != null && item.raqam.isNotBlank()) {
                "$savedName (${item.raqam})"
            } else if (item.raqam.isNotBlank()) {
                item.raqam
            } else {
                "Noma'lum raqam"
            }
            val body = item.matn.ifBlank { "(matn mavjud emas)" }
            AlertDialog.Builder(holder.root.context)
                .setTitle(who)
                .setMessage(body)
                .setPositiveButton("Yopish", null)
                .show()
        }
    }

    override fun getItemCount() = items.size
}
