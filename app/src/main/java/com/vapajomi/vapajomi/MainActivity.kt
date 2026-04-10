package com.vapajomi.vapajomi

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthMissingActivityForRecaptchaException

class MainActivity : AppCompatActivity() {

    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var loginButton: Button
    private lateinit var registerButton: Button
    private lateinit var mAuth: FirebaseAuth
    private var isLoggingIn = false
    private val loginTimeoutHandler = Handler(Looper.getMainLooper())
    private val loginTimeoutRunnable = Runnable {
        if (isLoggingIn) {
            setLoginInProgress(false)
            Toast.makeText(
                this,
                "El inicio de sesion esta tardando demasiado. Revisa tu conexion e intenta de nuevo",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseApp.initializeApp(this)
        mAuth = FirebaseAuth.getInstance()

        if (mAuth.currentUser != null) {
            goToHome()
            return
        }

        setContentView(R.layout.activity_main)

        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        registerButton = findViewById(R.id.registerButton)

        loginButton.setOnClickListener {
            loginUser()
        }

        registerButton.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun loginUser() {
        if (isLoggingIn) return

        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        emailEditText.error = null
        passwordEditText.error = null

        if (email.isEmpty()) {
            emailEditText.error = "El email es requerido"
            emailEditText.requestFocus()
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailEditText.error = "Ingresa un correo valido"
            emailEditText.requestFocus()
            return
        }

        if (password.isEmpty()) {
            passwordEditText.error = "La contrasena es requerida"
            passwordEditText.requestFocus()
            return
        }

        if (password.length < 6) {
            passwordEditText.error = "La contrasena debe tener minimo 6 caracteres"
            passwordEditText.requestFocus()
            return
        }

        if (!hasInternetConnection()) {
            Toast.makeText(this, "No hay conexion a internet", Toast.LENGTH_LONG).show()
            return
        }

        setLoginInProgress(true)
        loginTimeoutHandler.removeCallbacks(loginTimeoutRunnable)
        loginTimeoutHandler.postDelayed(loginTimeoutRunnable, 15000)

        try {
            mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    setLoginInProgress(false)

                    if (task.isSuccessful) {
                        Toast.makeText(this, "Login exitoso", Toast.LENGTH_SHORT).show()
                        goToHome()
                    } else {
                        handleLoginError(task.exception)
                    }
                }
                .addOnCanceledListener {
                    setLoginInProgress(false)
                    Toast.makeText(this, "El inicio de sesion fue cancelado", Toast.LENGTH_LONG).show()
                }
        } catch (exception: Exception) {
            setLoginInProgress(false)
            handleLoginError(exception)
        }
    }

    private fun setLoginInProgress(inProgress: Boolean) {
        isLoggingIn = inProgress
        if (!inProgress) {
            loginTimeoutHandler.removeCallbacks(loginTimeoutRunnable)
        }
        loginButton.isEnabled = !inProgress
        registerButton.isEnabled = !inProgress
        loginButton.text = if (inProgress) "Ingresando..." else "Iniciar Sesion"
    }

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onDestroy() {
        loginTimeoutHandler.removeCallbacks(loginTimeoutRunnable)
        super.onDestroy()
    }

    private fun handleLoginError(exception: Exception?) {
        val message = when (exception) {
            is FirebaseAuthInvalidUserException -> "No existe una cuenta con ese correo"
            is FirebaseAuthInvalidCredentialsException -> "Correo o contrasena incorrectos"
            is FirebaseNetworkException -> "Sin conexion. Verifica tu internet e intenta de nuevo"
            is FirebaseAuthMissingActivityForRecaptchaException ->
                "No se pudo validar el inicio de sesion. Intenta nuevamente"
            else -> exception?.localizedMessage ?: "No se pudo iniciar sesion"
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun goToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
