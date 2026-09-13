package uz.oilanazorati.parentcontrol.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.PremiumParent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminPremiumUserAdapter(
    private val onRevoke: (PremiumParent) -> Unit
) : RecyclerView.Adapter<AdminPremiumUserAdapter.VH>() {

    private var items: List<PremiumParent> = emptyList()

    fun setData(newItems: List<PremiumParent>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val ismi: TextView = view.findViewById(R.id.puIsmi)
        val email: TextView = view.findViewById(R.id.puEmail)
        val oxirgiKirish: TextView = view.findViewById(R.id.puOxirgiKirish)
        val revokeBtn: Button = view.findViewById(R.id.btnRevoke)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_premium_user, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.ismi.text = if (item.ismi.isNotBlank()) "⭐ ${item.ismi}" else "⭐ (ismi noma'lum)"
        holder.email.text = item.email.ifBlank { item.uid }
        holder.oxirgiKirish.text = if (item.oxirgiKirishMs > 0) {
            "Oxirgi kirish: " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(item.oxirgiKirishMs))
        } else ""
        holder.revokeBtn.setOnClickListener { onRevoke(item) }
    }

    override fun getItemCount() = items.size
}
