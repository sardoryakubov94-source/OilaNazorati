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
            getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
                .putBoolean("is_parent", false).apply()
            updateScreenState()
        }
    }

    override fun onResume() {
        super.onResume()
        updateScreenState()
    }

    private fun updateScreenState() {
        val user = FirebaseAuth.getInstance().currentUser
        val wasParentLoggedIn = getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE)
            .getBoolean("is_parent", false)

        // MUHIM TUZATISH: avval bu yerda `AppSession.loggedInThisProcess`
        // (xotirada, jarayonga bog'liq bayroq) tekshirilardi — bu bayroq
        // HAR SAFAR ilova jarayoni qayta boshlanganda (masalan qurilma
        // qayta yoqilganda yoki tizim ilovani fondan tozalab qo'yganda)
        // avtomatik `false`ga qaytar edi, va shu sabab pastdagi shart
        // Google sessiyasi HALI TO'LIQ YAROQLI bo'lsa ham uni majburan
        // signOut() qilib yuborar edi — foydalanuvchi "yana Gmail orqali
        // kirish kerak" degan holatga tushib qolardi. Endi buning o'rniga
        // SharedPreferences'da DOIMIY saqlangan "is_parent" bayrog'i
        // tekshiriladi — bu jarayon qayta boshlansa ham yo'qolmaydi.
        if (user != null && !user.isAnonymous && wasParentLoggedIn) {
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
                getSharedPreferences("oila_nazorati", Context.MODE_PRIVATE).edit()
                    .putBoolean("is_parent", true)
                    .apply()
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
