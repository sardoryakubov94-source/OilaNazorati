package uz.oilanazorati.parentcontrol.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
            Toast.makeText(this, "Google orqali kirish bekor qilindi", Toast.LENGTH_SHORT).show()
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

        binding.btnChildDevice.setOnClickListener {
            ensureAnonymousAuth()
            startActivity(Intent(this, ChildSetupActivity::class.java))
        }
        binding.btnParentGoogleSignIn.setOnClickListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }

        updateSignedInUi()
    }

    override fun onResume() {
        super.onResume()
        updateSignedInUi()
    }

    private fun updateSignedInUi() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null && !user.isAnonymous) {
            val label = user.displayName ?: user.email ?: "Ota-ona"
            binding.signedInAsText.text = "$label sifatida kirilgan - Ota-ona panelini ochish uchun bosing"
            binding.btnParentGoogleSignIn.text = "Ota-ona panelini ochish"
            binding.btnParentGoogleSignIn.setOnClickListener {
                startActivity(Intent(this, ParentDashboardActivity::class.java))
            }
        } else {
            binding.signedInAsText.text = ""
            binding.btnParentGoogleSignIn.text = "Google orqali kirish (Ota-ona)"
            binding.btnParentGoogleSignIn.setOnClickListener {
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            }
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
                startActivity(Intent(this, ParentDashboardActivity::class.java))
            }
            .addOnFailureListener {
                Toast.makeText(this, "Kirishda xato yuz berdi, qayta urinib ko'ring", Toast.LENGTH_SHORT).show()
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

    private fun ensureAnonymousAuth() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
        }
    }
}
