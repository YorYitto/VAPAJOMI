package com.vapajomi.vapajomi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class VoiceProfileActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var instructionText: TextView
    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var clearButton: Button
    private lateinit var saveButton: Button
    private lateinit var progressBar: ProgressBar

    private lateinit var voiceProfileManager: VoiceProfileManager
    private lateinit var tts: TextToSpeech
    private val mainHandler = Handler(Looper.getMainLooper())

    private val recordedSamples = mutableListOf<ShortArray>()
    private var isRecording = false
    private var currentStep = 0
    private val totalSteps = VoiceProfileManager.ENROLLMENT_SAMPLES_REQUIRED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_profile)

        voiceProfileManager = VoiceProfileManager(this)

        titleText = findViewById(R.id.profileTitleText)
        instructionText = findViewById(R.id.profileInstructionText)
        statusText = findViewById(R.id.profileStatusText)
        recordButton = findViewById(R.id.profileRecordButton)
        clearButton = findViewById(R.id.profileClearButton)
        saveButton = findViewById(R.id.profileSaveButton)
        progressBar = findViewById(R.id.profileProgressBar)

        progressBar.max = totalSteps
        progressBar.progress = 0
        saveButton.isEnabled = false

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.forLanguageTag("es-ES")
                tts.speak(
                    "Registro de voz. Grabaras tres muestras de tu voz diciendo la frase de verificacion.",
                    TextToSpeech.QUEUE_FLUSH, null, null
                )
            }
        }

        updateUI()

        recordButton.setOnClickListener {
            if (!isRecording) requestRecordingPermission()
        }

        clearButton.setOnClickListener {
            confirmClearProfile()
        }

        saveButton.setOnClickListener {
            saveProfile()
        }
    }

    private fun updateUI() {
        if (currentStep >= totalSteps) {
            titleText.text = "Todas las muestras grabadas"
            instructionText.text = "Toca 'Guardar perfil' para activar el reconocimiento.\n\nDesde ahora solo tu voz podrá usar el asistente."
            recordButton.isEnabled = false
            saveButton.isEnabled = true
        } else {
            titleText.text = "Muestra ${currentStep + 1} de $totalSteps"
            instructionText.text = "Toca el botón y di claramente:\n\n\"Mi voz activa el asistente VAPAJOMI\"\n\nHabla con tu tono de voz normal, sin gritar."
            recordButton.isEnabled = true
            recordButton.text = if (currentStep == 0) "Grabar primera muestra" else "Grabar muestra ${currentStep + 1}"
        }
    }

    private fun requestRecordingPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        isRecording = true
        recordButton.isEnabled = false
        if (::tts.isInitialized) tts.stop()

        statusText.text = "Prepárate para hablar..."
        recordButton.text = "3..."
        mainHandler.postDelayed({
            recordButton.text = "2..."
            statusText.text = "Di: \"Mi voz activa el asistente VAPAJOMI\""
            mainHandler.postDelayed({
                recordButton.text = "1..."
                mainHandler.postDelayed({
                    recordButton.text = "Grabando... habla ahora"
                    statusText.text = "Grabando ${VoiceProfileManager.RECORD_DURATION_MS / 1000} segundos..."

                    Thread {
                        val audio = voiceProfileManager.recordAudioBlocking(VoiceProfileManager.RECORD_DURATION_MS)
                        mainHandler.post {
                            isRecording = false
                            when {
                                audio == null -> {
                                    statusText.text = "Error al acceder al microfono. Revisa los permisos."
                                    recordButton.isEnabled = true
                                    recordButton.text = "Reintentar"
                                }
                                !voiceProfileManager.hasActiveVoice(audio) -> {
                                    statusText.text = "No se detecto voz. Habla mas fuerte o acerca el telefono."
                                    recordButton.isEnabled = true
                                    recordButton.text = "Reintentar muestra ${currentStep + 1}"
                                }
                                else -> {
                                    recordedSamples.add(audio)
                                    currentStep++
                                    progressBar.progress = currentStep
                                    val doneMsg = when {
                                        currentStep < totalSteps -> "Muestra $currentStep guardada. Continua con la siguiente."
                                        else -> "Todas las muestras grabadas correctamente."
                                    }
                                    statusText.text = doneMsg
                                    tts.speak(doneMsg, TextToSpeech.QUEUE_FLUSH, null, null)
                                    updateUI()
                                }
                            }
                        }
                    }.start()
                }, 1000)
            }, 1000)
        }, 1000)
    }

    private fun saveProfile() {
        if (recordedSamples.size < 2) {
            Toast.makeText(this, "Necesitas al menos 2 muestras de voz", Toast.LENGTH_SHORT).show()
            return
        }

        saveButton.isEnabled = false
        saveButton.text = "Guardando..."

        Thread {
            val success = voiceProfileManager.enrollFromSamples(recordedSamples)
            mainHandler.post {
                if (success) {
                    val msg = "Perfil de voz guardado. Solo tu voz podra controlar VAPAJOMI."
                    statusText.text = msg
                    tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null)
                    mainHandler.postDelayed({ finish() }, 3500)
                } else {
                    Toast.makeText(this, "Error al guardar el perfil. Intenta nuevamente.", Toast.LENGTH_LONG).show()
                    saveButton.isEnabled = true
                    saveButton.text = "Guardar perfil de voz"
                }
            }
        }.start()
    }

    private fun confirmClearProfile() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Borrar perfil de voz")
            .setMessage("Esto eliminara el perfil guardado y cualquier persona podra usar el asistente. ¿Continuar?")
            .setPositiveButton("Borrar") { _, _ ->
                voiceProfileManager.clearProfile()
                recordedSamples.clear()
                currentStep = 0
                progressBar.progress = 0
                statusText.text = "Perfil eliminado."
                tts.speak("Perfil de voz eliminado.", TextToSpeech.QUEUE_FLUSH, null, null)
                updateUI()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else if (requestCode == 200) {
            Toast.makeText(this, "Necesitas dar permiso de microfono para registrar tu voz", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        voiceProfileManager.stopRecording()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
