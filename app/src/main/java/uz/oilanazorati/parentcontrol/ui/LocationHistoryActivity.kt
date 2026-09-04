package uz.oilanazorati.parentcontrol.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RecyclerView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
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

class LocationHistoryActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var selectedDateText: TextView
    private lateinit var btnPickDate: Button
    private lateinit var listTitle: TextView
    private lateinit var emptyStateText: TextView
    private lateinit var locationList: RecyclerView
    private lateinit var btnLiveTracking: Button
    private lateinit var liveTrackingStatus: TextView

    private val adapter = LocationHistoryAdapter(
        onItemClick = { event -> focusMapOn(event) },
        onDirectionsClick = { event -> openDirections(event) }
    )
    private var googleMap: GoogleMap? = null
    private var currentEvents: List<LocationEvent> = emptyList()
    private var markersByTimeMs = mutableMapOf<Long, com.google.android.gms.maps.model.Marker>()
    private var liveTrackingActive = false
    private var liveTrackingUntilMs = 0L
    private var liveLocationListener: com.google.firebase.firestore.ListenerRegistration? = null
    private var liveMarker: com.google.android.gms.maps.model.Marker? = null
    private val countdownHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val countdownRunnable = object : Runnable {
        override fun run() {
            val remainingSec = ((liveTrackingUntilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (remainingSec <= 0) { stopLiveTrackingUi(); return }
            liveTrackingStatus.text = "Jonli kuzatish yoqilgan — ${remainingSec / 60}:${(remainingSec % 60).toString().padStart(2, '0')} qoldi"
            countdownHandler.postDelayed(this, 1000)
        }
    }
    private val selectedCalendar = Calendar.getInstance()

    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || result[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            enableMyLocationOnMap()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_history)
        selectedDateText = findViewById(R.id.selectedDateText)
        btnPickDate = findViewById(R.id.btnPickDate)
        listTitle = findViewById(R.id.listTitle)
        emptyStateText = findViewById(R.id.emptyStateText)
        locationList = findViewById(R.id.locationList)
        btnLiveTracking = findViewById(R.id.btnLiveTracking)
        liveTrackingStatus = findViewById(R.id.liveTrackingStatus)
        btnLiveTracking.setOnClickListener { if (liveTrackingActive) stopLiveTrackingUi() else startLiveTrackingUi() }
        locationList.layoutManager = LinearLayoutManager(this)
        locationList.adapter = adapter
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction().replace(R.id.mapContainer, mapFragment).commit()
        mapFragment.getMapAsync(this)
        btnPickDate.setOnClickListener { showDatePicker() }
        updateDateLabel()
        loadDataForSelectedDay()
        bindBottomNav(NavTab.LOCATION)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        // Bola markerlari yashil rangda qoladi.
        renderMapForCurrentEvents()
        val fineGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineGranted || coarseGranted) enableMyLocationOnMap() else locationPermissionLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun enableMyLocationOnMap() {
        val fineGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return
        googleMap?.apply {
            // Google Maps'ning standart ko'k "Mening joylashuvim" nuqtasi ota-ona telefonini bildiradi.
            isMyLocationEnabled = true
            uiSettings.isMyLocationButtonEnabled = true
        }
    }

    private fun openDirections(event: LocationEvent) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${event.lat},${event.lng}")).apply { setPackage("com.google.android.apps.maps") }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent) else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${event.lat},${event.lng}")))
    }

    private fun showDatePicker() {
        DatePickerDialog(this, { _, year, month, day -> selectedCalendar.set(year, month, day); updateDateLabel(); loadDataForSelectedDay() }, selectedCalendar.get(Calendar.YEAR), selectedCalendar.get(Calendar.MONTH), selectedCalendar.get(Calendar.DAY_OF_MONTH)).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
    }

    private fun updateDateLabel() { selectedDateText.text = SimpleDateFormat("dd.MM.yyyy", Locale.US).format(selectedCalendar.time) }

    private fun loadDataForSelectedDay() {
        val dayStart = (selectedCalendar.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
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

    private fun renderMapForCurrentEvents() {
        val map = googleMap ?: return
        // Faqat bola tarix markerlari va yo'lini tozalaymiz; keyin standart ota-ona My Location qatlamini Android/Google Maps qayta chizadi.
        map.clear()
        markersByTimeMs.clear()
        if (currentEvents.isEmpty()) return
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val points = currentEvents.map { LatLng(it.lat, it.lng) }
        map.addPolyline(PolylineOptions().addAll(points).width(6f).color(0xFF228B22.toInt()))
        currentEvents.forEach { event ->
            val marker = map.addMarker(MarkerOptions().position(LatLng(event.lat, event.lng)).title("Bola • ${timeFmt.format(Date(event.vaqtMs))}").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
            if (marker != null) markersByTimeMs[event.vaqtMs] = marker
        }
        val boundsBuilder = com.google.android.gms.maps.model.LatLngBounds.Builder()
        points.forEach { boundsBuilder.include(it) }
        try { map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80)) } catch (_: IllegalStateException) { map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 14f)) }
    }

    private fun focusMapOn(event: LocationEvent) {
        googleMap?.let { map ->
            val target = LatLng(event.lat, event.lng)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 16f))
            markersByTimeMs[event.vaqtMs]?.showInfoWindow()
        }
        adapter.selectByEvent(event)
    }

    private fun startLiveTrackingUi() {
        liveTrackingActive = true
        liveTrackingUntilMs = System.currentTimeMillis() + 5 * 60_000L
        FirebaseRepo.requestLiveTracking(5)
        btnLiveTracking.text = "⏹ To'xtatish"
        countdownHandler.post(countdownRunnable)
        liveLocationListener = FirebaseRepo.listenLatestLocation { loc ->
            if (loc == null) return@listenLatestLocation
            val map = googleMap ?: return@listenLatestLocation
            val position = LatLng(loc.lat, loc.lng)
            liveMarker?.remove()
            liveMarker = map.addMarker(MarkerOptions().position(position).title("Bola • Jonli joylashuv").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)))
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 16f))
        }
    }

    private fun stopLiveTrackingUi() {
        liveTrackingActive = false
        FirebaseRepo.stopLiveTracking()
        countdownHandler.removeCallbacks(countdownRunnable)
        liveLocationListener?.remove(); liveLocationListener = null
        liveMarker?.remove(); liveMarker = null
        btnLiveTracking.text = "🔴 Jonli kuzatish"
        liveTrackingStatus.text = "Jonli kuzatish o'chirilgan"
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { moveTaskToBack(true) }

    override fun onDestroy() {
        super.onDestroy()
        countdownHandler.removeCallbacks(countdownRunnable)
        liveLocationListener?.remove()
    }
}
