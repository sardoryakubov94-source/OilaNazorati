package uz.oilanazorati.parentcontrol.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.ContactMapping
import uz.oilanazorati.parentcontrol.util.ContactAnonymizer

/**
 * Ro'yxatdagi har bir qator: rangli nuqta + kontakt ismi. Faqat qurilmada
 * ISM BILAN saqlangan kontaktlar shu yerga keladi (oila a'zolari o'zi
 * ataylab saqlagan) — RAQAM esa bu ekranda ham hech qachon ko'rsatilmaydi,
 * chunki u Firestore'ga umuman yuborilmagan.
 */
class SavedContactsAdapter : RecyclerView.Adapter<SavedContactsAdapter.VH>() {

    private var contacts: List<ContactMapping> = emptyList()

    fun setContacts(newContacts: List<ContactMapping>) {
        contacts = newContacts.sortedBy { it.nomi.lowercase() }
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
        val c = contacts[position]
        holder.label.text = c.nomi
        val color = ContactAnonymizer.colorFor(c.kontaktHash)
        (holder.colorDot.background as? GradientDrawable)?.mutate()?.let {
            (it as GradientDrawable).setColor(color)
        }
    }

    override fun getItemCount() = contacts.size
}
