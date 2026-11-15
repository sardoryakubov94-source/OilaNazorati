package uz.oilanazorati.parentcontrol.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import uz.oilanazorati.parentcontrol.R

class SosLocationPickerActivity : AppCompatActivity(), OnMapReadyCallback {
    private var map: GoogleMap? = null
    private var marker: Marker? = null
    private var selected: LatLng? = null
    private var locationPermissionRequested = false

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        enableLocationIfAllowed()
        moveToCurrentLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sos_location_picker)
        val fragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction().replace(R.id.sosMapContainer, fragment).commit()
        fragment.getMapAsync(this)
        findViewById<Button>(R.id.btnConfirmSosMapPoint).setOnClickListener {
            val point = selected
            if (point == null) {
                Toast.makeText(this, "Xaritada kerakli joyni bosing.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_LAT, point.latitude)
                putExtra(EXTRA_LNG, point.longitude)
                putExtra(EXTRA_LABEL, "Xaritadan tanlangan manzil")
            })
            finish()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        googleMap.uiSettings.isZoomControlsEnabled = true
        googleMap.setOnMapClickListener { point ->
            selected = point
            marker?.remove()
            marker = googleMap.addMarker(MarkerOptions().position(point).title("SOS manzili"))
        }
        enableLocationIfAllowed()
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation && !locationPermissionRequested) {
            locationPermissionRequested = true
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        moveToCurrentLocation()
    }

    private fun enableLocationIfAllowed() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) runCatching { map?.isMyLocationEnabled = true }
    }

    private fun moveToCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(41.3, 69.25), 5.5f))
            return
        }
        runCatching {
            LocationServices.getFusedLocationProviderClient(this).lastLocation
                .addOnSuccessListener { location ->
                    if (location != null && selected == null) {
                        val point = LatLng(location.latitude, location.longitude)
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 15f))
                    } else if (selected == null) {
                        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(41.3, 69.25), 5.5f))
                    }
                }
        }
    }

    companion object {
        const val EXTRA_LAT = "sos_map_lat"
        const val EXTRA_LNG = "sos_map_lng"
        const val EXTRA_LABEL = "sos_map_label"
    }
}