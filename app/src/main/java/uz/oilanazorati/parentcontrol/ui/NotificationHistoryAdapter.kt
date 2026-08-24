package uz.oilanazorati.parentcontrol.ui

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.NotificationEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tanlangan kun uchun ijtimoiy tarmoq/messenjer bildirishnomalarini
 * ro'yxat qilib ko'rsatadi: qaysi ilova, sarlavha (odatda jo'natuvchi
 * ismi), matn oldindan ko'rinishi. Qatorga bosilganda to'liq matn
 * alohida oynada chiqadi.
 */
class NotificationHistoryAdapter : RecyclerView.Adapter<NotificationHistoryAdapter.VH>() {

    private var items: List<NotificationEvent> = emptyList()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setData(newItems: List<NotificationEvent>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.itemRoot)
        val appLabel: TextView = view.findViewById(R.id.notifAppLabel)
        val titleLabel: TextView = view.findViewById(R.id.notifTitleLabel)
        val bodyPreview: TextView = view.findViewById(R.id.notifBodyPreview)
        val timeLabel: TextView = view.findViewById(R.id.notifTimeLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_notification_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.appLabel.text = item.ilovaNomi
        holder.titleLabel.text = item.sarlavha.ifBlank { "(sarlavhasiz)" }
        holder.bodyPreview.text = item.matn
        holder.bodyPreview.visibility = if (item.matn.isBlank()) View.GONE else View.VISIBLE
        holder.timeLabel.text = timeFmt.format(Date(item.vaqtMs))

        holder.root.setOnClickListener {
            AlertDialog.Builder(holder.root.context)
                .setTitle("${item.ilovaNomi} — ${item.sarlavha}")
                .setMessage(item.matn.ifBlank { "(matn mavjud emas)" })
                .setPositiveButton("Yopish", null)
                .show()
        }
    }

    override fun getItemCount() = items.size
}
