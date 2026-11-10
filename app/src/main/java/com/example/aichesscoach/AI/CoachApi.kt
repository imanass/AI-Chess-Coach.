package com.example.aichesscoach.AI

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CoachConfig(val provider: String, val baseUrl: String, val apiKey: String, val model: String)

class CoachApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(100, TimeUnit.SECONDS)
        .build()

    /** Single AI turn: system prompt + user prompt -> assistant text. */
    suspend fun ask(config: CoachConfig, system: String, user: String, maxTokens: Int = 900): String =
        withContext(Dispatchers.IO) {
            require(config.apiKey.isNotBlank()) { "Add your API key in Settings first." }
            require(config.model.isNotBlank()) { "Pick a model ID in Settings first." }
            require(config.baseUrl.startsWith("https://")) { "The API base URL must use HTTPS." }

            val response = execute(config, system, user, maxTokens, useLegacyTokenField = false)
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Some newer OpenAI models reject "max_tokens" and ask for
                // "max_completion_tokens"; retry once in that case.
                if (raw.contains("max_completion_tokens", ignoreCase = true)) {
                    val retry = execute(config, system, user, maxTokens, useLegacyTokenField = true)
                    retry.body?.string().orEmpty().let { retryRaw ->
                        if (!retry.isSuccessful) error(parseError(retry.code, retryRaw))
                        return@withContext parseContent(config.provider, retryRaw)
                            ?: error("The provider response was missing message text. Try a different model.")
                    }
                }
                error(parseError(response.code, raw))
            }
            parseContent(config.provider, raw)
                ?: error("The provider response was missing message text. Try a different model.")
        }

    private fun execute(
        config: CoachConfig, system: String, user: String, maxTokens: Int, useLegacyTokenField: Boolean
    ): okhttp3.Response {
        val endpoint = config.baseUrl.trimEnd('/')
        val request = when (config.provider) {
            "Google Gemini" -> {
                val generationConfig = JSONObject().put("temperature", 0.4).put("maxOutputTokens", maxTokens)
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(
                        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
                    .put("generationConfig", generationConfig)
                Request.Builder().url("$endpoint/models/${config.model}:generateContent?key=${config.apiKey}")
                    .header("Accept", "application/json")
                    .post(body.toString().toRequestBody(JSON)).build()
            }
            "Anthropic Claude" -> {
                val body = JSONObject().put("model", config.model).put("max_tokens", maxTokens)
                    .put("temperature", 0.4).put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                Request.Builder().url("$endpoint/messages")
                    .header("x-api-key", config.apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .post(body.toString().toRequestBody(JSON)).build()
            }
            else -> {
                val tokenField = if (useLegacyTokenField) "max_tokens" else "max_completion_tokens"
                val body = JSONObject().put("model", config.model).put("temperature", 0.4)
                    .put(tokenField, maxTokens)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                Request.Builder().url("$endpoint/chat/completions")
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Accept", "application/json")
                    .post(body.toString().toRequestBody(JSON)).build()
            }
        }
        return client.newCall(request).execute()
    }

    suspend fun test(config: CoachConfig): String {
        val reply = ask(config, "You are a connectivity probe for a chess app. Reply with exactly: OK", "ping", maxTokens = 200)
        return "Connection successful — the model replied: ${reply.take(60).trim()}"
    }

    /** Ideas and plans for the position on the board right now. */
    suspend fun explainPosition(config: CoachConfig, fen: String, sanMoves: String, engineNote: String): String {
        val system = SYSTEM_PROMPT
        val user = buildString {
            append("Current position (FEN): $fen\n")
            append("Moves so far: ${sanMoves.ifBlank { "(game just started)" }}\n")
            append("$engineNote\n\n")
            append("Describe the position from the perspective of the player who is about to move: ")
            append("material balance, king safety, the most important piece or pawn, and two concrete candidate moves or plans. Keep it under 180 words.")
        }
        return ask(config, system, user)
    }

    /** Explanation of one specific move: what it does, why it was played, what to watch for. */
    suspend fun explainLastMove(config: CoachConfig, fenBefore: String, moveSan: String, fenAfter: String, engineNote: String): String {
        val user = buildString {
            append("The move just played was $moveSan.\n")
            append("Position before (FEN): $fenBefore\n")
            append("Position after (FEN): $fenAfter\n")
            append("$engineNote\n\n")
            append("Explain what this move does and one idea or threat each side should consider. ")
            append("If the move looks like a clear mistake, say so kindly and suggest the better idea. Keep it under 150 words.")
        }
        return ask(config, SYSTEM_PROMPT, user)
    }

    /** Structured review of a finished (or long) game. */
    suspend fun reviewGame(config: CoachConfig, pgn: String, result: String, engineNote: String): String {
        val user = buildString {
            append("Here is a complete game in PGN-style notation:\n$pgn\n")
            append("Result: $result\n")
            append("$engineNote\n\n")
            append("Give a structured review with exactly these three sections:\n")
            append("1. What went well\n2. Key moments (quote the move numbers)\n3. One thing to practise next\n")
            append("Stay grounded in the moves actually played; do not invent evaluations.")
        }
        return ask(config, SYSTEM_PROMPT, user, maxTokens = 1200)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private const val SYSTEM_PROMPT =
            "You are a patient, precise chess coach inside a mobile training app. " +
            "Explain plans and tactical ideas in accessible language for a club-level player. " +
            "Never claim to have run Stockfish or any strong engine; the only automated analysis available is a small " +
            "built-in analyzer whose note is provided with each request, and you should say when its number is approximate. " +
            "Distinguish verified facts from hypotheses, quote only moves that appear in the given notation, " +
            "and finish every answer with one practical lesson."

        /** Understands the common error shapes of OpenAI, Anthropic and Gemini. */
        private fun parseError(code: Int, raw: String): String {
            val detail = runCatching {
                val json = JSONObject(raw)
                val errorNode = json.opt("error")
                when (errorNode) {
                    is JSONObject -> errorNode.optString("message").ifBlank { errorNode.optString("status") }
                    is String -> errorNode
                    else -> json.optString("message").ifBlank { json.optString("detail") }
                }
            }.getOrNull().orEmpty()
            val shown = detail.ifBlank { raw.take(200) }
            return "Provider returned HTTP $code. $shown".trim() +
                " Check the endpoint, model ID and API key in Settings."
        }

        private fun parseContent(provider: String, raw: String): String? = runCatching {
            val json = JSONObject(raw)
            when (provider) {
                "Google Gemini" -> {
                    val candidate = json.getJSONArray("candidates").getJSONObject(0)
                    val text = buildString {
                        val parts = candidate.getJSONObject("content").getJSONArray("parts")
                        for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
                    }
                    when {
                        text.isNotBlank() -> text
                        candidate.optString("finishReason") == "SAFETY" -> "Gemini blocked this response for safety reasons. Try another model or prompt."
                        else -> null
                    }
                }
                "Anthropic Claude" -> {
                    val content = json.getJSONArray("content")
                    buildString {
                        for (i in 0 until content.length()) {
                            val item = content.getJSONObject(i)
                            if (item.optString("type") == "text") append(item.optString("text"))
                        }
                    }.takeIf { it.isNotBlank() }
                }
                else -> {
                    val choice = json.getJSONArray("choices").getJSONObject(0)
                    val message = choice.optJSONObject("message")
                    val text = message?.optString("content") ?: choice.optString("text")
                    text.takeIf { it.isNotBlank() }
                }
            }
        }.getOrNull()
    }
}
