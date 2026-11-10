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
    private val onReplyClick: (docId: String, msg: SupportMessage) -> Unit,
    // Faqat egasi (asosiy admin) chaqirganda ishlatiladi — shu foydalanuvchining
    // (fromUid) butun yozishmasini boshqa adminlardan yashiradi/qayta ko'rsatadi.
    private val onToggleHideSender: (fromUid: String, currentlyHidden: Boolean) -> Unit = { _, _ -> }
) : RecyclerView.Adapter<AdminSupportMessageAdapter.VH>() {

    private var items: List<Pair<String, SupportMessage>> = emptyList()
    // Faqat egasi ko'radi — shuning uchun bu tugma ham faqat egasiga ko'rinadi.
    var isOwner: Boolean = false
    // Egasi uchun: hozircha yashirilgan foydalanuvchilarning uid to'plami
    // (shu tab doirasida tugma matnini to'g'ri ko'rsatish uchun).
    var hiddenUids: Set<String> = emptySet()

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
        val hideBtn: Button = view.findViewById(R.id.btnToggleHideSender)
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

        if (isOwner && msg.fromUid.isNotBlank()) {
            val hidden = msg.fromUid in hiddenUids
            holder.hideBtn.visibility = View.VISIBLE
            holder.hideBtn.text = if (hidden) "Qayta ko'rsatish (boshqa adminlarga)" else "Boshqa adminlardan yashirish"
            holder.hideBtn.setOnClickListener { onToggleHideSender(msg.fromUid, hidden) }
        } else {
            holder.hideBtn.visibility = View.GONE
        }
    }

    override fun getItemCount() = items.size
}
