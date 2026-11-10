package com.example.aichesscoach.engine

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import java.util.Locale
import kotlin.math.abs

/**
 * Lightweight built-in chess engine used for the computer opponent and quick
 * position evaluations. It performs an iterative-deepening negamax search with
 * alpha-beta pruning, a capture-only quiescence search, MVV-LVA move ordering
 * and the classic "simplified evaluation function" (material + piece-square
 * tables). Every search runs on its own Board copy against a hard time budget,
 * so the UI can never freeze waiting for it.
 *
 * This is intentionally modest - it is not Stockfish - but it plays a legal,
 * reasonably sensible game and never invents evaluations.
 */

enum class Difficulty(val label: String, val budgetMs: Long, val maxDepth: Int, val randomCp: Int) {
    EASY("Easy", budgetMs = 150, maxDepth = 2, randomCp = 130),
    MEDIUM("Medium", budgetMs = 800, maxDepth = 4, randomCp = 25),
    HARD("Hard", budgetMs = 1800, maxDepth = 6, randomCp = 0)
}

object BuiltinEngine {

    private const val MATE = 100_000

    /** Publicly readable mate ceiling used by review and puzzle logic. */
    const val MATE_OPEN = MATE
    private const val TIMEOUT = Int.MIN_VALUE / 4

    private val PIECE_VALUE = mapOf(
        "PAWN" to 100, "KNIGHT" to 320, "BISHOP" to 330,
        "ROOK" to 500, "QUEEN" to 900, "KING" to 0
    )

    // Piece-square tables, written from White's point of view with the first
    // row = rank 8. Index convention: 0 = a8, 63 = h1, i.e. index = (8-rank)*8 + file.
    private val PST_PAWN = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
        5, 5, 10, 25, 25, 10, 5, 5,
        0, 0, 0, 20, 20, 0, 0, 0,
        5, -5, -10, 0, 0, -10, -5, 5,
        5, 10, 10, -20, -20, 10, 10, 5,
        0, 0, 0, 0, 0, 0, 0, 0
    )

    private val PST_KNIGHT = intArrayOf(
        -50, -40, -30, -30, -30, -30, -40, -50,
        -40, -20, 0, 0, 0, 0, -20, -40,
        -30, 0, 10, 15, 15, 10, 0, -30,
        -30, 5, 15, 20, 20, 15, 5, -30,
        -30, 0, 15, 20, 20, 15, 0, -30,
        -30, 5, 10, 15, 15, 10, 5, -30,
        -40, -20, 0, 5, 5, 0, -20, -40,
        -50, -40, -30, -30, -30, -30, -40, -50
    )

    private val PST_BISHOP = intArrayOf(
        -20, -10, -10, -10, -10, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 10, 10, 5, 0, -10,
        -10, 5, 5, 10, 10, 5, 5, -10,
        -10, 0, 10, 10, 10, 10, 0, -10,
        -10, 10, 10, 10, 10, 10, 10, -10,
        -10, 5, 0, 0, 0, 0, 5, -10,
        -20, -10, -10, -10, -10, -10, -10, -20
    )

    private val PST_ROOK = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0,
        5, 10, 10, 10, 10, 10, 10, 5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        -5, 0, 0, 0, 0, 0, 0, -5,
        0, 0, 0, 5, 5, 0, 0, 0
    )

    private val PST_QUEEN = intArrayOf(
        -20, -10, -10, -5, -5, -10, -10, -20,
        -10, 0, 0, 0, 0, 0, 0, -10,
        -10, 0, 5, 5, 5, 5, 0, -10,
        -5, 0, 5, 5, 5, 5, 0, -5,
        0, 0, 5, 5, 5, 5, 0, -5,
        -10, 5, 5, 5, 5, 5, 0, -10,
        -10, 0, 5, 0, 0, 0, 0, -10,
        -20, -10, -10, -5, -5, -10, -10, -20
    )

    private val PST_KING = intArrayOf(
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -30, -40, -40, -50, -50, -40, -40, -30,
        -20, -30, -30, -40, -40, -30, -30, -20,
        -10, -20, -20, -20, -20, -20, -20, -10,
        20, 20, 0, 0, 0, 0, 20, 20,
        20, 30, 10, 0, 0, 10, 30, 20
    )

    private val PST: Map<String, IntArray> = mapOf(
        "PAWN" to PST_PAWN, "KNIGHT" to PST_KNIGHT, "BISHOP" to PST_BISHOP,
        "ROOK" to PST_ROOK, "QUEEN" to PST_QUEEN, "KING" to PST_KING
    )

    /** Picks a move for the side to move. Returns null when the game is already over. */
    fun search(position: Board, difficulty: Difficulty): Move? {
        val board = Board()
        board.loadFromFen(position.fen)
        val rootMoves = board.legalMoves()
        if (rootMoves.isEmpty()) return null
        if (rootMoves.size == 1) return rootMoves[0]

        val deadline = System.nanoTime() + difficulty.budgetMs * 1_000_000
        var bestBatch: List<Move> = listOf(rootMoves[0])

        for (depth in 1..difficulty.maxDepth) {
            val scored = ArrayList<Pair<Move, Int>>(rootMoves.size)
            var aborted = false
            for (move in rootMoves) {
                board.doMove(move)
                val child = negamax(board, depth - 1, 1, -MATE, MATE, deadline)
                board.undoMove()
                if (child == TIMEOUT) { aborted = true; break }
                scored.add(move to -child)
            }
            if (aborted) break
            bestBatch = pickBatch(scored, difficulty)
            // Best moves first on the next iteration for stronger pruning.
            rootMoves.sortByDescending { m -> scored.first { it.first == m }.second }
        }

        val chosen = if (difficulty.randomCp > 0) bestBatch.random() else bestBatch.first()
        return position.legalMoves().firstOrNull {
            it.from == chosen.from && it.to == chosen.to && it.promotion == chosen.promotion
        } ?: position.legalMoves().firstOrNull { it.from == chosen.from && it.to == chosen.to }
    }

    private fun pickBatch(scored: List<Pair<Move, Int>>, difficulty: Difficulty): List<Move> {
        val sorted = scored.sortedByDescending { it.second }
        val top = sorted.first().second
        return sorted.takeWhile { top - it.second <= difficulty.randomCp }.map { it.first }
    }

    private fun negamax(
        board: Board, depth: Int, ply: Int, alphaIn: Int, beta: Int, deadline: Long
    ): Int {
        if ((ply and 3) == 0 && System.nanoTime() > deadline) return TIMEOUT
        val moves = board.legalMoves()
        if (moves.isEmpty()) {
            return if (board.isKingAttacked()) -MATE + ply else 0
        }
        if (depth <= 0) return quiesce(board, alphaIn, beta, deadline, 0)

        var alpha = alphaIn
        var best = -2 * MATE
        val ordered = orderMoves(board, moves)
        for (move in ordered) {
            board.doMove(move)
            val child = negamax(board, depth - 1, ply + 1, -beta, -alpha, deadline)
            board.undoMove()
            if (child == TIMEOUT) return TIMEOUT
            val score = -child
            if (score > best) best = score
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    private fun quiesce(
        board: Board, alphaIn: Int, beta: Int, deadline: Long, qsDepth: Int
    ): Int {
        if (System.nanoTime() > deadline) return TIMEOUT
        val stand = evaluateStm(board)
        if (qsDepth >= 4) return stand
        var alpha = maxOf(alphaIn, stand)
        if (alpha >= beta) return stand

        val captures = board.legalMoves().filter { isTactical(board, it) }
        val orderedCaptures = orderMoves(board, captures)
        var best = stand
        for (move in orderedCaptures) {
            board.doMove(move)
            val child = quiesce(board, -beta, -alpha, deadline, qsDepth + 1)
            board.undoMove()
            if (child == TIMEOUT) return TIMEOUT
            val score = -child
            if (score > best) best = score
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return best
    }

    private fun orderMoves(board: Board, moves: List<Move>): List<Move> =
        moves.sortedByDescending { move ->
            val victim = board.getPiece(move.to)
            val victimValue = if (victim == Piece.NONE) 0 else PIECE_VALUE[pieceType(victim)] ?: 0
            val attackerValue = PIECE_VALUE[pieceType(board.getPiece(move.from))] ?: 0
            val promoBonus = if (move.promotion != Piece.NONE) 800 else 0
            victimValue * 10 - attackerValue + promoBonus
        }

    private fun isTactical(board: Board, move: Move): Boolean {
        if (move.promotion != Piece.NONE) return true
        if (board.getPiece(move.to) != Piece.NONE) return true
        return pieceType(board.getPiece(move.from)) == "PAWN" && move.from.name[0] != move.to.name[0]
    }

    private fun pieceType(piece: Piece): String = piece.name.substringAfter('_')

    /**
     * Static evaluation in centipawns from White's point of view.
     * Positive values favour White. Fast, read-only, no search.
     */
    fun evaluateWhiteCp(position: Board): Int {
        val board = Board()
        board.loadFromFen(position.fen)
        return evalWhite(board)
    }

    /** Evaluation from the perspective of the side to move (negamax convention). */
    private fun evaluateStm(board: Board): Int {
        val white = evalWhite(board)
        return if (board.sideToMove == Side.WHITE) white else -white
    }

    private fun evalWhite(board: Board): Int {
        var total = 0
        for (square in Square.values()) {
            val piece = board.getPiece(square)
            if (piece == Piece.NONE) continue
            val index = squareIndexOf(square)
            val type = pieceType(piece)
            val value = PIECE_VALUE[type] ?: 0
            val table = PST[type]
            when {
                piece.name.startsWith("WHITE_") -> {
                    total += value + (table?.get(index) ?: 0)
                }
                else -> {
                    total -= value + (table?.get(index xor 56) ?: 0)
                }
            }
        }
        return total
    }

    /**
     * Full-strength analysis of a position: returns the best move and the search
     * score in centipawns from the side to move's perspective. Deterministic and
     * read-only - used by game review and puzzle verification.
     */
    fun analyzePosition(position: Board, budgetMs: Long = 300, maxDepth: Int = 4): Pair<Move?, Int> {
        val board = Board()
        board.loadFromFen(position.fen)
        val rootMoves = board.legalMoves()
        if (rootMoves.isEmpty()) return null to (if (board.isKingAttacked()) -MATE else 0)
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        var bestMove = rootMoves[0]
        var bestScore = -2 * MATE
        for (depth in 1..maxDepth) {
            var depthBest = -2 * MATE
            var depthMove: Move = rootMoves[0]
            var aborted = false
            for (move in rootMoves) {
                board.doMove(move)
                val child = negamax(board, depth - 1, 1, -MATE, MATE, deadline)
                board.undoMove()
                if (child == TIMEOUT) { aborted = true; break }
                val score = -child
                if (score > depthBest) { depthBest = score; depthMove = move }
            }
            if (!aborted) { bestScore = depthBest; bestMove = depthMove }
            if (aborted || bestScore >= MATE - 64 || bestScore <= -(MATE - 64)) break
        }
        return bestMove to bestScore
    }

    /** True when the side to move has a move that immediately checkmates the opponent. */
    fun hasMatingMove(position: Board): Boolean {
        val board = Board()
        board.loadFromFen(position.fen)
        for (move in board.legalMoves()) {
            board.doMove(move)
            val mated = board.isMated()
            board.undoMove()
            if (mated) return true
        }
        return false
    }

    /** True when the side to move is losing by force: the opponent's best play mates. */
    fun isGettingMated(position: Board, budgetMs: Long = 900, maxDepth: Int = 6): Boolean {
        val (_, score) = analyzePosition(position, budgetMs, maxDepth)
        return score <= -(MATE - 64)
    }

    /** True when the side to move has a forced mate within the search horizon. */
    fun hasForcedMate(position: Board, budgetMs: Long = 1500, maxDepth: Int = 6): Boolean {
        val board = Board()
        board.loadFromFen(position.fen)
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        for (move in board.legalMoves()) {
            board.doMove(move)
            val child = negamax(board, maxDepth - 1, 1, -MATE, MATE, deadline)
            board.undoMove()
            if (child == TIMEOUT) return false
            if (-child >= MATE - 64) return true
        }
        return false
    }

    /** Formats a White-perspective centipawn score like "+1.2" / "-0.4" / "0.0". */
    fun formatEval(whiteCp: Int): String {
        if (whiteCp >= MATE - 1000) return "+M"
        if (whiteCp <= -(MATE - 1000)) return "-M"
        val pawns = whiteCp / 100.0
        val sign = if (pawns > 0.05) "+" else if (pawns < -0.05) "-" else ""
        return sign + abs(pawns).let { String.format(Locale.US, "%.1f", it) }
    }

    private fun squareIndexOf(square: Square): Int {
        val name = square.name
        val file = name[0] - 'A'
        val rank = name[1].digitToInt()
        return (8 - rank) * 8 + file
    }
}
