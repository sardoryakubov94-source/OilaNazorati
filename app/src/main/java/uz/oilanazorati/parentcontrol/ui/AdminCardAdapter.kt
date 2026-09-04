package uz.oilanazorati.parentcontrol.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.AdminCard

class AdminCardAdapter(
    private val onDeleteClick: ((AdminCard) -> Unit)? = null
) : RecyclerView.Adapter<AdminCardAdapter.VH>() {

    private var items: List<AdminCard> = emptyList()

    fun setData(newItems: List<AdminCard>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val type: TextView = view.findViewById(R.id.cardTypeLabel)
        val number: TextView = view.findViewById(R.id.cardNumberLabel)
        val holder: TextView = view.findViewById(R.id.cardHolderLabel)
        val copyBtn: TextView = view.findViewById(R.id.btnCopyCard)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val card = items[position]
        holder.type.text = card.turi
        holder.number.text = card.raqam
        holder.holder.text = card.egasi

        holder.copyBtn.setOnClickListener {
            val ctx = holder.itemView.context
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Karta raqami", card.raqam))
            Toast.makeText(ctx, "Karta raqami nusxalandi", Toast.LENGTH_SHORT).show()
        }

        if (onDeleteClick != null) {
            holder.itemView.setOnLongClickListener {
                onDeleteClick.invoke(card)
                true
            }
        }
    }

    override fun getItemCount() = items.size
}
