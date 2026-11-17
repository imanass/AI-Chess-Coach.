package com.example.aichesscoach.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads the player's recent games from chess.com's free public API
 * (https://www.chess.com/news/view/published-data-api). No key is required;
 * the games arrive as annotated PGN and can be reviewed with the on-device
 * engine exactly like games played inside the app.
 */
class ChessComApi {

    data class ChessComGame(
        val url: String,
        val pgn: String,
        val whiteName: String,
        val blackName: String,
        /** "1-0", "0-1" or "1/2-1/2", derived from the per-side results. */
        val result: String,
        val endTimeMs: Long,
        val timeClass: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Latest rated/casual games across the most recent archive months. */
    suspend fun latestGames(username: String, count: Int = 12): List<ChessComGame> =
        withContext(Dispatchers.IO) {
            val name = username.trim().removePrefix("@").lowercase()
            require(name.isNotBlank()) { "Enter your chess.com username first." }
            val archives = runCatching { fetchJson("https://api.chess.com/pub/player/$name/games/archives") }
                .getOrNull()
                ?: error("Could not reach chess.com. Check the username and your connection.")
            val urls = archives.optJSONArray("archives")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it) }
            } ?: error("chess.com did not return game archives for \"$name\".")

            val games = ArrayList<ChessComGame>()
            // Newest months first; stop as soon as we have enough games.
            for (url in urls.takeLast(3).reversed()) {
                val monthly = runCatching { fetchJson(url) }.getOrNull() ?: continue
                val arr = monthly.optJSONArray("games") ?: continue
                for (i in 0 until arr.length()) {
                    val g = arr.getJSONObject(i)
                    val pgn = g.optString("pgn")
                    if (pgn.isBlank()) continue
                    val white = g.optJSONObject("white") ?: continue
                    val black = g.optJSONObject("black") ?: continue
                    games += ChessComGame(
                        url = g.optString("url"),
                        pgn = pgn,
                        whiteName = white.optString("username"),
                        blackName = black.optString("username"),
                        result = when {
                            white.optString("result") == "win" -> "1-0"
                            black.optString("result") == "win" -> "0-1"
                            else -> "1/2-1/2"
                        },
                        endTimeMs = g.optLong("end_time") * 1000L,
                        timeClass = g.optString("time_class").ifBlank { "game" }
                    )
                }
                if (games.size >= count) break
            }
            games.sortedByDescending { it.endTimeMs }.take(count)
        }

    private fun fetchJson(url: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(if (response.code == 404) "chess.com user not found." else "chess.com returned HTTP ${response.code}.")
            }
            return JSONObject(body)
        }
    }

    companion object {
        // chess.com requires a descriptive User-Agent on every request.
        private const val USER_AGENT = "AIChessCoach/5.0 (Android chess trainer; on-device review)"
    }
}
