package com.example.aichesscoach

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.aichesscoach.AI.CoachApi
import com.example.aichesscoach.AI.CoachConfig
import com.example.aichesscoach.data.ChessDatabase
import com.example.aichesscoach.data.SavedGame
import com.example.aichesscoach.engine.BuiltinEngine
import com.example.aichesscoach.engine.Difficulty
import com.example.aichesscoach.review.GameReview
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

enum class GameMode(val label: String, val dbValue: String) {
    VS_COMPUTER("You vs computer", SavedGame.MODE_VS_COMPUTER),
    TWO_PLAYERS("Two players", SavedGame.MODE_TWO_PLAYERS)
}

enum class PlayerSide(val label: String, val dbValue: String) {
    WHITE("White", SavedGame.SIDE_WHITE),
    BLACK("Black", SavedGame.SIDE_BLACK);

    val side: Side get() = if (this == WHITE) Side.WHITE else Side.BLACK
}

data class ChatMessage(val isUser: Boolean, val text: String, val at: Long = System.currentTimeMillis())

data class ReviewUiState(
    val busy: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val title: String = "",
    val report: GameReview.Report? = null
)

data class ChessUiState(
    val board: List<String> = emptyBoard(),
    val selected: Int? = null,
    val legalTargets: Set<Int> = emptySet(),
    val lastMoveFrom: Int? = null,
    val lastMoveTo: Int? = null,
    val checkedIndex: Int? = null,
    val turnLabel: String = "White to move",
    val status: String = "New game. White to move.",
    val sanHistory: List<String> = emptyList(),
    val lastMoveSan: String? = null,
    val fenBeforeLast: String? = null,
    val gameOver: Boolean = false,
    val resultLabel: String? = null,
    val pendingPromotion: PendingPromotion? = null,
    val mode: GameMode = GameMode.VS_COMPUTER,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val playerSide: PlayerSide = PlayerSide.WHITE,
    val flipBoard: Boolean = false,
    val showEval: Boolean = true,
    val evalLabel: String? = null,
    val engineThinking: Boolean = false,
    val isPlayersTurn: Boolean = true,
    val coachChat: List<ChatMessage> = emptyList(),
    val coachBusy: Boolean = false,
    val reviewState: ReviewUiState? = null,
    val savedGames: List<SavedGame> = emptyList(),
    val games: Int = 0,
    val moves: Int = 0,
    val provider: String = "OpenAI",
    val endpoint: String = "https://api.openai.com/v1",
    val model: String = "gpt-4.1-mini",
    val apiKey: String = "",
    val apiStatus: String = "Not configured",
    val busy: Boolean = false
)

private fun emptyBoard(): List<String> = List(64) { "" }

class ChessViewModel(app: Application) : AndroidViewModel(app) {

    private val database = ChessDatabase.get(app)
    private val dao = database.games()
    private val api = CoachApi()
    private var chess = Board()
    private val _ui = MutableStateFlow(ChessUiState(board = renderBoard(chess)))
    val ui = _ui.asStateFlow()

    private var engineJob: Job? = null
    private var evalJob: Job? = null

    private val secretPrefs = runCatching {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(app, "coach_secrets", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }.getOrNull()

    private val settingsPrefs = app.getSharedPreferences("coach_settings", Context.MODE_PRIVATE)

    init {
        secretPrefs?.let { prefs ->
            _ui.update {
                it.copy(
                    provider = prefs.getString("provider", "OpenAI") ?: "OpenAI",
                    endpoint = prefs.getString("endpoint", "https://api.openai.com/v1") ?: "",
                    model = prefs.getString("model", "gpt-4.1-mini") ?: "",
                    apiKey = prefs.getString("key", "") ?: ""
                )
            }
        }
        _ui.update { state ->
            state.copy(
                mode = settingsPrefs.getString("mode", null)?.let { saved -> GameMode.entries.firstOrNull { it.name == saved } } ?: state.mode,
                difficulty = settingsPrefs.getString("difficulty", null)?.let { saved -> Difficulty.entries.firstOrNull { it.name == saved } } ?: state.difficulty,
                playerSide = settingsPrefs.getString("playerSide", null)?.let { saved -> PlayerSide.entries.firstOrNull { it.name == saved } } ?: state.playerSide,
                flipBoard = settingsPrefs.getBoolean("flipBoard", false),
                showEval = settingsPrefs.getBoolean("showEval", true),
                coachChat = listOf(
                    ChatMessage(false, "Hi! I'm your chess coach. Play a game, then ask me to explain a move, " +
                        "discuss the position, or review a whole game. Configure an AI provider in Settings to activate me.")
                )
            )
        }
        viewModelScope.launch {
            dao.observeAll().collect { games ->
                _ui.update {
                    it.copy(
                        savedGames = games,
                        games = games.size,
                        moves = games.sumOf { g -> g.plyCount }
                    )
                }
            }
        }
        refreshEval()
        maybeTriggerEngine()
    }

    // ---------------------------------------------------------------------
    // Board interaction
    // ---------------------------------------------------------------------

    fun tapSquare(index: Int) {
        val s = _ui.value
        if (s.gameOver || s.engineThinking || s.pendingPromotion != null) return
        if (s.mode == GameMode.VS_COMPUTER && chess.sideToMove != s.playerSide.side) return
        val selected = s.selected
        if (selected == null) {
            selectPiece(index)
            return
        }
        if (index == selected) {
            _ui.update { it.copy(selected = null, legalTargets = emptySet(), status = "Selection cleared.") }
            return
        }
        val candidates = chess.legalMoves().filter {
            squareIndex(it.from) == selected && squareIndex(it.to) == index
        }
        when {
            candidates.isEmpty() -> {
                val piece = s.board[index]
                val own = piece.isNotBlank() && (piece.startsWith("w") == (chess.sideToMove == Side.WHITE))
                if (own) selectPiece(index)
                else _ui.update { it.copy(selected = null, legalTargets = emptySet(), status = "That move is not legal.") }
            }
            candidates.any { it.promotion != Piece.NONE } ->
                _ui.update { it.copy(pendingPromotion = PendingPromotion(selected, index), legalTargets = emptySet(), selected = null) }
            else -> applyMove(candidates.first())
        }
    }

    /** letter is one of "Q", "R", "B", "N"; the correct side is derived from the position. */
    fun choosePromotion(letter: String) {
        val pending = _ui.value.pendingPromotion ?: return
        _ui.update { it.copy(pendingPromotion = null) }
        val sidePrefix = if (chess.sideToMove == Side.WHITE) "WHITE" else "BLACK"
        val piece = when (letter) {
            "Q" -> Piece.valueOf("${sidePrefix}_QUEEN")
            "R" -> Piece.valueOf("${sidePrefix}_ROOK")
            "B" -> Piece.valueOf("${sidePrefix}_BISHOP")
            else -> Piece.valueOf("${sidePrefix}_KNIGHT")
        }
        val move = chess.legalMoves().firstOrNull {
            squareIndex(it.from) == pending.fromIndex && squareIndex(it.to) == pending.toIndex && it.promotion == piece
        } ?: return
        applyMove(move)
    }

    fun dismissPromotion() {
        _ui.update { it.copy(pendingPromotion = null, selected = null, legalTargets = emptySet()) }
    }

    /** Closes the game-over dialog without starting a new game. */
    fun dismissGameOver() {
        _ui.update { it.copy(resultLabel = null) }
    }

    private fun selectPiece(index: Int) {
        val piece = _ui.value.board.getOrElse(index) { "" }
        val isPlayersTurn = chess.sideToMove == Side.WHITE
        if (piece.isBlank() || (piece.startsWith("w") != isPlayersTurn)) return
        val targets = chess.legalMoves().filter { squareIndex(it.from) == index }.map { squareIndex(it.to) }.toSet()
        _ui.update {
            it.copy(
                selected = index,
                legalTargets = targets,
                status = if (targets.isEmpty()) "No legal moves for this piece." else "Choose a highlighted square."
            )
        }
    }

    private fun playersTurnNow(mode: GameMode, playerSide: PlayerSide) =
        mode == GameMode.TWO_PLAYERS || chess.sideToMove == playerSide.side

    /** Applies a legal move: records SAN, updates highlights, detects game end, saves and hands over to the engine. */
    private fun applyMove(move: Move) {
        val fenBefore = chess.fen
        val sanBase = sanBaseFor(chess, move)
        val mode = _ui.value.mode
        val playerSide = _ui.value.playerSide
        if (!chess.doMove(move)) {
            _ui.update { it.copy(selected = null, legalTargets = emptySet(), status = "Move rejected by the rules engine.") }
            return
        }
        val opponentInCheck = chess.isKingAttacked()
        val san = sanBase + if (opponentInCheck) (if (chess.isMated) "#" else "+") else ""
        val mate = chess.isMated
        val draw = chess.isDraw && !mate
        val over = mate || draw
        val result = when {
            mate -> if (chess.sideToMove == Side.WHITE) "0-1" else "1-0"
            draw -> "1/2-1/2"
            else -> "*"
        }
        val checkedIndex = if (chess.isKingAttacked()) kingIndexOf(chess.sideToMove) else null
        val resultLabel: String? = when {
            !over -> null
            mode == GameMode.TWO_PLAYERS -> if (mate) "Checkmate — ${if (chess.sideToMove == Side.WHITE) "Black" else "White"} wins" else "Draw"
            mate -> if (chess.sideToMove == playerSide.side) "Checkmate — the computer wins" else "Checkmate — you win!"
            else -> "Draw"
        }
        val status = when {
            mate -> "Checkmate. Result: $result."
            draw -> "Draw."
            checkedIndex != null -> "Check!"
            else -> "Move played."
        }
        _ui.update {
            it.copy(
                board = renderBoard(chess),
                selected = null,
                legalTargets = emptySet(),
                lastMoveFrom = squareIndex(move.from),
                lastMoveTo = squareIndex(move.to),
                checkedIndex = checkedIndex,
                turnLabel = if (chess.sideToMove == Side.WHITE) "White to move" else "Black to move",
                status = status,
                sanHistory = it.sanHistory + san,
                lastMoveSan = san,
                fenBeforeLast = fenBefore,
                gameOver = over,
                resultLabel = resultLabel,
                isPlayersTurn = playersTurnNow(mode, it.playerSide)
            )
        }
        if (over) saveFinishedGame(result)
        refreshEval()
        maybeTriggerEngine()
    }

    // ---------------------------------------------------------------------
    // SAN + helpers
    // ---------------------------------------------------------------------

    /** Standard algebraic notation without check/mate suffix (added after the move is applied). */
    private fun sanBaseFor(board: Board, move: Move): String {
        val piece = board.getPiece(move.from)
        val type = piece.name.substringAfter('_')
        val fileFrom = move.from.name[0]
        val rankFrom = move.from.name[1]
        val fileTo = move.to.name[0]

        if (type == "KING" && abs(fileFrom - fileTo) == 2) {
            return if (fileTo > fileFrom) "O-O" else "O-O-O"
        }
        val isCapture = board.getPiece(move.to) != Piece.NONE || (type == "PAWN" && fileFrom != fileTo)
        val letter = when (type) {
            "KING" -> "K"; "QUEEN" -> "Q"; "ROOK" -> "R"; "BISHOP" -> "B"; "KNIGHT" -> "N"
            else -> ""
        }
        val san = StringBuilder()
        if (letter.isNotEmpty()) {
            san.append(letter)
            val rivals = board.legalMoves().filter {
                it.to == move.to && it.from != move.from &&
                    board.getPiece(it.from).name.substringAfter('_') == type
            }
            if (rivals.isNotEmpty()) {
                val fileUnique = rivals.none { it.from.name[0] == fileFrom }
                val rankUnique = rivals.none { it.from.name[1] == rankFrom }
                when {
                    fileUnique -> san.append(fileFrom.lowercaseChar())
                    rankUnique -> san.append(rankFrom)
                    else -> san.append(fileFrom.lowercaseChar()).append(rankFrom)
                }
            }
            if (isCapture) san.append('x')
        } else if (isCapture) {
            san.append(fileFrom.lowercaseChar()).append('x')
        }
        san.append(move.to.name.lowercase())
        if (move.promotion != Piece.NONE) {
            san.append('=').append(promotionLetter(move.promotion))
        }
        return san.toString()
    }

    private fun promotionLetter(piece: Piece): String = when (piece.name.substringAfter('_')) {
        "QUEEN" -> "Q"; "ROOK" -> "R"; "BISHOP" -> "B"; "KNIGHT" -> "N"; else -> ""
    }

    private fun squareIndex(square: Square): Int {
        val name = square.name
        val file = name[0] - 'A'
        val rank = name[1].digitToInt()
        return (8 - rank) * 8 + file
    }

    private fun kingIndexOf(side: Side): Int = runCatching {
        squareIndex(chess.getKingSquare(side))
    }.getOrDefault(squareIndex(kingSquareByScan(side)))

    private fun kingSquareByScan(side: Side): Square =
        Square.values().firstOrNull { board -> chess.getPiece(board).name == (if (side == Side.WHITE) "WHITE_KING" else "BLACK_KING") }
            ?: Square.E1

    private fun renderBoard(board: Board): List<String> = (0 until 64).map { index ->
        val file = 'A' + (index % 8)
        val rank = 8 - (index / 8)
        val piece = board.getPiece(Square.valueOf("$file$rank")).name
        when {
            piece == "NONE" -> ""
            piece.startsWith("WHITE_") -> "w" + piece.removePrefix("WHITE_").let { if (it == "KNIGHT") "N" else it.take(1) }
            piece.startsWith("BLACK_") -> "b" + piece.removePrefix("BLACK_").let { if (it == "KNIGHT") "N" else it.take(1) }
            else -> ""
        }
    }

    private fun toPgn(sans: List<String>): String = buildString {
        var i = 0
        var number = 1
        while (i < sans.size) {
            append(number).append(". ").append(sans[i])
            if (i + 1 < sans.size) append(' ').append(sans[i + 1])
            append(' ')
            i += 2
            number++
        }
    }.trim()

    // ---------------------------------------------------------------------
    // Game control
    // ---------------------------------------------------------------------

    fun newGame() {
        cancelEngine()
        chess = Board()
        _ui.update {
            it.copy(
                board = renderBoard(chess),
                selected = null,
                legalTargets = emptySet(),
                lastMoveFrom = null,
                lastMoveTo = null,
                checkedIndex = null,
                turnLabel = "White to move",
                status = "New game. White to move.",
                sanHistory = emptyList(),
                lastMoveSan = null,
                fenBeforeLast = null,
                gameOver = false,
                resultLabel = null,
                pendingPromotion = null,
                isPlayersTurn = playersTurnNow(it.mode, it.playerSide)
            )
        }
        refreshEval()
        maybeTriggerEngine()
    }

    fun undo() {
        val s = _ui.value
        if (s.sanHistory.isEmpty()) return
        cancelEngine()
        val undone = runCatching { chess.undoMove() }.getOrNull()
        if (undone == null) {
            _ui.update { it.copy(status = "Nothing to undo.") }
            return
        }
        var plies = 1
        // Against the computer, also take back its reply so the player moves again.
        if (s.mode == GameMode.VS_COMPUTER && s.sanHistory.size > 1 && chess.sideToMove != s.playerSide.side) {
            runCatching { chess.undoMove() }
            plies = 2
        }
        _ui.update {
            val newHistory = it.sanHistory.dropLast(plies)
            it.copy(
                board = renderBoard(chess),
                selected = null,
                legalTargets = emptySet(),
                lastMoveFrom = null,
                lastMoveTo = null,
                checkedIndex = if (chess.isKingAttacked()) kingIndexOf(chess.sideToMove) else null,
                turnLabel = if (chess.sideToMove == Side.WHITE) "White to move" else "Black to move",
                status = "Move undone.",
                sanHistory = newHistory,
                lastMoveSan = newHistory.lastOrNull(),
                fenBeforeLast = null,
                gameOver = false,
                resultLabel = null,
                isPlayersTurn = playersTurnNow(it.mode, it.playerSide)
            )
        }
        refreshEval()
        maybeTriggerEngine()
    }

    fun toggleFlipBoard() {
        settingsPrefs.edit().putBoolean("flipBoard", !_ui.value.flipBoard).apply()
        _ui.update { it.copy(flipBoard = !it.flipBoard) }
    }

    fun setMode(mode: GameMode) {
        settingsPrefs.edit().putString("mode", mode.name).apply()
        _ui.update { it.copy(mode = mode, isPlayersTurn = playersTurnNow(mode, it.playerSide)) }
        cancelEngine()
        maybeTriggerEngine()
    }

    fun setDifficulty(difficulty: Difficulty) {
        settingsPrefs.edit().putString("difficulty", difficulty.name).apply()
        _ui.update { it.copy(difficulty = difficulty) }
        cancelEngine()
        maybeTriggerEngine()
    }

    fun setPlayerSide(playerSide: PlayerSide) {
        settingsPrefs.edit().putString("playerSide", playerSide.name).apply()
        _ui.update { it.copy(playerSide = playerSide, isPlayersTurn = playersTurnNow(it.mode, playerSide)) }
        cancelEngine()
        maybeTriggerEngine()
    }

    fun setShowEval(enabled: Boolean) {
        settingsPrefs.edit().putBoolean("showEval", enabled).apply()
        _ui.update { it.copy(showEval = enabled, evalLabel = if (enabled) it.evalLabel else null) }
        if (enabled) refreshEval()
    }

    // ---------------------------------------------------------------------
    // Built-in engine
    // ---------------------------------------------------------------------

    private fun maybeTriggerEngine() {
        val s = _ui.value
        if (s.mode != GameMode.VS_COMPUTER || s.gameOver) return
        if (chess.sideToMove == s.playerSide.side) return
        cancelEngine()
        val difficulty = s.difficulty
        engineJob = viewModelScope.launch {
            _ui.update { it.copy(engineThinking = true, isPlayersTurn = false, status = "Computer is thinking…") }
            val fen = chess.fen
            val move = withContext(Dispatchers.Default) {
                val position = Board()
                position.loadFromFen(fen)
                BuiltinEngine.search(position, difficulty)
            }
            if (move == null) {
                _ui.update { it.copy(engineThinking = false) }
                return@launch
            }
            delay(250) // let the move feel deliberate instead of instantaneous
            _ui.update { it.copy(engineThinking = false) }
            applyMove(move)
        }
    }

    private fun cancelEngine() {
        engineJob?.cancel()
        engineJob = null
        _ui.update { it.copy(engineThinking = false) }
    }

    private fun refreshEval() {
        evalJob?.cancel()
        if (!_ui.value.showEval) return
        if (_ui.value.gameOver) {
            _ui.update { it.copy(evalLabel = null) }
            return
        }
        val fen = chess.fen
        evalJob = viewModelScope.launch {
            val cp = withContext(Dispatchers.Default) {
                val position = Board()
                position.loadFromFen(fen)
                BuiltinEngine.evaluateWhiteCp(position)
            }
            _ui.update { it.copy(evalLabel = BuiltinEngine.formatEval(cp)) }
        }
    }

    // ---------------------------------------------------------------------
    // Storage
    // ---------------------------------------------------------------------

    private fun saveFinishedGame(result: String) {
        val s = _ui.value
        val pgn = toPgn(s.sanHistory)
        val game = SavedGame(
            playedAt = System.currentTimeMillis(),
            pgn = pgn,
            result = result,
            plyCount = s.sanHistory.size,
            fen = chess.fen,
            mode = s.mode.dbValue,
            playerSide = s.playerSide.dbValue
        )
        viewModelScope.launch {
            runCatching { dao.insert(game) }
                .onFailure { _ui.update { state -> state.copy(status = "Game ended, but saving failed: ${it.message}") } }
        }
    }

    fun deleteGame(id: Long) {
        viewModelScope.launch { runCatching { dao.deleteById(id) } }
    }

    fun clearHistory() {
        viewModelScope.launch { runCatching { dao.deleteAll() } }
    }

    // ---------------------------------------------------------------------
    // API settings
    // ---------------------------------------------------------------------

    private fun config() = CoachConfig(_ui.value.provider, _ui.value.endpoint, _ui.value.apiKey, _ui.value.model)

    fun saveApi(provider: String, endpoint: String, model: String, key: String) {
        val cleanEndpoint = endpoint.trim().trimEnd('/')
        if (!cleanEndpoint.startsWith("https://")) {
            _ui.update { it.copy(apiStatus = "Use an HTTPS endpoint.") }
            return
        }
        _ui.update { it.copy(provider = provider, endpoint = cleanEndpoint, model = model.trim(), apiKey = key.trim(), apiStatus = "Saving securely…") }
        runCatching {
            val app = getApplication<Application>()
            val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(app, "coach_secrets", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM).edit()
                .putString("provider", provider)
                .putString("endpoint", cleanEndpoint)
                .putString("model", model.trim())
                .putString("key", key.trim())
                .apply()
            _ui.update { it.copy(apiStatus = "Settings saved on this device.") }
        }.onFailure {
            _ui.update { state -> state.copy(apiStatus = "Secure storage unavailable: ${it.message}") }
        }
    }

    fun testApi() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, apiStatus = "Testing connection…") }
            runCatching { api.test(config()) }
                .onSuccess { msg -> _ui.update { it.copy(busy = false, apiStatus = msg) } }
                .onFailure { cause -> _ui.update { it.copy(busy = false, apiStatus = cause.message ?: "Connection failed.") } }
        }
    }

    // ---------------------------------------------------------------------
    // Coach
    // ---------------------------------------------------------------------

    private fun engineNote(): String = runCatching {
        val cp = BuiltinEngine.evaluateWhiteCp(chess)
        "Note from the app's built-in lightweight analyzer (material + piece-square tables, not Stockfish): ${BuiltinEngine.formatEval(cp)} from White's perspective."
    }.getOrDefault("No automated evaluation is available for this position.")

    private fun askCoach(userText: String, request: suspend () -> String) {
        if (_ui.value.coachBusy) return
        _ui.update { it.copy(coachBusy = true, coachChat = it.coachChat + ChatMessage(true, userText)) }
        viewModelScope.launch {
            runCatching { request() }
                .onSuccess { reply -> _ui.update { it.copy(coachBusy = false, coachChat = it.coachChat + ChatMessage(false, reply)) } }
                .onFailure { cause -> _ui.update { it.copy(coachBusy = false, coachChat = it.coachChat + ChatMessage(false, "⚠️ ${cause.message ?: "The request failed."}")) } }
        }
    }

    fun coachExplainPosition() {
        val s = _ui.value
        askCoach("What are my ideas in this position?") {
            api.explainPosition(config(), chess.fen, toPgn(s.sanHistory), engineNote())
        }
    }

    fun coachExplainLastMove() {
        val s = _ui.value
        val san = s.lastMoveSan
        val fenBefore = s.fenBeforeLast
        if (san == null || fenBefore == null) {
            _ui.update { it.copy(coachChat = it.coachChat + ChatMessage(false, "Make a move first, then I'll explain it.")) }
            return
        }
        askCoach("Explain my last move ($san).") {
            api.explainLastMove(config(), fenBefore, san, chess.fen, engineNote())
        }
    }

    fun coachReviewGame() {
        val s = _ui.value
        if (s.sanHistory.size < 4) {
            _ui.update { it.copy(coachChat = it.coachChat + ChatMessage(false, "Play at least a few moves and I'll review the game.")) }
            return
        }
        val pgn = toPgn(s.sanHistory)
        val result = if (s.gameOver) (s.resultLabel ?: "") else "Game in progress (${s.sanHistory.size} plies)"
        askCoach("Review this game.") {
            api.reviewGame(config(), pgn, result, engineNote())
        }
    }

    fun clearCoachChat() {
        _ui.update {
            it.copy(coachChat = listOf(ChatMessage(false, "Chat cleared. Ask me anything about your games.")))
        }
    }

    // ---------------------------------------------------------------------
    // Engine review
    // ---------------------------------------------------------------------

    fun startReview(sans: List<String>, title: String) {
        if (_ui.value.reviewState?.busy == true) return
        _ui.update { it.copy(reviewState = ReviewUiState(busy = true, title = title)) }
        viewModelScope.launch(Dispatchers.Default) {
            val report = runCatching {
                GameReview.analyze(sans, onProgress = { done, total ->
                    _ui.update { it.copy(reviewState = it.reviewState?.copy(completed = done, total = total)) }
                })
            }.getOrNull()
            _ui.update { it.copy(reviewState = it.reviewState?.copy(busy = false, report = report)) }
        }
    }

    fun closeReview() {
        _ui.update { it.copy(reviewState = null) }
    }
}
