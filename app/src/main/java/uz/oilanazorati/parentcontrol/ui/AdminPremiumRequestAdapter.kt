package uz.oilanazorati.parentcontrol.ui

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.PremiumRequest

class AdminPremiumRequestAdapter(
    private val onApprove: (docId: String, req: PremiumRequest) -> Unit,
    private val onReject: (docId: String) -> Unit
) : RecyclerView.Adapter<AdminPremiumRequestAdapter.VH>() {

    private var items: List<Pair<String, PremiumRequest>> = emptyList()

    fun setData(newItems: List<Pair<String, PremiumRequest>>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val email: TextView = view.findViewById(R.id.reqEmail)
        val status: TextView = view.findViewById(R.id.reqStatus)
        val screenshot: ImageView = view.findViewById(R.id.reqScreenshot)
        val approveBtn: Button = view.findViewById(R.id.btnApprove)
        val rejectBtn: Button = view.findViewById(R.id.btnReject)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_premium_request, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (docId, req) = items[position]
        holder.email.text = req.fromEmail

        when (req.holati) {
            "tolandi" -> {
                holder.status.text = "✅ Tasdiqlangan"
                holder.status.setTextColor(0xFF2ECC71.toInt())
            }
            "rad_etildi" -> {
                holder.status.text = "❌ Rad etilgan"
                holder.status.setTextColor(0xFFE74C3C.toInt())
            }
            else -> {
                holder.status.text = "⏳ Kutilmoqda"
                holder.status.setTextColor(0xFFF39C12.toInt())
            }
        }

        if (req.tolovSkrinshotiBase64.isNotBlank()) {
            try {
                val bytes = Base64.decode(req.tolovSkrinshotiBase64, Base64.NO_WRAP)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                holder.screenshot.setImageBitmap(bmp)
                holder.screenshot.visibility = View.VISIBLE
            } catch (e: Exception) {
                holder.screenshot.visibility = View.GONE
            }
        } else {
            holder.screenshot.visibility = View.GONE
        }

        val isPending = req.holati == "kutilmoqda"
        holder.approveBtn.isEnabled = isPending
        holder.rejectBtn.isEnabled = isPending
        holder.approveBtn.setOnClickListener { onApprove(docId, req) }
        holder.rejectBtn.setOnClickListener { onReject(docId) }
    }

    override fun getItemCount() = items.size
}
