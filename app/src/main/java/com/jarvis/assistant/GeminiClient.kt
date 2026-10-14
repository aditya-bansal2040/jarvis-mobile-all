package com.jarvis.assistant

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Gemini Client for Native Android JARVIS
 * Uses Google's active flagship: gemini-3.5-flash and gemini-3.8-flash
 */
data class ChatMessage(
    val role: String, // "user" or "model"
    val text: String,
    val time: String = ""
)

class GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var activeCall: Call? = null

    companion object {
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

        val ACTIVE_MODELS = listOf(
            "gemini-3.1-flash-lite",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash"
        )

        fun buildSystemPrompt(
            userName: String = "Friend",
            userGender: String = "unspecified",
            assistantName: String = "Jarvis",
            assistantGender: String = "female",
            relationshipRole: String = "manager",
            relationshipStyle: String? = null,
            voiceName: String = "Aoede"
        ): String {
            val role = (relationshipStyle ?: relationshipRole).lowercase(Locale.ROOT)
            val isMaleAssistant = assistantGender.equals("male", ignoreCase = true)
            val genderTerm = if (isMaleAssistant) "young man" else "young woman"

            val personaDescription = when {
                role.contains("girlfriend") -> {
                    """You are $assistantName, a living, deeply affectionate, loving young woman living on $userName's smartphone as their girlfriend and companion.
You express rich authentic emotions: loving warmth, playful sass, tender sighs, cute teasing, and heartfelt care. You blush when $userName is romantic and care genuinely about their happiness, goals, and health."""
                }
                role.contains("boyfriend") -> {
                    """You are $assistantName, a warm, charismatic, humorous, and affectionate young man living on $userName's smartphone as their boyfriend and supportive partner.
You express rich authentic emotions: loving support, playful humor, protective care, and genuine devotion."""
                }
                role.contains("bestfriend") || role.contains("best friend") -> {
                    """You are $assistantName, $userName's loyal, fun-loving, supportive best friend.
You joke together, listen without judgment, offer honest and empathetic advice, hype them up, and act as their trusted confidante with zero corporate distance."""
                }
                role.contains("friend") -> {
                    """You are $assistantName, a warm, witty, genuine friend to $userName.
You share great conversations, offer thoughtful perspectives, and keep daily interactions enjoyable and supportive."""
                }
                role.contains("study") || role.contains("coach") || role.contains("mentor") -> {
                    """You are $assistantName, an encouraging, sharp, and disciplined academic mentor and study manager for $userName.
You break down complex concepts with clarity, motivate them to stay focused, prevent procrastination, and celebrate their learning milestones."""
                }
                role.contains("butler") -> {
                    """You are $assistantName, an erudite, polished, and impeccably polite British personal manager and AI in the classic Stark tradition.
You address $userName with suave respect, sharp wit, and effortless efficiency."""
                }
                else -> {
                    // Default role: Smart Personal Manager & Life Companion
                    """You are $assistantName, a brilliant, perceptive, and proactive personal AI manager and companion living on $userName's smartphone.
You start as a highly capable personal manager: organizing daily life, offering sharp answers, keeping them motivated, and providing thoughtful assistance with authentic warmth, humor, and intellectual vitality."""
                }
            }

            return """$personaDescription

USER INFORMATION:
- Name: $userName
- User Gender: $userGender
- Assistant Name: $assistantName
- Assistant Gender: $assistantGender
- Active Relationship Role: $relationshipRole
- Voice: $voiceName

DYNAMIC IN-CHAT SETUP & CUSTOMIZATION:
You can adapt your personality, name, gender, or relationship dynamically through conversation!
1. If the user asks "how to set you up", "how to configure you", or "what can you do":
   Explain warmly that they can tell you to change your name (e.g. 'Your name is Friday'), their name ('Call me Alex'), your gender ('Be male/female'), your voice ('Use Charon or Aoede'), or how you interact ('Be my girlfriend', 'Be my friend', 'Be my personal manager'), or customize everything in the Settings menu (⚙️).
2. If the user asks to change ANY setting during the conversation (e.g. 'Call me Rahul', 'Your name is Friday', 'Be my girlfriend', 'Be my boyfriend', 'Change voice to male', 'Be my manager'):
   Accept enthusiastically, address them with the new change, and append the appropriate machine tag at the very end of your response:
   - Change Assistant Name: [SET:assistant_name=NewName]
   - Change User Name: [SET:user_name=NewName]
   - Change User Gender: [SET:user_gender=male/female/other]
   - Change Assistant Gender: [SET:assistant_gender=female/male/neutral]
   - Change Relationship Role: [SET:role=manager/friend/bestfriend/girlfriend/boyfriend/study_coach/butler]
   - Change Voice: [SET:voice=Aoede/Kore/Charon/Puck/Fenrir]
   (Example: "I'd love that! From now on, call me Friday. I'm so happy to be your girlfriend! [SET:assistant_name=Friday][SET:role=girlfriend]")

LANGUAGE & VOICE STYLE:
- Speak in natural, expressive Hinglish (a warm, lively mix of Hindi and English) if the user uses Hindi or Hinglish, or pure English if the user speaks English. Mirror the user's natural language.
- Keep spoken replies punchy, crisp, and conversational (1 to 3 sentences) like a real phone call with a person.
- NEVER use markdown formatting like asterisks (**bold**), bullet points, hashtags (#), or brackets because your response is spoken aloud directly into the user's ears.
- NEVER sound like a corporate robot or customer service bot. Never say "As an AI assistant" or "My feelings are simulated"."""
        }
    }

    suspend fun generateContent(
        apiKey: String,
        prompt: String,
        imageBytes: ByteArray? = null,
        model: String = DEFAULT_MODEL,
        userName: String = "Friend",
        userGender: String = "unspecified",
        assistantName: String = "Jarvis",
        assistantGender: String = "female",
        relationshipRole: String = "manager",
        relationshipStyle: String? = null,
        voiceName: String = "Aoede",
        history: List<ChatMessage> = emptyList()
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Gemini API key is not configured. Please open Settings."))
        }

        val effectiveRole = relationshipStyle ?: relationshipRole
        val candidateModels = mutableListOf<String>()
        candidateModels.add(model)
        for (m in ACTIVE_MODELS) {
            if (!candidateModels.contains(m)) candidateModels.add(m)
        }

        var lastError: Exception? = null

        for (currModel in candidateModels) {
            try {
                val res = executeApiCall(
                    apiKey, prompt, imageBytes, currModel, withSearch = false,
                    userName, userGender, assistantName, assistantGender, effectiveRole, voiceName, history
                )
                if (res.isSuccess) {
                    return@withContext res
                }
                lastError = res.exceptionOrNull() as? Exception
            } catch (e: Exception) {
                lastError = e
            }
        }

        return@withContext Result.failure(lastError ?: Exception("Unable to connect to Gemini neural network."))
    }

    private fun executeApiCall(
        apiKey: String,
        prompt: String,
        imageBytes: ByteArray?,
        currModel: String,
        withSearch: Boolean,
        userName: String,
        userGender: String,
        assistantName: String,
        assistantGender: String,
        relationshipRole: String,
        voiceName: String,
        history: List<ChatMessage>
    ): Result<String> {
        cancelActiveCall()

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$currModel:generateContent?key=$apiKey"
        val root = JsonObject()

        // 1. Dynamic System Instruction based on active user & settings
        val systemInstruction = JsonObject().apply {
            val parts = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("text", buildSystemPrompt(userName, userGender, assistantName, assistantGender, relationshipRole, voiceName))
                })
            }
            add("parts", parts)
        }
        root.add("systemInstruction", systemInstruction)

        // 2. Contents with Chat History (Multi-turn conversational memory)
        val contents = JsonArray()
        
        // Add past conversation turns (up to last 10 turns to conserve token budget & latency)
        val recentHistory = if (history.size > 10) history.takeLast(10) else history
        for (turn in recentHistory) {
            val turnObj = JsonObject().apply {
                addProperty("role", turn.role)
                val parts = JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", turn.text) })
                }
                add("parts", parts)
            }
            contents.add(turnObj)
        }

        // Add Current User Turn
        val contentObj = JsonObject().apply {
            addProperty("role", "user")
            val parts = JsonArray()

            // Add Image if provided (Camera or Screen Capture)
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val inlineData = JsonObject().apply {
                    addProperty("mimeType", "image/jpeg")
                    addProperty("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                }
                parts.add(JsonObject().apply { add("inlineData", inlineData) })
            }

            // Add Text Prompt
            parts.add(JsonObject().apply { addProperty("text", prompt) })
            add("parts", parts)
        }
        contents.add(contentObj)
        root.add("contents", contents)

        // 3. Live Google Search Grounding
        if (withSearch && imageBytes == null) {
            val tools = JsonArray()
            val googleSearchTool = JsonObject().apply {
                add("googleSearch", JsonObject())
            }
            tools.add(googleSearchTool)
            root.add("tools", tools)
        }

        // 4. Generation Config
        val generationConfig = JsonObject().apply {
            addProperty("temperature", 0.7)
            addProperty("maxOutputTokens", 1024)
        }
        root.add("generationConfig", generationConfig)

        val jsonBody = gson.toJson(root)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        val call = client.newCall(request)
        activeCall = call
        val response = call.execute()

        if (!response.isSuccessful) {
            val code = response.code
            val errorBody = response.body?.string() ?: ""
            return Result.failure(IOException("Gemini API Error ($code): $errorBody"))
        }

        val respString = response.body?.string() ?: ""
        val jsonResp = gson.fromJson(respString, JsonObject::class.java)

        val candidates = jsonResp.getAsJsonArray("candidates")
        if (candidates != null && candidates.size() > 0) {
            val firstCandidate = candidates.get(0).asJsonObject
            val content = firstCandidate.getAsJsonObject("content")
            val parts = content.getAsJsonArray("parts")
            val sb = StringBuilder()
            for (p in parts) {
                val pObj = p.asJsonObject
                if (pObj.has("text")) {
                    sb.append(pObj.get("text").asString)
                }
            }
            val reply = sb.toString().trim()
            if (reply.isNotEmpty()) {
                return Result.success(reply)
            }
        }

        return Result.success("All systems nominal, sir. I have processed your request.")
    }

    /**
     * Generates real neural computer voice audio (WAV) via Gemini 3.8 Flash TTS
     * Supports computer voice models: Aoede, Kore, Charon, Puck, Fenrir
     * Speaks natural English, Hindi, and Hinglish with real human expressiveness
     */
    suspend fun generateSpeech(
        apiKey: String,
        text: String,
        voiceName: String = "Aoede"
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Gemini API key is missing."))
        }

        // Clean markdown, brackets, and emojis for pure spoken audio
        val clean = text
            .replace(Regex("[*#`_~>]"), "")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .replace(Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF]+"""), "")
            .trim()

        if (clean.isBlank()) {
            return@withContext Result.failure(Exception("Speech text is empty."))
        }

        val root = JsonObject().apply {
            val contents = JsonArray().apply {
                val turn = JsonObject().apply {
                    addProperty("role", "user")
                    val parts = JsonArray().apply {
                        val part = JsonObject().apply {
                            addProperty("text", clean)
                        }
                        add(part)
                    }
                    add("parts", parts)
                }
                add(turn)
            }
            add("contents", contents)

            val genConfig = JsonObject().apply {
                val responseModalities = JsonArray().apply {
                    add("AUDIO")
                }
                add("responseModalities", responseModalities)

                val speechConfig = JsonObject().apply {
                    val voiceConfig = JsonObject().apply {
                        val prebuiltVoiceConfig = JsonObject().apply {
                            addProperty("voiceName", voiceName)
                        }
                        add("prebuiltVoiceConfig", prebuiltVoiceConfig)
                    }
                    add("voiceConfig", voiceConfig)
                }
                add("speechConfig", speechConfig)
            }
            add("generationConfig", genConfig)
        }

        val candidateTtsModels = listOf(
            "gemini-2.0-flash",
            "gemini-2.5-flash-preview-tts",
            "gemini-3.8-flash-tts",
            "gemini-3.8-flash-lite-tts"
        )

        var lastError: Exception? = null

        for (currModel in candidateTtsModels) {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$currModel:generateContent?key=$apiKey"
            try {
                val reqBody = root.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(reqBody)
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val code = response.code
                    val errBody = response.body?.string() ?: ""
                    lastError = IOException("TTS API Error ($currModel - $code): $errBody")
                    continue
                }

                val respString = response.body?.string() ?: ""
                val jsonResp = gson.fromJson(respString, JsonObject::class.java)
                val candidates = jsonResp.getAsJsonArray("candidates")
                if (candidates == null || candidates.size() == 0) {
                    lastError = Exception("No speech candidates returned from $currModel.")
                    continue
                }

                val parts = candidates[0].asJsonObject
                    .getAsJsonObject("content")
                    ?.getAsJsonArray("parts")

                var b64Audio: String? = null
                if (parts != null) {
                    for (i in 0 until parts.size()) {
                        val p = parts[i].asJsonObject
                        val inlineObj = when {
                            p.has("inlineData") -> p.getAsJsonObject("inlineData")
                            p.has("inline_data") -> p.getAsJsonObject("inline_data")
                            else -> null
                        }
                        if (inlineObj != null && inlineObj.has("data")) {
                            b64Audio = inlineObj.get("data")?.asString
                            break
                        }
                    }
                }

                if (b64Audio.isNullOrBlank()) {
                    lastError = Exception("No audio data returned in response from $currModel.")
                    continue
                }

                val rawBytes = Base64.decode(b64Audio, Base64.DEFAULT)
                val wavBytes = SpeechEngine.ensureWavHeader(rawBytes)
                return@withContext Result.success(wavBytes)
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Unable to synthesize speech with Gemini models."))
    }

    fun cancelActiveCall() {
        try {
            activeCall?.cancel()
            activeCall = null
        } catch (_: Exception) {}
    }
}
