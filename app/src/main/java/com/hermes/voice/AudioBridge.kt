package com.hermes.voice

import android.media.*
import android.media.AudioRecord
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.k2fsa.sherpa.onnx.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale

class AudioBridge(private val activity: MainActivity, private val webView: WebView) {

    private var mediaRecorder: MediaRecorder? = null
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null
    private var tempAudioFile: java.io.File? = null
    private var currentMimeType = "audio/wav"
    private var sherpaTts: OfflineTts? = null

    private val playQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    @Volatile private var isPlayingQueue = false
    @Volatile private var isStreamFinished = true
    @Volatile private var isTtsStreamingActive = false

    private val audioManager: android.media.AudioManager by lazy {
        activity.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    }

    private var mediaPlayer: MediaPlayer? = null
    private var speakerEnabled = false
    private var bluetoothEnabled = false

    // Android TTS engine
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsEngine: String? = null // default to system engine

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(activity, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val engine = tts?.defaultEngine ?: ""
                val result = tts?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
                activity.runOnUiThread {
                    webView.evaluateJavascript(
                        "if (window.onTtsReady) window.onTtsReady('$engine', ${result == TextToSpeech.LANG_AVAILABLE});",
                        null
                    )
                }
            }
        }, ttsEngine)
    }

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
                    try {
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
                    } catch (e: Exception) {
                        // MediaRecorder might have been stopped/released by another thread
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
            var fileData: ByteArray? = null
            var mime: String? = null
            try {
                recordingThread?.interrupt()
                recordingThread?.join(2000)
                mediaRecorder?.stop()
            } catch (e: Exception) {
                // Log or handle stop exception safely
            } finally {
                try {
                    mediaRecorder?.release()
                } catch (e: Exception) {}
                mediaRecorder = null

                val file = tempAudioFile
                if (file != null && file.exists()) {
                    if (file.length() > 0) {
                        try {
                            fileData = file.readBytes()
                            mime = currentMimeType
                        } catch (e: Exception) {}
                    }
                    file.delete()
                }
                tempAudioFile = null
            }

            if (fileData != null && mime != null) {
                sendAudioToBridge(fileData, mime)
            } else {
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('No audio recorded file found');", null)
                }
    private fun sendAudioToBridge(audioData: ByteArray, mimeType: String) {
        // If Kokoro mode, use streaming endpoint for low-latency sentence-by-sentence TTS
        val mode = getTtsMode()
        if (mode == "kokoro" && isLocalTtsEnabled()) {
            sendAudioToBridgeStreaming(audioData, mimeType)
            return
        }
        Thread {
            try {
                val bridgeUrl = getBridgeUrl()
                // Request text-only mode (no server TTS) - we speak locally
                val useLocalTts = isLocalTtsEnabled()
                val endpoint = if (useLocalTts) "$bridgeUrl/voice?tts=false" else "$bridgeUrl/voice"
                val url = java.net.URL(endpoint)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = getRequestTimeout().toInt()
                conn.doOutput = true
                conn.doInput = true
                conn.setRequestProperty("Content-Type", mimeType)

                conn.outputStream.use { os ->
                    os.write(audioData)
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    if (useLocalTts) {
                        val contentType = conn.contentType ?: ""
                        if (contentType.contains("text/event-stream")) {
                            val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                            var line: String?
                            var userTranscript = ""
                            val assistantResponseBuilder = StringBuilder()

                            // Set up a thread to generate and play Kokoro TTS sentences as they are received.
                            val sentenceQueue = java.util.concurrent.LinkedBlockingQueue<String>()

                            val ttsMode = getTtsMode()
                            val ttsThread = if (ttsMode == "kokoro") {
                                Thread {
                                    try {
                                        while (!isStreamFinished || sentenceQueue.isNotEmpty()) {
                                            val sentence = sentenceQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                                            if (sentence != null) {
                                                speakKokoroLocalSync(sentence)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // Ignore or log
                                    }
                                }.apply { start() }
                            } else {
                                null
                            }

                            val currentSentence = StringBuilder()

                            while (reader.readLine().also { line = it } != null) {
                                val lineStr = line?.trim() ?: ""
                                if (lineStr.startsWith("data:")) {
                                    val dataJsonStr = lineStr.substring(5).trim()
                                    try {
                                        val json = JSONObject(dataJsonStr)
                                        if (json.has("text_in")) {
                                            userTranscript = json.getString("text_in")
                                            activity.runOnUiThread {
                                                val escUser = JSONObject.quote(userTranscript)
                                                webView.evaluateJavascript("if (window.onBridgeSuccess) window.onBridgeSuccess($escUser, '');", null)
                                            }
                                        }
                                        if (json.has("text_out_chunk")) {
                                            val chunk = json.getString("text_out_chunk")
                                            assistantResponseBuilder.append(chunk)

                                            activity.runOnUiThread {
                                                val escChunk = JSONObject.quote(chunk)
                                                webView.evaluateJavascript("if (window.onBridgeChunk) window.onBridgeChunk($escChunk);", null)
                                            }

                                            if (ttsMode == "kokoro") {
                                                currentSentence.append(chunk)
                                                val textSoFar = currentSentence.toString()
                                                var boundaryIndex = -1
                                                for (i in textSoFar.indices) {
                                                    val c = textSoFar[i]
                                                    if (c == '.' || c == '?' || c == '!' || c == '\n') {
                                                        if (i == textSoFar.length - 1 || textSoFar[i + 1].isWhitespace()) {
                                                            boundaryIndex = i
                                                        }
                                                    }
                                                }
                                                if (boundaryIndex != -1) {
                                                    val sentence = textSoFar.substring(0, boundaryIndex + 1).trim()
                                                    if (sentence.isNotEmpty()) {
                                                        sentenceQueue.put(sentence)
                                                    }
                                                    currentSentence.delete(0, boundaryIndex + 1)
                                                }
                                            }
                                        }
                                        if (json.has("done") && json.getBoolean("done")) {
                                            isStreamFinished = true
                                        }
                                    } catch (e: Exception) {
                                        // Ignore parse errors on incomplete chunk json
                                    }
                                }
                            }

                            if (ttsMode == "kokoro") {
                                val remaining = currentSentence.toString().trim()
                                if (remaining.isNotEmpty()) {
                                    sentenceQueue.put(remaining)
                                }
                                isStreamFinished = true
                                ttsThread?.join(5000)
                            } else if (ttsMode == "system") {
                                val assistantResponse = assistantResponseBuilder.toString()
                                activity.runOnUiThread {
                                    speakText(assistantResponse)
                                }
                            }

                            isTtsStreamingActive = false

                            if (!isPlayingQueue && playQueue.isEmpty()) {
                                activity.runOnUiThread {
                                    webView.evaluateJavascript("if (window.onAudioPlayCompleted) window.onAudioPlayCompleted();", null)
                                }
                            }
                        } else {
                            isTtsStreamingActive = false
                            // Fallback: Text-only JSON response - speak locally
                            val responseBody = conn.inputStream.use { it.readBytes() }
                            val json = JSONObject(String(responseBody))
                            val userTranscript = json.optString("text_in", "")
                            val assistantResponse = json.optString("text_out", "")

                            activity.runOnUiThread {
                                val escUser = JSONObject.quote(userTranscript)
                                val escAssistant = JSONObject.quote(assistantResponse)
                                webView.evaluateJavascript("if (window.onBridgeSuccess) window.onBridgeSuccess($escUser, $escAssistant);", null)

                            // Speak locally via Android TTS or Kokoro Local
                            val mode = getTtsMode()
                            if (mode == "kokoro") {
                                speakKokoroLocal(assistantResponse)
                            } else {
                                speakText(assistantResponse)
                            }
                        }
                    } else {
                        // Legacy mode: server returned audio
                        val userTranscript = conn.getHeaderField("X-Transcript") ?: ""
                        val assistantResponse = conn.getHeaderField("X-Response") ?: ""
                        val audioBytes = conn.inputStream.use { it.readBytes() }

                        activity.runOnUiThread {
                            val escUser = JSONObject.quote(userTranscript)
                            val escAssistant = JSONObject.quote(assistantResponse)
                            webView.evaluateJavascript("if (window.onBridgeSuccess) window.onBridgeSuccess($escUser, $escAssistant);", null)

                            playAudioBytes(audioBytes)
                        }
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
            } finally {
                isStreamFinished = true
                isTtsStreamingActive = false
            }
        }.start()
    }

    // -----------------------------------------------------------------------
    // Streaming Kokoro TTS — sentence-by-sentence synthesis over SSE
    // -----------------------------------------------------------------------

    @Volatile private var isStreamingPlayback = false
    private val ttsQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray?>()
    private var audioTrack: android.media.AudioTrack? = null

    private fun sendAudioToBridgeStreaming(audioData: ByteArray, mimeType: String) {
        Thread {
            try {
                val bridgeUrl = getBridgeUrl()
                val url = java.net.URL("$bridgeUrl/voice/stream?tts=false")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = getRequestTimeout().toInt()
                conn.doOutput = true
                conn.doInput = true
                conn.setRequestProperty("Content-Type", mimeType)

                conn.outputStream.use { os ->
                    os.write(audioData)
                    os.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val errorMsg = "Stream returned code $responseCode"
                    activity.runOnUiThread {
                        webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('$errorMsg');", null)
                    }
                    return@Thread
                }

                // Notify UI that we're processing
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onAudioPlayStarted) window.onAudioPlayStarted();", null)
                }

                // Init Kokoro engine once (before streaming)
                initSherpaTts()
                val tts = sherpaTts
                if (tts == null) {
                    val errorMsg = "Kokoro TTS init failed"
                    activity.runOnUiThread {
                        webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('$errorMsg');", null)
                    }
                    return@Thread
                }

                // Start playback thread (consumes PCM queue)
                startQueuePlayback()

                var userTranscript = ""
                val fullResponse = StringBuilder()

                // Read SSE stream
                conn.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (!l.startsWith("data: ")) continue

                        val jsonStr = l.removePrefix("data: ").trim()
                        if (jsonStr.isEmpty()) continue

                        try {
                            val event = JSONObject(jsonStr)
                            when (event.optString("event")) {
                                "transcript" -> {
                                    userTranscript = event.optString("text", "")
                                    activity.runOnUiThread {
                                        val esc = JSONObject.quote(userTranscript)
                                        webView.evaluateJavascript("if (window.onBridgeSuccess) window.onBridgeSuccess($esc, '');", null)
                                    }
                                }
                                "sentence" -> {
                                    val sentence = event.optString("text", "").trim()
                                    if (sentence.isNotEmpty()) {
                                        fullResponse.append(sentence).append(" ")
                                        // Update UI with partial response
                                        activity.runOnUiThread {
                                            val esc = JSONObject.quote(fullResponse.toString().trim())
                                            webView.evaluateJavascript("if (window.onBridgeSuccess) window.onBridgeSuccess(${JSONObject.quote(userTranscript)}, $esc);", null)
                                        }
                                        // Synthesize this sentence immediately
                                        synthesizeAndQueue(tts, sentence)
                                    }
                                }
                                "done" -> {
                                    // Final response — already handled sentence by sentence
                                    val finalText = event.optString("text_out", fullResponse.toString().trim())
                                    Log.i("AudioBridge", "[stream-kokoro] Done: ${finalText.take(80)}")
                                }
                                "error" -> {
                                    val errText = event.optString("text", "Unknown error")
                                    activity.runOnUiThread {
                                        webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('$errText');", null)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("AudioBridge", "[stream-kokoro] Parse error: $e")
                        }
                    }
                }

                // Signal playback thread that we're done
                ttsQueue.put(null)

            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown error"
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('$errorMsg');", null)
                }
                ttsQueue.put(null) // unblock playback thread
            }
        }.start()
    }

    private fun synthesizeAndQueue(tts: OfflineTts, text: String) {
        try {
            val voiceStr = getKokoroVoice()
            val sid = when (voiceStr) {
                "af" -> 0
                "af_bella" -> 1
                "af_nicole" -> 2
                "af_sarah" -> 3
                "af_sky" -> 4
                "am_adam" -> 5
                "am_michael" -> 6
                "bf_emma" -> 7
                "bf_isabella" -> 8
                "bm_george" -> 9
                "bm_lewis" -> 10
                else -> 1
            }
            val speed = getKokoroSpeed()

            val audio = tts.generate(text = text, sid = sid, speed = speed)
            
            // Peak normalization to fix low volume issue
            var maxVal = 0.0f
            for (s in audio.samples) {
                val absS = Math.abs(s)
                if (absS > maxVal) {
                    maxVal = absS
                }
            }
            val scale = if (maxVal > 0.01f) 0.95f / maxVal else 1.0f

            // Convert float samples to PCM16 bytes directly (no WAV header needed for AudioTrack)
            val pcmData = ByteArray(audio.samples.size * 2)
            for (i in audio.samples.indices) {
                val scaledSample = audio.samples[i] * scale
                val sample = Math.max(-1.0f, Math.min(1.0f, scaledSample))
                val intSample = (sample * 32767).toInt().toShort()
                pcmData[i * 2] = (intSample.toInt() and 0xff).toByte()
                pcmData[i * 2 + 1] = ((intSample.toInt() shr 8) and 0xff).toByte()
            }
            ttsQueue.put(pcmData)
        } catch (e: Exception) {
            Log.e("AudioBridge", "[kokoro] Synth error: ${e.message}")
        }
    }

    private fun startQueuePlayback() {
        if (isStreamingPlayback) return
        isStreamingPlayback = true

        Thread {
            try {
                val sampleRate = 24000 // Kokoro output sample rate
                val bufSize = android.media.AudioTrack.getMinBufferSize(
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                )

                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(
                                if (speakerEnabled) android.media.AudioAttributes.USAGE_MEDIA
                                else android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
                            )
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(bufSize, 4096))
                    .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                track.play()

                while (true) {
                    val pcm = ttsQueue.take() // blocks until available
                    if (pcm == null) break // null = done signal
                    track.write(pcm, 0, pcm.size)
                }

                // Wait for last samples to play out
                Thread.sleep(100)
                track.stop()
                track.release()
                audioTrack = null

                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onAudioPlayCompleted) window.onAudioPlayCompleted();", null)
                }
            } catch (e: Exception) {
                Log.e("AudioBridge", "[kokoro-playback] Error: ${e.message}")
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onAudioPlayCompleted) window.onAudioPlayCompleted();", null)
                }
            } finally {
                isStreamingPlayback = false
                ttsQueue.clear()
            }
        }.start()
    }

    // -----------------------------------------------------------------------
    // Android native TTS
    // -----------------------------------------------------------------------

    private fun speakText(text: String) {
        if (!ttsReady || tts == null) {
            webView.evaluateJavascript("if (window.onBridgeError) window.onBridgeError('TTS not ready');", null)
            return
        }

        val speed = getTtsSpeed()
        tts?.setSpeechRate(speed)

        webView.evaluateJavascript("if (window.onAudioPlayStarted) window.onAudioPlayStarted();", null)

        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onAudioPlayCompleted) window.onAudioPlayCompleted();", null)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('TTS error');", null)
                }
            }
        })

        val params = android.os.Bundle()
        if (speakerEnabled) {
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        } else {
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "hermes_tts")
    }

    @JavascriptInterface
    fun stopSpeaking() {
        tts?.stop()
        // Stop streaming Kokoro playback
        ttsQueue.clear()
        ttsQueue.put(null) // unblock playback thread
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioTrack = null
        isStreamingPlayback = false
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
        tts?.stop()
        playQueue.clear()
        isPlayingQueue = false
        isStreamFinished = true
        isTtsStreamingActive = false
        activity.runOnUiThread {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = null
        }
    }

    private fun routeToSpeaker(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val speakerDevice = devices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                }
            } else {
                val currentDevice = audioManager.communicationDevice
                if (currentDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    audioManager.clearCommunicationDevice()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = enabled
        }
    }

    private fun routeToBluetooth(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val bluetoothDevice = devices.find {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (bluetoothDevice != null) {
                    audioManager.setCommunicationDevice(bluetoothDevice)
                }
            } else {
                val currentDevice = audioManager.communicationDevice
                if (currentDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    currentDevice?.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    audioManager.clearCommunicationDevice()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (enabled) {
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            } else {
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
            }
        }
    }

    @JavascriptInterface
    fun setSpeakerEnabled(enabled: Boolean) {
        speakerEnabled = enabled

        if (enabled) {
            bluetoothEnabled = false
            routeToBluetooth(false)
            audioManager.mode = android.media.AudioManager.MODE_NORMAL
            routeToSpeaker(true)
        } else {
            routeToSpeaker(false)
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        }
    }

    @JavascriptInterface
    fun setBluetoothEnabled(enabled: Boolean) {
        bluetoothEnabled = enabled
        if (enabled) {
            speakerEnabled = false
            routeToSpeaker(false)
            audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
            routeToBluetooth(true)
        } else {
            routeToBluetooth(false)
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
    fun getRequestTimeout(): Long {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getLong("request_timeout", 120000L)
    }

    @JavascriptInterface
    fun setRequestTimeout(timeoutMs: Long) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putLong("request_timeout", timeoutMs).apply()
    }

    // TTS settings
    @JavascriptInterface
    fun isLocalTtsEnabled(): Boolean {
        val mode = getTtsMode()
        return mode == "system" || mode == "kokoro"
    }

    @JavascriptInterface
    fun getTtsMode(): String {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getString("tts_mode", "system") ?: "system"
    }

    @JavascriptInterface
    fun setTtsMode(mode: String) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("tts_mode", mode).apply()
    }

    @JavascriptInterface
    fun getTtsSpeed(): Float {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getFloat("tts_speed", 1.1f) // slightly faster than normal
    }

    @JavascriptInterface
    fun setTtsSpeed(speed: Float) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat("tts_speed", speed).apply()
    }

    @JavascriptInterface
    fun getKokoroVoice(): String {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getString("kokoro_voice", "af_bella") ?: "af_bella"
    }

    @JavascriptInterface
    fun setKokoroVoice(voice: String) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("kokoro_voice", voice).apply()
    }

    @JavascriptInterface
    fun getKokoroSpeed(): Float {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        return prefs.getFloat("kokoro_speed", 1.0f)
    }

    @JavascriptInterface
    fun setKokoroSpeed(speed: Float) {
        val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
        prefs.edit().putFloat("kokoro_speed", speed).apply()
    }

    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, assetPath: String, destPath: String): Boolean {
        try {
            val files = assetManager.list(assetPath) ?: return false
            if (files.isEmpty()) {
                copyAssetFile(assetManager, assetPath, destPath)
            } else {
                val dir = java.io.File(destPath)
                if (!dir.exists()) dir.mkdirs()
                for (file in files) {
                    val subAssetPath = if (assetPath.isEmpty()) file else "$assetPath/$file"
                    val subDestPath = "$destPath/$file"
                    copyAssetFolder(assetManager, subAssetPath, subDestPath)
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun copyAssetFile(assetManager: android.content.res.AssetManager, assetPath: String, destPath: String) {
        val destFile = java.io.File(destPath)
        if (destFile.exists()) return
        assetManager.open(assetPath).use { inputStream ->
            java.io.FileOutputStream(destFile).use { outputStream ->
                val buffer = ByteArray(4096)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
        }
    }

    private fun downloadFile(urlStr: String, destFile: java.io.File, onProgress: (Int) -> Unit) {
        val url = java.net.URL(urlStr)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 300000  // 5 min for large files
        val responseCode = conn.responseCode
        if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
            throw java.io.IOException("Server returned HTTP $responseCode for $urlStr")
        }

        val contentLength = conn.contentLength
        var bytesCopied = 0L

        val tempFile = java.io.File(destFile.absolutePath + ".tmp")
        tempFile.parentFile?.mkdirs()

        conn.inputStream.use { inputStream ->
            java.io.FileOutputStream(tempFile).use { outputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    bytesCopied += bytesRead
                    if (contentLength > 0) {
                        val progress = ((bytesCopied * 100) / contentLength).toInt()
                        onProgress(progress)
                    }
                }
            }
        }
        
        if (destFile.exists()) destFile.delete()
        tempFile.renameTo(destFile)
    }

    @JavascriptInterface
    fun isKokoroModelDownloaded(): Boolean {
        val kokoroDir = java.io.File(activity.filesDir, "kokoro")
        val modelFile = java.io.File(kokoroDir, "model.onnx")
        val voicesFile = java.io.File(kokoroDir, "voices.bin")
        return modelFile.exists() && modelFile.length() > 10 * 1024 * 1024 &&
               voicesFile.exists() && voicesFile.length() > 5 * 1024 * 1024
    }

    @JavascriptInterface
    fun downloadKokoroModels() {
        Thread {
            try {
                val kokoroDir = java.io.File(activity.filesDir, "kokoro")
                if (!kokoroDir.exists()) kokoroDir.mkdirs()

                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onKokoroDownloadProgress) window.onKokoroDownloadProgress('Extracting phonemizer data...', 0);", null)
                }

                copyAssetFolder(activity.assets, "espeak-ng-data", java.io.File(kokoroDir, "espeak-ng-data").absolutePath)
                copyAssetFile(activity.assets, "tokens.txt", java.io.File(kokoroDir, "tokens.txt").absolutePath)

                val prefs = activity.getSharedPreferences("hermes_voice", android.content.Context.MODE_PRIVATE)
                val bridgeUrl = prefs.getString("bridge_url", "http://192.168.1.100:8700") ?: "http://192.168.1.100:8700"
                val modelFile = java.io.File(kokoroDir, "model.onnx")
                if (!modelFile.exists() || modelFile.length() < 10 * 1024 * 1024) {
                    downloadFile("$bridgeUrl/kokoro/model.onnx", modelFile) { progress ->
                        activity.runOnUiThread {
                            webView.evaluateJavascript("if (window.onKokoroDownloadProgress) window.onKokoroDownloadProgress('Downloading model.onnx', $progress);", null)
                        }
                    }
                }

                val voicesFile = java.io.File(kokoroDir, "voices.bin")
                if (!voicesFile.exists() || voicesFile.length() < 5 * 1024 * 1024) {
                    downloadFile("$bridgeUrl/kokoro/voices.bin", voicesFile) { progress ->
                        activity.runOnUiThread {
                            webView.evaluateJavascript("if (window.onKokoroDownloadProgress) window.onKokoroDownloadProgress('Downloading voices.bin', $progress);", null)
                        }
                    }
                }

                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onKokoroDownloadComplete) window.onKokoroDownloadComplete();", null)
                }
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Unknown download error"
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onKokoroDownloadError) window.onKokoroDownloadError('$errorMsg');", null)
                }
            }
        }.start()
    }

    private fun initSherpaTts() {
        if (sherpaTts != null) return

        val kokoroDir = java.io.File(activity.filesDir, "kokoro")
        val modelFile = java.io.File(kokoroDir, "model.onnx")
        val voicesFile = java.io.File(kokoroDir, "voices.bin")
        val tokensFile = java.io.File(kokoroDir, "tokens.txt")
        val dataDir = java.io.File(kokoroDir, "espeak-ng-data")

        if (!modelFile.exists() || !voicesFile.exists() || !tokensFile.exists() || !dataDir.exists()) {
            throw IllegalStateException("Kokoro model files are missing. Please download them in settings first.")
        }

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = modelFile.absolutePath,
                    voices = voicesFile.absolutePath,
                    tokens = tokensFile.absolutePath,
                    dataDir = dataDir.absolutePath,
                ),
                numThreads = 2,
                debug = false
            )
        )
        sherpaTts = OfflineTts(config = config)
    }

    private fun convertFloatToWav(samples: FloatArray, sampleRate: Int): ByteArray {
        var maxVal = 0.0f
        for (s in samples) {
            val absS = Math.abs(s)
            if (absS > maxVal) {
                maxVal = absS
            }
        }
        val scale = if (maxVal > 0.01f) 0.95f / maxVal else 1.0f

        val pcmData = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val scaledSample = samples[i] * scale
            val sample = Math.max(-1.0f, Math.min(1.0f, scaledSample))
            val intSample = (sample * 32767).toInt().toShort()
            pcmData[i * 2] = (intSample.toInt() and 0xff).toByte()
            pcmData[i * 2 + 1] = ((intSample.toInt() shr 8) and 0xff).toByte()
        }

        val totalAudioLen = pcmData.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8

        val header = ByteArray(44)
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = ((longSampleRate shr 8) and 0xff).toByte()
        header[26] = ((longSampleRate shr 16) and 0xff).toByte()
        header[27] = ((longSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (1 * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val wavFile = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wavFile, 0, 44)
        System.arraycopy(pcmData, 0, wavFile, 44, pcmData.size)
        return wavFile
    }

    private fun speakKokoroLocal(text: String) {
        Thread {
            speakKokoroLocalSync(text)
        }.start()
    }

    private fun speakKokoroLocalSync(text: String) {
        try {
            initSherpaTts()
            val tts = sherpaTts ?: throw IllegalStateException("Offline TTS initialization failed")

            val voiceStr = getKokoroVoice()
            val sid = when (voiceStr) {
                "af" -> 0
                "af_bella" -> 1
                "af_nicole" -> 2
                "af_sarah" -> 3
                "af_sky" -> 4
                "am_adam" -> 5
                "am_michael" -> 6
                "bf_emma" -> 7
                "bf_isabella" -> 8
                "bm_george" -> 9
                "bm_lewis" -> 10
                else -> 1
            }
            val speed = getKokoroSpeed()

            val audio = tts.generate(text = text, sid = sid, speed = speed)
            val wavBytes = convertFloatToWav(audio.samples, audio.sampleRate)

            queueAndPlayAudio(wavBytes)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown Kokoro TTS error"
            activity.runOnUiThread {
                webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('$errorMsg');", null)
            }
        }
    }

    private fun queueAndPlayAudio(wavBytes: ByteArray) {
        playQueue.put(wavBytes)
        if (!isPlayingQueue) {
            playNextInQueue()
        }
    }

    private fun playNextInQueue() {
        val nextBytes = playQueue.poll()
        if (nextBytes == null) {
            isPlayingQueue = false
            if (!isTtsStreamingActive) {
                activity.runOnUiThread {
                    webView.evaluateJavascript("if (window.onAudioPlayCompleted) window.onAudioPlayCompleted();", null)
                }
            }
            return
        }
        isPlayingQueue = true
        activity.runOnUiThread {
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer()

                val tempFile = java.io.File.createTempFile("audio_resp_", ".wav", activity.cacheDir)
                tempFile.writeBytes(nextBytes)
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
                        tempFile.delete()
                        playNextInQueue()
                    }
                    setOnErrorListener { _, what, extra ->
                        tempFile.delete()
                        webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('$what:$extra');", null)
                        playNextInQueue()
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                webView.evaluateJavascript("if (window.onAudioError) window.onAudioError('${e.message}');", null)
                playNextInQueue()
            }
        }
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
