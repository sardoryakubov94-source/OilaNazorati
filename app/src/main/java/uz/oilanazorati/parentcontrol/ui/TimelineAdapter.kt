package uz.oilanazorati.parentcontrol.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.CallEvent
import uz.oilanazorati.parentcontrol.util.ContactAnonymizer
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Har bir qo'ng'iroqni "soat necha - necha" ko'rinishida ro'yxat qilib
 * chiqadi. Qatorning oldida rangli nuqta bor — bu rang qo'ng'iroq
 * qilingan/qilingan RAQAMDAN emas, balki uning anonim xashidan
 * (ContactAnonymizer) hosil qilinadi. Shu tufayli:
 *   - bir xil raqam bilan bo'lgan barcha qo'ng'iroqlar DOIM bir xil
 *     rangda ko'rinadi (bitta odam bilan ko'p gaplashgan bo'lsa —
 *     bitta rang ko'p qaytariladi),
 *   - lekin qaysi raqam ekani hech qachon ko'rsatilmaydi.
 */
class TimelineAdapter : RecyclerView.Adapter<TimelineAdapter.VH>() {

    private var calls: List<CallEvent> = emptyList()
    private var fmt = SimpleDateFormat("HH:mm")

    fun setCalls(newCalls: List<CallEvent>, formatter: SimpleDateFormat) {
        calls = newCalls
        fmt = formatter
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.timelineLabel)
        val colorDot: View = view.findViewById(R.id.contactColorDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_timeline, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = calls[position]
        val icon = when (c.turi) {
            "kiruvchi" -> "📲"
            "chiquvchi" -> "☎️"
            else -> "📵"
        }
        val start = fmt.format(Date(c.boshlanishMs))
        val end = if (c.tugashMs > 0) fmt.format(Date(c.tugashMs)) else "-"
        val durMin = c.davomiylikSoniya / 60
        holder.label.text = "$icon $start – $end  ($durMin daq, ${c.turi})"

        // Shu kontaktning rangi (raqamning o'zi emas — faqat anonim xashdan).
        val hashForColor = c.kontaktHash.ifBlank { "noma_lum" }
        val color = ContactAnonymizer.colorFor(hashForColor)
        (holder.colorDot.background as? GradientDrawable)?.mutate()?.let {
            (it as GradientDrawable).setColor(color)
        }
    }

    override fun getItemCount() = calls.size
}
