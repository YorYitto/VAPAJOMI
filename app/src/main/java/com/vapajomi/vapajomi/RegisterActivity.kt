package com.vapajomi.vapajomi

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class RegisterActivity : AppCompatActivity() {

    private lateinit var nameEditText: TextInputEditText
    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var confirmPasswordEditText: TextInputEditText
    private lateinit var registerButton: Button
    private lateinit var backToLoginButton: Button

    private lateinit var mAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private var isRegistering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        mAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        nameEditText = findViewById(R.id.nameEditText)
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        confirmPasswordEditText = findViewById(R.id.confirmPasswordEditText)
        registerButton = findViewById(R.id.registerButton)
        backToLoginButton = findViewById(R.id.backToLoginButton)

        registerButton.setOnClickListener {
            registerUser()
        }

        backToLoginButton.setOnClickListener {
            finish()
        }
    }

    private fun registerUser() {
        if (isRegistering) return

        val name = nameEditText.text.toString().trim()
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()
        val confirmPassword = confirmPasswordEditText.text.toString().trim()

        clearErrors()

        if (name.isEmpty()) {
            nameEditText.error = "El nombre es requerido"
            nameEditText.requestFocus()
            return
        }

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

        if (password != confirmPassword) {
            confirmPasswordEditText.error = "Las contrasenas no coinciden"
            confirmPasswordEditText.requestFocus()
            return
        }

        setRegisterInProgress(true)
        mAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    saveUserData(name, email)
                } else {
                    setRegisterInProgress(false)
                    Toast.makeText(
                        this,
                        "No se pudo crear la cuenta: ${task.exception?.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun saveUserData(name: String, email: String) {
        val userId = mAuth.currentUser?.uid
        if (userId == null) {
            setRegisterInProgress(false)
            Toast.makeText(this, "No se pudo obtener el usuario creado", Toast.LENGTH_LONG).show()
            return
        }

        val userMap = hashMapOf(
            "name" to name,
            "email" to email
        )

        database.reference.child("users").child(userId).setValue(userMap)
            .addOnSuccessListener {
                setRegisterInProgress(false)
                Toast.makeText(this, "Cuenta creada exitosamente", Toast.LENGTH_SHORT).show()

                val intent = Intent(this, HomeActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { error ->
                setRegisterInProgress(false)
                Toast.makeText(
                    this,
                    "Error al guardar datos: ${error.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun clearErrors() {
        nameEditText.error = null
        emailEditText.error = null
        passwordEditText.error = null
        confirmPasswordEditText.error = null
    }

    private fun setRegisterInProgress(inProgress: Boolean) {
        isRegistering = inProgress
        registerButton.isEnabled = !inProgress
        backToLoginButton.isEnabled = !inProgress
        registerButton.text = if (inProgress) "Creando cuenta..." else "Registrarse"
    }
}
