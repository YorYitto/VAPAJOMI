package com.vapajomi.vapajomi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.util.*
import kotlin.math.sqrt

/**
 * VoiceProfileTrainingActivity - Entrena el perfil de voz del usuario
 * Captura 3 muestras de voz para crear una "huella de voz"
 */
class VoiceProfileTrainingActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var instructionsText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var recordButton: Button
    private lateinit var skipButton: Button

    private val mAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    private var currentSample = 0
    private val totalSamples = 3
    private val voiceSamples = mutableListOf<FloatArray>()

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_profile_training)

        tts = TextToSpeech(this, this)

        instructionsText = findViewById(R.id.instructionsText)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        recordButton = findViewById(R.id.recordButton)
        skipButton = findViewById(R.id.skipButton)

        progressBar.max = totalSamples
        progressBar.progress = 0

        recordButton.setOnClickListener {
            if (checkPermissions()) {
                startRecording()
            }
        }

        skipButton.setOnClickListener {
            skipTraining()
        }

        updateUI()
    }

    private fun checkPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return false
        }
        return true
    }

    private fun updateUI() {
        instructionsText.text = "Muestra ${currentSample + 1} de $totalSamples"
        statusText.text = "Por favor, di la siguiente frase:\n\n\"OK ASISTENTE, SOY YO\""
        recordButton.text = "GRABAR MUESTRA ${currentSample + 1}"
        progressBar.progress = currentSample
    }

    private fun startRecording() {
        recordButton.isEnabled = false
        statusText.text = "Grabando... Di: OK ASISTENTE, SOY YO"
        speak("Di: OK ASISTENTE, SOY YO")

        // Iniciar grabación después de que termine de hablar
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            recordVoiceSample()
        }, 2000)
    }

    private fun recordVoiceSample() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            val audioData = ShortArray(bufferSize)
            val features = mutableListOf<Float>()

            audioRecord?.startRecording()
            isRecording = true

            // Grabar por 2 segundos
            val startTime = System.currentTimeMillis()
            while (isRecording && System.currentTimeMillis() - startTime < 2000) {
                val read = audioRecord?.read(audioData, 0, bufferSize) ?: 0
                if (read > 0) {
                    // Extraer características de voz (energía, pitch, etc.)
                    val energy = calculateEnergy(audioData, read)
                    val zeroCrossings = calculateZeroCrossings(audioData, read)
                    features.add(energy)
                    features.add(zeroCrossings)
                }
            }

            stopRecording()

            // Guardar muestra
            if (features.isNotEmpty()) {
                voiceSamples.add(features.toFloatArray())
                currentSample++

                if (currentSample >= totalSamples) {
                    saveVoiceProfile()
                } else {
                    statusText.text = "Muestra grabada correctamente"
                    speak("Muestra grabada")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        recordButton.isEnabled = true
                        updateUI()
                    }, 1500)
                }
            } else {
                statusText.text = "Error: No se detectó voz. Intenta de nuevo"
                speak("No se detectó voz. Intenta de nuevo")
                recordButton.isEnabled = true
            }

        } catch (e: Exception) {
            statusText.text = "Error al grabar: ${e.message}"
            recordButton.isEnabled = true
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun calculateEnergy(audioData: ShortArray, length: Int): Float {
        var energy = 0.0
        for (i in 0 until length) {
            energy += (audioData[i] * audioData[i]).toDouble()
        }
        return sqrt(energy / length).toFloat()
    }

    private fun calculateZeroCrossings(audioData: ShortArray, length: Int): Float {
        var crossings = 0
        for (i in 1 until length) {
            if ((audioData[i] >= 0 && audioData[i - 1] < 0) ||
                (audioData[i] < 0 && audioData[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / length
    }

    private fun saveVoiceProfile() {
        statusText.text = "Guardando perfil de voz..."
        speak("Perfil de voz creado correctamente")

        val userId = mAuth.currentUser?.uid ?: return

        // Calcular promedio de características
        val avgFeatures = mutableListOf<Float>()
        if (voiceSamples.isNotEmpty()) {
            val featureCount = voiceSamples[0].size
            for (i in 0 until featureCount) {
                var sum = 0f
                for (sample in voiceSamples) {
                    if (i < sample.size) {
                        sum += sample[i]
                    }
                }
                avgFeatures.add(sum / voiceSamples.size)
            }
        }

        // Guardar en Firebase
        val voiceProfile = mapOf(
            "features" to avgFeatures,
            "samplesCount" to totalSamples,
            "createdAt" to System.currentTimeMillis()
        )

        database.reference.child("users").child(userId).child("voiceProfile")
            .setValue(voiceProfile)
            .addOnSuccessListener {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    finishTraining()
                }, 2000)
            }
            .addOnFailureListener {
                statusText.text = "Error al guardar perfil"
                recordButton.isEnabled = true
            }
    }

    private fun skipTraining() {
        // Marcar que se saltó el entrenamiento
        val userId = mAuth.currentUser?.uid ?: return

        database.reference.child("users").child(userId).child("voiceProfile")
            .child("skipped").setValue(true)
            .addOnSuccessListener {
                finishTraining()
            }
    }

    private fun finishTraining() {
        val intent = Intent(this, HomeActivity::class.java)
        startActivity(intent)
        finish()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "CO")
            speak("Bienvenido. Vamos a entrenar tu perfil de voz. Necesito que digas la frase tres veces.")
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }
}