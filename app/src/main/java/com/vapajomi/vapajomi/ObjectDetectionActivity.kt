package com.vapajomi.vapajomi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ObjectDetectionActivity - Reconocimiento de objetos en tiempo real
 * Usa ML Kit de Google para identificar objetos, personas y cosas
 */
class ObjectDetectionActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var tts: TextToSpeech
    private lateinit var cameraExecutor: ExecutorService

    private var lastSpokenObject = ""
    private var lastSpokenTime = 0L
    private val spanishLocale = Locale("es", "CO")

    // Traducción de objetos comunes inglés -> español
    private val objectTranslations = mapOf(
        "person" to "persona",
        "people" to "personas",
        "face" to "cara",
        "hand" to "mano",
        "phone" to "celular",
        "mobile phone" to "celular",
        "cell phone" to "celular",
        "laptop" to "computadora portátil",
        "computer" to "computadora",
        "keyboard" to "teclado",
        "mouse" to "ratón",
        "book" to "libro",
        "chair" to "silla",
        "table" to "mesa",
        "desk" to "escritorio",
        "door" to "puerta",
        "window" to "ventana",
        "bottle" to "botella",
        "cup" to "taza",
        "glass" to "vaso",
        "plate" to "plato",
        "spoon" to "cuchara",
        "fork" to "tenedor",
        "knife" to "cuchillo",
        "car" to "carro",
        "vehicle" to "vehículo",
        "bicycle" to "bicicleta",
        "motorcycle" to "motocicleta",
        "bus" to "bus",
        "truck" to "camión",
        "traffic light" to "semáforo",
        "stop sign" to "señal de pare",
        "building" to "edificio",
        "house" to "casa",
        "tree" to "árbol",
        "plant" to "planta",
        "flower" to "flor",
        "grass" to "pasto",
        "sky" to "cielo",
        "cloud" to "nube",
        "sun" to "sol",
        "dog" to "perro",
        "cat" to "gato",
        "bird" to "pájaro",
        "animal" to "animal",
        "food" to "comida",
        "fruit" to "fruta",
        "apple" to "manzana",
        "banana" to "banano",
        "orange" to "naranja",
        "water" to "agua",
        "drink" to "bebida",
        "clothing" to "ropa",
        "shirt" to "camisa",
        "pants" to "pantalones",
        "shoes" to "zapatos",
        "hat" to "sombrero",
        "bag" to "bolsa",
        "backpack" to "mochila",
        "clock" to "reloj",
        "watch" to "reloj",
        "glasses" to "gafas",
        "sunglasses" to "gafas de sol",
        "bed" to "cama",
        "pillow" to "almohada",
        "blanket" to "cobija",
        "tv" to "televisor",
        "television" to "televisor",
        "remote" to "control remoto",
        "light" to "luz",
        "lamp" to "lámpara"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_object_detection)

        previewView = findViewById(R.id.previewView)
        resultText = findViewById(R.id.resultText)

        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ObjectAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                resultText.text = "Error al iniciar cámara"
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private inner class ObjectAnalyzer : ImageAnalysis.Analyzer {
        private val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.7f) // 70% de confianza mínima
                .build()
        )

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees
                )

                labeler.process(image)
                    .addOnSuccessListener { labels ->
                        if (labels.isNotEmpty()) {
                            val topLabel = labels[0]
                            val englishName = topLabel.text.lowercase()
                            val spanishName = objectTranslations[englishName] ?: englishName
                            val confidence = (topLabel.confidence * 100).toInt()

                            runOnUiThread {
                                resultText.text = "Veo: $spanishName ($confidence% seguro)"

                                // Hablar solo si es un objeto diferente o pasaron 5 segundos
                                val currentTime = System.currentTimeMillis()
                                if (spanishName != lastSpokenObject ||
                                    currentTime - lastSpokenTime > 5000) {
                                    speakObject(spanishName, confidence, labels.size)
                                    lastSpokenObject = spanishName
                                    lastSpokenTime = currentTime
                                }
                            }
                        } else {
                            runOnUiThread {
                                resultText.text = "No veo objetos claros"
                            }
                        }
                    }
                    .addOnFailureListener {
                        runOnUiThread {
                            resultText.text = "Error al analizar imagen"
                        }
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    private fun speakObject(objectName: String, confidence: Int, totalObjects: Int) {
        val message = when {
            totalObjects > 3 -> "Veo varios objetos. El más claro es $objectName"
            confidence > 90 -> "Veo $objectName"
            confidence > 70 -> "Creo que veo $objectName"
            else -> "Posiblemente es $objectName"
        }

        speak(message)
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                resultText.text = "Permisos de cámara denegados"
                finish()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(spanishLocale)
            speak("Cámara lista. Apunta a un objeto para identificarlo")
        }
    }

    private fun speak(text: String) {
        if (::tts.isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}