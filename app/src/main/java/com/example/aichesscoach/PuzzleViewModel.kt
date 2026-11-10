package com.example.aichesscoach

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichesscoach.chess.ChessUtils
import com.example.aichesscoach.data.ChessDatabase
import com.example.aichesscoach.data.PuzzleStats
import com.example.aichesscoach.engine.BuiltinEngine
import com.example.aichesscoach.puzzles.Puzzle
import com.example.aichesscoach.puzzles.TrainingBank
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PendingPromotion(val fromIndex: Int, val toIndex: Int)

data class PuzzleUiState(
    val loaded: Boolean = false,
    val puzzle: Puzzle? = null,
    val board: List<String> = emptyList(),
    val selected: Int? = null,
    val legalTargets: Set<Int> = emptySet(),
    val lastMoveFrom: Int? = null,
    val lastMoveTo: Int? = null,
    val message: String = "Solve it: find the strongest move.",
    val phase: String = "solving",
    val fails: Int = 0,
    val showHint: Boolean = false,
    val pendingPromotion: PendingPromotion? = null,
    val stats: PuzzleStats = PuzzleStats(),
    val busy: Boolean = false
)

class PuzzleViewModel(app: Application) : AndroidViewModel(app) {

    private val database = ChessDatabase.get(app)
    private val dao = database.puzzles()
    private val puzzles = TrainingBank.loadPuzzles(app)
    private var board = Board()
    private var solutionMove: Move? = null

    private val _ui = MutableStateFlow(PuzzleUiState())
    val ui = _ui.asStateFlow()

    private var thinkingJob: Job? = null

    init {
        viewModelScope.launch {
            dao.observe().collect { stats ->
                _ui.update { it.copy(stats = stats ?: PuzzleStats()) }
            }
        }
        nextPuzzle()
    }

    fun nextPuzzle() {
        thinkingJob?.cancel()
        if (puzzles.isEmpty()) {
            _ui.update { it.copy(loaded = false, message = "No puzzles available.") }
            return
        }
        val stats = _ui.value.stats
        val recent = stats.recent.split(',').filter { it.isNotBlank() }.toSet()
        val pool = puzzles.filter { it.id !in recent }.ifEmpty { puzzles }
        val puzzle = pool.random()
        board = Board()
        board.loadFromFen(puzzle.fen)
        solutionMove = null
        _ui.update {
            it.copy(
                loaded = true,
                puzzle = puzzle,
                board = ChessUtils.renderBoard(board),
                selected = null,
                legalTargets = emptySet(),
                lastMoveFrom = null,
                lastMoveTo = null,
                message = "Puzzle: ${puzzle.theme}. Find the strongest move.",
                phase = "solving",
                fails = 0,
                showHint = false,
                pendingPromotion = null,
                busy = false
            )
        }
    }

    fun tapSquare(index: Int) {
        val s = _ui.value
        if (!s.loaded || s.phase != "solving" || s.busy || s.pendingPromotion != null) return
        val selected = s.selected
        if (selected == null) {
            selectPiece(index)
            return
        }
        if (index == selected) {
            _ui.update { it.copy(selected = null, legalTargets = emptySet()) }
            return
        }
        val candidates = board.legalMoves().filter {
            ChessUtils.squareIndex(it.from) == selected && ChessUtils.squareIndex(it.to) == index
        }
        when {
            candidates.isEmpty() -> {
                val piece = s.board[index]
                val own = piece.isNotBlank() && piece.startsWith("w")
                if (own) selectPiece(index)
                else _ui.update { it.copy(selected = null, legalTargets = emptySet(), message = "Not a legal move from here.") }
            }
            candidates.any { it.promotion != Piece.NONE } ->
                _ui.update { it.copy(pendingPromotion = PendingPromotion(selected, index), selected = null, legalTargets = emptySet()) }
            else -> playMove(candidates.first())
        }
    }

    fun choosePromotion(letter: String) {
        val pending = _ui.value.pendingPromotion ?: return
        _ui.update { it.copy(pendingPromotion = null) }
        val piece = Piece.valueOf("WHITE_" + when (letter) {
            "Q" -> "QUEEN"; "R" -> "ROOK"; "B" -> "BISHOP"; else -> "KNIGHT"
        })
        val move = board.legalMoves().firstOrNull {
            ChessUtils.squareIndex(it.from) == pending.fromIndex &&
                ChessUtils.squareIndex(it.to) == pending.toIndex && it.promotion == piece
        } ?: return
        playMove(move)
    }

    fun dismissPromotion() {
        _ui.update { it.copy(pendingPromotion = null, selected = null, legalTargets = emptySet()) }
    }

    fun showHint() {
        _ui.update { it.copy(showHint = true) }
    }

    private fun selectPiece(index: Int) {
        val piece = _ui.value.board.getOrElse(index) { "" }
        if (piece.isBlank() || !piece.startsWith("w")) return
        val targets = board.legalMoves().filter { ChessUtils.squareIndex(it.from) == index }
            .map { ChessUtils.squareIndex(it.to) }.toSet()
        _ui.update { it.copy(selected = index, legalTargets = targets) }
    }

    private fun playMove(move: Move) {
        val puzzle = _ui.value.puzzle ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        thinkingJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val work = Board()
                work.loadFromFen(board.fen)
                val applied = work.legalMoves().firstOrNull {
                    it.from == move.from && it.to == move.to && it.promotion == move.promotion
                } ?: return@withContext null
                work.doMove(applied)

                var verdict = Verdict.REJECT
                var reply: Move? = null
                if (work.isMated()) {
                    verdict = Verdict.SOLVED
                } else when (puzzle.type) {
                    Puzzle.TYPE_MATE1 -> Verdict.REJECT
                    Puzzle.TYPE_MATE2 -> if (BuiltinEngine.isGettingMated(work, budgetMs = 1000, maxDepth = 6)) {
                        verdict = Verdict.CONTINUE
                        reply = BuiltinEngine.analyzePosition(work, budgetMs = 800, maxDepth = 5).first
                        reply?.let { work.doMove(it) }
                    } else Verdict.REJECT
                    Puzzle.TYPE_MATERIAL -> {
                        val stmScore = BuiltinEngine.analyzePosition(work, budgetMs = 600, maxDepth = 4).second
                        val moverScore = if (work.sideToMove == Side.WHITE) -stmScore else stmScore
                        if (moverScore >= 250) verdict = Verdict.SOLVED else Verdict.REJECT
                    }
                    else -> Verdict.REJECT
                }
                Triple(verdict, reply, work)
            }
            _ui.update { it.copy(busy = false) }
            if (result == null) {
                _ui.update { it.copy(selected = null, legalTargets = emptySet()) }
                return@launch
            }
            val (verdict, reply, workBoard) = result
            applyVerdict(move, verdict, reply, workBoard)
        }
    }

    private enum class Verdict { SOLVED, CONTINUE, REJECT }

    private fun applyVerdict(move: Move, verdict: Verdict, reply: Move?, workBoard: Board) {
        val puzzle = _ui.value.puzzle ?: return
        when (verdict) {
            Verdict.REJECT -> {
                val fails = _ui.value.fails + 1
                _ui.update {
                    it.copy(
                        fails = fails,
                        selected = null,
                        legalTargets = emptySet(),
                        message = if (fails >= 2) "Not this time. The key move was ${puzzle.solution}. Try the next puzzle." else "Close, but not the key move. Look again.",
                        phase = if (fails >= 2) "failed" else "solving"
                    )
                }
                if (fails >= 2) recordResult(false)
            }
            Verdict.CONTINUE -> {
                board = workBoard
                val replySan: String? = reply?.let { m ->
                    val back = Board()
                    back.loadFromFen(puzzle.fen)
                    // Reconstruct the position after the user's move to name the reply.
                    runCatching {
                        val userMove = back.legalMoves().firstOrNull {
                            it.from == move.from && it.to == move.to && it.promotion == move.promotion
                        }
                        userMove?.let { back.doMove(it) }
                        ChessUtils.sanWithSuffix(ChessUtils.sanFor(back, m), back)
                    }.getOrNull()
                }
                _ui.update {
                    it.copy(
                        board = ChessUtils.renderBoard(board),
                        selected = null,
                        legalTargets = emptySet(),
                        lastMoveFrom = reply?.let { m -> ChessUtils.squareIndex(m.from) },
                        lastMoveTo = reply?.let { m -> ChessUtils.squareIndex(m.to) },
                        message = "Correct! ${replySan?.let { s -> "The opponent defended with $s." } ?: ""} Keep going - finish the attack."
                    )
                }
            }
            Verdict.SOLVED -> {
                board = workBoard
                _ui.update {
                    it.copy(
                        board = ChessUtils.renderBoard(board),
                        selected = null,
                        legalTargets = emptySet(),
                        lastMoveFrom = ChessUtils.squareIndex(move.from),
                        lastMoveTo = ChessUtils.squareIndex(move.to),
                        message = when (puzzle.type) {
                            Puzzle.TYPE_MATE1, Puzzle.TYPE_MATE2 -> "Checkmate - solved!"
                            else -> "Correct - you won material!"
                        },
                        phase = "solved"
                    )
                }
                recordResult(true)
            }
        }
    }

    private fun recordResult(success: Boolean) {
        viewModelScope.launch {
            val current = dao.get() ?: PuzzleStats()
            val puzzle = _ui.value.puzzle
            val rating = if (success) {
                val delta = (((puzzle?.rating ?: 400) - current.rating) / 8).coerceIn(3, 30)
                current.rating + delta
            } else {
                val delta = -(((current.rating - (puzzle?.rating ?: 400)).coerceIn(0, 200)) / 8 + 6).coerceIn(6, 20)
                current.rating + delta
            }.coerceIn(100, 3000)
            val streak = if (success) current.streak + 1 else 0
            val recentIds = (current.recent.split(',').filter { it.isNotBlank() } + (puzzle?.id ?: ""))
                .takeLast(12).joinToString(",")
            dao.put(
                current.copy(
                    rating = rating,
                    solved = current.solved + if (success) 1 else 0,
                    failed = current.failed + if (success) 0 else 1,
                    streak = streak,
                    bestStreak = maxOf(current.bestStreak, streak),
                    recent = recentIds
                )
            )
        }
    }

    fun cleanup() {
        thinkingJob?.cancel()
    }
}
