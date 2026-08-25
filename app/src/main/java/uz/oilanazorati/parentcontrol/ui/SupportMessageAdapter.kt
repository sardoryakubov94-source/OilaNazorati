package uz.oilanazorati.parentcontrol.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.SupportMessage

class SupportMessageAdapter : RecyclerView.Adapter<SupportMessageAdapter.VH>() {

    private var items: List<Pair<String, SupportMessage>> = emptyList()

    fun setData(newItems: List<Pair<String, SupportMessage>>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.msgText)
        val status: TextView = view.findViewById(R.id.msgStatus)
        val replyContainer: View = view.findViewById(R.id.replyContainer)
        val replyText: TextView = view.findViewById(R.id.replyText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_support_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (_, msg) = items[position]
        holder.text.text = msg.matn
        if (msg.holati == "javob_berildi") {
            holder.status.text = "✅ Javob berildi"
            holder.status.setTextColor(0xFF2ECC71.toInt())
            holder.replyContainer.visibility = View.VISIBLE
            holder.replyText.text = msg.adminJavobi
        } else {
            holder.status.text = "⏳ Kutilmoqda"
            holder.status.setTextColor(0xFFF39C12.toInt())
            holder.replyContainer.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size
}
