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

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var tempAudioFile: java.io.File? = null
    private var currentMimeType = "audio/wav"

    private val audioManager: android.media.AudioManager by lazy {
        activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }

    private var mediaPlayer: MediaPlayer? = null
    private var speakerEnabled = false
    private var bluetoothEnabled = false

    @JavascriptInterface
    fun startRecording(): String {
        if (isRecording) return "already_recording"

        try {
            val useOgg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val fileSuffix = if (useOgg) ".ogg" else ".m4a"
            currentMimeType = if (useOgg) "audio/ogg" else "audio/mp4"

            tempAudioFile = java.io.File.createTempFile("hermes_rec_", fileSuffix, activity.cacheDir)

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(activity)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                if (useOgg) {
                    setOutputFormat(11) // MediaRecorder.OutputFormat.OGG is 11
                    setAudioEncoder(7)  // MediaRecorder.AudioEncoder.OPUS is 7
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                setAudioEncodingBitRate(24000)
                setAudioSamplingRate(16000)
                setOutputFile(tempAudioFile?.absolutePath)
                prepare()
                start()
            }

            isRecording = true

            val vadEnabled = isVadEnabled()
            val silenceDurationMs = getSilenceDuration()
            val speechThreshold = getSpeechThreshold().toDouble()
            var hasSpoken = false
            var lastActiveTime = System.currentTimeMillis()

            recordingThread = Thread {
                var lastAmplitudeUpdate = 0L
                while (isRecording) {
                    val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                    val rms = maxAmp.toDouble()

                    val now = System.currentTimeMillis()
                    if (now - lastAmplitudeUpdate > 50) {
                        lastAmplitudeUpdate = now
                        activity.runOnUiThread {
                            webView.evaluateJavascript("if (window.onMicVolume) window.onMicVolume($rms);", null)
                        }
                    }

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
                                activity.runOnUiThread {
                                    webView.evaluateJavascript("if (window.onVadSilenceTriggered) window.onVadSilenceTriggered();", null)
                                }
                                stopAndSendNativeInternal()
                                hasSpoken = false
                            }
                        }
                    }

                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        break
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
            recordingThread?.interrupt()
            recordingThread?.join(2000)
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null

            val file = tempAudioFile
            if (file != null && file.exists()) {
                val data = file.readBytes()
                file.delete()
                tempAudioFile = null
                return Base64.encodeToString(data, Base64.NO_WRAP)
            }
            return ""
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
                recordingThread?.interrupt()
                recordingThread?.join(2000)
                mediaRecorder?.stop()
                mediaRecorder?.release()
                mediaRecorder = null

                val file = tempAudioFile
                if (file != null && file.exists()) {
                    val compressedData = file.readBytes()
                    file.delete()
                    tempAudioFile = null
                    sendAudioToBridge(compressedData, currentMimeType)
                } else {
                    activity.runOnUiThread {
                        webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('No audio recorded file found');", null)
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('Stop failed: ${e.message}');", null)
                }
            }
        }.start()
    }

    private fun sendAudioToBridge(audioData: ByteArray, mimeType: String) {
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
                conn.setRequestProperty("Content-Type", mimeType)

                conn.outputStream.use { os ->
                    os.write(audioData)
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

    // WAV wrapping helpers removed, compression handles native containers

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
        return prefs.getString("bridge_url", "http://192.168.1.100:8700") ?: "http://192.168.1.100:8700"
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
