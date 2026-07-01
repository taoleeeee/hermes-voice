package com.hermes.voice

import android.media.*
import android.media.AudioRecord
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class AudioBridge(private val activity: MainActivity, private val webView: WebView) {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var audioData = ByteArrayOutputStream()
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val audioManager: android.media.AudioManager by lazy {
        activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }

    private var mediaPlayer: MediaPlayer? = null
    private var speakerEnabled = false
    private var bluetoothEnabled = false

    @JavascriptInterface
    fun startRecording(): String {
        if (isRecording) return "already_recording"

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            return "error_bad_value"
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return "error_init"
            }

            audioData.reset()
            audioRecord?.startRecording()
            isRecording = true

            val vadEnabled = isVadEnabled()
            val silenceDurationMs = getSilenceDuration()
            val speechThreshold = getSpeechThreshold().toDouble()
            var hasSpoken = false
            var lastActiveTime = System.currentTimeMillis()

            recordingThread = Thread {
                val buffer = ByteArray(bufferSize)
                var lastAmplitudeUpdate = 0L
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        audioData.write(buffer, 0, read)
                        
                        // Calculate RMS amplitude for 16-bit Mono PCM
                        var sum = 0.0
                        for (i in 0 until read step 2) {
                            if (i + 1 < read) {
                                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                                sum += sample * sample
                            }
                        }
                        val rms = Math.sqrt(sum / (read / 2))
                        
                        // Call JS function to update visualizer (throttled to avoid JS bridge overload)
                        val now = System.currentTimeMillis()
                        if (now - lastAmplitudeUpdate > 50) {
                            lastAmplitudeUpdate = now
                            activity.runOnUiThread {
                                webView.evaluateJavascript("if (window.onMicVolume) window.onMicVolume($rms);", null)
                            }
                        }
                        
                        // Silence Detection VAD
                        if (vadEnabled) {
                            if (rms > speechThreshold) {
                                if (!hasSpoken) {
                                    hasSpoken = true
                                    activity.runOnUiThread {
                                        webView.evaluateJavascript("if (window.onSpeechStarted) window.onSpeechStarted();", null)
                                    }
                                }
                                lastActiveTime = now
                            } else if (hasSpoken) {
                                if (now - lastActiveTime > silenceDurationMs) {
                                    // Trigger silence detection (VAD completion)
                                    activity.runOnUiThread {
                                        webView.evaluateJavascript("if (window.onVadSilenceTriggered) window.onVadSilenceTriggered();", null)
                                    }
                                    stopAndSendNativeInternal()
                                    hasSpoken = false // reset to prevent multiple callbacks
                                }
                            }
                        }
                    }
                }
            }.apply { start() }

            return "ok"
        } catch (e: SecurityException) {
            return "permission_denied"
        } catch (e: Exception) {
            return "error:${e.message}"
        }
    }

    @JavascriptInterface
    fun stopRecording(): String {
        if (!isRecording) return ""
        isRecording = false

        try {
            recordingThread?.join(2000)
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            val pcmData = audioData.toByteArray()
            audioData.reset()

            val oggData = pcmToOgg(pcmData)
            return Base64.encodeToString(oggData, Base64.NO_WRAP)
        } catch (e: Exception) {
            return ""
        }
    }

    @JavascriptInterface
    fun stopAndSendNative() {
        stopAndSendNativeInternal()
    }

    private fun stopAndSendNativeInternal() {
        if (!isRecording) return
        isRecording = false

        Thread {
            try {
                recordingThread?.join(2000)
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null

                val pcmData = audioData.toByteArray()
                audioData.reset()

                val wavData = pcmToOgg(pcmData)
                sendAudioToBridge(wavData)
            } catch (e: Exception) {
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('Stop failed: ${e.message}');", null)
                }
            }
        }.start()
    }

    private fun sendAudioToBridge(wavData: ByteArray) {
        Thread {
            try {
                val bridgeUrl = getBridgeUrl()
                val url = java.net.URL("$bridgeUrl/voice")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 8000
                conn.readTimeout = 20000
                conn.doOutput = true
                conn.doInput = true
                conn.setRequestProperty("Content-Type", "audio/wav")

                conn.outputStream.use { os ->
                    os.write(wavData)
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val userTranscript = conn.getHeaderField("X-Transcript") ?: ""
                    val assistantResponse = conn.getHeaderField("X-Response") ?: ""
                    val audioBytes = conn.inputStream.use { it.readBytes() }

                    activity.runOnUiThread {
                        val escUser = JSONObject.quote(userTranscript)
                        val escAssistant = JSONObject.quote(assistantResponse)
                        webView.evaluateJavascript("if (window.onBridgeSuccess) window.onBridgeSuccess($escUser, $escAssistant);", null)
                        
                        playAudioBytes(audioBytes)
                    }
                } else {
                    val errorMsg = "Server returned code $responseCode"
                    activity.runOnUiThread {
                        webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('$errorMsg');", null)
                    }
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('$errorMsg');", null)
                }
            }
        }.start()
    }

    private fun playAudioBytes(audioBytes: ByteArray) {
        try {
            activity.runOnUiThread {
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer()

                    val tempFile = java.io.File.createTempFile("audio_resp_", ".mp3", activity.cacheDir)
                    tempFile.writeBytes(audioBytes)
                    tempFile.deleteOnExit()

                    mediaPlayer?.apply {
                        setDataSource(tempFile.absolutePath)

                        if (speakerEnabled) {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                        } else {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                        }

                        setOnPreparedListener {
                            start()
                            webView.evaluateJavascript("if (window.onAudioPlayStarted) window.onAudioPlayStarted();", null)
                        }
                        setOnCompletionListener {
                            webView.evaluateJavascript("if (window.onAudioPlayCompleted) window.onAudioPlayCompleted();", null)
                            tempFile.delete()
                        }
                        setOnErrorListener { _, what, extra ->
                            webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('$what:$extra');", null)
                            tempFile.delete()
                            true
                        }
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('${e.message}');", null)
                }
            }
        } catch (e: Exception) {
            webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('${e.message}');", null)
        }
    }

    private fun pcmToOgg(pcmData: ByteArray): ByteArray {
        // For simplicity, wrap PCM in a WAV-like container
        // The bridge server accepts audio/ogg but also handles raw audio
        val sampleCount = pcmData.size / 2
        val wav = ByteArrayOutputStream()
        wav.write("RIFF".toByteArray())
        wav.write(intToBytes(36 + pcmData.size))
        wav.write("WAVE".toByteArray())
        wav.write("fmt ".toByteArray())
        wav.write(intToBytes(16))
        wav.write(shortToBytes(1)) // PCM
        wav.write(shortToBytes(1)) // mono
        wav.write(intToBytes(sampleRate))
        wav.write(intToBytes(sampleRate * 2))
        wav.write(shortToBytes(2))
        wav.write(shortToBytes(16))
        wav.write("data".toByteArray())
        wav.write(intToBytes(pcmData.size))
        wav.write(pcmData)
        return wav.toByteArray()
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte()
        )
    }

    @JavascriptInterface
    fun playAudio(base64Audio: String) {
        try {
            val audioBytes = Base64.decode(base64Audio, Base64.DEFAULT)

            activity.runOnUiThread {
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer()

                    val tempFile = java.io.File.createTempFile("audio_", ".mp3", activity.cacheDir)
                    tempFile.writeBytes(audioBytes)
                    tempFile.deleteOnExit()

                    mediaPlayer?.apply {
                        setDataSource(tempFile.absolutePath)

                        if (speakerEnabled) {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                        } else {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                        }

                        setOnPreparedListener {
                            start()
                            webView.evaluateJavascript("onAudioPlayStarted()", null)
                        }
                        setOnCompletionListener {
                            webView.evaluateJavascript("onAudioPlayCompleted()", null)
                            tempFile.delete()
                        }
                        setOnErrorListener { _, what, extra ->
                            webView.evaluateJavascript("onAudioError('$what:$extra')", null)
                            tempFile.delete()
                            true
                        }
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    webView.evaluateJavascript("onAudioError('${e.message}')", null)
                }
            }
        } catch (e: Exception) {
            webView.evaluateJavascript("onAudioError('${e.message}')", null)
        }
    }

    @JavascriptInterface
    fun stopAudio() {
        activity.runOnUiThread {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
        }
    }

    @JavascriptInterface
    fun setSpeakerEnabled(enabled: Boolean) {
        speakerEnabled = enabled
        audioManager.isSpeakerphoneOn = enabled

        if (enabled) {
            bluetoothEnabled = false
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
        } else {
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        }
    }

    @JavascriptInterface
    fun setBluetoothEnabled(enabled: Boolean) {
        bluetoothEnabled = enabled
        if (enabled) {
            speakerEnabled = false
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } else {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            if (!speakerEnabled) {
                audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            }
        }
    }

    @JavascriptInterface
    fun getAudioDevices(): String {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
        val result = JSONArray()
        for (device in devices) {
            val obj = JSONObject()
            obj.put("id", device.id)
            obj.put("name", device.productName?.toString() ?: "Unknown")
            obj.put("type", device.type)
            obj.put("isSource", device.isSource)
            result.put(obj)
        }
        return result.toString()
    }

    @JavascriptInterface
    fun getBridgeUrl(): String {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getString("bridge_url", "http://100.67.204.21:8700") ?: "http://100.67.204.21:8700"
    }

    @JavascriptInterface
    fun setBridgeUrl(url: String) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("bridge_url", url).apply()
    }

    @JavascriptInterface
    fun hasPermission(permission: String): Boolean {
        return activity.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @JavascriptInterface
    fun isVadEnabled(): Boolean {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("vad_enabled", false)
    }

    @JavascriptInterface
    fun setVadEnabled(enabled: Boolean) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("vad_enabled", enabled).apply()
    }

    @JavascriptInterface
    fun getSilenceDuration(): Long {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getLong("silence_duration", 1500L)
    }

    @JavascriptInterface
    fun setSilenceDuration(durationMs: Long) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putLong("silence_duration", durationMs).apply()
    }

    @JavascriptInterface
    fun getSpeechThreshold(): Float {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getFloat("speech_threshold", 1000f)
    }

    @JavascriptInterface
    fun setSpeechThreshold(threshold: Float) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat("speech_threshold", threshold).apply()
    }

    @JavascriptInterface
    fun startForegroundService() {
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(activity, VoiceService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(intent)
                } else {
                    activity.startService(intent)
                }
            } catch (e: Exception) {
                // Ignore or log error
            }
        }
    }

    @JavascriptInterface
    fun stopForegroundService() {
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(activity, VoiceService::class.java)
                activity.stopService(intent)
            } catch (e: Exception) {
                // Ignore or log error
            }
        }
    }
}
