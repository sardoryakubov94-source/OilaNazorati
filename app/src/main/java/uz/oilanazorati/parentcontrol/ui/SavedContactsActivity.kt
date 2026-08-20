package uz.oilanazorati.parentcontrol.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import uz.oilanazorati.parentcontrol.databinding.ActivitySavedContactsBinding
import uz.oilanazorati.parentcontrol.repo.FirebaseRepo

/**
 * Ota-ona ekrani: bola qurilmasida ISM BILAN saqlangan kontaktlar
 * ro'yxatini, har birining rangini (timeline/statistikadagi rang bilan
 * bir xil) ko'rsatadi. Shu orqali ota-ona "bu ko'k rang — Onam bilan"
 * deb bilib oladi. Saqlanmagan (notanish) raqamlar bu yerda ko'rinmaydi.
 */
class SavedContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySavedContactsBinding
    private val adapter = SavedContactsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySavedContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.savedContactsList.layoutManager = LinearLayoutManager(this)
        binding.savedContactsList.adapter = adapter

        FirebaseRepo.listenSavedContacts { contacts ->
            adapter.setContacts(contacts)
        }
    }
}
