package uz.oilanazorati.parentcontrol.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import uz.oilanazorati.parentcontrol.databinding.ActivitySosCenterBinding

class SosCenterActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySosCenterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySosCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSosSendSection.setOnClickListener {
            startActivity(Intent(this, SosComposeActivity::class.java))
        }
        binding.btnParentSosPanel.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PARENT_SOS, true))
        }
    }
}