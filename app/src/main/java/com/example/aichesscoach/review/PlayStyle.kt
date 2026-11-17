package com.example.aichesscoach.review

import com.example.aichesscoach.data.PlayerProfile
import kotlin.math.roundToInt

/**
 * Aggregates engine game reviews into a persistent learning profile: how
 * accurate the player is, which move-quality tags dominate, where in the game
 * accuracy drops, and which recurring mistake themes to drill. All analysis is
 * computed on-device and is free; the optional AI coach only receives the
 * finished summary text.
 */
object PlayStyle {

    /** Per-game statistics for one side, extracted from a review report. */
    data class PlayerStats(
        val plies: Int,
        val accuracy: Double,
        val counts: Map<GameReview.Quality, Int>,
        val openingLoss: Double,
        val openingPlies: Int,
        val middleLoss: Double,
        val middlePlies: Int,
        val endLoss: Double,
        val endPlies: Int,
        val captures: Int,
        val themes: Map<String, Int>
    )

    /** Extracts the player-side statistics from one reviewed game. */
    fun extract(report: GameReview.Report, playerSide: String): PlayerStats {
        val mine = report.moves.filter { it.moverSide == playerSide }
        if (mine.isEmpty()) {
            return PlayerStats(
                0, 0.0, emptyMap(), 0.0, 0, 0.0, 0, 0.0, 0, 0, emptyMap()
            )
        }
        val counts: Map<GameReview.Quality, Int> =
            GameReview.Quality.entries.associateWith { q -> mine.count { it.quality == q } }

        val opening = mine.filter { it.phase == "Opening" }
        val middle = mine.filter { it.phase == "Middlegame" }
        val end = mine.filter { it.phase == "Endgame" }

        val themes = buildMap {
            val hanging = mine.count {
                it.refutedByCapture && (it.quality == GameReview.Quality.MISTAKE || it.quality == GameReview.Quality.BLUNDER)
            }
            if (hanging > 0) put("hanging", hanging)
            val missed = counts[GameReview.Quality.MISS] ?: 0
            if (missed > 0) put("missed_tactics", missed)
            val blunders = counts[GameReview.Quality.BLUNDER] ?: 0
            if (blunders > 0) put("blunders", blunders)
            val riskyCaptures = mine.count {
                it.isCapture && (it.quality == GameReview.Quality.BLUNDER || it.quality == GameReview.Quality.MISTAKE)
            }
            if (riskyCaptures >= 2) put("risky_captures", riskyCaptures)
            val openingAvg = if (opening.isEmpty()) 0.0 else opening.map { it.lossWp }.average()
            if (opening.size >= 6 && openingAvg >= 8.0) put("opening", 1)
            val middleAvg = if (middle.isEmpty()) 0.0 else middle.map { it.lossWp }.average()
            val endAvg = if (end.isEmpty()) 0.0 else end.map { it.lossWp }.average()
            if (end.size >= 5 && endAvg >= middleAvg + 3.0) put("endgame", 1)
        }

        return PlayerStats(
            plies = mine.size,
            accuracy = mine.map { GameReview.moveAccuracy(it.lossWp) }.average(),
            counts = counts,
            openingLoss = if (opening.isEmpty()) 0.0 else opening.map { it.lossWp }.average(),
            openingPlies = opening.size,
            middleLoss = if (middle.isEmpty()) 0.0 else middle.map { it.lossWp }.average(),
            middlePlies = middle.size,
            endLoss = if (end.isEmpty()) 0.0 else end.map { it.lossWp }.average(),
            endPlies = end.size,
            captures = mine.count { it.isCapture },
            themes = themes
        )
    }

    /** Folds one game's stats into the stored profile (all values accumulate). */
    fun mergeProfile(existing: PlayerProfile?, stats: PlayerStats, now: Long): PlayerProfile {
        val base = existing ?: PlayerProfile(id = 1)
        val themes = decodeThemes(base.themes).toMutableMap()
        for ((id, count) in stats.themes) {
            themes[id] = (themes[id] ?: 0) + count
        }
        return base.copy(
            updatedAt = now,
            gamesReviewed = base.gamesReviewed + 1,
            accuracySum = base.accuracySum + stats.accuracy,
            plyCount = base.plyCount + stats.plies,
            brilliant = base.brilliant + (stats.counts[GameReview.Quality.BRILLIANT] ?: 0),
            great = base.great + (stats.counts[GameReview.Quality.GREAT] ?: 0),
            best = base.best + (stats.counts[GameReview.Quality.BEST] ?: 0),
            excellent = base.excellent + (stats.counts[GameReview.Quality.EXCELLENT] ?: 0),
            good = base.good + (stats.counts[GameReview.Quality.GOOD] ?: 0),
            book = base.book + (stats.counts[GameReview.Quality.BOOK] ?: 0),
            inaccuracy = base.inaccuracy + (stats.counts[GameReview.Quality.INACCURACY] ?: 0),
            mistake = base.mistake + (stats.counts[GameReview.Quality.MISTAKE] ?: 0),
            blunder = base.blunder + (stats.counts[GameReview.Quality.BLUNDER] ?: 0),
            miss = base.miss + (stats.counts[GameReview.Quality.MISS] ?: 0),
            openingLossSum = base.openingLossSum + stats.openingLoss * stats.openingPlies,
            openingPlies = base.openingPlies + stats.openingPlies,
            middleLossSum = base.middleLossSum + stats.middleLoss * stats.middlePlies,
            middlePlies = base.middlePlies + stats.middlePlies,
            endLossSum = base.endLossSum + stats.endLoss * stats.endPlies,
            endPlies = base.endPlies + stats.endPlies,
            captures = base.captures + stats.captures,
            themes = encodeThemes(themes)
        )
    }

    // ---------------------------------------------------------------------
    // Human-readable profile
    // ---------------------------------------------------------------------

    data class FocusArea(val title: String, val detail: String, val drill: String)

    data class Advice(
        val styleLabel: String,
        val styleDetail: String,
        val accuracy: Int,
        val focus: List<FocusArea>
    )

    private data class ThemeMeta(val title: String, val detail: String, val drill: String)

    private val themeMeta = mapOf(
        "hanging" to ThemeMeta(
            "Leaving pieces undefended",
            "The most common way club games are lost: material left where a simple capture wins it.",
            "Before every move, scan all your pieces: is each one defended, and what can my opponent capture in reply?"
        ),
        "missed_tactics" to ThemeMeta(
            "Missing winning tactics",
            "You reached clearly winning positions and let the advantage slip away.",
            "When you are winning, look for forcing moves first - checks, captures, threats - in that order."
        ),
        "blunders" to ThemeMeta(
            "One-move blunders",
            "Blunders appear even in quiet positions, so a habit check is needed.",
            "After picking a move, ask two questions: what did this stop defending, and what is my opponent's best reply?"
        ),
        "risky_captures" to ThemeMeta(
            "Risky captures",
            "Captures in your games often lose material to a recapture.",
            "Count attackers and defenders on any piece before you take it, and mind the order of recaptures."
        ),
        "opening" to ThemeMeta(
            "Shaky opening play",
            "Accuracy already drops in the first moves, before the middlegame starts.",
            "Choose one opening for each colour, learn its first 8 moves and the plans behind them - not just the moves."
        ),
        "endgame" to ThemeMeta(
            "Endgame accuracy",
            "Your accuracy falls off more in the endgame than in the middlegame.",
            "Drill basic rook and pawn endings; king activity and passed pawns decide them."
        )
    )

    /** Describes the aggregated profile: a style label plus focused practice areas. */
    fun describe(profile: PlayerProfile): Advice {
        val plies = profile.plyCount
        if (plies == 0 || profile.gamesReviewed == 0) {
            return Advice("Building your profile", "Review a finished game to start your learning profile.", 0, emptyList())
        }
        val accuracy = (profile.accuracySum / plies).toInt().coerceIn(0, 99)
        val blunderRate = profile.blunder.toDouble() / plies
        val captureRate = profile.captures.toDouble() / plies

        val (label, detail) = when {
            blunderRate >= 0.08 -> "Sharp but loose" to
                "You find good moves, but one-move blunders appear too often and decide your games."
            captureRate >= 0.22 -> "Aggressive attacker" to
                "You play active, capture-heavy chess. Sharpen your calculation so attacks stay sound."
            accuracy >= 85 -> "Solid and precise" to
                "Your moves rarely lose value. Push for richer positions and stronger opponents."
            accuracy >= 75 -> "Steady club player" to
                "A reliable foundation with room to grow - tactics review is your fastest gain."
            else -> "Developing player" to
                "Focus on not leaving anything undefended; that alone lifts accuracy quickly."
        }

        val games = profile.gamesReviewed.coerceAtLeast(1)
        val focus = decodeThemes(profile.themes)
            .filter { (_, count) -> count >= 2 && count.toDouble() / games >= 0.75 }
            .entries.sortedByDescending { it.value }
            .take(4)
            .mapNotNull { (id, count) ->
                themeMeta[id]?.let { meta ->
                    FocusArea(
                        title = meta.title,
                        detail = "${meta.detail} ($count× across $games games)",
                        drill = meta.drill
                    )
                }
            }

        return Advice(label, detail, accuracy, focus)
    }

    /** Renders the profile as plain text for the optional AI coach prompt. */
    fun statsText(profile: PlayerProfile): String = buildString {
        val advice = describe(profile)
        append("Style: ${advice.styleLabel} - ${advice.styleDetail}\n")
        append("Games reviewed: ${profile.gamesReviewed}; player moves analysed: ${profile.plyCount}\n")
        append("Average accuracy: ${advice.accuracy}%\n")
        append("Move tags (player side): ")
        append("Brilliant ${profile.brilliant}, Great ${profile.great}, Best ${profile.best}, ")
        append("Excellent ${profile.excellent}, Good ${profile.good}, Book ${profile.book}, ")
        append("Inaccuracy ${profile.inaccuracy}, Mistake ${profile.mistake}, ")
        append("Blunder ${profile.blunder}, Miss ${profile.miss}\n")
        fun phase(name: String, sum: Double, count: Int) {
            if (count > 0) {
                val avg = (sum / count * 10).roundToInt() / 10.0
                append("$name: average win-point loss $avg\n")
            }
        }
        phase("Opening accuracy", profile.openingLossSum, profile.openingPlies)
        phase("Middlegame accuracy", profile.middleLossSum, profile.middlePlies)
        phase("Endgame accuracy", profile.endLossSum, profile.endPlies)
        val themes = decodeThemes(profile.themes)
        if (themes.isNotEmpty()) {
            append("Recurring mistake themes: ")
            append(themes.entries.sortedByDescending { it.value }
                .joinToString("; ") { (id, count) -> "${themeMeta[id]?.title ?: id} ($count×)" })
            append("\n")
        }
    }

    // Themes are stored as a compact "id:count,id:count" string so the profile
    // stays pure Kotlin (no org.json) and stays testable on the JVM.
    private fun encodeThemes(themes: Map<String, Int>): String =
        themes.entries.filter { it.value > 0 }.joinToString(",") { "${it.key}:${it.value}" }

    fun decodeThemes(raw: String): Map<String, Int> =
        raw.split(",").filter { it.isNotBlank() }.mapNotNull { entry ->
            val parts = entry.split(":")
            val count = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            if (count > 0) parts[0] to count else null
        }.toMap()
}
