package uz.oilanazorati.parentcontrol.ui

import android.app.DatePickerDialog
import android.content.Intent
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
 * Tanlangan kun uchun BARCHA SMS'larni vaqt va yo'nalish bilan ro'yxat
 * qilib ko'rsatadi. CallHistoryActivity bilan bir xil g'oya: har bir
 * kontakt (raqamsiz) barqaror rang bilan ajratiladi.
 */
class SmsHistoryActivity : AppCompatActivity() {

    private lateinit var selectedDateText: TextView
    private lateinit var btnPickDate: Button
    private lateinit var listTitle: TextView
    private lateinit var emptyStateText: TextView
    private lateinit var historyList: RecyclerView

    private val adapter = SmsHistoryAdapter()
    private val selectedCalendar: Calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_history)

        selectedDateText = findViewById(R.id.selectedDateText)
        btnPickDate = findViewById(R.id.btnPickDate)
        listTitle = findViewById(R.id.listTitle)
        emptyStateText = findViewById(R.id.emptyStateText)
        historyList = findViewById(R.id.historyList)

        historyList.layoutManager = LinearLayoutManager(this)
        historyList.adapter = adapter

        btnPickDate.setOnClickListener { showDatePicker() }

        FirebaseRepo.listenSavedContacts { contacts ->
            adapter.setNames(contacts.associate { it.kontaktHash to it.nomi })
        }
        FirebaseRepo.checkIsPremium { isPremium -> adapter.setPremium(isPremium) }

        updateDateLabel()
        loadDataForSelectedDay()

        bindBottomNav(NavTab.SMS)
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
        val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.US)
        selectedDateText.text = fmt.format(selectedCalendar.time)
    }

    private fun loadDataForSelectedDay() {
        val dayStart = (selectedCalendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L

        FirebaseRepo.fetchSmsInRange(dayStart, dayEnd) { smsList ->
            val sorted = smsList.sortedByDescending { it.vaqtMs }
            adapter.setData(sorted)
            emptyStateText.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
            historyList.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
            listTitle.text = "SMS ro'yxati — ${sorted.size} ta"
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
