package com.example.aichesscoach.review

import com.example.aichesscoach.chess.ChessUtils
import com.example.aichesscoach.engine.BuiltinEngine
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Offline game review: replays a game and classifies every move by comparing
 * the played move against the engine's best choice. Works entirely on-device
 * with the built-in engine - no API key required.
 */
object GameReview {

    enum class Quality(val symbol: String, val label: String) {
        BRILLIANT("!!", "Brilliant"),
        BEST("★", "Best"),
        GOOD("✓", "Good"),
        INACCURACY("?!", "Inaccuracy"),
        MISTAKE("?", "Mistake"),
        BLUNDER("??", "Blunder")
    }

    data class MoveReview(
        val ply: Int,
        val san: String,
        val quality: Quality,
        val bestSan: String?,
        val lossCp: Int,
        val evalWhiteCp: Int
    )

    data class Report(
        val moves: List<MoveReview>,
        val whiteAccuracy: Int,
        val blackAccuracy: Int
    ) {
        val blunders: List<MoveReview> get() = moves.filter { it.quality == Quality.BLUNDER }
        val mistakes: List<MoveReview> get() = moves.filter { it.quality == Quality.MISTAKE }
    }

    /** Classifies one move from the loss (in centipawns) vs the engine's best line. */
    private fun classify(lossCp: Int, isBestMove: Boolean, sacrifice: Boolean, missedMate: Boolean): Quality = when {
        missedMate -> Quality.BLUNDER
        isBestMove && sacrifice -> Quality.BRILLIANT
        isBestMove || lossCp <= 25 -> Quality.BEST
        lossCp <= 90 -> Quality.GOOD
        lossCp <= 200 -> Quality.INACCURACY
        lossCp <= 450 -> Quality.MISTAKE
        else -> Quality.BLUNDER
    }

    /** Accuracy on a 0-100 scale from average centipawn loss, per side. */
    private fun accuracy(avgLossCp: Double): Int {
        if (avgLossCp <= 0.5) return 99
        val score = (103.0 * exp(-avgLossCp / 260.0)).roundToInt()
        return score.coerceIn(30, 99)
    }

    /**
     * Analyzes a game given as a list of SAN moves. The heavy work runs on
     * Dispatchers.Default; [onProgress] reports (completedPlies, totalPlies).
     */
    suspend fun analyze(
        sans: List<String>,
        startFen: String? = null,
        budgetMs: Long = 220,
        maxDepth: Int = 4,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): Report = withContext(Dispatchers.Default) {
        val board = Board()
        if (startFen != null) board.loadFromFen(startFen)
        val reviews = ArrayList<MoveReview>(sans.size)
        var completed = 0

        for (san in sans) {
            val mover = board.sideToMove
            val (bestMove, bestScore) = BuiltinEngine.analyzePosition(board, budgetMs, maxDepth)

            // Material sacrificed by the played move (static, approximate).
            val materialBefore = ChessUtils.materialWhiteCp(board)

            val legal = board.legalMoves()
            val played = legal.firstOrNull { ChessUtils.sanFor(board, it) == san || san.startsWith(ChessUtils.sanFor(board, it)) }
            if (played == null) {
                // Could not replay this move (annotation or typo) - skip gracefully.
                if (!board.doMove(san)) break
                completed++
                onProgress(completed, sans.size)
                continue
            }
            val sanBase = ChessUtils.sanFor(board, played)
            val isBestMove = bestMove != null &&
                played.from == bestMove.from && played.to == bestMove.to && played.promotion == bestMove.promotion
            val bestSan = bestMove?.let { ChessUtils.sanFor(board, it) }

            board.doMove(played)
            val opponentScore = BuiltinEngine.analyzePosition(board, budgetMs, maxDepth).second
            val playedScore = -opponentScore
            val sanFull = ChessUtils.sanWithSuffix(sanBase, board)
            val materialAfter = ChessUtils.materialWhiteCp(board)

            // Loss measured from the mover's perspective.
            val lossRaw = if (mover == Side.WHITE) bestScore - playedScore else playedScore - bestScore
            val lossCp = lossRaw.coerceAtLeast(0)

            val bestWasMate = bestScore >= BuiltinEngine.MATE_OPEN - 64
            val playedIsMate = playedScore >= BuiltinEngine.MATE_OPEN - 64
            val missedMate = bestWasMate && !playedIsMate

            val sacrifice = when (mover) {
                Side.WHITE -> materialAfter - materialBefore <= -180 && playedScore >= -60
                else -> materialBefore - materialAfter <= -180 && playedScore >= -60
            }

            reviews.add(
                MoveReview(
                    ply = reviews.size + 1,
                    san = sanFull,
                    quality = classify(lossCp, isBestMove, sacrifice, missedMate),
                    bestSan = bestSan,
                    lossCp = lossCp,
                    evalWhiteCp = if (mover == Side.WHITE) playedScore else -playedScore
                )
            )
            completed++
            onProgress(completed, sans.size)
        }

        val whiteLosses = reviews.filterIndexed { i, _ -> i % 2 == 0 }.map { it.lossCp }
        val blackLosses = reviews.filterIndexed { i, _ -> i % 2 == 1 }.map { it.lossCp }
        val whiteAvg = if (whiteLosses.isEmpty()) 0.0 else whiteLosses.average()
        val blackAvg = if (blackLosses.isEmpty()) 0.0 else blackLosses.average()
        Report(reviews, accuracy(whiteAvg), accuracy(blackAvg))
    }
}
