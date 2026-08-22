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
import uz.oilanazorati.parentcontrol.util.AppSession

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
            AppSession.loggedInThisProcess = false
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
        if (user != null && !user.isAnonymous && AppSession.loggedInThisProcess) {
            binding.loginSection.visibility = View.GONE
            binding.roleSection.visibility = View.VISIBLE
            val label = user.displayName ?
