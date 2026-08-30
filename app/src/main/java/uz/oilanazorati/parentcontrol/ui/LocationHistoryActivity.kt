package uz.oilanazorati.parentcontrol.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
 *   ko'rsatiladi. Xaritaning o'ng-pastki burchagida standart Google
 *   Xarita "mening joylashuvim" tugmasi (ko'k nuqta + markazlashtirish)
 *   ko'rinadi — bu ANIQ SHU (ota-ona) qurilmaning hozirgi joylashuvi,
 *   bola bilan solishtirib, masofa/yo'nalishni ko'z bilan baholash uchun.
 * Pastda — o'sha kunlik ro'yxat + har bir qatorda "🧭 Yo'nalish" tugmasi
 *   — bosilsa, Google Xarita ilovasi ochilib, HOZIRGI joylashuvdan shu
 *   nuqtagacha to'liq, bosqichma-bosqich yo'nalish (navigatsiya) beradi.
 */
class LocationHistoryActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var selectedDateText: TextView
    private lateinit var btnPickDate: Button
    private lateinit var listTitle: TextView
    private lateinit var emptyStateText: TextView
    private lateinit var locationList: RecyclerView

    private val adapter = LocationHistoryAdapter(
        onItemClick = { event -> focusMapOn(event) },
        onDirectionsClick = { event -> openDirections(event) }
    )
    private var googleMap: GoogleMap? = null
    private var currentEvents: List<LocationEvent> = emptyList()
    private var markersByTimeMs: MutableMap<Long, com.google.android.gms.maps.model.Marker> = mutableMapOf()

    private val selectedCalendar: Calendar = Calendar.getInstance()

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableMyLocationOnMap()
    }

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

        bindBottomNav(NavTab.LOCATION)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        renderMapForCurrentEvents()

        // "Mening joylashuvim" (ko'k nuqta + markazlashtirish tugmasi) —
        // bu ANIQ SHU (ota-ona) qurilmaning joylashuv ruxsatiga bog'liq,
        // bolaning joylashuviga hech qanday aloqasi yo'q.
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            enableMyLocationOnMap()
        } else {
            locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun enableMyLocationOnMap() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        googleMap?.isMyLocationEnabled = true
        googleMap?.uiSettings?.isMyLocationButtonEnabled = true
    }

    // ---------------- Yo'nalish olish ----------------

    /**
     * Google Xarita ilovasini "hozirgi joylashuvdan shu nuqtagacha
     * yo'l ko'rsatish" rejimida ochadi. Agar Google Xarita o'rnatilmagan
     * bo'lsa, brauzerdagi Google Maps'ga tushadi (standart Android
     * xatti-harakati).
     */
    private fun openDirections(event: LocationEvent) {
        val uri = Uri.parse("google.navigation:q=${event.lat},${event.lng}")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            // Google Xarita ilovasi topilmasa — brauzer orqali ochamiz
            val webUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=${event.lat},${event.lng}"
            )
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
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
        val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.US)
        selectedDateText.text = fmt.format(selectedCalendar.time)
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
