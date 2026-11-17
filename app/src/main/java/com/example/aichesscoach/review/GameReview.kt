package com.example.aichesscoach.review

import android.content.Context
import com.example.aichesscoach.chess.ChessUtils
import com.example.aichesscoach.engine.BuiltinEngine
import com.example.aichesscoach.engine.StockfishEngine
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Offline game review in the style of chess.com: replays a game and classifies
 * every move as Brilliant, Great, Best, Excellent, Good, Book, Inaccuracy,
 * Mistake, Blunder or Miss by comparing the played move with the engine's best
 * (and second-best) choice on a win-probability scale. Uses Stockfish 19 when
 * it is available on the device, otherwise the built-in engine - no API key
 * required.
 */
object GameReview {

    /** Engine abstraction so review can run on Stockfish or the built-in engine. */
    interface Engine {
        val mateCeiling: Int

        /**
         * Returns the best move with its score (side-to-move perspective),
         * plus the second-best distinct move and its score when available.
         */
        suspend fun analyzePosition(board: Board, budgetMs: Long, maxDepth: Int): Analysis
    }

    data class Analysis(
        val best: com.github.bhlangonijr.chesslib.move.Move?,
        val bestScore: Int,
        val second: com.github.bhlangonijr.chesslib.move.Move? = null,
        val secondScore: Int? = null
    )

    /** Adapter over the lightweight built-in engine (JVM tests and fallback). */
    object BuiltinAdapter : Engine {
        override val mateCeiling: Int = BuiltinEngine.MATE_OPEN
        override suspend fun analyzePosition(board: Board, budgetMs: Long, maxDepth: Int): Analysis {
            val (best, score, secondScore) = BuiltinEngine.analyzePositionWithSecond(board, budgetMs, maxDepth)
            return Analysis(best, score, second = null, secondScore = secondScore)
        }
    }

    /** Adapter over the on-device Stockfish 19 binary (MultiPV 2 for Great-move detection). */
    class StockfishAdapter(private val context: Context, private val depth: Int = 12) : Engine {
        override val mateCeiling: Int = BuiltinEngine.MATE_OPEN
        override suspend fun analyzePosition(board: Board, budgetMs: Long, maxDepth: Int): Analysis {
            val analysis = StockfishEngine.analyse(context, board.fen, depth = depth, multiPv = 2)
                ?: return BuiltinAdapter.analyzePosition(board, budgetMs, maxDepth)
            return Analysis(
                best = StockfishEngine.uciToMove(board, analysis.bestMoveUci),
                bestScore = analysis.scoreStm(mateCeiling),
                second = StockfishEngine.uciToMove(board, analysis.secondBestUci),
                secondScore = analysis.secondScoreStm(mateCeiling)
            )
        }
    }

    /** Move qualities, ordered from best to worst (chess.com classification). */
    enum class Quality(val symbol: String, val label: String) {
        BRILLIANT("!!", "Brilliant"),
        GREAT("!", "Great"),
        BEST("★", "Best"),
        EXCELLENT("✓✓", "Excellent"),
        GOOD("✓", "Good"),
        BOOK("📖", "Book"),
        INACCURACY("?!", "Inaccuracy"),
        MISTAKE("?", "Mistake"),
        BLUNDER("??", "Blunder"),
        MISS("✗", "Miss")
    }

    data class MoveReview(
        val ply: Int,
        val san: String,
        val quality: Quality,
        val bestSan: String?,
        val lossCp: Int,
        val evalWhiteCp: Int,
        /** Win-probability loss caused by this move (0-100 scale). */
        val lossWp: Double = 0.0,
        val isCapture: Boolean = false,
        /** "White" or "Black" - who played this move. */
        val moverSide: String = "White",
        /** "Opening", "Middlegame" or "Endgame". */
        val phase: String = "Middlegame",
        val secondBestSan: String? = null,
        val book: Boolean = false,
        /** True when the move lost material to a simple opponent capture. */
        val refutedByCapture: Boolean = false
    )

    data class Report(
        val moves: List<MoveReview>,
        val whiteAccuracy: Int,
        val blackAccuracy: Int
    ) {
        val blunders: List<MoveReview> get() = moves.filter { it.quality == Quality.BLUNDER }
        val mistakes: List<MoveReview> get() = moves.filter { it.quality == Quality.MISTAKE }
        val misses: List<MoveReview> get() = moves.filter { it.quality == Quality.MISS }
        val brilliants: List<MoveReview> get() = moves.filter { it.quality == Quality.BRILLIANT }

        /** Per-quality counts across the whole game. */
        fun counts(): Map<Quality, Int> = Quality.entries.associateWith { q -> moves.count { it.quality == q } }
    }

    // ---------------------------------------------------------------------
    // Win-probability model
    // ---------------------------------------------------------------------

    /**
     * Converts a side-to-move score in centipawns into an expected score on a
     * 0-100 scale (mate scores are 100/0). Uses the widely used logistic
     * curve shared by Lichess and chess.com-style analyses.
     */
    fun winPercent(scoreCpStm: Int, mateCeiling: Int = BuiltinEngine.MATE_OPEN): Double = when {
        scoreCpStm >= mateCeiling - 64 -> 100.0
        scoreCpStm <= -(mateCeiling - 64) -> 0.0
        else -> 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * scoreCpStm.toDouble())) - 1.0)
    }

    /** Per-move accuracy from the win-probability loss (Lichess-style curve). */
    fun moveAccuracy(lossWp: Double): Double =
        (103.1668 * exp(-0.04354 * lossWp) - 3.1669).coerceIn(0.0, 100.0)

    private fun accuracy(avgLossWp: Double): Int {
        if (avgLossWp <= 0.001) return 99
        return moveAccuracy(avgLossWp).roundToInt().coerceIn(30, 99)
    }

    /**
     * Classifies one move. All win percentages are from the mover's perspective.
     * [secondGapWp] is how much worse the second-best move is than the best
     * (only present when the engine reported a second line).
     */
    fun classifyMove(
        lossWp: Double,
        isBestMove: Boolean,
        sacrifice: Boolean,
        missedMate: Boolean,
        winBefore: Double,
        winAfter: Double,
        inBook: Boolean,
        onlyMove: Boolean,
        secondGapWp: Double?
    ): Quality = when {
        onlyMove -> Quality.BEST
        inBook && lossWp <= 6 -> Quality.BOOK
        isBestMove && sacrifice -> Quality.BRILLIANT
        isBestMove && secondGapWp != null && secondGapWp >= 30 -> Quality.GREAT
        isBestMove -> Quality.BEST
        missedMate -> if (winAfter <= 30) Quality.BLUNDER else Quality.MISS
        winBefore >= 85 && winBefore - winAfter >= 25 ->
            if (winAfter <= 30 || winBefore - winAfter >= 50) Quality.BLUNDER else Quality.MISS
        lossWp <= 1.5 -> Quality.EXCELLENT
        lossWp <= 5 -> Quality.GOOD
        lossWp <= 10 -> Quality.INACCURACY
        lossWp <= 20 -> Quality.MISTAKE
        else -> Quality.BLUNDER
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
        engine: Engine = BuiltinAdapter,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): Report = withContext(Dispatchers.Default) {
        val board = Board()
        if (startFen != null) board.loadFromFen(startFen)
        val reviews = ArrayList<MoveReview>(sans.size)
        val baseSans = ArrayList<String>(sans.size)
        var completed = 0

        for (san in sans) {
            val mover = board.sideToMove
            val analysis = engine.analyzePosition(board, budgetMs, maxDepth)
            val bestMove = analysis.best
            val bestScore = analysis.bestScore

            // Material sacrificed by the played move (static, approximate).
            val materialBefore = ChessUtils.materialWhiteCp(board)
            val legal = board.legalMoves()

            val played = legal.firstOrNull {
                ChessUtils.sanFor(board, it) == san || san.startsWith(ChessUtils.sanFor(board, it))
            }
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
            val secondSan = analysis.second?.let { ChessUtils.sanFor(board, it) }

            // Win percentages before the move, from the mover's perspective.
            val winBefore = winPercent(bestScore, engine.mateCeiling)

            board.doMove(played)
            val opponentAnalysis = engine.analyzePosition(board, budgetMs, maxDepth)
            val playedScore = -(opponentAnalysis.bestScore)
            val sanFull = ChessUtils.sanWithSuffix(sanBase, board)
            val materialAfter = ChessUtils.materialWhiteCp(board)

            // Loss measured from the mover's perspective, in centipawns and win%.
            // Both scores are already from the mover's (side-to-move) view.
            val lossRaw = bestScore - playedScore
            val lossCp = lossRaw.coerceAtLeast(0)
            val winAfter = winPercent(playedScore, engine.mateCeiling)
            val lossWp = (winBefore - winAfter).coerceAtLeast(0.0)

            val bestWasMate = bestScore >= engine.mateCeiling - 64
            val playedIsMate = playedScore >= engine.mateCeiling - 64
            val missedMate = bestWasMate && !playedIsMate

            val sacrifice = when (mover) {
                Side.WHITE -> materialAfter - materialBefore <= -180 && playedScore >= -60
                else -> materialBefore - materialAfter <= -180 && playedScore >= -60
            }

            // "Great" needs a real gap between the best and second-best move.
            // Both scores are already from the mover's (side-to-move) perspective.
            val secondGapWp = analysis.secondScore?.let { secondScore ->
                val gapWp = winPercent(bestScore, engine.mateCeiling) - winPercent(secondScore, engine.mateCeiling)
                if (gapWp <= 0) null else gapWp
            }

            baseSans.add(sanBase)
            val inBook = Openings.isBookMove(baseSans)

            val isCapture = sanBase.contains('x')
            val phase = when {
                reviews.size < 20 -> "Opening"
                ChessUtils.materialTotalCp(board) <= 2200 -> "Endgame"
                else -> "Middlegame"
            }

            reviews.add(
                MoveReview(
                    ply = reviews.size + 1,
                    san = sanFull,
                    quality = classifyMove(
                        lossWp = lossWp,
                        isBestMove = isBestMove,
                        sacrifice = sacrifice,
                        missedMate = missedMate,
                        winBefore = winBefore,
                        winAfter = winAfter,
                        inBook = inBook,
                        onlyMove = legal.size == 1,
                        secondGapWp = if (isBestMove) secondGapWp else null
                    ),
                    bestSan = bestSan,
                    lossCp = lossCp,
                    evalWhiteCp = if (mover == Side.WHITE) playedScore else -playedScore,
                    lossWp = lossWp,
                    isCapture = isCapture,
                    moverSide = if (mover == Side.WHITE) "White" else "Black",
                    phase = phase,
                    secondBestSan = secondSan,
                    book = inBook
                )
            )
            completed++
            onProgress(completed, sans.size)
        }

        // A mistake/blunder is "refuted by a simple capture" when the engine's
        // best reply on the next ply is a capture.
        for (i in reviews.indices) {
            val move = reviews[i]
            if (move.quality in listOf(Quality.INACCURACY, Quality.MISTAKE, Quality.BLUNDER, Quality.MISS)) {
                val nextBest = reviews.getOrNull(i + 1)?.bestSan
                if (nextBest?.contains('x') == true) {
                    reviews[i] = move.copy(refutedByCapture = true)
                }
            }
        }

        val whiteLosses = reviews.filter { it.moverSide == "White" }.map { it.lossWp }
        val blackLosses = reviews.filter { it.moverSide == "Black" }.map { it.lossWp }
        val whiteAvg = if (whiteLosses.isEmpty()) 0.0 else whiteLosses.average()
        val blackAvg = if (blackLosses.isEmpty()) 0.0 else blackLosses.average()
        Report(reviews, accuracy(whiteAvg), accuracy(blackAvg))
    }
}
