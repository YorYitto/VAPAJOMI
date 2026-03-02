package com.vapajomi.vapajomi

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.text.Normalizer
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
    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private var torchCameraId: String? = null
    private var isTorchEnabled = false
    private val spanishLocale: Locale = Locale.forLanguageTag("es-ES")

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
        audioManager = getSystemService(AudioManager::class.java)

        welcomeText = findViewById(R.id.welcomeText)
        voiceResultText = findViewById(R.id.voiceResultText)
        listenButton = findViewById(R.id.listenButton)
        logoutButton = findViewById(R.id.logoutButton)

        voiceCommandListener = VoiceCommandListener(
            context = this,
            onResult = { text ->
                runOnUiThread {
                    voiceResultText.text = "Escuche: $text"
                    handleVoiceCommand(text)
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
        initTorch()

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

    private fun initTorch() {
        cameraManager = getSystemService(CameraManager::class.java)

        torchCameraId = cameraManager.cameraIdList.firstOrNull { cameraId ->
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    private fun handleVoiceCommand(rawCommand: String) {
        val command = normalizeCommand(rawCommand)
        val smsCommand = parseSmsCommand(command)
        val callTarget = extractCommandTarget(command, listOf("llamar a ", "llama a "))
        val navigationTarget = extractCommandTarget(command, listOf("navegar a ", "llevame a ", "ir a "))

        when {
            command == "ayuda" || command.contains("que puedes hacer") || command.contains("comandos") -> {
                speakAvailableCommands()
            }

            command.contains("enciende la linterna") ||
                command.contains("prende la linterna") ||
                (command.contains("enciende") && command.contains("linterna")) ||
                (command.contains("prende") && command.contains("linterna")) -> {
                setFlashlight(true)
            }

            command.contains("apaga la linterna") ||
                (command.contains("apaga") && command.contains("linterna")) -> {
                setFlashlight(false)
            }

            command.contains("que hora es") || command.contains("dime la hora") || command == "hora" -> {
                speakCurrentTime()
            }

            command.contains("que fecha es") || command.contains("dime la fecha") || command == "fecha" -> {
                speakCurrentDate()
            }

            command.contains("nivel de bateria") || command.contains("cuanta bateria tengo") -> {
                speakBatteryLevel()
            }

            command.contains("sube volumen") || command.contains("aumenta volumen") -> {
                changeVolume(up = true)
            }

            command.contains("baja volumen") || command.contains("disminuye volumen") -> {
                changeVolume(up = false)
            }

            command.contains("silencio") || command.contains("mute") -> {
                muteVolume()
            }

            command.contains("abrir camara") -> {
                openCamera()
            }

            command.contains("abrir mapas") || command.contains("abrir mapa") -> {
                openMaps()
            }

            command.contains("donde estoy") || command.contains("cual es mi ubicacion") || command == "mi ubicacion" -> {
                speakCurrentLocation()
            }

            navigationTarget != null -> {
                navigateTo(navigationTarget)
            }

            command.contains("abrir ajustes") || command.contains("configuracion") -> {
                openSettings(Settings.ACTION_SETTINGS)
            }

            command.contains("abrir wifi") || command.contains("ajustes wifi") -> {
                openSettings(Settings.ACTION_WIFI_SETTINGS)
            }

            command.contains("abrir bluetooth") || command.contains("ajustes bluetooth") -> {
                openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            }

            command.contains("llamar emergencia") || command.contains("llama a emergencia") -> {
                makePhoneCall("911")
            }

            callTarget != null -> {
                makePhoneCall(callTarget)
            }

            smsCommand != null -> {
                sendSmsToTarget(smsCommand.first, smsCommand.second)
            }

            command.contains("abrir whatsapp") -> {
                openAppByPackage("com.whatsapp", "WhatsApp")
            }

            command.contains("abrir youtube") -> {
                openAppByPackage("com.google.android.youtube", "YouTube")
            }

            else -> {
                speak("Escuche: $rawCommand")
            }
        }
    }

    private fun normalizeCommand(text: String): String {
        return Normalizer.normalize(text.lowercase(spanishLocale), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("[^a-z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun speakAvailableCommands() {
        speak(
            "Puedes decir: enciende o apaga la linterna, llamar a un contacto o numero, " +
                "enviar mensaje a alguien, donde estoy, navegar a un destino, abrir camara, " +
                "abrir mapas, abrir ajustes, subir o bajar volumen, silencio, hora, fecha y bateria."
        )
    }

    private fun extractCommandTarget(command: String, prefixes: List<String>): String? {
        val prefix = prefixes.firstOrNull { command.startsWith(it) } ?: return null
        val target = command.removePrefix(prefix).trim()
        return target.takeIf { it.isNotBlank() }
    }

    private fun parseSmsCommand(command: String): Pair<String, String>? {
        if (!command.startsWith("enviar mensaje a ")) return null

        val rest = command.removePrefix("enviar mensaje a ").trim()
        if (rest.isBlank()) return null

        val separators = listOf(" diciendo ", " que diga ", " mensaje ")
        for (separator in separators) {
            val index = rest.indexOf(separator)
            if (index > 0) {
                val target = rest.substring(0, index).trim()
                val body = rest.substring(index + separator.length).trim()
                if (target.isNotEmpty() && body.isNotEmpty()) {
                    return target to body
                }
            }
        }

        return rest to "Necesito ayuda, por favor."
    }

    private fun setFlashlight(enabled: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            speak("Necesito permiso de camara para controlar la linterna")
            return
        }

        val cameraId = torchCameraId
        if (cameraId == null) {
            speak("Este dispositivo no tiene linterna disponible")
            return
        }

        if (isTorchEnabled == enabled) {
            speak(if (enabled) "La linterna ya esta encendida" else "La linterna ya esta apagada")
            return
        }

        try {
            cameraManager.setTorchMode(cameraId, enabled)
            isTorchEnabled = enabled
            speak(if (enabled) "Linterna encendida" else "Linterna apagada")
        } catch (_: CameraAccessException) {
            speak("No pude cambiar el estado de la linterna")
        } catch (_: IllegalArgumentException) {
            speak("No pude acceder a la linterna del dispositivo")
        }
    }

    private fun makePhoneCall(target: String) {
        val phone = resolvePhoneTarget(target)
        if (phone == null) {
            speak("No encontre un numero para $target")
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

        val action = if (hasPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$phone"))

        try {
            startActivity(intent)
            if (hasPermission) {
                speak("Llamando a $target")
            } else {
                speak("No tengo permiso de llamada directa. Te abri el marcador con el numero")
            }
        } catch (_: ActivityNotFoundException) {
            speak("No pude iniciar la llamada")
        }
    }

    private fun sendSmsToTarget(target: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            speak("Necesito permiso para enviar mensajes")
            return
        }

        val phone = resolvePhoneTarget(target)
        if (phone == null) {
            speak("No encontre el numero de $target para enviar el mensaje")
            return
        }

        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            if (smsManager == null) {
                speak("No pude acceder al servicio de mensajes")
                return
            }

            smsManager.sendTextMessage(phone, null, message, null, null)
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
            PackageManager.PERMISSION_GRANTED
        ) {
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
        val formatter = DateTimeFormatter.ofPattern("HH:mm", spanishLocale)
        val time = LocalDateTime.now().format(formatter)
        speak("Son las $time")
    }

    private fun speakCurrentDate() {
        val formatter = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", spanishLocale)
        val date = LocalDateTime.now().format(formatter)
        speak("Hoy es $date")
    }

    private fun speakBatteryLevel() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        if (level < 0 || scale <= 0) {
            speak("No pude obtener el nivel de bateria")
            return
        }

        val percentage = (level * 100) / scale
        speak("Tu bateria esta al $percentage por ciento")
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
            speak("Abriendo camara")
        } catch (_: ActivityNotFoundException) {
            speak("No encontre una aplicacion de camara")
        }
    }

    private fun openMaps() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        intent.setPackage("com.google.android.apps.maps")

        try {
            startActivity(intent)
            speak("Abriendo mapas")
        } catch (_: ActivityNotFoundException) {
            speak("No encontre Google Maps en este dispositivo")
        }
    }

    private fun navigateTo(destination: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(destination)}"))
        intent.setPackage("com.google.android.apps.maps")

        try {
            startActivity(intent)
            speak("Iniciando navegacion a $destination")
        } catch (_: ActivityNotFoundException) {
            speak("No pude iniciar la navegacion porque Google Maps no esta disponible")
        }
    }

    private fun speakCurrentLocation() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            speak("Necesito permiso de ubicacion para decirte donde estas")
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null

        for (provider in providers) {
            val location = try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            }

            if (location != null && (bestLocation == null || location.accuracy < bestLocation!!.accuracy)) {
                bestLocation = location
            }
        }

        if (bestLocation == null) {
            speak("No tengo una ubicacion reciente disponible")
            return
        }

        val lat = String.format(Locale.US, "%.5f", bestLocation!!.latitude)
        val lon = String.format(Locale.US, "%.5f", bestLocation!!.longitude)
        speak("Tu ubicacion aproximada es latitud $lat y longitud $lon")
    }

    private fun openSettings(action: String) {
        val intent = Intent(action)
        try {
            startActivity(intent)
            speak("Abriendo ajustes")
        } catch (_: ActivityNotFoundException) {
            speak("No pude abrir esa pantalla de ajustes")
        }
    }

    private fun openAppByPackage(packageName: String, appName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            speak("No encontre $appName instalado")
            return
        }

        startActivity(launchIntent)
        speak("Abriendo $appName")
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
            val result = tts.setLanguage(spanishLocale)
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
