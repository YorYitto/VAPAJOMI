package com.vapajomi.vapajomi

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.*

class HomeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var mAuth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var tts: TextToSpeech
    private lateinit var voiceCommandListener: VoiceCommandListener
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private lateinit var welcomeText: TextView
    private lateinit var voiceResultText: TextView
    private lateinit var statusText: TextView
    private lateinit var helpButton: Button
    private lateinit var logoutButton: Button
    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager

    private var torchCameraId: String? = null
    private var isTorchEnabled = false
    private var isListening = false
    private var currentLocation: Location? = null
    private var currentAddress: String = ""
    private val spanishLocale: Locale = Locale("es", "CO")

    private val permissionsRequestCode = 100

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        mAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()
        tts = TextToSpeech(this, this)
        audioManager = getSystemService(AudioManager::class.java)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        welcomeText = findViewById(R.id.welcomeText)
        voiceResultText = findViewById(R.id.voiceResultText)
        statusText = findViewById(R.id.statusText)
        helpButton = findViewById(R.id.helpButton)
        logoutButton = findViewById(R.id.logoutButton)

        // OCULTAR el texto "Escuché: ..."
        voiceResultText.visibility = View.GONE

        voiceCommandListener = VoiceCommandListener(
            context = this,
            onResult = { text ->
                runOnUiThread {
                    // NO mostrar en pantalla
                    // voiceResultText.text = "Escuché: $text"
                    handleVoiceCommand(text)
                }
            },
            onError = { _ ->
                runOnUiThread {
                    restartListening()
                }
            }
        )

        setupLocationCallback()
        loadUserProfile()
        initTorch()

        helpButton.setOnClickListener {
            showCommandsList()
        }

        logoutButton.setOnClickListener {
            logout()
        }

        checkAndRequestPermissions()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location
                    updateAddress(location)
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000
        ).build()

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    private fun updateAddress(location: Location) {
        try {
            val geocoder = Geocoder(this, spanishLocale)
            @Suppress("DEPRECATION")
            val addresses: List<Address>? = geocoder.getFromLocation(
                location.latitude,
                location.longitude,
                1
            )

            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val parts = mutableListOf<String>()

                address.thoroughfare?.let { parts.add(it) }
                address.subThoroughfare?.let { parts.add("#$it") }
                address.subLocality?.let { parts.add(it) }
                address.locality?.let { parts.add(it) }

                currentAddress = parts.joinToString(", ")
            }
        } catch (_: Exception) {
            currentAddress = "Ubicación desconocida"
        }
    }

    private fun loadUserProfile() {
        val userId = mAuth.currentUser?.uid ?: return

        database.reference.child("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val disabilityType = snapshot.child("disabilityType").getValue(String::class.java)

                    welcomeText.text = "Bienvenido"
                    applyDisabilityAdaptations(disabilityType)

                    Handler(Looper.getMainLooper()).postDelayed({
                        startContinuousListening()
                        startLocationUpdates()
                    }, 1000)
                }

                override fun onCancelled(error: DatabaseError) {
                    welcomeText.text = "Bienvenido"
                    Handler(Looper.getMainLooper()).postDelayed({
                        startContinuousListening()
                        startLocationUpdates()
                    }, 1000)
                }
            })
    }

    private fun applyDisabilityAdaptations(disabilityType: String?) {
        when (disabilityType) {
            "visual_total" -> {
                welcomeText.textSize = 36f
                statusText.textSize = 20f
            }
            "visual_parcial" -> {
                welcomeText.textSize = 32f
                statusText.textSize = 18f
            }
        }
    }

    private fun startContinuousListening() {
        if (!isListening && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) {
            isListening = true
            statusText.text = "🎤 Escuchando..."
            voiceCommandListener.startListening()
        }
    }

    private fun restartListening() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (isListening) {
                voiceCommandListener.startListening()
            }
        }, 500)
    }

    private fun initTorch() {
        cameraManager = getSystemService(CameraManager::class.java)
        torchCameraId = cameraManager.cameraIdList.firstOrNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    private fun handleVoiceCommand(rawCommand: String) {
        val command = normalizeCommand(rawCommand)

        // PALABRA DE ACTIVACIÓN: "OK ASISTENTE" o "HOLA ASISTENTE"
        val hasActivation = command.contains("ok asistente") ||
                command.contains("hola asistente") ||
                command.contains("okey asistente")

        if (!hasActivation && !isCommandRecognized(command)) {
            restartListening()
            return
        }

        val cleanCommand = command
            .replace("ok asistente", "")
            .replace("hola asistente", "")
            .replace("okey asistente", "")
            .trim()

        val smsCommand = parseSmsCommand(cleanCommand)
        val callTarget = extractCallTarget(cleanCommand)
        val navigationTarget = extractNavigationTarget(cleanCommand)

        when {
            matchesHelp(cleanCommand) -> speakAvailableCommands()
            matchesTorchOn(cleanCommand) -> setFlashlight(true)
            matchesTorchOff(cleanCommand) -> setFlashlight(false)
            matchesTime(cleanCommand) -> speakCurrentTime()
            matchesDate(cleanCommand) -> speakCurrentDate()
            matchesBattery(cleanCommand) -> speakBatteryLevel()
            matchesVolumeUp(cleanCommand) -> changeVolume(up = true)
            matchesVolumeDown(cleanCommand) -> changeVolume(up = false)
            matchesMute(cleanCommand) -> muteVolume()
            matchesIdentifyObjects(cleanCommand) -> openCameraForObjectDetection()
            matchesCamera(cleanCommand) -> openCamera()
            matchesMaps(cleanCommand) -> openMaps()
            matchesLocation(cleanCommand) -> speakDetailedLocation()
            navigationTarget != null -> navigateTo(navigationTarget)
            matchesSettings(cleanCommand) -> openSettings(Settings.ACTION_SETTINGS)
            cleanCommand.contains("wifi") -> openSettings(Settings.ACTION_WIFI_SETTINGS)
            cleanCommand.contains("bluetooth") -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            matchesEmergency(cleanCommand) -> makePhoneCall("911")
            callTarget != null -> makePhoneCall(callTarget)
            smsCommand != null -> sendSmsToTarget(smsCommand.first, smsCommand.second)
            matchesWhatsApp(cleanCommand) -> openWhatsApp()
            matchesYouTube(cleanCommand) -> openYouTube()
            matchesGmail(cleanCommand) -> openGmail()
            matchesSpotify(cleanCommand) -> openSpotify()
            else -> {
                restartListening()
                return
            }
        }

        restartListening()
    }

    private fun isCommandRecognized(command: String): Boolean {
        return matchesHelp(command) || matchesTorchOn(command) || matchesTorchOff(command) ||
                matchesTime(command) || matchesDate(command) || matchesBattery(command) ||
                matchesVolumeUp(command) || matchesVolumeDown(command) || matchesMute(command) ||
                matchesCamera(command) || matchesMaps(command) || matchesLocation(command) ||
                matchesSettings(command) || matchesEmergency(command) ||
                matchesWhatsApp(command) || matchesYouTube(command) || matchesGmail(command) ||
                command.contains("llamar") || command.contains("enviar mensaje") ||
                command.contains("navegar") || command.contains("llevame")
    }

    private fun matchesHelp(cmd: String) =
        cmd.contains("ayuda") || cmd.contains("que puedes hacer") ||
                cmd.contains("comandos") || cmd.contains("que sabes")

    private fun matchesTorchOn(cmd: String) =
        (cmd.contains("enciende") || cmd.contains("prende") || cmd.contains("activa")) &&
                (cmd.contains("linterna") || cmd.contains("luz"))

    private fun matchesTorchOff(cmd: String) =
        (cmd.contains("apaga") || cmd.contains("desactiva")) &&
                (cmd.contains("linterna") || cmd.contains("luz"))

    private fun matchesTime(cmd: String) =
        (cmd.contains("que hora") || cmd.contains("dime la hora") || cmd == "hora") &&
                !cmd.contains("fecha")

    private fun matchesDate(cmd: String) =
        cmd.contains("que fecha") || cmd.contains("que dia") ||
                cmd.contains("dime la fecha") || cmd.contains("dime el dia") ||
                cmd == "fecha" || cmd == "dia"

    private fun matchesBattery(cmd: String) =
        cmd.contains("bateria") || cmd.contains("cuanta bateria")

    private fun matchesVolumeUp(cmd: String) =
        (cmd.contains("sube") || cmd.contains("aumenta")) && cmd.contains("volumen")

    private fun matchesVolumeDown(cmd: String) =
        (cmd.contains("baja") || cmd.contains("disminuye")) && cmd.contains("volumen")

    private fun matchesMute(cmd: String) =
        cmd.contains("silencio") || cmd.contains("mute")

    private fun matchesIdentifyObjects(cmd: String) =
        cmd.contains("que veo") || cmd.contains("que hay") ||
                cmd.contains("identificar") || cmd.contains("que es esto") ||
                cmd.contains("describir") || cmd.contains("reconocer objeto")

    private fun matchesCamera(cmd: String) =
        (cmd.contains("abrir") || cmd.contains("abre")) && cmd.contains("camara")

    private fun matchesMaps(cmd: String) =
        (cmd.contains("abrir") || cmd.contains("abre")) &&
                (cmd.contains("mapas") || cmd.contains("mapa"))

    private fun matchesLocation(cmd: String) =
        cmd.contains("donde estoy") || cmd.contains("mi ubicacion") ||
                cmd.contains("ubicacion actual") || cmd.contains("en que calle")

    private fun matchesSettings(cmd: String) =
        (cmd.contains("abrir") || cmd.contains("abre")) &&
                (cmd.contains("ajustes") || cmd.contains("configuracion"))

    private fun matchesEmergency(cmd: String) =
        cmd.contains("emergencia")

    private fun matchesWhatsApp(cmd: String) =
        (cmd.contains("abrir") || cmd.contains("abre") || cmd.contains("abreme")) &&
                (cmd.contains("whatsapp") || cmd.contains("guasap") || cmd.contains("wasa"))

    private fun matchesYouTube(cmd: String) =
        (cmd.contains("abrir") || cmd.contains("abre") || cmd.contains("abreme")) &&
                (cmd.contains("youtube") || cmd.contains("videos"))

    private fun matchesGmail(cmd: String) =
        (cmd.contains("abrir") || cmd.contains("abre") || cmd.contains("abreme")) &&
                (cmd.contains("gmail") || cmd.contains("correo"))

    private fun matchesSpotify(cmd: String) =
        (cmd.contains("abrir") || cmd.contains("abre") || cmd.contains("abreme")) &&
                (cmd.contains("spotify") || cmd.contains("musica"))

    private fun extractCallTarget(cmd: String): String? {
        val prefixes = listOf("llamar a ", "llama a ", "llamale a ")
        for (prefix in prefixes) {
            if (cmd.contains(prefix)) {
                return cmd.substringAfter(prefix).trim()
            }
        }
        return null
    }

    private fun extractNavigationTarget(cmd: String): String? {
        val prefixes = listOf("navegar a ", "llevame a ", "ir a ", "como llego a ")
        for (prefix in prefixes) {
            if (cmd.contains(prefix)) {
                return cmd.substringAfter(prefix).trim()
            }
        }
        return null
    }

    private fun openCameraForObjectDetection() {
        speak("Abriendo reconocimiento de objetos")
        val intent = Intent(this, ObjectDetectionActivity::class.java)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            speak("No pude abrir el reconocimiento de objetos")
        }
    }

    private fun speakDetailedLocation() {
        if (currentAddress.isNotEmpty() && currentAddress != "Ubicación desconocida") {
            speak("Estás en $currentAddress")
        } else if (currentLocation != null) {
            speak("Obteniendo dirección exacta")
            updateAddress(currentLocation!!)
            Handler(Looper.getMainLooper()).postDelayed({
                if (currentAddress.isNotEmpty()) {
                    speak("Estás en $currentAddress")
                }
            }, 2000)
        } else {
            speak("Obteniendo tu ubicación")
            getCurrentLocation()
        }
    }

    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            speak("Necesito permiso de ubicación")
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                currentLocation = location
                updateAddress(location)
                speakDetailedLocation()
            } else {
                speak("No pude obtener tu ubicación")
            }
        }
    }

    private fun openWhatsApp() {
        val intent = packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (intent != null) {
            startActivity(intent)
            speak("Abriendo WhatsApp")
        } else {
            speak("WhatsApp no está instalado")
        }
    }

    private fun openYouTube() {
        val intent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
        if (intent != null) {
            startActivity(intent)
            speak("Abriendo YouTube")
        } else {
            // Intentar abrir en navegador
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
                startActivity(webIntent)
                speak("Abriendo YouTube en navegador")
            } catch (_: Exception) {
                speak("YouTube no está disponible")
            }
        }
    }

    private fun openGmail() {
        val intent = packageManager.getLaunchIntentForPackage("com.google.android.gm")
        if (intent != null) {
            startActivity(intent)
            speak("Abriendo Gmail")
        } else {
            speak("Gmail no está instalado")
        }
    }

    private fun openSpotify() {
        val intent = packageManager.getLaunchIntentForPackage("com.spotify.music")
        if (intent != null) {
            startActivity(intent)
            speak("Abriendo Spotify")
        } else {
            speak("Spotify no está instalado")
        }
    }

    private fun normalizeCommand(text: String): String {
        return Normalizer.normalize(text.lowercase(spanishLocale), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun showCommandsList() {
        val commands = """
            Di "OK ASISTENTE" + tu comando:
            
            LINTERNA: Enciende/Apaga la linterna
            INFORMACIÓN: Qué hora es, Qué día es, Batería
            VOLUMEN: Sube/Baja volumen, Silencio
            RECONOCIMIENTO: Qué veo, Identificar objetos
            APPS: Abrir WhatsApp/YouTube/Gmail/Spotify/Cámara/Mapas
            COMUNICACIÓN: Llamar a [nombre], Emergencia
            NAVEGACIÓN: Dónde estoy, Navegar a [destino]
            CONFIGURACIÓN: Abrir ajustes/WiFi/Bluetooth
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Comandos de Voz")
            .setMessage(commands)
            .setPositiveButton("Entendido", null)
            .show()

        speak("Di OK ASISTENTE seguido de tu comando")
    }

    private fun speakAvailableCommands() {
        speak("Puedo ayudarte con linterna, hora, fecha, batería, volumen, llamadas, mensajes, navegación, reconocer objetos y abrir aplicaciones")
    }

    private fun parseSmsCommand(command: String): Pair<String, String>? {
        if (!command.contains("enviar mensaje a")) return null
        val rest = command.substringAfter("enviar mensaje a").trim()
        if (rest.isBlank()) return null

        val separators = listOf(" diciendo ", " que diga ")
        for (separator in separators) {
            if (rest.contains(separator)) {
                val target = rest.substringBefore(separator).trim()
                val body = rest.substringAfter(separator).trim()
                if (target.isNotEmpty() && body.isNotEmpty()) {
                    return target to body
                }
            }
        }
        return rest to "Necesito ayuda"
    }

    private fun setFlashlight(enabled: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            speak("Necesito permiso de cámara")
            return
        }

        val cameraId = torchCameraId
        if (cameraId == null) {
            speak("Este dispositivo no tiene linterna")
            return
        }

        try {
            cameraManager.setTorchMode(cameraId, enabled)
            isTorchEnabled = enabled
            speak(if (enabled) "Linterna encendida" else "Linterna apagada")
        } catch (_: Exception) {
            speak("No pude cambiar la linterna")
        }
    }

    private fun makePhoneCall(target: String) {
        val phone = resolvePhoneTarget(target)
        if (phone == null) {
            speak("No encontré el contacto $target")
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED

        val action = if (hasPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$phone"))

        try {
            startActivity(intent)
            speak(if (hasPermission) "Llamando a $target" else "Abriendo marcador")
        } catch (_: Exception) {
            speak("No pude iniciar la llamada")
        }
    }

    private fun sendSmsToTarget(target: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED) {
            speak("Necesito permiso para enviar mensajes")
            return
        }

        val phone = resolvePhoneTarget(target)
        if (phone == null) {
            speak("No encontré el número de $target")
            return
        }

        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            smsManager?.sendTextMessage(phone, null, message, null, null)
            speak("Mensaje enviado a $target")
        } catch (_: Exception) {
            speak("No pude enviar el mensaje")
        }
    }

    private fun resolvePhoneTarget(target: String): String? {
        val normalized = target.trim()
        if (normalized.isBlank()) return null

        val phoneCandidate = normalizePhoneNumber(normalized)
        if (phoneCandidate.length >= 7) return phoneCandidate

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$normalized%")

        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex >= 0) {
                    return normalizePhoneNumber(cursor.getString(numberIndex))
                }
            }
        }

        return null
    }

    private fun normalizePhoneNumber(text: String): String {
        val trimmed = text.trim()
        val startsWithPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        return if (startsWithPlus) "+$digits" else digits
    }

    private fun speakCurrentTime() {
        val time = SimpleDateFormat("HH:mm", spanishLocale).format(Date())
        speak("Son las $time")
    }

    private fun speakCurrentDate() {
        val date = SimpleDateFormat("EEEE d 'de' MMMM", spanishLocale).format(Date())
        speak("Hoy es $date")
    }

    private fun speakBatteryLevel() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        if (level < 0 || scale <= 0) {
            speak("No pude obtener el nivel de batería")
            return
        }

        val percentage = (level * 100) / scale
        speak("Tu batería está al $percentage por ciento")
    }

    private fun changeVolume(up: Boolean) {
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        speak(if (up) "Subiendo volumen" else "Bajando volumen")
    }

    private fun muteVolume() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        speak("Volumen en silencio")
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        try {
            startActivity(intent)
            speak("Abriendo cámara")
        } catch (_: Exception) {
            speak("No encontré aplicación de cámara")
        }
    }

    private fun openMaps() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        intent.setPackage("com.google.android.apps.maps")

        try {
            startActivity(intent)
            speak("Abriendo mapas")
        } catch (_: Exception) {
            speak("No encontré Google Maps")
        }
    }

    private fun navigateTo(destination: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
        intent.setPackage("com.google.android.apps.maps")

        try {
            startActivity(intent)
            speak("Navegando a $destination")
        } catch (_: Exception) {
            speak("Google Maps no está disponible")
        }
    }

    private fun openSettings(action: String) {
        val intent = Intent(action)
        try {
            startActivity(intent)
            speak("Abriendo ajustes")
        } catch (_: Exception) {
            speak("No pude abrir ajustes")
        }
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
                .setMessage("VAPAJOMI necesita permisos para funcionar.")
                .setPositiveButton("Aceptar") { _, _ ->
                    ActivityCompat.requestPermissions(
                        this,
                        permissionsToRequest.toTypedArray(),
                        permissionsRequestCode
                    )
                }
                .setCancelable(false)
                .show()
        } else {
            startContinuousListening()
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == permissionsRequestCode) {
            startContinuousListening()
            startLocationUpdates()
        }
    }

    private fun logout() {
        isListening = false
        voiceCommandListener.stopListening()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        speak("Cerrando sesión")

        Handler(Looper.getMainLooper()).postDelayed({
            mAuth.signOut()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1500)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(spanishLocale)
        }
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        isListening = false
        voiceCommandListener.destroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!isListening) {
            Handler(Looper.getMainLooper()).postDelayed({
                startContinuousListening()
            }, 500)
        }
    }
}