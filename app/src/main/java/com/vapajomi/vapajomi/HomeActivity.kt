package com.vapajomi.vapajomi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.*

class HomeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var mAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var tts: TextToSpeech

    private lateinit var welcomeText: TextView
    private lateinit var logoutButton: Button

    private val PERMISSIONS_REQUEST_CODE = 100

    // Lista de todos los permisos necesarios
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Inicializar Firebase
        mAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        // Inicializar Text-to-Speech
        tts = TextToSpeech(this, this)

        // Conectar vistas
        welcomeText = findViewById(R.id.welcomeText)
        logoutButton = findViewById(R.id.logoutButton)

        // Obtener nombre del usuario
        loadUserName()

        // Configurar botón de cerrar sesión
        logoutButton.setOnClickListener {
            logout()
        }

        // Pedir permisos
        checkAndRequestPermissions()
    }

    private fun loadUserName() {
        val userId = mAuth.currentUser?.uid

        userId?.let {
            database.reference.child("users").child(it)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val name = snapshot.child("name").getValue(String::class.java)
                        if (name != null) {
                            welcomeText.text = "Bienvenido, $name"
                            speak("Bienvenido $name")
                        } else {
                            val email = mAuth.currentUser?.email
                            welcomeText.text = "Bienvenido"
                            speak("Bienvenido")
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        welcomeText.text = "Bienvenido"
                        speak("Bienvenido")
                    }
                })
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            // Explicar por qué necesitamos los permisos
            AlertDialog.Builder(this)
                .setTitle("Permisos Necesarios")
                .setMessage("VAPAJOMI necesita acceso a:\n\n" +
                        "• Micrófono: Para comandos de voz\n" +
                        "• Cámara: Para detectar obstáculos\n" +
                        "• Contactos: Para hacer llamadas\n" +
                        "• Teléfono: Para realizar llamadas\n" +
                        "• SMS: Para enviar mensajes\n" +
                        "• Ubicación: Para navegación")
                .setPositiveButton("Aceptar") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest.toTypedArray(),
                        PERMISSIONS_REQUEST_CODE
                    )
                }
                .setNegativeButton("Cancelar") { _, _ ->
                    speak("Los permisos son necesarios para el funcionamiento de la aplicación")
                }
                .show()
        } else {
            speak("Todos los permisos están activos. ¿En qué puedo ayudarte?")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                speak("Permisos concedidos. Estoy listo para ayudarte")
            } else {
                speak("Algunos permisos fueron denegados. Algunas funciones podrían no estar disponibles")
            }
        }
    }

    private fun logout() {
        speak("Cerrando sesión")

        // Esperar a que termine de hablar antes de cerrar sesión
        android.os.Handler().postDelayed({
            mAuth.signOut()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1500)
    }

    // Text-to-Speech
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("es", "ES"))

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Idioma no soportado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}