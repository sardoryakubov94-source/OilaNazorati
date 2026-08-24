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
import java.util.Locale

/**
 * Tanlangan kun uchun HAR BIR qo'ng'iroqni alohida qator qilib
 * ko'rsatadi (TrendsActivity'dagi kabi jamlab emas). Har bir qatorda:
 *  - kontaktning barqaror rangi (ChatAnonymizer.colorFor orqali) —
 *    agar bir kishi kuni bo'yicha bir necha marta qo'ng'iroq qilgan/
 *    qabul qilgan bo'lsa, HAMMASI bir xil rangda ko'rinadi, shu orqali
 *    "bu ertalabgi va kechqurungi qo'ng'iroq bir odamniki" bilinadi —
 *    lekin kim ekani (raqami) hech qachon ko'rsatilmaydi
 *  - agar kontakt qurilmada ism bilan saqlangan bo'lsa — o'sha ism
 *  - vaqti, yo'nalishi (kiruvchi/chiquvchi/javobsiz), davomiyligi
 */
class CallHistoryAdapter : RecyclerView.Adapter<CallHistoryAdapter.VH>() {

    private var items: List<CallEvent> = emptyList()
    private var names: Map<String, String> = emptyMap()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setData(newItems: List<CallEvent>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setNames(newNames: Map<String, String>) {
        names = newNames
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val colorDot: View = view.findViewById(R.id.contactColorDot)
        val contactLabel: TextView = view.findViewById(R.id.callContactLabel)
        val detailLabel: TextView = view.findViewById(R.id.callDetailLabel)
        val timeLabel: TextView = view.findViewById(R.id.callTimeLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_call_history, parent, false)
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

        val turiLabel = when (item.turi) {
            "kiruvchi" -> "📥 Kiruvchi"
            "chiquvchi" -> "📤 Chiquvchi"
            else -> "❌ Javobsiz"
        }
        val davomiylik = if (item.davomiylikSoniya > 0) {
            val m = item.davomiylikSoniya / 60
            val s = item.davomiylikSoniya % 60
            " • ${m} daq ${s} son"
        } else ""
        holder.detailLabel.text = "$turiLabel$davomiylik"

        holder.timeLabel.text = timeFmt.format(Date(item.boshlanishMs))

        val color = ContactAnonymizer.colorFor(hash)
        (holder.colorDot.background as? GradientDrawable)?.mutate()?.let {
            (it as GradientDrawable).setColor(color)
        }
    }

    override fun getItemCount() = items.size
}
