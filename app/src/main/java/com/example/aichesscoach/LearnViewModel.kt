package com.example.aichesscoach

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aichesscoach.chess.ChessUtils
import com.example.aichesscoach.data.ChessDatabase
import com.example.aichesscoach.data.LessonProgress
import com.example.aichesscoach.engine.BuiltinEngine
import com.example.aichesscoach.puzzles.Chapter
import com.example.aichesscoach.puzzles.Exercise
import com.example.aichesscoach.puzzles.Lesson
import com.example.aichesscoach.puzzles.TrainingBank
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LearnUiState(
    val chapters: List<Chapter> = emptyList(),
    val completedLessons: Set<String> = emptySet(),
    val activeLesson: Lesson? = null,
    val exerciseIndex: Int = 0,
    val taskIndex: Int = 0,
    val board: List<String> = emptyList(),
    val selected: Int? = null,
    val legalTargets: Set<Int> = emptySet(),
    val message: String = "",
    val phase: String = "overview",
    val showHint: Boolean = false,
    val pendingPromotion: PendingPromotion? = null
) {
    val currentExercise: Exercise? get() = activeLesson?.exercises?.getOrNull(exerciseIndex)
}

/** Validates lesson exercises with the built-in engine so impossible drills can never ship. */
class LearnViewModel(app: Application) : AndroidViewModel(app) {

    private val database = ChessDatabase.get(app)
    private val dao = database.lessons()

    private var board = Board()

    private val _ui = MutableStateFlow(LearnUiState())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            dao.observeAll().collect { rows ->
                _ui.update { it.copy(completedLessons = rows.map { r -> r.lessonId }.toSet()) }
            }
        }
        _ui.update { it.copy(chapters = TrainingBank.loadLessons(app)) }
    }

    val isChapterUnlocked: (Chapter, Int) -> Boolean = { _, index ->
        index == 0 || _ui.value.completedLessons.containsAll(
            _ui.value.chapters.getOrNull(index - 1)?.lessons?.map { l -> l.id } ?: emptyList()
        )
    }

    fun startLesson(lesson: Lesson) {
        _ui.update {
            it.copy(
                activeLesson = lesson,
                exerciseIndex = 0,
                taskIndex = 0,
                phase = "exercise",
                showHint = false,
                pendingPromotion = null
            )
        }
        loadExercise()
    }

    fun exitLesson() {
        _ui.update { it.copy(activeLesson = null, phase = "overview") }
    }

    private fun loadExercise() {
        val s = _ui.value
        val exercise = s.currentExercise ?: return
        board = Board()
        board.loadFromFen(exercise.fen)
        _ui.update {
            it.copy(
                exerciseIndex = s.exerciseIndex,
                taskIndex = 0,
                board = ChessUtils.renderBoard(board),
                selected = null,
                legalTargets = emptySet(),
                message = exercise.instruction,
                showHint = false,
                pendingPromotion = null
            )
        }
    }

    fun tapSquare(index: Int) {
        val s = _ui.value
        if (s.phase != "exercise" || s.pendingPromotion != null) return
        val exercise = s.currentExercise ?: return
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
                else _ui.update { it.copy(selected = null, legalTargets = emptySet(), message = "That move is not legal here.") }
            }
            candidates.any { it.promotion != Piece.NONE } ->
                _ui.update { it.copy(pendingPromotion = PendingPromotion(selected, index), selected = null, legalTargets = emptySet()) }
            else -> tryMove(candidates.first())
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
        tryMove(move)
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

    private fun taskMatches(exercise: Exercise, taskIndex: Int, move: Move): Boolean {
        val from = ChessUtils.indexName(ChessUtils.squareIndex(move.from))
        val to = ChessUtils.indexName(ChessUtils.squareIndex(move.to))
        val primary = exercise.tasks.getOrNull(taskIndex)
        val primaryMatch = primary != null && primary.from == from && primary.to == to
        val altMatch = exercise.alternatives.any { it.from == from && it.to == to }
        return primaryMatch || altMatch
    }

    private fun tryMove(move: Move) {
        val s = _ui.value
        val exercise = s.currentExercise ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) { evaluate(exercise, s.taskIndex, move) }
            _ui.update { it.copy(selected = null, legalTargets = emptySet()) }
            when (outcome) {
                is Outcome.Rejected -> _ui.update {
                    it.copy(message = "Not quite - try again. ${exercise.instruction}")
                }
                is Outcome.Advanced -> {
                    applyWithReplies(exercise, s.taskIndex, move)
                    _ui.update {
                        it.copy(board = ChessUtils.renderBoard(board), message = outcome.message, taskIndex = outcome.nextTask)
                    }
                }
                Outcome.ExerciseDone -> {
                    applyWithReplies(exercise, s.taskIndex, move)
                    finishExercise()
                }
            }
        }
    }

    private sealed class Outcome {
        object Rejected : Outcome()
        object ExerciseDone : Outcome()
        data class Advanced(val nextTask: Int, val message: String) : Outcome()
    }

    /** Runs on Default: validates the move on a scratch board without touching the live one. */
    private fun evaluate(exercise: Exercise, taskIndex: Int, move: Move): Outcome {
        val work = Board()
        work.loadFromFen(board.fen)
        val applied = work.legalMoves().firstOrNull {
            it.from == move.from && it.to == move.to && it.promotion == move.promotion
        } ?: return Outcome.Rejected
        work.doMove(applied)
        return when (exercise.type) {
            Exercise.TYPE_MOVE -> {
                if (!taskMatches(exercise, taskIndex, move)) return Outcome.Rejected
                val nextTask = taskIndex + 1
                if (nextTask >= exercise.tasks.size) Outcome.ExerciseDone
                else Outcome.Advanced(nextTask, "Nice! Now: ${describeTask(exercise, nextTask)}")
            }
            Exercise.TYPE_MATE1 -> if (work.isMated()) Outcome.ExerciseDone else Outcome.Rejected
            Exercise.TYPE_PUZZLE -> {
                val stmScore = BuiltinEngine.analyzePosition(work, budgetMs = 500, maxDepth = 4).second
                val moverScore = if (work.sideToMove == Side.WHITE) -stmScore else stmScore
                if (moverScore >= 250) Outcome.ExerciseDone else Outcome.Rejected
            }
            else -> Outcome.Rejected
        }
    }

    /** Applies the validated move on the main thread, plus a scripted reply for move exercises. */
    private fun applyWithReplies(exercise: Exercise, taskIndex: Int, move: Move) {
        val applied = board.legalMoves().firstOrNull {
            it.from == move.from && it.to == move.to && it.promotion == move.promotion
        } ?: return
        board.doMove(applied)
        if (exercise.type == Exercise.TYPE_MOVE) {
            exercise.replies.getOrNull(taskIndex)?.let { replyTask ->
                val reply = board.legalMoves().firstOrNull {
                    ChessUtils.indexName(ChessUtils.squareIndex(it.from)) == replyTask.from &&
                        ChessUtils.indexName(ChessUtils.squareIndex(it.to)) == replyTask.to
                }
                reply?.let { board.doMove(it) }
            }
        }
    }

    private fun describeTask(exercise: Exercise, taskIndex: Int): String {
        val task = exercise.tasks.getOrNull(taskIndex) ?: return "make the next move"
        return "${task.from} to ${task.to}."
    }

    private fun finishExercise() {
        val s = _ui.value
        val lesson = s.activeLesson ?: return
        val nextIndex = s.exerciseIndex + 1
        if (nextIndex < lesson.exercises.size) {
            _ui.update { it.copy(exerciseIndex = nextIndex) }
            loadExercise()
        } else {
            viewModelScope.launch {
                dao.upsert(LessonProgress(lesson.id, System.currentTimeMillis()))
            }
            _ui.update {
                it.copy(
                    phase = "lesson_complete",
                    message = "Lesson complete! Well played.",
                    board = ChessUtils.renderBoard(board)
                )
            }
        }
    }
}
