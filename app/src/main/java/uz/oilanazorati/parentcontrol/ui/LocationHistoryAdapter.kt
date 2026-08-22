package uz.oilanazorati.parentcontrol.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.LocationEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tanlangan kun uchun har 30 daqiqada yozilgan joylashuv nuqtalarini
 * "soat:daqiqa — koordinata" ko'rinishida ro'yxat qilib chiqadi.
 * Qatorga bosilganda [onItemClick] chaqiriladi — shu orqali
 * LocationHistoryActivity xaritani o'sha nuqtaga ko'chiradi.
 */
class LocationHistoryAdapter(
    private val onItemClick: (LocationEvent) -> Unit
) : RecyclerView.Adapter<LocationHistoryAdapter.VH>() {

    private var items: List<LocationEvent> = emptyList()
    private var selectedIndex: Int = -1
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setData(newItems: List<LocationEvent>) {
        items = newItems
        selectedIndex = -1
        notifyDataSetChanged()
    }

    fun selectByEvent(event: LocationEvent) {
        val idx = items.indexOfFirst { it.vaqtMs == event.vaqtMs }
        if (idx == -1) return
        selectedIndex = idx
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.itemRoot)
        val time: TextView = view.findViewById(R.id.locTime)
        val coords: TextView = view.findViewById(R.id.locCoords)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_location_history, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.time.text = timeFmt.format(Date(item.vaqtMs))
        holder.coords.text = String.format(Locale.getDefault(), "%.5f, %.5f", item.lat, item.lng)
        holder.root.setBackgroundColor(
            if (position == selectedIndex) 0x33228B22 else 0x00000000
        )
        holder.root.setOnClickListener {
            selectedIndex = holder.adapterPosition
            notifyDataSetChanged()
            onItemClick(item)
        }
    }

    override fun getItemCount() = items.size
}
