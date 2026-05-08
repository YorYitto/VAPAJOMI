package com.vapajomi.vapajomi

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONArray
import kotlin.math.*

class VoiceProfileManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceProfile"
        private const val PREFS = "voice_profile_prefs"
        private const val KEY_MEANS = "means"
        private const val KEY_STDS = "stds"
        private const val KEY_ENROLLED = "enrolled"

        const val SAMPLE_RATE = 16000
        const val RECORD_DURATION_MS = 4000L
        private const val FRAME_SAMPLES = 320     // 20ms at 16kHz
        const val NUM_FEATURES = 8
        private const val ACCEPTANCE_THRESHOLD = 7.0f
        const val ENROLLMENT_SAMPLES_REQUIRED = 3
        private const val MIN_VOICE_RMS = 80.0    // mínimo para considerar voz activa
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var stopFlag = false

    fun isEnrolled(): Boolean = prefs.getBoolean(KEY_ENROLLED, false)

    fun clearProfile() {
        prefs.edit().clear().apply()
    }

    fun stopRecording() {
        stopFlag = true
    }

    fun recordAudioBlocking(durationMs: Long = RECORD_DURATION_MS): ShortArray? {
        stopFlag = false
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord creation failed", e)
            return null
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }

        val totalSamples = (SAMPLE_RATE * durationMs / 1000L).toInt()
        val buffer = ShortArray(totalSamples)
        var offset = 0
        recorder.startRecording()

        try {
            while (offset < totalSamples && !stopFlag) {
                val chunk = minOf(minBuf, totalSamples - offset)
                val read = recorder.read(buffer, offset, chunk)
                if (read > 0) offset += read else break
            }
        } finally {
            recorder.stop()
            recorder.release()
        }

        return if (offset < totalSamples / 2) null else buffer.copyOf(offset)
    }

    fun hasActiveVoice(audio: ShortArray): Boolean {
        val rms = sqrt(audio.fold(0.0) { acc, v -> acc + v * v } / audio.size)
        return rms > MIN_VOICE_RMS
    }

    fun extractFeatures(audio: ShortArray): FloatArray {
        val normalized = FloatArray(audio.size) { audio[it] / 32768f }
        val frames = mutableListOf<FloatArray>()
        var i = 0
        while (i + FRAME_SAMPLES <= normalized.size) {
            frames.add(normalized.slice(i until i + FRAME_SAMPLES).toFloatArray())
            i += FRAME_SAMPLES / 2  // 50% overlap
        }
        if (frames.isEmpty()) return FloatArray(NUM_FEATURES)

        val rmsPerFrame = frames.map { computeRMS(it) }
        val zcrPerFrame = frames.map { computeZCR(it) }

        val meanRms = rmsPerFrame.average().toFloat()
        val stdRms = stdDev(rmsPerFrame).coerceAtLeast(1e-7f)
        val meanZcr = zcrPerFrame.average().toFloat()
        val stdZcr = stdDev(zcrPerFrame).coerceAtLeast(1e-7f)
        val maxRms = rmsPerFrame.maxOrNull() ?: 0f
        val minRms = rmsPerFrame.minOrNull() ?: 0f
        val dynamicRange = (maxRms - minRms).coerceAtLeast(1e-7f)
        val voicedRatio = zcrPerFrame.count { it < 0.12f }.toFloat() / frames.size.toFloat()
        val logEnergy = rmsPerFrame.map { ln(it.coerceAtLeast(1e-7f)) }.average().toFloat()
        val autoCorr = computeAutoCorr(normalized, lag = 80)

        return floatArrayOf(meanRms, stdRms, meanZcr, stdZcr, dynamicRange, voicedRatio, logEnergy, autoCorr)
    }

    private fun computeRMS(frame: FloatArray): Float {
        val sum = frame.fold(0.0) { acc, v -> acc + v * v }
        return sqrt(sum / frame.size).toFloat()
    }

    private fun computeZCR(frame: FloatArray): Float {
        var count = 0
        for (i in 1 until frame.size) {
            if (frame[i] * frame[i - 1] < 0f) count++
        }
        return count.toFloat() / frame.size
    }

    private fun computeAutoCorr(signal: FloatArray, lag: Int): Float {
        if (signal.size < lag + 256) return 0f
        var corr = 0.0
        val n = minOf(1024, signal.size - lag)
        for (i in 0 until n) corr += signal[i] * signal[i + lag]
        return (corr / n).toFloat()
    }

    private fun stdDev(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average()
        val variance = values.sumOf { ((it - mean) * (it - mean)).toDouble() } / values.size
        return sqrt(variance).toFloat()
    }

    fun enrollFromSamples(samples: List<ShortArray>): Boolean {
        if (samples.size < 2) return false

        val featureMatrix = samples.map { extractFeatures(it) }
        val means = FloatArray(NUM_FEATURES)
        val stds = FloatArray(NUM_FEATURES)

        for (j in 0 until NUM_FEATURES) {
            val vals = featureMatrix.map { it[j] }
            means[j] = vals.sum() / vals.size
            val variance = vals.sumOf { ((it - means[j]) * (it - means[j])).toDouble() }.toFloat() / vals.size
            val rawStd = sqrt(variance.toDouble()).toFloat()
            // Mínimo = 8% del valor medio para evitar que condiciones ambientales exploten la distancia
            val minStd = (abs(means[j]) * 0.08f).coerceAtLeast(0.015f)
            stds[j] = maxOf(rawStd, minStd)
        }

        val meansJson = JSONArray().apply { means.forEach { put(it.toDouble()) } }
        val stdsJson = JSONArray().apply { stds.forEach { put(it.toDouble()) } }

        prefs.edit()
            .putString(KEY_MEANS, meansJson.toString())
            .putString(KEY_STDS, stdsJson.toString())
            .putBoolean(KEY_ENROLLED, true)
            .apply()

        return true
    }

    fun verify(audio: ShortArray): VerificationResult {
        if (!isEnrolled()) return VerificationResult.NOT_ENROLLED
        if (!hasActiveVoice(audio)) return VerificationResult.REJECTED

        val meansStr = prefs.getString(KEY_MEANS, null) ?: return VerificationResult.NOT_ENROLLED
        val stdsStr = prefs.getString(KEY_STDS, null) ?: return VerificationResult.NOT_ENROLLED

        val means = FloatArray(NUM_FEATURES) { JSONArray(meansStr).getDouble(it).toFloat() }
        val stds = FloatArray(NUM_FEATURES) { JSONArray(stdsStr).getDouble(it).toFloat() }
        val features = extractFeatures(audio)

        var distSq = 0.0
        for (j in 0 until NUM_FEATURES) {
            val diff = (features[j] - means[j]) / stds[j]
            distSq += diff * diff
        }
        val distance = sqrt(distSq).toFloat()
        Log.d(TAG, "Voice distance: ${"%.3f".format(distance)} | threshold: $ACCEPTANCE_THRESHOLD | result: ${if (distance <= ACCEPTANCE_THRESHOLD) "ACCEPTED" else "REJECTED"}")

        return if (distance <= ACCEPTANCE_THRESHOLD) VerificationResult.ACCEPTED else VerificationResult.REJECTED
    }

    enum class VerificationResult {
        ACCEPTED,
        REJECTED,
        NOT_ENROLLED
    }
}
