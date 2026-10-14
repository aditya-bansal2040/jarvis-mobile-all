package com.jarvis.assistant

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Top Header
    private lateinit var tvAppTitle: TextView
    private lateinit var tvActiveProfile: TextView
    private lateinit var btnWipeMemory: ImageButton
    private lateinit var btnSettings: ImageButton

    // Voice HUD & Arc Reactor
    private lateinit var hologramView: HologramView
    private lateinit var tvVoiceStatus: TextView
    private lateinit var tvHearingPreview: TextView
    private lateinit var btnVoiceToggle: Button
    private lateinit var btnVoiceStop: ImageButton

    // Chat UI
    private lateinit var rvChatMessages: RecyclerView
    private lateinit var tvTypingIndicator: TextView

    // Quick Chips
    private lateinit var chipSuno: Button
    private lateinit var chipTired: Button
    private lateinit var chipStudy: Button
    private lateinit var chipMood: Button

    // Bottom Input Bar
    private lateinit var etUserInput: EditText
    private lateinit var btnMicInput: ImageButton
    private lateinit var btnSendText: ImageButton

    // Core Systems
    private lateinit var prefs: SharedPreferences
    private lateinit var geminiClient: GeminiClient
    private lateinit var speechEngine: SpeechEngine
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null

    private val gson = Gson()
    private val chatList = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private val mainHandler = Handler(Looper.getMainLooper())

    // State Variables
    private var isListening = false
    private var isSpeaking = false
    private var isGenerating = false
    private var isContinuousMode = false

    companion object {
        const val PREFS_NAME = "jarvis_prefs"
        const val KEY_API = "gemini_api_key"
        const val KEY_MODEL = "gemini_model"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_GENDER = "user_gender"
        const val KEY_ASSISTANT_NAME = "assistant_name"
        const val KEY_ASSISTANT_GENDER = "assistant_gender"
        const val KEY_RELATIONSHIP_ROLE = "relationship_role"
        const val KEY_VOICE_MODEL = "gemini_computer_voice"
        const val KEY_CONTINUOUS_MODE = "continuous_voice_mode"
        const val KEY_HISTORY = "chat_history_json"

        private const val REQUEST_RECORD_AUDIO = 201
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        geminiClient = GeminiClient()
        speechEngine = SpeechEngine(this)

        val savedVoice = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE)
            ?: SpeechEngine.DEFAULT_VOICE
        speechEngine.setVoiceModel(savedVoice)
        isContinuousMode = prefs.getBoolean(KEY_CONTINUOUS_MODE, false)

        initViews()
        initSpeechRecognizer()
        loadHistory()

        // Check if API key is configured (1st open onboarding)
        val apiKey = prefs.getString(KEY_API, "")
        if (apiKey.isNullOrBlank()) {
            showSettingsDialog(isFirstTime = true)
        }
    }

    private fun initViews() {
        tvAppTitle = findViewById(R.id.tvAppTitle)
        tvActiveProfile = findViewById(R.id.tvActiveProfile)
        btnWipeMemory = findViewById(R.id.btnWipeMemory)
        btnSettings = findViewById(R.id.btnSettings)

        hologramView = findViewById(R.id.hologramView)
        tvVoiceStatus = findViewById(R.id.tvVoiceStatus)
        tvHearingPreview = findViewById(R.id.tvHearingPreview)
        btnVoiceToggle = findViewById(R.id.btnVoiceToggle)
        btnVoiceStop = findViewById(R.id.btnVoiceStop)

        rvChatMessages = findViewById(R.id.rvChatMessages)
        tvTypingIndicator = findViewById(R.id.tvTypingIndicator)

        chipSuno = findViewById(R.id.chipSuno)
        chipTired = findViewById(R.id.chipTired)
        chipStudy = findViewById(R.id.chipStudy)
        chipMood = findViewById(R.id.chipMood)

        etUserInput = findViewById(R.id.etUserInput)
        btnMicInput = findViewById(R.id.btnMicInput)
        btnSendText = findViewById(R.id.btnSendText)

        // Setup Chat RecyclerView
        chatAdapter = ChatAdapter(chatList)
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChatMessages.layoutManager = layoutManager
        rvChatMessages.adapter = chatAdapter

        // Voice Controls
        btnVoiceToggle.setOnClickListener {
            handleMicButtonTapped()
        }

        btnMicInput.setOnClickListener {
            handleMicButtonTapped()
        }

        btnVoiceStop.setOnClickListener {
            handleBargeIn()
        }

        hologramView.setOnClickListener {
            if (isSpeaking) {
                handleBargeIn()
            } else {
                handleMicButtonTapped()
            }
        }

        // Send Text Actions
        btnSendText.setOnClickListener { sendMessage() }
        etUserInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendMessage()
                true
            } else false
        }

        // Quick Prompt Chips
        chipSuno.setOnClickListener { sendQuickPrompt("How do I set you up and what can you do?") }
        chipTired.setOnClickListener { sendQuickPrompt("I want you to be my personal manager") }
        chipStudy.setOnClickListener { sendQuickPrompt("Can you help motivate and organize my study schedule?") }
        chipMood.setOnClickListener { sendQuickPrompt("How are you feeling today?") }

        // Wipe Memory
        btnWipeMemory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Wipe Memory")
                .setMessage("Clear conversation memory and start fresh with a clean slate?")
                .setPositiveButton("WIPE") { _, _ -> wipeChat() }
                .setNegativeButton("CANCEL", null)
                .show()
        }

        // Settings
        btnSettings.setOnClickListener {
            showSettingsDialog(isFirstTime = false)
        }

        updateProfileHeader()
        resetVoiceHudToIdle("Tap TALK or mic to speak in English or Hindi")
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            tvHearingPreview.text = "Speech recognition unavailable on device"
            return
        }

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-US"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                hologramView.setState(HologramView.State.LISTENING)
                val userName = prefs.getString(KEY_USER_NAME, "Friend") ?: "Friend"
                tvVoiceStatus.text = "🟢 LISTENING... Speak now $userName 👂"
                tvVoiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green_ready))
                tvHearingPreview.text = "Listening... Speak in Hindi, Hinglish or English"
                btnVoiceToggle.text = "🔴 STOP"
                btnVoiceToggle.setBackgroundResource(R.drawable.bg_btn_stop)
                btnVoiceToggle.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.red_stop))
                btnVoiceStop.visibility = View.VISIBLE
            }

            override fun onBeginningOfSpeech() {
                tvVoiceStatus.text = "🟢 LISTENING... Hearing speech..."
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                hologramView.setState(HologramView.State.THINKING)
                val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
                tvVoiceStatus.text = "🟡 THINKING... $assistantName processing 🤔"
                tvVoiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.gold_glow))
            }

            // CRITICAL FIX: NEVER loop on error or silence timeouts! Stop calmly.
            override fun onError(error: Int) {
                isListening = false
                resetVoiceHudToIdle("Mic closed. Tap TALK to speak again.")
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull()?.trim() ?: ""
                if (spoken.isNotBlank()) {
                    processVoiceInput(spoken)
                } else {
                    resetVoiceHudToIdle("No speech caught. Tap TALK to try again.")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = partialMatches?.firstOrNull()?.trim()
                if (!partialText.isNullOrBlank()) {
                    tvHearingPreview.text = "Hearing: \"$partialText\""
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun handleMicButtonTapped() {
        if (isSpeaking) {
            handleBargeIn()
            return
        }

        if (isListening) {
            safeStopListening()
            resetVoiceHudToIdle("Cancelled. Tap TALK to speak.")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
            return
        }

        startListening()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initSpeechRecognizer()
                startListening()
            } else {
                Toast.makeText(this, "Microphone permission is required to talk.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startListening() {
        if (isSpeaking || isGenerating) return
        safeStopListening()
        mainHandler.post {
            try {
                speechRecognizer?.startListening(speechIntent)
            } catch (e: Exception) {
                initSpeechRecognizer()
                try {
                    speechRecognizer?.startListening(speechIntent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun safeStopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        isListening = false
    }

    private fun handleBargeIn() {
        speechEngine.stop()
        isSpeaking = false
        btnVoiceStop.visibility = View.GONE
        resetVoiceHudToIdle("Speech interrupted.")
    }

    private fun resetVoiceHudToIdle(statusMsg: String? = null) {
        hologramView.setState(HologramView.State.IDLE)
        val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
        tvVoiceStatus.text = "⚪ IDLE • Tap TALK to chat with $assistantName"
        tvVoiceStatus.setTextColor(ContextCompat.getColor(this, R.color.cyan_glow))
        tvHearingPreview.text = statusMsg ?: "Ready • Speaks authentic Hindi & Hinglish"
        btnVoiceToggle.text = "🎙️ TALK"
        btnVoiceToggle.setBackgroundResource(R.drawable.bg_btn_talk)
        btnVoiceToggle.setTextColor(ContextCompat.getColor(this, R.color.bg_dark))
        btnVoiceStop.visibility = View.GONE
    }

    private fun processVoiceInput(spokenText: String) {
        val lower = spokenText.lowercase(Locale.ROOT)
        if (lower == "stop" || lower == "ruko" || lower == "chup" || lower == "cancel") {
            handleBargeIn()
            return
        }

        tvHearingPreview.text = "\"$spokenText\""
        sendQuery(spokenText, isFromVoice = true)
    }

    private fun updateProfileHeader() {
        val userName = prefs.getString(KEY_USER_NAME, "Friend") ?: "Friend"
        val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
        val role = prefs.getString(KEY_RELATIONSHIP_ROLE, "manager") ?: "manager"
        val voiceId = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE)

        tvAppTitle.text = assistantName.uppercase(Locale.ROOT)
        tvActiveProfile.text = "${userName.uppercase(Locale.ROOT)} • ${role.uppercase(Locale.ROOT)} • $voiceId"
    }

    private fun loadHistory() {
        chatList.clear()
        val json = prefs.getString(KEY_HISTORY, null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                val saved: List<ChatMessage> = gson.fromJson(json, type)
                chatList.addAll(saved)
            } catch (_: Exception) {}
        }

        if (chatList.isEmpty()) {
            val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
            val welcome = ChatMessage(
                role = "model",
                text = "Hello! I am $assistantName, your personal AI manager and companion. You can ask me questions, get organized, or tell me how you want us to interact (as a manager, friend, girlfriend, boyfriend, or coach). How can I help you today?",
                time = getCurrentTime()
            )
            chatList.add(welcome)
        }

        chatAdapter.notifyDataSetChanged()
        scrollToBottom()
    }

    private fun saveHistory() {
        val toSave = if (chatList.size > 30) chatList.takeLast(30) else chatList
        val json = gson.toJson(toSave)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }

    private fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun scrollToBottom() {
        if (chatList.isNotEmpty()) {
            rvChatMessages.post {
                rvChatMessages.smoothScrollToPosition(chatList.size - 1)
            }
        }
    }

    private fun sendQuickPrompt(prompt: String) {
        if (isGenerating) return
        etUserInput.setText(prompt)
        sendMessage()
    }

    private fun sendMessage() {
        val text = etUserInput.text.toString().trim()
        if (text.isEmpty() || isGenerating) return
        etUserInput.setText("")
        sendQuery(text, isFromVoice = false)
    }

    private fun sendQuery(queryText: String, isFromVoice: Boolean) {
        val apiKey = prefs.getString(KEY_API, "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Please enter your Gemini API key in Settings first.", Toast.LENGTH_LONG).show()
            showSettingsDialog(isFirstTime = true)
            return
        }

        // Add user message to UI
        val userMsg = ChatMessage(role = "user", text = queryText, time = getCurrentTime())
        chatList.add(userMsg)
        chatAdapter.notifyItemInserted(chatList.size - 1)
        scrollToBottom()
        saveHistory()

        // State update
        isGenerating = true
        tvTypingIndicator.visibility = View.VISIBLE
        hologramView.setState(HologramView.State.THINKING)
        val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
        tvVoiceStatus.text = "🟡 THINKING... $assistantName processing 🤔"
        tvVoiceStatus.setTextColor(ContextCompat.getColor(this, R.color.gold_glow))
        scrollToBottom()

        val userName = prefs.getString(KEY_USER_NAME, "Friend") ?: "Friend"
        val userGender = prefs.getString(KEY_USER_GENDER, "unspecified") ?: "unspecified"
        val assistantGender = prefs.getString(KEY_ASSISTANT_GENDER, "female") ?: "female"
        val relationshipRole = prefs.getString(KEY_RELATIONSHIP_ROLE, "manager") ?: "manager"
        val model = prefs.getString(KEY_MODEL, GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL
        val selectedVoice = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE) ?: SpeechEngine.DEFAULT_VOICE

        lifecycleScope.launch {
            // Step 1: Sub-second fast text generation with dynamic personality
            val result = geminiClient.generateContent(
                apiKey = apiKey,
                prompt = queryText,
                model = model,
                userName = userName,
                userGender = userGender,
                assistantName = assistantName,
                assistantGender = assistantGender,
                relationshipRole = relationshipRole,
                voiceName = selectedVoice,
                history = chatList.takeLast(10)
            )

            isGenerating = false
            tvTypingIndicator.visibility = View.GONE

            if (result.isSuccess) {
                val rawReply = result.getOrNull() ?: "I'm right here with you."

                // Step 2: Parse any dynamic in-chat setup commands like [SET:assistant_name=X]
                val cleanReply = parseInChatSettingsAndUpdate(rawReply)
                addJarvisMessage(cleanReply)

                // Step 3: Speak with Gemini Computer Voice Model
                val currentVoice = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE) ?: SpeechEngine.DEFAULT_VOICE
                speakWithComputerVoice(apiKey, cleanReply, currentVoice, isFromVoice)
            } else {
                val err = result.exceptionOrNull()?.message ?: "Network error"
                val errReply = "Connection issue: $err. Please try asking again."
                addJarvisMessage(errReply)
                resetVoiceHudToIdle("Error: $err")
            }
        }
    }

    /**
     * Parses dynamic machine tags like [SET:assistant_name=Friday] or [SET:role=girlfriend]
     * emitted by Gemini during in-chat configuration, updates SharedPreferences, and returns clean text.
     */
    private fun parseInChatSettingsAndUpdate(rawReply: String): String {
        val tagRegex = Regex("\\[SET:([a-zA-Z_]+)=([^\\]]+)\\]")
        val matches = tagRegex.findAll(rawReply).toList()

        if (matches.isNotEmpty()) {
            val editor = prefs.edit()
            for (m in matches) {
                val key = m.groupValues[1].lowercase(Locale.ROOT)
                val value = m.groupValues[2].trim()
                when (key) {
                    "assistant_name" -> editor.putString(KEY_ASSISTANT_NAME, value)
                    "user_name" -> editor.putString(KEY_USER_NAME, value)
                    "user_gender" -> editor.putString(KEY_USER_GENDER, value)
                    "assistant_gender" -> {
                        editor.putString(KEY_ASSISTANT_GENDER, value)
                        if (value.equals("male", ignoreCase = true)) {
                            val curV = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE)
                            if (curV == "Aoede" || curV == "Kore") {
                                editor.putString(KEY_VOICE_MODEL, "Charon")
                                speechEngine.setVoiceModel("Charon")
                            }
                        } else if (value.equals("female", ignoreCase = true)) {
                            val curV = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE)
                            if (curV == "Charon" || curV == "Fenrir") {
                                editor.putString(KEY_VOICE_MODEL, "Aoede")
                                speechEngine.setVoiceModel("Aoede")
                            }
                        }
                    }
                    "role" -> editor.putString(KEY_RELATIONSHIP_ROLE, value)
                    "voice" -> {
                        val matched = SpeechEngine.COMPUTER_VOICES.firstOrNull { it.id.equals(value, ignoreCase = true) }
                        if (matched != null) {
                            editor.putString(KEY_VOICE_MODEL, matched.id)
                            speechEngine.setVoiceModel(matched.id)
                        }
                    }
                }
            }
            editor.apply()
            updateProfileHeader()
        }

        // Return clean string with tags stripped
        return rawReply.replace(tagRegex, "").trim()
    }

    private fun addJarvisMessage(text: String) {
        val jarvisMsg = ChatMessage(role = "model", text = text, time = getCurrentTime())
        chatList.add(jarvisMsg)
        chatAdapter.notifyItemInserted(chatList.size - 1)
        scrollToBottom()
        saveHistory()
    }

    /**
     * Synthesizes and plays Gemini Neural Computer Voice Audio (Aoede, Kore, Charon, Puck, Fenrir)
     * Speaks natural Hindi and Hinglish with authentic human emotion
     */
    private fun speakWithComputerVoice(apiKey: String, text: String, voiceName: String, isFromVoice: Boolean) {
        tvVoiceStatus.text = "🟣 SYNTHESIZING VOICE ($voiceName)..."
        tvVoiceStatus.setTextColor(ContextCompat.getColor(this, R.color.gold_glow))
        hologramView.setState(HologramView.State.THINKING)

        lifecycleScope.launch {
            val audioResult = geminiClient.generateSpeech(apiKey, text, voiceName)

            if (audioResult.isSuccess) {
                val wavBytes = audioResult.getOrNull()!!
                speechEngine.playNeuralAudio(
                    wavBytes = wavBytes,
                    onStart = {
                        runOnUiThread {
                            isSpeaking = true
                            hologramView.setState(HologramView.State.SPEAKING)
                            tvVoiceStatus.text = "🟣 $voiceName SPEAKING 🗣️"
                            tvVoiceStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cyan_glow))
                            btnVoiceStop.visibility = View.VISIBLE
                        }
                    },
                    onDone = {
                        runOnUiThread {
                            isSpeaking = false
                            btnVoiceStop.visibility = View.GONE

                            if (isContinuousMode && isFromVoice) {
                                tvHearingPreview.text = "Listening... Speak when ready"
                                mainHandler.postDelayed({
                                    if (isContinuousMode && !isSpeaking && !isGenerating && !isListening) {
                                        startListening()
                                    }
                                }, 400)
                            } else {
                                resetVoiceHudToIdle("Tap TALK to continue conversation")
                            }
                        }
                    }
                )
            } else {
                // Network speech fallback to offline TTS if needed
                speechEngine.speakFallbackTts(
                    text = text,
                    onStart = {
                        runOnUiThread {
                            isSpeaking = true
                            hologramView.setState(HologramView.State.SPEAKING)
                            tvVoiceStatus.text = "🟣 SPEAKING..."
                            btnVoiceStop.visibility = View.VISIBLE
                        }
                    },
                    onDone = {
                        runOnUiThread {
                            isSpeaking = false
                            btnVoiceStop.visibility = View.GONE
                            resetVoiceHudToIdle("Tap TALK to speak")
                        }
                    }
                )
            }
        }
    }

    private fun wipeChat() {
        chatList.clear()
        val assistantName = prefs.getString(KEY_ASSISTANT_NAME, "Jarvis") ?: "Jarvis"
        val resetMsg = ChatMessage(
            role = "model",
            text = "Memory wiped clean! Fresh start. I'm $assistantName, ready to help you with whatever you need.",
            time = getCurrentTime()
        )
        chatList.add(resetMsg)
        chatAdapter.notifyDataSetChanged()
        saveHistory()
        Toast.makeText(this, "Memory wiped clean!", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsDialog(isFirstTime: Boolean) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // 1. API Key (Required)
        val tvApiLabel = TextView(this).apply {
            text = "Google Gemini API Key (Required):"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.cyan_glow))
        }
        val etApi = EditText(this).apply {
            hint = "Free key from aistudio.google.com"
            setText(prefs.getString(KEY_API, ""))
        }

        // 2. User Name (Optional)
        val tvUserLabel = TextView(this).apply { text = "Your Name (Optional):" }
        val etUser = EditText(this).apply {
            hint = "e.g. Alex, Rahul, Sarah"
            setText(prefs.getString(KEY_USER_NAME, ""))
        }

        // 3. User Gender (Optional)
        val tvUserGenderLabel = TextView(this).apply { text = "Your Gender (Optional):" }
        val spinnerUserGender = Spinner(this)
        val userGenderOptions = listOf("Prefer not to say", "Male", "Female", "Non-binary")
        spinnerUserGender.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, userGenderOptions)
        val savedUserGender = prefs.getString(KEY_USER_GENDER, "Prefer not to say") ?: "Prefer not to say"
        val userGenderIdx = userGenderOptions.indexOfFirst { it.equals(savedUserGender, true) }.coerceAtLeast(0)
        spinnerUserGender.setSelection(userGenderIdx)

        // 4. Assistant Name (Optional)
        val tvAssistantLabel = TextView(this).apply { text = "Assistant Name (Optional):" }
        val etAssistant = EditText(this).apply {
            hint = "e.g. Jarvis, Friday, Nova"
            setText(prefs.getString(KEY_ASSISTANT_NAME, "Jarvis"))
        }

        // 5. Assistant Gender
        val tvAssistantGenderLabel = TextView(this).apply { text = "Assistant Gender:" }
        val spinnerAssistantGender = Spinner(this)
        val assistantGenderOptions = listOf("Female", "Male", "Neutral / AI")
        spinnerAssistantGender.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, assistantGenderOptions)
        val savedAssistantGender = prefs.getString(KEY_ASSISTANT_GENDER, "Female") ?: "Female"
        val assistantGenderIdx = assistantGenderOptions.indexOfFirst { it.equals(savedAssistantGender, true) }.coerceAtLeast(0)
        spinnerAssistantGender.setSelection(assistantGenderIdx)

        // 6. Initial Role / Nature
        val tvRoleLabel = TextView(this).apply { text = "Initial Personality / Relationship:" }
        val spinnerRole = Spinner(this)
        val roleOptions = listOf(
            "💼 Smart Personal Manager (Recommended)",
            "💖 Loving Girlfriend",
            "👔 Caring Boyfriend",
            "✨ Supportive Best Friend",
            "📚 Study Coach & Academic Mentor",
            "🎩 British Butler / Stark AI"
        )
        val roleKeys = listOf("manager", "girlfriend", "boyfriend", "bestfriend", "study_coach", "butler")
        spinnerRole.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roleOptions)
        val savedRole = prefs.getString(KEY_RELATIONSHIP_ROLE, "manager") ?: "manager"
        val roleIdx = roleKeys.indexOfFirst { it.equals(savedRole, true) }.coerceAtLeast(0)
        spinnerRole.setSelection(roleIdx)

        // 7. Computer Voice Model Selector
        val tvVoiceLabel = TextView(this).apply { text = "Gemini Neural Computer Voice Model:" }
        val spinnerVoice = Spinner(this)
        val voiceOptions = SpeechEngine.COMPUTER_VOICES.map { it.displayName }
        spinnerVoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceOptions)
        val savedVoiceId = prefs.getString(KEY_VOICE_MODEL, SpeechEngine.DEFAULT_VOICE)
        val currentVoiceIndex = SpeechEngine.COMPUTER_VOICES.indexOfFirst { it.id == savedVoiceId }.coerceAtLeast(0)
        spinnerVoice.setSelection(currentVoiceIndex)

        // Auto-select voice when assistant gender changes
        spinnerAssistantGender.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedGender = assistantGenderOptions[position]
                if (selectedGender.equals("Male", ignoreCase = true)) {
                    val charonIdx = SpeechEngine.COMPUTER_VOICES.indexOfFirst { it.id == "Charon" }
                    if (charonIdx >= 0) spinnerVoice.setSelection(charonIdx)
                } else if (selectedGender.equals("Female", ignoreCase = true)) {
                    val aoedeIdx = SpeechEngine.COMPUTER_VOICES.indexOfFirst { it.id == "Aoede" }
                    if (aoedeIdx >= 0) spinnerVoice.setSelection(aoedeIdx)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Test Voice Button
        val btnPreviewVoice = Button(this).apply {
            text = "🔊 TEST SELECTED VOICE"
            setOnClickListener {
                val currentKey = etApi.text.toString().trim()
                if (currentKey.isBlank()) {
                    Toast.makeText(this@MainActivity, "Please enter API Key above first.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val selectedIdx = spinnerVoice.selectedItemPosition
                val selectedVoice = SpeechEngine.COMPUTER_VOICES.getOrNull(selectedIdx)?.id
                    ?: SpeechEngine.DEFAULT_VOICE

                Toast.makeText(this@MainActivity, "Synthesizing $selectedVoice in Hindi & English...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    val testAudio = geminiClient.generateSpeech(
                        apiKey = currentKey,
                        text = "Hello! I am ready to be your companion and manager. Kaisi lagi meri voice?",
                        voiceName = selectedVoice
                    )
                    if (testAudio.isSuccess) {
                        speechEngine.playNeuralAudio(testAudio.getOrNull()!!)
                    } else {
                        Toast.makeText(this@MainActivity, "TTS error: ${testAudio.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        val cbContinuous = CheckBox(this).apply {
            text = "Continuous Voice Loop (Auto-relisten after reply)"
            isChecked = prefs.getBoolean(KEY_CONTINUOUS_MODE, false)
        }

        layout.addView(tvApiLabel)
        layout.addView(etApi)
        layout.addView(tvUserLabel)
        layout.addView(etUser)
        layout.addView(tvUserGenderLabel)
        layout.addView(spinnerUserGender)
        layout.addView(tvAssistantLabel)
        layout.addView(etAssistant)
        layout.addView(tvAssistantGenderLabel)
        layout.addView(spinnerAssistantGender)
        layout.addView(tvRoleLabel)
        layout.addView(spinnerRole)
        layout.addView(tvVoiceLabel)
        layout.addView(spinnerVoice)
        layout.addView(btnPreviewVoice)
        layout.addView(cbContinuous)

        AlertDialog.Builder(this)
            .setTitle(if (isFirstTime) "Welcome to J.A.R.V.I.S. Setup" else "Settings & Customization")
            .setView(layout)
            .setPositiveButton("SAVE") { _, _ ->
                val newKey = etApi.text.toString().trim()
                val newUser = etUser.text.toString().trim().ifEmpty { "Friend" }
                val newUserGender = userGenderOptions.getOrNull(spinnerUserGender.selectedItemPosition) ?: "unspecified"
                val newAssistant = etAssistant.text.toString().trim().ifEmpty { "Jarvis" }
                val newAssistantGender = assistantGenderOptions.getOrNull(spinnerAssistantGender.selectedItemPosition) ?: "Female"
                val newRole = roleKeys.getOrNull(spinnerRole.selectedItemPosition) ?: "manager"
                val selectedIdx = spinnerVoice.selectedItemPosition
                val selectedVoice = SpeechEngine.COMPUTER_VOICES.getOrNull(selectedIdx)?.id
                    ?: SpeechEngine.DEFAULT_VOICE
                val newContinuous = cbContinuous.isChecked

                if (newKey.isBlank()) {
                    Toast.makeText(this, "Gemini API Key is required to chat.", Toast.LENGTH_LONG).show()
                    showSettingsDialog(isFirstTime = true)
                    return@setPositiveButton
                }

                prefs.edit()
                    .putString(KEY_API, newKey)
                    .putString(KEY_USER_NAME, newUser)
                    .putString(KEY_USER_GENDER, newUserGender)
                    .putString(KEY_ASSISTANT_NAME, newAssistant)
                    .putString(KEY_ASSISTANT_GENDER, newAssistantGender)
                    .putString(KEY_RELATIONSHIP_ROLE, newRole)
                    .putString(KEY_VOICE_MODEL, selectedVoice)
                    .putBoolean(KEY_CONTINUOUS_MODE, newContinuous)
                    .apply()

                speechEngine.setVoiceModel(selectedVoice)
                isContinuousMode = newContinuous
                updateProfileHeader()
                Toast.makeText(this, "Setup complete! Hello $newUser, meet $newAssistant.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(if (isFirstTime) null else "CANCEL", null)
            .setCancelable(!isFirstTime)
            .show()
    }

    override fun onPause() {
        super.onPause()
        safeStopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechEngine.shutdown()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }
}
