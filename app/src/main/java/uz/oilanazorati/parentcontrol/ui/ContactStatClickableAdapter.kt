package uz.oilanazorati.parentcontrol.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.util.ContactAnonymizer

/**
 * "Kontaktlar" ekranidagi ikkala ro'yxat uchun (bugungi faol kontaktlar va
 * kalendarda tanlangan kundagi kontaktlar) umumiy adapter: rangli nuqta,
 * ism (yoki "Saqlanmagan kontakt" / "Noma'lum raqam") va qo'ng'iroqlar soni.
 * Qatorga bosilganda tashqi callback chaqiriladi — shu orqali 30 kunlik yoki
 * oylik tafsilot ochiladi. Raqamning o'zi bu yerda hech qachon ko'rsatilmaydi.
 */
class ContactStatClickableAdapter(
    private val onClick: (ContactStat) -> Unit
) : RecyclerView.Adapter<ContactStatClickableAdapter.VH>() {

    private var stats: List<ContactStat> = emptyList()
    private var names: Map<String, String> = emptyMap()

    fun setStats(newStats: List<ContactStat>) {
        stats = newStats
        notifyDataSetChanged()
    }

    fun setNames(newNames: Map<String, String>) {
        names = newNames
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.contactLabel)
        val meta: TextView = view.findViewById(R.id.contactMeta)
        val colorDot: View = view.findViewById(R.id.contactColorDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact_clickable, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = stats[position]
        holder.label.text = when {
            s.kontaktHash == "noma_lum" -> "🔒 Noma'lum raqam"
            names.containsKey(s.kontaktHash) -> "👤 ${names[s.kontaktHash]}"
            else -> "👤 Saqlanmagan kontakt"
        }
        holder.meta.text = "${s.qongiroqSoni} marta"
        val color = ContactAnonymizer.colorFor(s.kontaktHash)
        (holder.colorDot.background as? GradientDrawable)?.mutate()?.let {
            (it as GradientDrawable).setColor(color)
        }
        holder.itemView.setOnClickListener { onClick(s) }
    }

    override fun getItemCount() = stats.size
}
