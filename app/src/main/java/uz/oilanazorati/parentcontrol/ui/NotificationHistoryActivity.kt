package uz.oilanazorati.parentcontrol.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Tanlangan kun uchun BARCHA ijtimoiy tarmoq/messenjer bildirishnomalarini
 * ro'yxat qilib ko'rsatadi (Instagram, Telegram, WhatsApp va h.k.).
 */
class NotificationHistoryActivity : AppCompatActivity() {

    private lateinit var selectedDateText: TextView
    private lateinit var btnPickDate: Button
    private lateinit var listTitle: TextView
    private lateinit var emptyStateText: TextView
    private lateinit var historyList: RecyclerView

    private val adapter = NotificationHistoryAdapter()
    private val selectedCalendar: Calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_history)

        selectedDateText = findViewById(R.id.selectedDateText)
        btnPickDate = findViewById(R.id.btnPickDate)
        listTitle = findViewById(R.id.listTitle)
        emptyStateText = findViewById(R.id.emptyStateText)
        historyList = findViewById(R.id.historyList)

        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = adapter

        btnPickDate.setOnClickListener { showDatePicker() }

        updateDateLabel()
        loadDataForSelectedDay()
    }

    private fun showDatePicker() {
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                selectedCalendar.set(Calendar.YEAR, year)
                selectedCalendar.set(Calendar.MONTH, month)
                selectedCalendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                updateDateLabel()
                loadDataForSelectedDay()
            },
            selectedCalendar.get(Calendar.YEAR),
            selectedCalendar.get(Calendar.MONTH),
            selectedCalendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun updateDateLabel() {
        val today = Calendar.getInstance()
        val isToday = today.get(Calendar.YEAR) == selectedCalendar.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == selectedCalendar.get(Calendar.DAY_OF_YEAR)

        val fmt = SimpleDateFormat("dd-MMMM, yyyy", Locale("uz"))
        selectedDateText.text = if (isToday) "Bugun (${fmt.format(selectedCalendar.time)})" else fmt.format(selectedCalendar.time)
    }

    private fun loadDataForSelectedDay() {
        val dayStart = (selectedCalendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L

        FirebaseRepo.fetchNotificationsInRange(dayStart, dayEnd) { list ->
            val sorted = list.sortedByDescending { it.vaqtMs }
            adapter.setData(sorted)
            emptyStateText.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
            historyList.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
            listTitle.text = "Bildirishnomalar ro'yxati — ${sorted.size} ta"
        }
    }
}
