package uz.oilanazorati.parentcontrol.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R

class AppUsageAdapter : RecyclerView.Adapter<AppUsageAdapter.VH>() {

    private var data: List<Pair<String, Long>> = emptyList() // ilova nomi -> soniya

    fun setData(newData: List<Pair<String, Long>>) {
        data = newData
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.appName)
        val duration: TextView = view.findViewById(R.id.appDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_usage, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (name, seconds) = data[position]
        val minutes = seconds / 60
        holder.name.text = name
        holder.duration.text = "${minutes} daqiqa"
    }

    override fun getItemCount() = data.size
}
