package uz.oilanazorati.parentcontrol.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.model.LocationEvent
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * "Bola qurilmasi kun davomida qayerda bo'lgan" ekrani.
 *
 * Yuqorida — sana filtri (istalgan kunni tanlash mumkin).
 * O'rtada — Google Xarita: shu kun uchun yozilgan barcha nuqtalar
 *   marker sifatida, va ular orasidan chizilgan yo'l (polyline) bilan
 *   ko'rsatiladi.
 * Pastda — o'sha kunlik ro'yxat, har bir qator "soat:daqiqa — koordinata"
 *   ko'rinishida (yozuvlar MonitorForegroundService tomonidan har 30
 *   daqiqada bir marta qo'shiladi). Ro'yxatdan biror qatorga bosilsa,
 *   xarita o'sha nuqtaga kamerani ko'chiradi va markerni ko'rsatadi.
 */
class LocationHistoryActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var selectedDateText: TextView
    private lateinit var btnPickDate: Button
    private lateinit var listTitle: TextView
    private lateinit var emptyStateText: TextView
    private lateinit var locationList: RecyclerView

    private val adapter = LocationHistoryAdapter { event -> focusMapOn(event) }
    private var googleMap: GoogleMap? = null
    private var currentEvents: List<LocationEvent> = emptyList()
    private var markersByTimeMs: MutableMap<Long, com.google.android.gms.maps.model.Marker> = mutableMapOf()

    private val selectedCalendar: Calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_history)

        selectedDateText = findViewById(R.id.selectedDateText)
        btnPickDate = findViewById(R.id.btnPickDate)
        listTitle = findViewById(R.id.listTitle)
        emptyStateText = findViewById(R.id.emptyStateText)
        locationList = findViewById(R.id.locationList)

        locationList.layoutManager = LinearLayoutManager(this)
        locationList.adapter = adapter

        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.mapContainer, mapFragment)
            .commit()
        mapFragment.getMapAsync(this)

        btnPickDate.setOnClickListener { showDatePicker() }

        updateDateLabel()
        loadDataForSelectedDay()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        renderMapForCurrentEvents()
    }

    // ---------------- Sana filtri ----------------

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
            // Kelajakdagi kunni tanlashning ma'nosi yo'q — hali ma'lumot yo'q
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun updateDateLabel() {
        val today = Calendar.getInstance()
        val isToday = today.get(Calendar.YEAR) == selectedCalendar.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == selectedCalendar.get(Calendar.DAY_OF_YEAR)

        val fmt = SimpleDateFormat("dd-MMMM, yyyy", Locale("uz"))
        selectedDateText.text = if (isToday) {
            "Bugun (${fmt.format(selectedCalendar.time)})"
        } else {
            fmt.format(selectedCalendar.time)
        }
    }

    // ---------------- Ma'lumotni yuklash ----------------

    private fun loadDataForSelectedDay() {
        val dayStart = (selectedCalendar.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L

        FirebaseRepo.fetchLocationsInRange(dayStart, dayEnd) { events ->
            currentEvents = events
            adapter.setData(events)
            emptyStateText.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
            locationList.visibility = if (events.isEmpty()) View.GONE else View.VISIBLE
            listTitle.text = "Joylashuv ro'yxati (har 30 daqiqada) — ${events.size} ta yozuv"
            renderMapForCurrentEvents()
        }
    }

    // ---------------- Xaritani chizish ----------------

    private fun renderMapForCurrentEvents() {
        val map = googleMap ?: return
        map.clear()
        markersByTimeMs.clear()

        if (currentEvents.isEmpty()) return

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val points = currentEvents.map { LatLng(it.lat, it.lng) }

        // Kun davomidagi harakat yo'lini chiziq bilan ko'rsatish
        map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .width(6f)
                .color(0xFF228B22.toInt())
        )

        currentEvents.forEach { event ->
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(event.lat, event.lng))
                    .title(timeFmt.format(Date(event.vaqtMs)))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
            )
            if (marker != null) markersByTimeMs[event.vaqtMs] = marker
        }

        // Barcha nuqtalar ekranga sig'adigan qilib kamerani moslashtirish
        val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
        points.forEach { boundsBuilder.include(it) }
        try {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80))
        } catch (e: IllegalStateException) {
            // Faqat bitta nuqta bo'lsa bounds hisoblanmaydi — shunchaki markazga qo'yamiz
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 14f))
        }
    }

    private fun focusMapOn(event: LocationEvent) {
        val map = googleMap ?: return
        val target = LatLng(event.lat, event.lng)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 16f))
        markersByTimeMs[event.vaqtMs]?.showInfoWindow()
        adapter.selectByEvent(event)
    }
}
