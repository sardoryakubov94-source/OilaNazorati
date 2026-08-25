package uz.oilanazorati.parentcontrol.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.CallEvent
import uz.oilanazorati.parentcontrol.model.SmsEvent
import uz.oilanazorati.parentcontrol.util.ContactAnonymizer

/** Bitta anonim kontakt uchun kunlik statistik yig'indi. */
data class ContactStat(
    val kontaktHash: String,
    val qongiroqSoni: Int,
    val jamiDaqiqa: Long,
    val raqam: String = ""
)

/**
 * Berilgan kun uchun qo'ng'iroqlarni anonim kontaktHash bo'yicha guruhlab,
 * eng ko'p gaplashilgandan boshlab tartiblaydi. Shu ro'yxatning uzunligi —
 * "necha xil raqam bilan gaplashgan" degani.
 */
fun buildContactStats(calls: List<CallEvent>): List<ContactStat> {
    return calls
        .groupBy { it.kontaktHash.ifBlank { "noma_lum" } }
        .map { (hash, list) ->
            ContactStat(
                kontaktHash = hash,
                qongiroqSoni = list.size,
                jamiDaqiqa = list.sumOf { it.davomiylikSoniya } / 60,
                raqam = list.firstOrNull { it.raqam.isNotBlank() }?.raqam.orEmpty()
            )
        }
        .sortedByDescending { it.jamiDaqiqa }
}

/** Bitta anonim kontakt uchun SMS statistik yig'indi (davomiylik yo'q — SMS'da bo'lmaydi). */
data class SmsContactStat(
    val kontaktHash: String,
    val yuborilganSoni: Int,
    val qabulQilinganSoni: Int,
    val raqam: String = ""
) {
    val jamiSoni: Int get() = yuborilganSoni + qabulQilinganSoni
}

/**
 * Berilgan oraliq uchun SMS'larni anonim kontaktHash bo'yicha guruhlab,
 * eng ko'p yozishilgandan boshlab tartiblaydi — qo'ng'iroqdagi bilan bir xil
 * mantiq, faqat "daqiqa" o'rniga "xabar soni" asosida.
 */
fun buildSmsContactStats(sms: List<SmsEvent>): List<SmsContactStat> {
    return sms
        .groupBy { it.kontaktHash.ifBlank { "noma_lum" } }
        .map { (hash, list) ->
            SmsContactStat(
                kontaktHash = hash,
                yuborilganSoni = list.count { it.turi == "yuborilgan" },
                qabulQilinganSoni = list.count { it.turi == "qabul_qilingan" },
                raqam = list.firstOrNull { it.raqam.isNotBlank() }?.raqam.orEmpty()
            )
        }
        .sortedByDescending { it.jamiSoni }
}

/**
 * Har bir qatorda: rangli nuqta (= anonim kontakt), necha marta qo'ng'iroq
 * qilingan/qilingan va jami necha daqiqa gaplashilgani. Raqamning o'zi
 * hech qachon ko'rsatilmaydi — faqat rang orqali "shu odam" ekani bilinadi.
 */
class ContactSummaryAdapter : RecyclerView.Adapter<ContactSummaryAdapter.VH>() {

    private var stats: List<ContactStat> = emptyList()
    private var names: Map<String, String> = emptyMap() // kontaktHash -> nomi (faqat saqlanganlar uchun)
    private var isPremium: Boolean = false

    fun setStats(newStats: List<ContactStat>) {
        stats = newStats
        notifyDataSetChanged()
    }

    /** Agar kontakt qurilmada ism bilan saqlangan bo'lsa, ro'yxatda o'sha ism ham ko'rsatiladi. */
    fun setNames(newNames: Map<String, String>) {
        names = newNames
        notifyDataSetChanged()
    }

    /** Saqlanmagan raqamning o'zini ko'rsatish FAQAT Premium bo'lsa. */
    fun setPremium(premium: Boolean) {
        isPremium = premium
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
        val s = stats[position]
        val label = when {
            s.kontaktHash == "noma_lum" -> "🔒 Noma'lum raqam"
            names.containsKey(s.kontaktHash) -> "👤 ${names[s.kontaktHash]}"
            isPremium && s.raqam.isNotBlank() -> "👤 ${s.raqam}"
            else -> "👤 Saqlanmagan kontakt"
        }
        holder.label.text = "$label — ${s.qongiroqSoni} marta, ${s.jamiDaqiqa} daq"

        val color = ContactAnonymizer.colorFor(s.kontaktHash)
        (holder.colorDot.background as? GradientDrawable)?.mutate()?.let {
            (it as GradientDrawable).setColor(color)
        }
    }

    override fun getItemCount() = stats.size
}

/**
 * SMS uchun bir xil g'oya: raqam ko'rsatilmaydi, rang orqali "shu odam"
 * ekani bilinadi, saqlangan bo'lsa ismi ko'rinadi. Yuborilgan/qabul
 * qilingan sonlari alohida ko'rsatiladi (davomiylik SMS'da bo'lmaydi).
 */
class SmsContactSummaryAdapter : RecyclerView.Adapter<SmsContactSummaryAdapter.VH>() {

    private var stats: List<SmsContactStat> = emptyList()
    private var names: Map<String, String> = emptyMap() // kontaktHash -> nomi (faqat saqlanganlar uchun)
    private var isPremium: Boolean = false

    fun setStats(newStats: List<SmsContactStat>) {
        stats = newStats
        notifyDataSetChanged()
    }

    fun setNames(newNames: Map<String, String>) {
        names = newNames
        notifyDataSetChanged()
    }

    fun setPremium(premium: Boolean) {
        isPremium = premium
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
        val s = stats[position]
        val label = when {
            s.kontaktHash == "noma_lum" -> "🔒 Noma'lum raqam"
            names.containsKey(s.kontaktHash) -> "👤 ${names[s.kontaktHash]}"
            isPremium && s.raqam.isNotBlank() -> "👤 ${s.raqam}"
            else -> "👤 Saqlanmagan kontakt"
        }
        holder.label.text = "$label — ${s.jamiSoni} ta SMS (${s.yuborilganSoni} yuborilgan, ${s.qabulQilinganSoni} qabul qilingan)"

        val color = ContactAnonymizer.colorFor(s.kontaktHash)
        (holder.colorDot.background as? GradientDrawable)?.mutate()?.let {
            (it as GradientDrawable).setColor(color)
        }
    }

    override fun getItemCount() = stats.size
}
