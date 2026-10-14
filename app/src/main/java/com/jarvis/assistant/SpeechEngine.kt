package com.jarvis.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/**
 * SpeechEngine for JARVIS Android
 * Supports:
 * - Google Gemini Neural Computer Voice Models (Aoede, Kore, Charon, Puck, Fenrir)
 * - Plays high-fidelity 24kHz WAV audio via MediaPlayer
 * - Native Hindi & Hinglish pronunciation with authentic human emotions
 * - Instant Barge-In / Force-Stopping
 * - Graceful fallback to Android native TTS if offline
 */
class SpeechEngine(
    private val context: Context,
    private val onReadyCallback: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    data class ComputerVoice(
        val id: String,
        val displayName: String,
        val description: String,
        val persona: String,
        val gender: String = "Female"
    )

    companion object {
        const val DEFAULT_VOICE = "Aoede"

        val COMPUTER_VOICES = listOf(
            ComputerVoice(
                id = "Aoede",
                displayName = "💖 Aoede (Warm & Lively Female)",
                description = "Original PC Jarvis voice. Natural Hinglish & Hindi.",
                persona = "girlfriend",
                gender = "Female"
            ),
            ComputerVoice(
                id = "Kore",
                displayName = "🌸 Kore (Calm & Soothing Female)",
                description = "Gentle, relaxed, companion tone.",
                persona = "calm",
                gender = "Female"
            ),
            ComputerVoice(
                id = "Charon",
                displayName = "🎩 Charon (British Butler / Stark AI)",
                description = "Deep, poised, authoritative male assistant.",
                persona = "butler",
                gender = "Male"
            ),
            ComputerVoice(
                id = "Puck",
                displayName = "⚡ Puck (Youthful & Playful Male)",
                description = "Energetic, spirited companion.",
                persona = "playful",
                gender = "Male"
            ),
            ComputerVoice(
                id = "Fenrir",
                displayName = "🛡️ Fenrir (Bold & Confident Male)",
                description = "Strong, clear, resolute male voice.",
                persona = "bold",
                gender = "Male"
            )
        )

        fun getDefaultVoiceForGender(gender: String): String {
            return if (gender.equals("male", ignoreCase = true)) "Charon" else "Aoede"
        }

        fun ensureWavHeader(audioBytes: ByteArray, sampleRate: Int = 24000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
            if (audioBytes.size < 4) return audioBytes

            // If already WAV (RIFF header)
            if (audioBytes[0] == 'R'.code.toByte() &&
                audioBytes[1] == 'I'.code.toByte() &&
                audioBytes[2] == 'F'.code.toByte() &&
                audioBytes[3] == 'F'.code.toByte()) {
                return audioBytes
            }

            // If MP3
            if ((audioBytes[0] == 'I'.code.toByte() && audioBytes[1] == 'D'.code.toByte() && audioBytes[2] == '3'.code.toByte()) ||
                (audioBytes[0] == 0xFF.toByte() && (audioBytes[1].toInt() and 0xE0) == 0xE0)) {
                return audioBytes
            }

            // Wrap raw PCM into standard 44-byte WAV header
            val totalAudioLen = audioBytes.size
            val totalDataLen = totalAudioLen + 36
            val byteRate = sampleRate * channels * (bitsPerSample / 8)

            val header = ByteArray(44)
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()

            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * (bitsPerSample / 8)).toByte()
            header[33] = 0
            header[34] = bitsPerSample.toByte()
            header[35] = 0

            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

            return header + audioBytes
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    var currentVoiceId: String = DEFAULT_VOICE
        private set
    var isMuted: Boolean = false

    private var onSpeechStart: (() -> Unit)? = null
    private var onSpeechDone: (() -> Unit)? = null

    init {
        // Initialize fallback TTS
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (_: Exception) {}
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeechStart?.invoke()
                }

                override fun onDone(utteranceId: String?) {
                    onSpeechDone?.invoke()
                }

                override fun onError(utteranceId: String?) {
                    onSpeechDone?.invoke()
                }
            })
            onReadyCallback?.invoke()
        }
    }

    fun setVoiceModel(voiceId: String) {
        currentVoiceId = voiceId
    }

    /**
     * Plays high-fidelity neural computer voice WAV audio returned from Gemini 3.8 Flash TTS
     */
    fun playNeuralAudio(
        wavBytes: ByteArray,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (isMuted) {
            onDone?.invoke()
            return
        }

        stop()

        this.onSpeechStart = onStart
        this.onSpeechDone = onDone

        try {
            val safeBytes = ensureWavHeader(wavBytes)
            val cacheFile = File(context.cacheDir, "jarvis_voice_temp.wav")
            FileOutputStream(cacheFile).use { it.write(safeBytes) }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .build()
                )
                setDataSource(cacheFile.absolutePath)
                setOnPreparedListener { mp ->
                    onSpeechStart?.invoke()
                    mp.start()
                }
                setOnCompletionListener {
                    cleanupMediaPlayer()
                    onSpeechDone?.invoke()
                }
                setOnErrorListener { _, _, _ ->
                    cleanupMediaPlayer()
                    onSpeechDone?.invoke()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            cleanupMediaPlayer()
            onDone?.invoke()
        }
    }

    /**
     * Fallback to native device TTS if network audio is unavailable
     */
    fun speakFallbackTts(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (isMuted || !isTtsReady || tts == null) {
            onDone?.invoke()
            return
        }

        stop()

        this.onSpeechStart = onStart
        this.onSpeechDone = onDone

        val clean = text
            .replace(Regex("[*#`_~>]"), "")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .replace(Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF]+"""), "")
            .trim()

        if (clean.isEmpty()) {
            onDone?.invoke()
            return
        }

        tts?.setPitch(1.05f)
        tts?.setSpeechRate(1.0f)
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * Standard speak method for background services & alarm receivers
     */
    fun speak(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        speakFallbackTts(text, onStart, onDone)
    }

    /**
     * Instant Barge-In: immediately halts all neural playback and TTS
     */
    fun stop() {
        cleanupMediaPlayer()
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (_: Exception) {}
        onSpeechDone?.invoke()
    }

    fun isSpeaking(): Boolean {
        return (mediaPlayer?.isPlaying == true) || (tts?.isSpeaking == true)
    }

    private fun cleanupMediaPlayer() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun shutdown() {
        cleanupMediaPlayer()
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        } catch (_: Exception) {}
    }
}
