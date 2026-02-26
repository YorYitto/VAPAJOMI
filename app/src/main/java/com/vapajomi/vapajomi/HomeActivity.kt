package com.vapajomi.vapajomi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.Locale

class HomeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var mAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var tts: TextToSpeech
    private lateinit var voiceCommandListener: VoiceCommandListener

    private lateinit var welcomeText: TextView
    private lateinit var voiceResultText: TextView
    private lateinit var listenButton: Button
    private lateinit var logoutButton: Button

    private val permissionsRequestCode = 100

    private val requiredPermissions = arrayOf(
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

        mAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        tts = TextToSpeech(this, this)

        welcomeText = findViewById(R.id.welcomeText)
        voiceResultText = findViewById(R.id.voiceResultText)
        listenButton = findViewById(R.id.listenButton)
        logoutButton = findViewById(R.id.logoutButton)

        voiceCommandListener = VoiceCommandListener(
            context = this,
            onResult = { text ->
                runOnUiThread {
                    voiceResultText.text = "Escuche: $text"
                    speak("Escuche: $text")
                }
            },
            onError = { message ->
                runOnUiThread {
                    voiceResultText.text = message
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                }
            }
        )

        loadUserName()

        listenButton.setOnClickListener {
            startVoiceListening()
        }

        logoutButton.setOnClickListener {
            logout()
        }

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

    private fun startVoiceListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                permissionsRequestCode
            )
            return
        }

        voiceResultText.text = "Escuchando..."
        voiceCommandListener.startListening()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        for (permission in requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Permisos necesarios")
                .setMessage(
                    "VAPAJOMI necesita acceso a:\n\n" +
                        "- Microfono: para comandos de voz\n" +
                        "- Camara: para detectar obstaculos\n" +
                        "- Contactos: para hacer llamadas\n" +
                        "- Telefono: para realizar llamadas\n" +
                        "- SMS: para enviar mensajes\n" +
                        "- Ubicacion: para navegacion"
                )
                .setPositiveButton("Aceptar") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest.toTypedArray(),
                        permissionsRequestCode
                    )
                }
                .setNegativeButton("Cancelar") { _, _ ->
                    speak("Los permisos son necesarios para el funcionamiento de la aplicacion")
                }
                .show()
        } else {
            speak("Todos los permisos estan activos. En que puedo ayudarte?")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == permissionsRequestCode) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                speak("Permisos concedidos. Estoy listo para ayudarte")
            } else {
                speak("Algunos permisos fueron denegados. Algunas funciones podrian no estar disponibles")
            }
        }
    }

    private fun logout() {
        speak("Cerrando sesion")

        Handler(Looper.getMainLooper()).postDelayed({
            mAuth.signOut()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1500)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("es", "ES"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Idioma no soportado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        voiceCommandListener.destroy()

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        super.onDestroy()
    }
}
