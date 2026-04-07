package com.vapajomi.vapajomi

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabel
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ObjectDetectionActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var openRouteButton: Button
    private lateinit var closeButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech
    private lateinit var labeler: ImageLabeler
    private lateinit var objectDetector: ObjectDetector

    private var isProcessing = false
    private var lastAnalysisTimestamp = 0L
    private var lastSpokenKey = ""
    private var lastSpokenAt = 0L
    private var lastStatus = "Preparando camara..."
    private var navigationDestination: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_object_detection)

        viewFinder = findViewById(R.id.viewFinder)
        statusText = findViewById(R.id.statusText)
        hintText = findViewById(R.id.hintText)
        openRouteButton = findViewById(R.id.openRouteButton)
        closeButton = findViewById(R.id.closeButton)

        navigationDestination = intent.getStringExtra(EXTRA_NAVIGATION_DESTINATION)?.trim()?.takeIf { it.isNotEmpty() }

        cameraExecutor = Executors.newSingleThreadExecutor()
        objectDetector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        )
        labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(MIN_CONFIDENCE)
                .build()
        )

        initTTS()
        setupActions()
        updateInitialTexts()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun initTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.forLanguageTag("es-ES")
                speakOnce(
                    key = if (navigationDestination == null) "start_scan" else "start_assisted_navigation",
                    text = if (navigationDestination == null) {
                        "Detector con ML Kit activo. Te avisare si encuentro obstaculos."
                    } else {
                        "Modo navegacion asistida con ML Kit activo. Monitoreando obstaculos rumbo a $navigationDestination."
                    },
                    minIntervalMs = 0L
                )
            }
        }
    }

    private fun setupActions() {
        openRouteButton.setOnClickListener {
            openRouteInGoogleMaps()
        }

        closeButton.setOnClickListener {
            finish()
        }

        openRouteButton.isEnabled = navigationDestination != null
        openRouteButton.alpha = if (navigationDestination != null) 1f else 0.45f
    }

    private fun updateInitialTexts() {
        hintText.text = if (navigationDestination == null) {
            "ML Kit esta analizando el entorno para identificar objetos y obstaculos."
        } else {
            "ML Kit esta monitoreando obstaculos mientras te diriges a $navigationDestination."
        }
        statusText.text = lastStatus
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                showStatus("No pude iniciar la camara.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (isProcessing || now - lastAnalysisTimestamp < ANALYSIS_INTERVAL_MS) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessing = true
        lastAnalysisTimestamp = now

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val frameWidth = imageProxy.width
        val frameHeight = imageProxy.height

        val detectionTask = objectDetector.process(image)
        val labelingTask = labeler.process(image)

        Tasks.whenAllComplete(detectionTask, labelingTask)
            .addOnSuccessListener {
                val objects = if (detectionTask.isSuccessful) detectionTask.result ?: emptyList() else emptyList()
                val labels = if (labelingTask.isSuccessful) labelingTask.result ?: emptyList() else emptyList()
                handleDetection(objects, labels, frameWidth, frameHeight)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "ML Kit analysis failed", error)
            }
            .addOnCompleteListener {
                isProcessing = false
                imageProxy.close()
            }
    }

    private fun handleDetection(
        objects: List<DetectedObject>,
        labels: List<ImageLabel>,
        frameWidth: Int,
        frameHeight: Int
    ) {
        val dominantObject = objects.maxByOrNull { boundingBoxArea(it.boundingBox) }
        val objectDetection = dominantObject?.let { detectedObject ->
            buildDetectionFromObject(detectedObject, frameWidth, frameHeight)
        }

        val labelDetection = labels
            .sortedByDescending { it.confidence }
            .mapNotNull { mapImageLabel(it) }
            .maxWithOrNull(
                compareBy<DetectionResult> { it.alertLevel.priority }
                    .thenBy { it.confidence }
            )

        val detection = chooseDetection(objectDetection, labelDetection)
        if (detection == null) {
            showStatus("Sin deteccion clara. Sigue moviendo la camara lentamente.")
            return
        }

        val confidencePct = (detection.confidence * 100).toInt()
        val status = when (detection.alertLevel) {
            AlertLevel.HIGH -> "ALERTA: ${detection.statusLabel} ${detection.distanceLabel}. Confianza $confidencePct%"
            AlertLevel.MEDIUM -> "Precaucion: ${detection.statusLabel} ${detection.distanceLabel}. Confianza $confidencePct%"
            AlertLevel.LOW -> "Detectado: ${detection.statusLabel} ${detection.distanceLabel}. Confianza $confidencePct%"
        }
        showStatus(status)

        val speech = when (detection.alertLevel) {
            AlertLevel.HIGH -> "Atencion. ${detection.speechLabel} ${detection.speechDistanceLabel}. Reduce la velocidad."
            AlertLevel.MEDIUM -> "Precaucion. Detecte ${detection.speechLabel} ${detection.speechDistanceLabel}."
            AlertLevel.LOW -> "Veo ${detection.speechLabel}."
        }

        val speakInterval = when (detection.alertLevel) {
            AlertLevel.HIGH -> OBSTACLE_SPEAK_INTERVAL_MS
            AlertLevel.MEDIUM -> NOTICE_SPEAK_INTERVAL_MS
            AlertLevel.LOW -> GENERAL_SPEAK_INTERVAL_MS
        }

        val speechKey = buildString {
            append(detection.alertLevel.name)
            append(':')
            append(detection.statusLabel)
            detection.trackingId?.let {
                append(':')
                append(it)
            }
        }

        speakOnce(
            key = speechKey,
            text = speech,
            minIntervalMs = speakInterval
        )
    }

    private fun chooseDetection(
        objectDetection: DetectionResult?,
        labelDetection: DetectionResult?
    ): DetectionResult? {
        if (objectDetection == null) return labelDetection
        if (labelDetection == null) return objectDetection

        return when {
            objectDetection.alertLevel.priority > labelDetection.alertLevel.priority -> objectDetection
            labelDetection.alertLevel.priority > objectDetection.alertLevel.priority -> labelDetection
            objectDetection.confidence >= labelDetection.confidence -> objectDetection
            else -> labelDetection
        }
    }

    private fun buildDetectionFromObject(
        detectedObject: DetectedObject,
        frameWidth: Int,
        frameHeight: Int
    ): DetectionResult {
        val ratio = calculateAreaRatio(detectedObject.boundingBox, frameWidth, frameHeight)
        val proximity = proximityFromRatio(ratio)
        val objectLabel = detectedObject.labels
            .mapNotNull { mapObjectLabel(it.text, it.confidence) }
            .maxByOrNull { it.confidence }

        val baseResult = objectLabel ?: defaultObstacleResult(proximity)
        val escalatedLevel = escalateAlertLevel(baseResult.alertLevel, proximity)

        return baseResult.copy(
            alertLevel = escalatedLevel,
            confidence = maxOf(baseResult.confidence, proximity.baseConfidence),
            distanceLabel = proximity.statusLabel,
            speechDistanceLabel = proximity.speechLabel,
            trackingId = detectedObject.trackingId
        )
    }

    private fun defaultObstacleResult(proximity: ProximityLevel): DetectionResult {
        val level = if (proximity == ProximityLevel.NEAR) AlertLevel.HIGH else AlertLevel.MEDIUM
        return DetectionResult(
            statusLabel = "objeto detectado",
            speechLabel = "un objeto frente a ti",
            alertLevel = level,
            confidence = proximity.baseConfidence,
            distanceLabel = proximity.statusLabel,
            speechDistanceLabel = proximity.speechLabel
        )
    }

    private fun escalateAlertLevel(level: AlertLevel, proximity: ProximityLevel): AlertLevel {
        return when {
            proximity == ProximityLevel.NEAR -> AlertLevel.HIGH
            level == AlertLevel.HIGH -> AlertLevel.HIGH
            proximity == ProximityLevel.MEDIUM && level == AlertLevel.LOW -> AlertLevel.MEDIUM
            else -> level
        }
    }

    private fun mapObjectLabel(rawText: String, confidence: Float): DetectionResult? {
        val normalized = rawText.lowercase(Locale.US)
        return exactDescriptorMap[normalized]?.toResult(confidence)
    }

    private fun mapImageLabel(label: ImageLabel): DetectionResult? {
        val raw = label.text.lowercase(Locale.US)
        val exactDescriptor = exactDescriptorMap[raw]
        if (exactDescriptor != null) {
            return exactDescriptor.toResult(label.confidence)
        }

        val keywordDescriptor = keywordDescriptors.firstOrNull { descriptor ->
            descriptor.keywords.any { raw.contains(it) }
        } ?: return null

        return keywordDescriptor.toResult(label.confidence)
    }

    private fun boundingBoxArea(rect: Rect): Int = rect.width() * rect.height()

    private fun calculateAreaRatio(rect: Rect, frameWidth: Int, frameHeight: Int): Float {
        val frameArea = (frameWidth * frameHeight).coerceAtLeast(1)
        return boundingBoxArea(rect).toFloat() / frameArea.toFloat()
    }

    private fun proximityFromRatio(ratio: Float): ProximityLevel {
        return when {
            ratio >= 0.28f -> ProximityLevel.NEAR
            ratio >= 0.12f -> ProximityLevel.MEDIUM
            else -> ProximityLevel.FAR
        }
    }

    private fun showStatus(text: String) {
        lastStatus = text
        runOnUiThread {
            statusText.text = text
        }
    }

    private fun speakOnce(key: String, text: String, minIntervalMs: Long) {
        if (!::tts.isInitialized) return

        val now = SystemClock.elapsedRealtime()
        val canRepeat = key != lastSpokenKey || now - lastSpokenAt >= minIntervalMs
        if (!canRepeat) return

        lastSpokenKey = key
        lastSpokenAt = now
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, key)
    }

    private fun openRouteInGoogleMaps() {
        val destination = navigationDestination
        if (destination.isNullOrBlank()) {
            Toast.makeText(this, "No hay un destino configurado para esta vista.", Toast.LENGTH_SHORT).show()
            return
        }

        val routeUri = Uri.parse(
            "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=walking"
        )
        val intent = Intent(Intent.ACTION_VIEW, routeUri).apply {
            setPackage("com.google.android.apps.maps")
        }

        try {
            startActivity(intent)
            speakOnce("open_maps_route", "Abriendo ruta a pie en Google Maps.", minIntervalMs = 0L)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Google Maps no esta disponible en este dispositivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

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
                Toast.makeText(this, "Permiso de camara denegado.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        if (::objectDetector.isInitialized) {
            objectDetector.close()
        }
        if (::labeler.isInitialized) {
            labeler.close()
        }
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private data class DetectionDescriptor(
        val statusLabel: String,
        val speechLabel: String,
        val alertLevel: AlertLevel,
        val keywords: List<String> = emptyList()
    ) {
        fun toResult(confidence: Float): DetectionResult {
            return DetectionResult(
                statusLabel = statusLabel,
                speechLabel = speechLabel,
                alertLevel = alertLevel,
                confidence = confidence,
                distanceLabel = ProximityLevel.FAR.statusLabel,
                speechDistanceLabel = ProximityLevel.FAR.speechLabel
            )
        }
    }

    private data class DetectionResult(
        val statusLabel: String,
        val speechLabel: String,
        val alertLevel: AlertLevel,
        val confidence: Float,
        val distanceLabel: String,
        val speechDistanceLabel: String,
        val trackingId: Int? = null
    )

    private enum class AlertLevel(val priority: Int) {
        LOW(1),
        MEDIUM(2),
        HIGH(3)
    }

    private enum class ProximityLevel(
        val statusLabel: String,
        val speechLabel: String,
        val baseConfidence: Float
    ) {
        FAR("a distancia", "a cierta distancia", 0.65f),
        MEDIUM("a media distancia", "a media distancia", 0.78f),
        NEAR("muy cerca", "muy cerca", 0.92f)
    }

    companion object {
        const val EXTRA_NAVIGATION_DESTINATION = "extra_navigation_destination"

        private const val TAG = "ObjectDetection"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val MIN_CONFIDENCE = 0.65f
        private const val ANALYSIS_INTERVAL_MS = 750L
        private const val GENERAL_SPEAK_INTERVAL_MS = 6000L
        private const val NOTICE_SPEAK_INTERVAL_MS = 4200L
        private const val OBSTACLE_SPEAK_INTERVAL_MS = 2400L
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        private val exactDescriptorMap = mapOf(
            "person" to DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH),
            "fashion goods" to DetectionDescriptor("objeto personal", "un objeto en el camino", AlertLevel.MEDIUM),
            "food" to DetectionDescriptor("objeto pequeno", "un objeto pequeno", AlertLevel.MEDIUM),
            "home goods" to DetectionDescriptor("objeto del entorno", "un objeto del entorno", AlertLevel.MEDIUM),
            "place" to DetectionDescriptor("estructura", "una estructura", AlertLevel.LOW),
            "places" to DetectionDescriptor("estructura", "una estructura", AlertLevel.LOW),
            "plant" to DetectionDescriptor("planta", "una planta", AlertLevel.MEDIUM),
            "plants" to DetectionDescriptor("planta", "una planta", AlertLevel.MEDIUM),
            "man" to DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH),
            "woman" to DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH),
            "bicycle" to DetectionDescriptor("bicicleta", "una bicicleta", AlertLevel.HIGH),
            "motorcycle" to DetectionDescriptor("motocicleta", "una motocicleta", AlertLevel.HIGH),
            "car" to DetectionDescriptor("vehiculo", "un vehiculo", AlertLevel.HIGH),
            "bus" to DetectionDescriptor("bus", "un bus", AlertLevel.HIGH),
            "truck" to DetectionDescriptor("camion", "un camion", AlertLevel.HIGH),
            "stairs" to DetectionDescriptor("escaleras", "escaleras", AlertLevel.HIGH),
            "step" to DetectionDescriptor("escalon", "un escalon", AlertLevel.HIGH),
            "curb" to DetectionDescriptor("bordillo", "un bordillo", AlertLevel.HIGH),
            "barrier" to DetectionDescriptor("barrera", "una barrera", AlertLevel.HIGH),
            "cone" to DetectionDescriptor("cono", "un cono en el camino", AlertLevel.HIGH),
            "pole" to DetectionDescriptor("poste", "un poste", AlertLevel.MEDIUM),
            "tree" to DetectionDescriptor("arbol", "un arbol cercano", AlertLevel.MEDIUM),
            "bench" to DetectionDescriptor("banco", "un banco", AlertLevel.MEDIUM),
            "chair" to DetectionDescriptor("silla", "una silla", AlertLevel.MEDIUM),
            "door" to DetectionDescriptor("puerta", "una puerta", AlertLevel.LOW),
            "building" to DetectionDescriptor("edificio", "un edificio", AlertLevel.LOW),
            "crosswalk" to DetectionDescriptor("cruce peatonal", "un cruce peatonal", AlertLevel.MEDIUM),
            "sidewalk" to DetectionDescriptor("anden", "el anden", AlertLevel.LOW),
            "road" to DetectionDescriptor("via", "la via", AlertLevel.MEDIUM),
            "street" to DetectionDescriptor("calle", "la calle", AlertLevel.MEDIUM),
            "pothole" to DetectionDescriptor("hueco", "un hueco", AlertLevel.HIGH),
            "divider" to DetectionDescriptor("separador", "un separador", AlertLevel.HIGH)
        )

        private val keywordDescriptors = listOf(
            DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH, listOf("person", "people", "pedestrian")),
            DetectionDescriptor("vehiculo", "un vehiculo", AlertLevel.HIGH, listOf("vehicle", "car", "truck", "bus", "van")),
            DetectionDescriptor("bicicleta", "una bicicleta", AlertLevel.HIGH, listOf("bike", "bicycle", "cycl")),
            DetectionDescriptor("motocicleta", "una motocicleta", AlertLevel.HIGH, listOf("motorcycle", "scooter")),
            DetectionDescriptor("escaleras", "escaleras", AlertLevel.HIGH, listOf("stair", "step", "stairs")),
            DetectionDescriptor("bordillo", "un bordillo", AlertLevel.HIGH, listOf("curb", "ledge")),
            DetectionDescriptor("hueco", "un hueco", AlertLevel.HIGH, listOf("hole", "pothole", "drain")),
            DetectionDescriptor("separador", "un separador", AlertLevel.HIGH, listOf("divider", "median", "separator")),
            DetectionDescriptor("barrera", "una barrera", AlertLevel.HIGH, listOf("barrier", "fence", "gate")),
            DetectionDescriptor("cono", "un cono en el camino", AlertLevel.HIGH, listOf("cone", "bollard")),
            DetectionDescriptor("poste", "un poste", AlertLevel.MEDIUM, listOf("pole", "column", "sign")),
            DetectionDescriptor("arbol", "un arbol cercano", AlertLevel.MEDIUM, listOf("tree", "branch")),
            DetectionDescriptor("banco", "un banco", AlertLevel.MEDIUM, listOf("bench", "seat")),
            DetectionDescriptor("calle", "la calle", AlertLevel.MEDIUM, listOf("street", "road", "traffic")),
            DetectionDescriptor("anden", "el anden", AlertLevel.LOW, listOf("sidewalk", "walkway", "path")),
            DetectionDescriptor("puerta", "una puerta", AlertLevel.LOW, listOf("door", "entrance")),
            DetectionDescriptor("edificio", "un edificio", AlertLevel.LOW, listOf("building", "house", "store"))
        )
    }
}
