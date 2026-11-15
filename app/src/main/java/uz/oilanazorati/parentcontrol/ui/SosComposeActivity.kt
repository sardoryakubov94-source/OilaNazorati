package uz.oilanazorati.parentcontrol.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import uz.oilanazorati.parentcontrol.databinding.ActivitySosComposeBinding
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo
import uz.oilanazorati.parentcontrol.util.SosHelper
import uz.oilanazorati.parentcontrol.util.SosTimerManager

class SosComposeActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySosComposeBinding
    private var selectedLat: Double? = null
    private var selectedLng: Double? = null
    private var selectedLabel: String? = null
    private var pendingDelay = 0L

    private val mapPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            selectedLat = result.data?.getDoubleExtra(SosLocationPickerActivity.EXTRA_LAT, 0.0)
            selectedLng = result.data?.getDoubleExtra(SosLocationPickerActivity.EXTRA_LNG, 0.0)
            selectedLabel = result.data?.getStringExtra(SosLocationPickerActivity.EXTRA_LABEL)
            binding.sosSelectedLocation.text = "Xaritadan tanlangan: ${selectedLat}, ${selectedLng}"
        }
    }

    private val sendPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.SEND_SMS] == true) sendNow()
        else showStatus("SMS yuborish ruxsati berilmadi. SOS SMS yuborilmadi.")
    }

    private val timerPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.SEND_SMS] == true &&
            (android.os.Build.VERSION.SDK_INT < 33 || result[Manifest.permission.POST_NOTIFICATIONS] == true)) {
            startTimer(pendingDelay)
        } else showStatus("SOS taymeri uchun SMS va bildirishnoma ruxsatlari kerak.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySosComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSosUseCurrentLocation.setOnClickListener {
            selectedLat = null
            selectedLng = null
            selectedLabel = null
            binding.sosSelectedLocation.text = "Yuborish vaqtida telefonning joriy GPS joylashuvi olinadi."
        }
        binding.btnSosChooseMapLocation.setOnClickListener {
            mapPicker.launch(Intent(this, SosLocationPickerActivity::class.java))
        }
        binding.btnSosSendNow.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                sendPermissions.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION))
            } else sendNow()
        }
        binding.btnSosSetTimer.setOnClickListener { showTimerDialog() }
    }

    private fun childDetails(): Triple<String, String, String>? {
        val prefs = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
        val family = prefs.getString("family_code", null)
        val child = prefs.getString("child_id", null) ?: FirebaseRepo.childId
        val name = prefs.getString("child_name", null)?.takeIf { it.isNotBlank() } ?: "Bola"
        if (family.isNullOrBlank() || child.isNullOrBlank()) {
            showStatus("Avval qurilmani ota-ona oilasiga ulang.")
            return null
        }
        return Triple(family, child, name)
    }

    private fun note() = binding.inputSosNote.text?.toString()?.trim().orEmpty().take(500)
    private fun placeLabel() = binding.inputSosPlaceLabel.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: selectedLabel

    private fun sendNow() {
        val details = childDetails() ?: return
        binding.btnSosSendNow.isEnabled = false
        SosHelper.send(this, details.third, null, note(), selectedLat, selectedLng, placeLabel()) { message ->
            runOnUiThread {
                binding.btnSosSendNow.isEnabled = true
                showStatus(message)
            }
        }
    }

    private fun showTimerDialog() {
        val activeId = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).getString("sos_timer_id", null)
        if (!activeId.isNullOrBlank()) {
            AlertDialog.Builder(this).setTitle("Faol SOS taymeri")
                .setMessage("Faol taymerni bekor qilasizmi?")
                .setPositiveButton("Taymerni bekor qilish") { _, _ ->
                    SosTimerManager.cancel(this) { _, message -> showStatus(message) }
                }.setNegativeButton("Yopish", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("SOS taymerini sozlash")
            .setItems(arrayOf("15 daqiqadan keyin", "30 soatdan keyin", "Boshqa vaqtni kiritish")) { _, which ->
                when (which) {
                    0 -> requestTimer(15L)
                    1 -> requestTimer(1800L)
                    else -> {
                        val input = EditText(this).apply { hint = "Daqiqa"; inputType = InputType.TYPE_CLASS_NUMBER }
                        AlertDialog.Builder(this).setTitle("Taymer muddati")
                            .setMessage("1 daqiqadan 30 kungacha kiriting.")
                            .setView(input)
                            .setPositiveButton("Boshlash") { _, _ -> requestTimer(input.text?.toString()?.toLongOrNull() ?: 0L) }
                            .setNegativeButton("Bekor qilish", null).show()
                    }
                }
            }.setNegativeButton("Yopish", null).show()
    }

    private fun requestTimer(minutes: Long) {
        if (minutes !in 1L..(30L * 24L * 60L)) {
            showStatus("1 daqiqadan 30 kungacha vaqt kiriting.")
            return
        }
        if (childDetails() == null) return
        pendingDelay = minutes
        val needsSms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED
        val needsNotif = android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsSms || needsNotif) {
            val permissions = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            timerPermissions.launch(permissions.toTypedArray())
        } else startTimer(minutes)
    }

    private fun startTimer(minutes: Long) {
        val d = childDetails() ?: return
        SosTimerManager.start(this, d.first, d.second, d.third, minutes, note(), selectedLat, selectedLng, placeLabel()) { _, message ->
            runOnUiThread { showStatus(message) }
        }
    }

    private fun showStatus(message: String) {
        binding.sosSendStatus.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}