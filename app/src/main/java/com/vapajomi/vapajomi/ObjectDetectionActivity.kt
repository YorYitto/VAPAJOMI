package com.vapajomi.vapajomi

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
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
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ObjectDetectionActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var viewFinder: PreviewView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var openRouteButton: Button
    private lateinit var closeButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech
    private lateinit var labeler: ImageLabeler
    private lateinit var objectDetector: ObjectDetector
    private lateinit var voiceCommandListener: VoiceCommandListener
    private lateinit var sensorManager: SensorManager
    private lateinit var vibrator: Vibrator

    private val mainHandler = Handler(Looper.getMainLooper())
    private var proximitySensor: Sensor? = null
    private var isProcessing = false
    private var isVoiceListening = false
    private var shouldResumeVoiceListeningAfterSpeech = false
    private var isNearFromProximitySensor = false
    private var lastAnalysisTimestamp = 0L
    private var lastSpokenKey = ""
    private var lastSpokenAt = 0L
    private var lastProximityAlertAt = 0L
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
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        objectDetector = ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        )
        labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(MIN_LABEL_CONFIDENCE)
                .build()
        )

        initTTS()
        initVoiceCommands()
        setupActions()
        updateInitialTexts()

        if (allPermissionsGranted()) {
            startCamera()
            startVoiceCommandListening()
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
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        resumeVoiceListeningIfNeeded()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        resumeVoiceListeningIfNeeded()
                    }
                })
                speakOnce(
                    key = if (navigationDestination == null) "start_scan" else "start_assisted_navigation",
                    text = if (navigationDestination == null) {
                        "Detector con ML Kit activo. Te avisare si encuentro obstaculos y su distancia aproximada en metros. Di regresar para volver."
                    } else {
                        "Modo navegacion asistida con ML Kit activo. Monitoreando obstaculos rumbo a $navigationDestination con distancia aproximada en metros. Di regresar para volver, o iniciar ruta para abrir indicaciones a pie."
                    },
                    minIntervalMs = 0L
                )
            }
        }
    }

    private fun initVoiceCommands() {
        voiceCommandListener = VoiceCommandListener(
            context = this,
            onResult = { text ->
                runOnUiThread {
                    isVoiceListening = false
                    handleVoiceCommand(text)
                }
            },
            onError = {
                runOnUiThread {
                    isVoiceListening = false
                    mainHandler.postDelayed({ startVoiceCommandListening() }, VOICE_RETRY_DELAY_MS)
                }
            }
        )
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
            "ML Kit analiza el entorno y estima distancias. Di \"regresar\" para volver."
        } else {
            "ML Kit monitorea obstaculos hacia $navigationDestination y estima distancias. Di \"iniciar ruta\" o \"regresar\"."
        }
        statusText.text = lastStatus
    }

    private fun handleVoiceCommand(rawCommand: String) {
        val command = normalizeCommand(rawCommand)

        when {
            command == "regresar" ||
                command == "volver" ||
                command == "atras" ||
                command.contains("regresar al inicio") ||
                command.contains("volver atras") -> {
                speakOnce("voice_return", "Regresando.", minIntervalMs = 0L)
                mainHandler.postDelayed({ finish() }, 450)
            }

            command.contains("iniciar ruta") ||
                command.contains("abrir ruta") ||
                command.contains("abre ruta") ||
                command.contains("indicaciones") ||
                command.contains("abrir maps") ||
                command.contains("abrir mapas") -> {
                openRouteInGoogleMaps()
                mainHandler.postDelayed({ startVoiceCommandListening() }, MAPS_RETURN_LISTEN_DELAY_MS)
            }

            command.contains("ayuda") || command.contains("comandos") -> {
                speakOnce(
                    key = "voice_help",
                    text = "En esta pantalla puedes decir regresar para volver, o iniciar ruta para abrir Google Maps a pie. Las distancias son aproximadas.",
                    minIntervalMs = 0L
                )
            }

            else -> {
                startVoiceCommandListening()
            }
        }
    }

    private fun normalizeCommand(text: String): String {
        return Normalizer.normalize(text.lowercase(Locale.forLanguageTag("es-ES")), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun startVoiceCommandListening() {
        if (isFinishing || isDestroyed || isVoiceListening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (::tts.isInitialized && tts.isSpeaking) {
            shouldResumeVoiceListeningAfterSpeech = true
            return
        }

        isVoiceListening = true
        voiceCommandListener.startListening()
    }

    private fun resumeVoiceListeningIfNeeded() {
        if (!shouldResumeVoiceListeningAfterSpeech) return

        shouldResumeVoiceListeningAfterSpeech = false
        mainHandler.postDelayed({ startVoiceCommandListening() }, VOICE_RESUME_DELAY_MS)
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
        val dominantObject = objects.maxByOrNull { detectedObject ->
            collisionScore(detectedObject.boundingBox, frameWidth, frameHeight)
        }
        val objectDetection = dominantObject?.let { detectedObject ->
            buildDetectionFromObject(detectedObject, frameWidth, frameHeight)
        }

        val rawLabelDetection = labels
            .sortedByDescending { it.confidence }
            .mapNotNull { mapImageLabel(it) }
            .maxWithOrNull(
                compareBy<DetectionResult> { it.alertLevel.priority }
                    .thenBy { it.confidence }
            )
        val labelDetection = rawLabelDetection?.let { detection ->
            dominantObject?.let { detectedObject ->
                detection.withEstimatedDistanceFrom(
                    boundingBox = detectedObject.boundingBox,
                    frameHeight = frameHeight
                )
            } ?: detection
        }

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
            AlertLevel.LOW -> "Veo ${detection.speechLabel} ${detection.speechDistanceLabel}."
        }

        if (detection.alertLevel == AlertLevel.HIGH) {
            vibratePattern(longArrayOf(0, 140, 80, 140))
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

    private fun DetectionResult.withEstimatedDistanceFrom(
        boundingBox: Rect,
        frameHeight: Int
    ): DetectionResult {
        val estimatedDistance = estimateDistanceMeters(
            boundingBox = boundingBox,
            frameHeight = frameHeight,
            descriptor = descriptor
        ) ?: return this

        return copy(
            distanceLabel = "a ${formatMeters(estimatedDistance)} metros aprox.",
            speechDistanceLabel = "a ${formatMeters(estimatedDistance)} metros aproximadamente"
        )
    }

    private fun buildDetectionFromObject(
        detectedObject: DetectedObject,
        frameWidth: Int,
        frameHeight: Int
    ): DetectionResult {
        val ratio = calculateAreaRatio(detectedObject.boundingBox, frameWidth, frameHeight)
        val proximity = proximityFromRatio(ratio)
        val pathPosition = pathPositionFromBoundingBox(detectedObject.boundingBox, frameWidth)
        val objectLabel = detectedObject.labels
            .mapNotNull { mapObjectLabel(it.text, it.confidence) }
            .maxByOrNull { it.confidence }

        val baseResult = objectLabel ?: defaultObstacleResult(proximity, pathPosition)
        val estimatedDistance = estimateDistanceMeters(
            boundingBox = detectedObject.boundingBox,
            frameHeight = frameHeight,
            descriptor = baseResult.descriptor
        )
        val escalatedLevel = escalateAlertLevel(
            level = baseResult.alertLevel,
            proximity = proximity,
            pathPosition = pathPosition,
            estimatedDistanceMeters = estimatedDistance
        )
        val distanceLabel = estimatedDistance?.let { "a ${formatMeters(it)} metros aprox." }
            ?: proximity.statusLabel
        val speechDistanceLabel = estimatedDistance?.let { "a ${formatMeters(it)} metros aproximadamente" }
            ?: proximity.speechLabel

        return baseResult.copy(
            alertLevel = escalatedLevel,
            confidence = maxOf(baseResult.confidence, proximity.baseConfidence),
            statusLabel = applyPathPosition(baseResult.statusLabel, pathPosition),
            speechLabel = applyPathPosition(baseResult.speechLabel, pathPosition),
            distanceLabel = distanceLabel,
            speechDistanceLabel = speechDistanceLabel,
            trackingId = detectedObject.trackingId
        )
    }

    private fun defaultObstacleResult(
        proximity: ProximityLevel,
        pathPosition: PathPosition
    ): DetectionResult {
        val level = when {
            pathPosition == PathPosition.CENTER -> AlertLevel.HIGH
            proximity == ProximityLevel.NEAR -> AlertLevel.HIGH
            else -> AlertLevel.MEDIUM
        }
        return DetectionResult(
            statusLabel = "obstaculo detectado",
            speechLabel = "un obstaculo",
            alertLevel = level,
            confidence = proximity.baseConfidence,
            distanceLabel = proximity.statusLabel,
            speechDistanceLabel = proximity.speechLabel,
            descriptor = null
        )
    }

    private fun escalateAlertLevel(
        level: AlertLevel,
        proximity: ProximityLevel,
        pathPosition: PathPosition,
        estimatedDistanceMeters: Float?
    ): AlertLevel {
        return when {
            pathPosition == PathPosition.CENTER && estimatedDistanceMeters != null &&
                estimatedDistanceMeters <= COLLISION_DISTANCE_METERS -> AlertLevel.HIGH
            pathPosition == PathPosition.CENTER && proximity != ProximityLevel.FAR -> AlertLevel.HIGH
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

    private fun collisionScore(rect: Rect, frameWidth: Int, frameHeight: Int): Float {
        val areaRatio = calculateAreaRatio(rect, frameWidth, frameHeight)
        val centerBonus = when (pathPositionFromBoundingBox(rect, frameWidth)) {
            PathPosition.CENTER -> 2.6f
            PathPosition.LEFT, PathPosition.RIGHT -> 1.2f
        }
        val lowerFrameBonus = if (rect.centerY() > frameHeight * 0.42f) 1.4f else 1f
        val heightRatio = rect.height().toFloat() / frameHeight.coerceAtLeast(1).toFloat()
        return (areaRatio * centerBonus * lowerFrameBonus) + (heightRatio * 0.35f)
    }

    private fun calculateAreaRatio(rect: Rect, frameWidth: Int, frameHeight: Int): Float {
        val frameArea = (frameWidth * frameHeight).coerceAtLeast(1)
        return boundingBoxArea(rect).toFloat() / frameArea.toFloat()
    }

    private fun proximityFromRatio(ratio: Float): ProximityLevel {
        return when {
            ratio >= 0.20f -> ProximityLevel.NEAR
            ratio >= 0.06f -> ProximityLevel.MEDIUM
            else -> ProximityLevel.FAR
        }
    }

    private fun pathPositionFromBoundingBox(rect: Rect, frameWidth: Int): PathPosition {
        val centerXRatio = rect.centerX().toFloat() / frameWidth.coerceAtLeast(1).toFloat()
        return when {
            centerXRatio < 0.36f -> PathPosition.LEFT
            centerXRatio > 0.64f -> PathPosition.RIGHT
            else -> PathPosition.CENTER
        }
    }

    private fun applyPathPosition(label: String, pathPosition: PathPosition): String {
        return when {
            label.contains("frente") || label.contains("izquierda") || label.contains("derecha") -> label
            pathPosition == PathPosition.CENTER -> "$label al frente"
            pathPosition == PathPosition.LEFT -> "$label a la izquierda"
            pathPosition == PathPosition.RIGHT -> "$label a la derecha"
            else -> label
        }
    }

    private fun estimateDistanceMeters(
        boundingBox: Rect,
        frameHeight: Int,
        descriptor: DetectionDescriptor?
    ): Float? {
        val realHeightMeters = descriptor?.averageHeightMeters ?: DEFAULT_OBJECT_HEIGHT_METERS
        val boxHeightRatio = boundingBox.height().toFloat() / frameHeight.coerceAtLeast(1).toFloat()
        if (boxHeightRatio <= 0.02f) return null

        val estimated = (realHeightMeters * DISTANCE_CALIBRATION_FACTOR) / boxHeightRatio
        return estimated.coerceIn(MIN_ESTIMATED_DISTANCE_METERS, MAX_ESTIMATED_DISTANCE_METERS)
    }

    private fun formatMeters(distanceMeters: Float): String {
        return if (distanceMeters < 10f) {
            String.format(Locale.US, "%.1f", distanceMeters)
        } else {
            distanceMeters.toInt().toString()
        }
    }

    private fun showStatus(text: String) {
        lastStatus = text
        runOnUiThread {
            statusText.text = text
            statusText.contentDescription = text
        }
    }

    private fun speakOnce(key: String, text: String, minIntervalMs: Long) {
        if (!::tts.isInitialized) return

        val now = SystemClock.elapsedRealtime()
        val canRepeat = key != lastSpokenKey || now - lastSpokenAt >= minIntervalMs
        if (!canRepeat) return

        lastSpokenKey = key
        lastSpokenAt = now
        if (::voiceCommandListener.isInitialized) {
            voiceCommandListener.stopListening()
            isVoiceListening = false
            shouldResumeVoiceListeningAfterSpeech = true
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, key)
    }

    private fun openRouteInGoogleMaps() {
        val destination = navigationDestination
        if (destination.isNullOrBlank()) {
            Toast.makeText(this, "No hay un destino configurado para esta vista.", Toast.LENGTH_SHORT).show()
            return
        }

        val routeUri = Uri.parse(
            "google.navigation:q=${Uri.encode(destination)}&mode=w"
        )
        val intent = Intent(Intent.ACTION_VIEW, routeUri).apply {
            setPackage("com.google.android.apps.maps")
        }

        try {
            startActivity(intent)
            speakOnce("open_maps_route", "Abriendo ruta a pie en Google Maps.", minIntervalMs = 0L)
            mainHandler.postDelayed({ bringDetectorBackToFront(destination) }, MAPS_TO_CAMERA_DELAY_MS)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Google Maps no esta disponible en este dispositivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bringDetectorBackToFront(destination: String) {
        if (isFinishing || isDestroyed) return

        val returnIntent = Intent(this, ObjectDetectionActivity::class.java).apply {
            putExtra(EXTRA_NAVIGATION_DESTINATION, destination)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(returnIntent)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return

        val maximumRange = event.sensor.maximumRange
        val isNear = event.values.firstOrNull()?.let { value ->
            value < maximumRange
        } ?: false

        if (!isNear) {
            isNearFromProximitySensor = false
            return
        }

        if (isNearFromProximitySensor) return

        isNearFromProximitySensor = true
        val now = SystemClock.elapsedRealtime()
        if (now - lastProximityAlertAt < PROXIMITY_SENSOR_ALERT_INTERVAL_MS) return

        lastProximityAlertAt = now
        showStatus("ALERTA: objeto muy cerca del sensor de proximidad.")
        speakOnce(
            key = "proximity_sensor_near",
            text = "Atencion. Hay algo muy cerca del telefono.",
            minIntervalMs = PROXIMITY_SENSOR_ALERT_INTERVAL_MS
        )
        vibratePattern(longArrayOf(0, 180, 80, 180, 80, 180))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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
                startVoiceCommandListening()
            } else {
                Toast.makeText(this, "Permisos de camara o microfono denegados.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        proximitySensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        mainHandler.postDelayed({ startVoiceCommandListening() }, VOICE_RESUME_DELAY_MS)
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        if (::voiceCommandListener.isInitialized) {
            voiceCommandListener.stopListening()
        }
        isVoiceListening = false
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (::voiceCommandListener.isInitialized) {
            voiceCommandListener.destroy()
        }
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

    private fun vibratePattern(pattern: LongArray) {
        if (!::vibrator.isInitialized || !vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private data class DetectionDescriptor(
        val statusLabel: String,
        val speechLabel: String,
        val alertLevel: AlertLevel,
        val keywords: List<String> = emptyList(),
        val averageHeightMeters: Float = DEFAULT_OBJECT_HEIGHT_METERS
    ) {
        fun toResult(confidence: Float): DetectionResult {
            return DetectionResult(
                statusLabel = statusLabel,
                speechLabel = speechLabel,
                alertLevel = alertLevel,
                confidence = confidence,
                distanceLabel = ProximityLevel.FAR.statusLabel,
                speechDistanceLabel = ProximityLevel.FAR.speechLabel,
                descriptor = this
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
        val descriptor: DetectionDescriptor?,
        val trackingId: Int? = null
    )

    private enum class AlertLevel(val priority: Int) {
        LOW(1),
        MEDIUM(2),
        HIGH(3)
    }

    private enum class PathPosition {
        LEFT,
        CENTER,
        RIGHT
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
        private const val MIN_LABEL_CONFIDENCE = 0.45f
        private const val ANALYSIS_INTERVAL_MS = 350L
        private const val GENERAL_SPEAK_INTERVAL_MS = 6000L
        private const val NOTICE_SPEAK_INTERVAL_MS = 3000L
        private const val OBSTACLE_SPEAK_INTERVAL_MS = 1200L
        private const val PROXIMITY_SENSOR_ALERT_INTERVAL_MS = 2500L
        private const val VOICE_RETRY_DELAY_MS = 900L
        private const val VOICE_RESUME_DELAY_MS = 1000L
        private const val MAPS_RETURN_LISTEN_DELAY_MS = 1800L
        private const val MAPS_TO_CAMERA_DELAY_MS = 2600L
        private const val DEFAULT_OBJECT_HEIGHT_METERS = 1.0f
        private const val DISTANCE_CALIBRATION_FACTOR = 1.45f
        private const val MIN_ESTIMATED_DISTANCE_METERS = 0.3f
        private const val MAX_ESTIMATED_DISTANCE_METERS = 25f
        private const val COLLISION_DISTANCE_METERS = 3.0f
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        private val exactDescriptorMap = mapOf(
            "person" to DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH, averageHeightMeters = 1.65f),
            "people" to DetectionDescriptor("personas", "personas frente a ti", AlertLevel.HIGH, averageHeightMeters = 1.65f),
            "human face" to DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH, averageHeightMeters = 1.65f),
            "face" to DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH, averageHeightMeters = 1.65f),
            "head" to DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH, averageHeightMeters = 1.65f),
            "portrait" to DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH, averageHeightMeters = 1.65f),
            "crowd" to DetectionDescriptor("personas", "personas frente a ti", AlertLevel.HIGH, averageHeightMeters = 1.65f),
            "fashion goods" to DetectionDescriptor("objeto personal", "un objeto en el camino", AlertLevel.MEDIUM, averageHeightMeters = 0.6f),
            "food" to DetectionDescriptor("objeto pequeno", "un objeto pequeno", AlertLevel.MEDIUM, averageHeightMeters = 0.25f),
            "home goods" to DetectionDescriptor("objeto del entorno", "un objeto del entorno", AlertLevel.MEDIUM),
            "place" to DetectionDescriptor("estructura", "una estructura", AlertLevel.LOW, averageHeightMeters = 2.2f),
            "places" to DetectionDescriptor("estructura", "una estructura", AlertLevel.LOW, averageHeightMeters = 2.2f),
            "plant" to DetectionDescriptor("planta", "una planta", AlertLevel.MEDIUM, averageHeightMeters = 0.9f),
            "plants" to DetectionDescriptor("planta", "una planta", AlertLevel.MEDIUM, averageHeightMeters = 0.9f),
            "man" to DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH, averageHeightMeters = 1.7f),
            "woman" to DetectionDescriptor("persona", "una persona frente a ti", AlertLevel.HIGH, averageHeightMeters = 1.6f),
            "bicycle" to DetectionDescriptor("bicicleta", "una bicicleta", AlertLevel.HIGH, averageHeightMeters = 1.1f),
            "motorcycle" to DetectionDescriptor("motocicleta", "una motocicleta", AlertLevel.HIGH, averageHeightMeters = 1.25f),
            "car" to DetectionDescriptor("vehiculo", "un vehiculo", AlertLevel.HIGH, averageHeightMeters = 1.45f),
            "bus" to DetectionDescriptor("bus", "un bus", AlertLevel.HIGH, averageHeightMeters = 3.0f),
            "truck" to DetectionDescriptor("camion", "un camion", AlertLevel.HIGH, averageHeightMeters = 3.2f),
            "stairs" to DetectionDescriptor("escaleras", "escaleras", AlertLevel.HIGH, averageHeightMeters = 0.6f),
            "step" to DetectionDescriptor("escalon", "un escalon", AlertLevel.HIGH, averageHeightMeters = 0.18f),
            "curb" to DetectionDescriptor("bordillo", "un bordillo", AlertLevel.HIGH, averageHeightMeters = 0.18f),
            "barrier" to DetectionDescriptor("barrera", "una barrera", AlertLevel.HIGH, averageHeightMeters = 1.0f),
            "cone" to DetectionDescriptor("cono", "un cono en el camino", AlertLevel.HIGH, averageHeightMeters = 0.75f),
            "pole" to DetectionDescriptor("poste", "un poste", AlertLevel.MEDIUM, averageHeightMeters = 2.2f),
            "tree" to DetectionDescriptor("arbol", "un arbol cercano", AlertLevel.MEDIUM, averageHeightMeters = 3.0f),
            "bench" to DetectionDescriptor("banco", "un banco", AlertLevel.MEDIUM, averageHeightMeters = 0.8f),
            "chair" to DetectionDescriptor("silla", "una silla", AlertLevel.MEDIUM, averageHeightMeters = 0.9f),
            "table" to DetectionDescriptor("mesa", "una mesa", AlertLevel.MEDIUM, averageHeightMeters = 0.75f),
            "desk" to DetectionDescriptor("escritorio", "un escritorio", AlertLevel.MEDIUM, averageHeightMeters = 0.75f),
            "bed" to DetectionDescriptor("cama", "una cama", AlertLevel.MEDIUM, averageHeightMeters = 0.55f),
            "couch" to DetectionDescriptor("sofa", "un sofa", AlertLevel.MEDIUM, averageHeightMeters = 0.9f),
            "sofa" to DetectionDescriptor("sofa", "un sofa", AlertLevel.MEDIUM, averageHeightMeters = 0.9f),
            "television" to DetectionDescriptor("televisor", "un televisor", AlertLevel.LOW, averageHeightMeters = 0.7f),
            "tv" to DetectionDescriptor("televisor", "un televisor", AlertLevel.LOW, averageHeightMeters = 0.7f),
            "monitor" to DetectionDescriptor("monitor", "un monitor", AlertLevel.LOW, averageHeightMeters = 0.45f),
            "screen" to DetectionDescriptor("pantalla", "una pantalla", AlertLevel.LOW, averageHeightMeters = 0.55f),
            "laptop" to DetectionDescriptor("portatil", "un portatil", AlertLevel.LOW, averageHeightMeters = 0.25f),
            "computer" to DetectionDescriptor("computador", "un computador", AlertLevel.LOW, averageHeightMeters = 0.5f),
            "door" to DetectionDescriptor("puerta", "una puerta", AlertLevel.LOW, averageHeightMeters = 2.0f),
            "building" to DetectionDescriptor("edificio", "un edificio", AlertLevel.LOW, averageHeightMeters = 3.0f),
            "crosswalk" to DetectionDescriptor("cruce peatonal", "un cruce peatonal", AlertLevel.MEDIUM, averageHeightMeters = 0.05f),
            "sidewalk" to DetectionDescriptor("anden", "el anden", AlertLevel.LOW, averageHeightMeters = 0.12f),
            "road" to DetectionDescriptor("via", "la via", AlertLevel.MEDIUM, averageHeightMeters = 0.05f),
            "street" to DetectionDescriptor("calle", "la calle", AlertLevel.MEDIUM, averageHeightMeters = 0.05f),
            "pothole" to DetectionDescriptor("hueco", "un hueco", AlertLevel.HIGH, averageHeightMeters = 0.08f),
            "divider" to DetectionDescriptor("separador", "un separador", AlertLevel.HIGH, averageHeightMeters = 0.35f)
        )

        private val keywordDescriptors = listOf(
            DetectionDescriptor(
                "persona",
                "una persona frente a ti",
                AlertLevel.HIGH,
                listOf("person", "people", "pedestrian", "human", "face", "head", "portrait", "crowd", "standing", "selfie"),
                1.65f
            ),
            DetectionDescriptor("vehiculo", "un vehiculo", AlertLevel.HIGH, listOf("vehicle", "car", "truck", "bus", "van"), 1.6f),
            DetectionDescriptor("bicicleta", "una bicicleta", AlertLevel.HIGH, listOf("bike", "bicycle", "cycl"), 1.1f),
            DetectionDescriptor("motocicleta", "una motocicleta", AlertLevel.HIGH, listOf("motorcycle", "scooter"), 1.25f),
            DetectionDescriptor("escaleras", "escaleras", AlertLevel.HIGH, listOf("stair", "step", "stairs"), 0.6f),
            DetectionDescriptor("bordillo", "un bordillo", AlertLevel.HIGH, listOf("curb", "ledge"), 0.18f),
            DetectionDescriptor("hueco", "un hueco", AlertLevel.HIGH, listOf("hole", "pothole", "drain"), 0.08f),
            DetectionDescriptor("separador", "un separador", AlertLevel.HIGH, listOf("divider", "median", "separator"), 0.35f),
            DetectionDescriptor("barrera", "una barrera", AlertLevel.HIGH, listOf("barrier", "fence", "gate"), 1.0f),
            DetectionDescriptor("cono", "un cono en el camino", AlertLevel.HIGH, listOf("cone", "bollard"), 0.75f),
            DetectionDescriptor("poste", "un poste", AlertLevel.MEDIUM, listOf("pole", "column", "sign"), 2.2f),
            DetectionDescriptor("arbol", "un arbol cercano", AlertLevel.MEDIUM, listOf("tree", "branch"), 3.0f),
            DetectionDescriptor("banco", "un banco", AlertLevel.MEDIUM, listOf("bench", "seat"), 0.8f),
            DetectionDescriptor("silla", "una silla", AlertLevel.MEDIUM, listOf("chair", "stool"), 0.9f),
            DetectionDescriptor("mesa", "una mesa", AlertLevel.MEDIUM, listOf("table", "desk"), 0.75f),
            DetectionDescriptor("cama", "una cama", AlertLevel.MEDIUM, listOf("bed", "mattress", "pillow"), 0.55f),
            DetectionDescriptor("sofa", "un sofa", AlertLevel.MEDIUM, listOf("couch", "sofa"), 0.9f),
            DetectionDescriptor("televisor", "un televisor", AlertLevel.LOW, listOf("tv", "television"), 0.7f),
            DetectionDescriptor("monitor", "un monitor", AlertLevel.LOW, listOf("monitor", "display", "screen"), 0.45f),
            DetectionDescriptor("portatil", "un portatil", AlertLevel.LOW, listOf("laptop", "notebook", "computer", "pc"), 0.3f),
            DetectionDescriptor("calle", "la calle", AlertLevel.MEDIUM, listOf("street", "road", "traffic"), 0.05f),
            DetectionDescriptor("anden", "el anden", AlertLevel.LOW, listOf("sidewalk", "walkway", "path"), 0.12f),
            DetectionDescriptor("puerta", "una puerta", AlertLevel.LOW, listOf("door", "entrance"), 2.0f),
            DetectionDescriptor("edificio", "un edificio", AlertLevel.LOW, listOf("building", "house", "store"), 3.0f)
        )
    }
}
