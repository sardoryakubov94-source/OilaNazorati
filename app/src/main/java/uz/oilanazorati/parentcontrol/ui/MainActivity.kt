package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import uz.oilanazorati.parentcontrol.R
import uz.oilanazorati.parentcontrol.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var googleSignInClient: GoogleSignInClient

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            firebaseAuthWithGoogle(account.idToken)
        } catch (e: ApiException) {
            binding.loginStatusText.text = "Xato kodi: " + e.statusCode + " - " + e.message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // XAVFSIZLIK TALABI: ota-ona sozlamalariga (Bu farzandimning
        // telefoni / Ota-ona panelini ochish) kirish uchun ilova HAR
        // SAFAR yangidan ochilganda qayta Google orqali kirish talab
        // qilinishi kerak — bu bola tasodifan ilovani ochib qolsa,
        // avvalgi keshlangan sessiya orqali sozlamalarga kira olmasligi
        // uchun ataylab shunday qilingan.
        //
        // MUHIM: bu FAQAT Google (anonim bo'lmagan) sessiyaga tegishli.
        // Agar bu aynan BOLA qurilmasi bo'lib, u allaqachon anonim
        // identifikator bilan ulangan bo'lsa (ChildSetupActivity orqali),
        // uni signOut QILMAYMIZ — aks holda bola qurilmasining ma'lumot
        // yuborish huquqi butunlay yo'qolib qoladi. Faqat shu Activity
        // yangidan yaratilganda (ilova sovuq holatda ochilganda)
        // ishlaydi — ichki ekranlar orasida orqaga qaytishda emas.
        val cachedUser = FirebaseAuth.getInstance().currentUser
        if (cachedUser != null && !cachedUser.isAnonymous) {
            FirebaseAuth.getInstance().signOut()
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        binding.btnGoogleSignIn.setOnClickListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
        binding.btnChildDevice.setOnClickListener {
            startActivity(Intent(this, ChildSetupActivity::class.java))
        }
        binding.btnOpenParentDashboard.setOnClickListener {
            startActivity(Intent(this, ParentDashboardActivity::class.java))
        }
        binding.btnSignOut.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            googleSignInClient.signOut()
            updateScreenState()
        }

        updateScreenState()
    }

    override fun onResume() {
        super.onResume()
        updateScreenState()
    }

    private fun updateScreenState() {
        // Bu yerda "is_parent" kabi doimiy saqlanadigan bayroq ATAYLAB
        // ishlatilmaydi — ekran holati faqat SHU Activity instance
        // hayoti davomida (onCreate'da signOut qilingandan keyin)
        // xotiradagi Firebase sessiyasiga qarab aniqlanadi. Shu tufayli
        // ilova sovuq holatda qayta ochilsa, avvalgi "kirilgan edi"
        // holati saqlanib qolmaydi — har safar qayta Google orqali
        // kirish so'raladi.
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null && !user.isAnonymous) {
            binding.loginSection.visibility = View.GONE
            binding.roleSection.visibility = View.VISIBLE
            val label = user.displayName ?: user.email ?: "Ota-ona"
            binding.signedInAsText.text = label + " sifatida kirilgan"
        } else {
            binding.loginSection.visibility = View.VISIBLE
            binding.roleSection.visibility = View.GONE
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String?) {
        if (idToken == null) return
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        FirebaseAuth.getInstance().signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val user = result.user ?: return@addOnSuccessListener
                saveParentProfile(user.uid, user.displayName.orEmpty(), user.email.orEmpty())
                updateScreenState()
            }
            .addOnFailureListener {
                binding.loginStatusText.text = "Kirishda xato yuz berdi, qayta urinib ko'ring"
            }
    }

    private fun saveParentProfile(uid: String, name: String, email: String) {
        FirebaseFirestore.getInstance()
            .collection("parents").document(uid)
            .set(
                mapOf(
                    "ismi" to name,
                    "email" to email,
                    "oxirgiKirishMs" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            )
    }
}
