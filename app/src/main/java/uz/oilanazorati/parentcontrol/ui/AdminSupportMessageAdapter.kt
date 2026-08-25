package uz.oilanazorati.parentcontrol.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.SupportMessage

class AdminSupportMessageAdapter(
    private val onReplyClick: (docId: String, msg: SupportMessage) -> Unit
) : RecyclerView.Adapter<AdminSupportMessageAdapter.VH>() {

    private var items: List<Pair<String, SupportMessage>> = emptyList()

    fun setData(newItems: List<Pair<String, SupportMessage>>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val email: TextView = view.findViewById(R.id.msgEmail)
        val text: TextView = view.findViewById(R.id.msgText)
        val contactNumber: TextView = view.findViewById(R.id.msgContactNumber)
        val status: TextView = view.findViewById(R.id.msgStatus)
        val replyBtn: Button = view.findViewById(R.id.btnReply)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_support_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (docId, msg) = items[position]
        holder.email.text = msg.fromEmail
        holder.text.text = msg.matn
        holder.contactNumber.text = if (msg.aloqaRaqami.isNotBlank()) "📞 ${msg.aloqaRaqami}" else ""
        holder.contactNumber.visibility = if (msg.aloqaRaqami.isNotBlank()) View.VISIBLE else View.GONE

        if (msg.holati == "javob_berildi") {
            holder.status.text = "✅ Javob berilgan: ${msg.adminJavobi}"
            holder.status.setTextColor(0xFF2ECC71.toInt())
            holder.replyBtn.text = "Javobni o'zgartirish"
        } else {
            holder.status.text = "⏳ Kutilmoqda"
            holder.status.setTextColor(0xFFF39C12.toInt())
            holder.replyBtn.text = "Javob yozish"
        }

        holder.replyBtn.setOnClickListener { onReplyClick(docId, msg) }
    }

    override fun getItemCount() = items.size
}
