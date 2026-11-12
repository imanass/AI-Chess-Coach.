package com.example.aichesscoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aichesscoach.data.SavedGame
import com.example.aichesscoach.engine.Difficulty
import com.example.aichesscoach.ui.BoardDark
import com.example.aichesscoach.ui.BoardLight
import com.example.aichesscoach.ui.ChessCoachTheme
import com.example.aichesscoach.ui.PieceBlack
import com.example.aichesscoach.ui.PieceWhite
import com.example.aichesscoach.ui.PieceWhiteOutline
import com.example.aichesscoach.ui.SquareCheck
import com.example.aichesscoach.ui.SquareLastMove
import com.example.aichesscoach.ui.SquareSelected
import com.example.aichesscoach.ui.SquareTarget
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChessCoachTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ChessCoachApp()
                }
            }
        }
    }
}

@Composable
fun ChessCoachApp(vm: ChessViewModel = viewModel(), learnVm: LearnViewModel = viewModel(), puzzleVm: PuzzleViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        "Play" to Icons.Default.SportsEsports,
        "Learn" to Icons.Default.MenuBook,
        "Puzzles" to Icons.Default.Extension,
        "Coach" to Icons.Default.School,
        "Progress" to Icons.Default.Insights
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, null) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> PlayScreen(vm, onGoToCoach = { tab = 3 }, onGoToSettings = { tab = 5 })
                1 -> LearnScreen(learnVm)
                2 -> PuzzleScreen(puzzleVm)
                3 -> CoachScreen(vm)
                4 -> ProgressScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
    GlobalDialogs(vm, onGoToCoach = { tab = 3 })
    vm.ui.collectAsState().value.reviewState?.let { review ->
        ReviewOverlay(review) { vm.closeReview() }
    }
}

// ---------------------------------------------------------------------------
// Play
// ---------------------------------------------------------------------------

@Composable
fun PlayScreen(vm: ChessViewModel, onGoToCoach: () -> Unit, onGoToSettings: () -> Unit) {
    val state by vm.ui.collectAsState()
    val haptic = LocalHapticFeedback.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI Chess Coach", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.engineThinking) {
                Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onGoToSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        if (state.engineThinking) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }

        Spacer(Modifier.height(10.dp))

        // Quick controls
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { vm.newGame() }) { Icon(Icons.Default.Add, "New game") }
            OutlinedIconButton(onClick = { vm.undo() }, enabled = state.sanHistory.isNotEmpty() && !state.engineThinking) {
                Icon(Icons.Default.Undo, "Undo")
            }
            OutlinedIconButton(onClick = { vm.toggleFlipBoard() }) { Icon(Icons.Default.Flip, "Flip board") }
            Spacer(Modifier.weight(1f))
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    state.turnLabel,
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // Mode & difficulty
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.mode == GameMode.VS_COMPUTER,
                onClick = { vm.setMode(GameMode.VS_COMPUTER) },
                label = { Text("vs Computer") }
            )
            FilterChip(
                selected = state.mode == GameMode.TWO_PLAYERS,
                onClick = { vm.setMode(GameMode.TWO_PLAYERS) },
                label = { Text("Two players") }
            )
        }
        if (state.mode == GameMode.VS_COMPUTER) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Difficulty.entries.forEach { difficulty ->
                    FilterChip(
                        selected = state.difficulty == difficulty,
                        onClick = { vm.setDifficulty(difficulty) },
                        label = { Text(difficulty.label) }
                    )
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Play as:", style = MaterialTheme.typography.labelMedium)
                PlayerSide.entries.forEach { side ->
                    FilterChip(
                        selected = state.playerSide == side,
                        onClick = { vm.setPlayerSide(side) },
                        label = { Text(side.label) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        ChessBoard(
            board = state.board,
            selected = state.selected,
            targets = state.legalTargets,
            lastMoveFrom = state.lastMoveFrom,
            lastMoveTo = state.lastMoveTo,
            checkedIndex = state.checkedIndex,
            flip = state.flipBoard,
            enabled = !state.gameOver && !state.engineThinking && state.pendingPromotion == null && state.isPlayersTurn,
            onTap = { index ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                vm.tapSquare(index)
            }
        )

        Spacer(Modifier.height(12.dp))

        // Status + evaluation
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.showEval && state.evalLabel != null && !state.gameOver) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                "Eval ${state.evalLabel}",
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                    Text(
                        if (state.gameOver) "Game over" else "${state.sanHistory.size} plies",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Move list
        if (state.sanHistory.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Moves", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoveList(state.sanHistory)
        }

        Spacer(Modifier.height(10.dp))

        Button(onClick = { vm.coachExplainLastMove() }, modifier = Modifier.fillMaxWidth(), enabled = !state.coachBusy && state.lastMoveSan != null) {
            Icon(Icons.Default.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text("Explain my last move")
        }
        Spacer(Modifier.height(8.dp))
    }
}

private data class MovePair(val number: Int, val white: String, val black: String?)

@Composable
fun MoveList(sanHistory: List<String>) {
    val pairs = remember(sanHistory) {
        sanHistory.chunked(2).mapIndexed { i, chunk -> MovePair(i + 1, chunk[0], chunk.getOrNull(1)) }
    }
    val lastIndex = sanHistory.size - 1
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pairs.forEachIndexed { pairIndex, pair ->
            val isLatest = pairIndex * 2 + 1 == lastIndex || (pair.black == null && pairIndex * 2 == lastIndex)
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isLatest) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = buildString {
                        append("${pair.number}. ").append(pair.white)
                        pair.black?.let { append("  ").append(it) }
                    },
                    Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun ChessBoard(
    board: List<String>,
    selected: Int?,
    targets: Set<Int>,
    lastMoveFrom: Int?,
    lastMoveTo: Int?,
    checkedIndex: Int?,
    flip: Boolean,
    enabled: Boolean,
    onTap: (Int) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val tileSize = maxWidth / 8
        val outlinePx = with(LocalDensity.current) { 1.4.dp.toPx() }
        Column(Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.outline)) {
            for (displayRow in 0..7) {
                Row(Modifier.fillMaxWidth().height(tileSize)) {
                    for (displayCol in 0..7) {
                        val boardIndex = if (flip) (7 - displayRow) * 8 + (7 - displayCol) else displayRow * 8 + displayCol
                        val isLight = (displayRow + displayCol) % 2 == 0
                        val base = if (isLight) BoardLight else BoardDark
                        val isSelected = selected == boardIndex
                        val isLastMove = boardIndex == lastMoveFrom || boardIndex == lastMoveTo
                        val isChecked = checkedIndex == boardIndex
                        val isTarget = boardIndex in targets

                        Box(
                            Modifier
                                .size(tileSize)
                                .background(base)
                                .background(
                                    when {
                                        isChecked -> SquareCheck
                                        isSelected -> SquareSelected
                                        isLastMove -> SquareLastMove
                                        else -> Color.Transparent
                                    }
                                )
                                .then(
                                    if (isTarget) Modifier.border(
                                        width = 3.dp,
                                        color = SquareTarget,
                                        shape = MaterialTheme.shapes.small
                                    ) else Modifier
                                )
                                .clickable(enabled = enabled) { onTap(boardIndex) },
                            contentAlignment = Alignment.Center
                        ) {
                            // Coordinates on the edge tiles
                            if (displayCol == 0) {
                                val rank = 8 - boardIndex / 8
                                Text(
                                    rank.toString(),
                                    Modifier.align(Alignment.TopStart).padding(2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = if (isLight) BoardDark else BoardLight
                                )
                            }
                            if (displayRow == 7) {
                                val fileChar = 'a' + (boardIndex % 8)
                                Text(
                                    fileChar.toString(),
                                    Modifier.align(Alignment.BottomEnd).padding(2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    color = if (isLight) BoardDark else BoardLight
                                )
                            }

                            // Legal-move marker: dot on empty squares (rings come from the border)
                            if (isTarget && board[boardIndex].isBlank()) {
                                Box(Modifier.size(tileSize / 4).background(SquareTarget, CircleShape))
                            }

                            val pieceCode = board[boardIndex]
                            if (pieceCode.isNotBlank()) {
                                val glyph = when (pieceCode.drop(1)) {
                                    "K" -> "♚"; "Q" -> "♛"; "R" -> "♜"; "B" -> "♝"; "N" -> "♞"; else -> "♟"
                                }
                                if (pieceCode.startsWith("w")) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            glyph,
                                            fontSize = (tileSize.value * 0.62f).sp,
                                            style = TextStyle(
                                                color = PieceWhiteOutline,
                                                drawStyle = Stroke(width = outlinePx, join = StrokeJoin.Round)
                                            )
                                        )
                                        Text(
                                            glyph,
                                            fontSize = (tileSize.value * 0.62f).sp,
                                            color = PieceWhite
                                        )
                                    }
                                } else {
                                    Text(glyph, fontSize = (tileSize.value * 0.62f).sp, color = PieceBlack)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Coach
// ---------------------------------------------------------------------------

@Composable
fun CoachScreen(vm: ChessViewModel) {
    val state by vm.ui.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.coachChat.size, state.coachBusy) {
        if (state.coachChat.isNotEmpty()) {
            listState.animateScrollToItem(state.coachChat.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your coach", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.clearCoachChat() }) { Icon(Icons.Default.DeleteSweep, "Clear chat") }
        }
        Text(
            "The coach explains ideas in plain language. It never claims to run Stockfish — the evaluation note comes from the app's built-in analyzer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { vm.coachExplainLastMove() },
                enabled = !state.coachBusy && state.lastMoveSan != null,
                label = { Text("Explain last move") },
                leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) }
            )
            AssistChip(
                onClick = { vm.coachExplainPosition() },
                enabled = !state.coachBusy,
                label = { Text("Ideas in this position") },
                leadingIcon = { Icon(Icons.Default.Lightbulb, null, Modifier.size(18.dp)) }
            )
            AssistChip(
                onClick = { vm.coachReviewGame() },
                enabled = !state.coachBusy && state.sanHistory.size >= 4,
                label = { Text("Review this game") },
                leadingIcon = { Icon(Icons.Default.RateReview, null, Modifier.size(18.dp)) }
            )
        }

        Spacer(Modifier.height(8.dp))

        if (state.coachBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(state.coachChat) { message ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 1.dp,
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Text(
                            message.text,
                            Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------

@Composable
fun ProgressScreen(vm: ChessViewModel) {
    val state by vm.ui.collectAsState()
    val vsComputer = state.savedGames.filter { it.mode == SavedGame.MODE_VS_COMPUTER }
    val wins = vsComputer.count {
        (it.result == "1-0" && it.playerSide == SavedGame.SIDE_WHITE) ||
            (it.result == "0-1" && it.playerSide == SavedGame.SIDE_BLACK)
    }
    val losses = vsComputer.count {
        (it.result == "0-1" && it.playerSide == SavedGame.SIDE_WHITE) ||
            (it.result == "1-0" && it.playerSide == SavedGame.SIDE_BLACK)
    }
    val draws = vsComputer.count { it.result == "1/2-1/2" }
    val winRate = if (vsComputer.isEmpty()) 0f else wins.toFloat() / vsComputer.size
    val focus = when {
        state.games == 0 -> "Play your first game to start a learning profile"
        vsComputer.size < 3 -> "Play more games against the computer to build a record"
        losses > wins * 2 -> "Practise defending worse positions and exchanging actively"
        wins > losses * 2 -> "Strong record — try the Hard difficulty for a bigger challenge"
        else -> "Review games with the coach to spot recurring themes"
    }

    var viewingGame by remember { mutableStateOf<SavedGame?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Learning profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Games played", state.games.toString(), Modifier.weight(1f))
            StatCard("Moves recorded", state.moves.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("vs Computer", "$wins-$losses-$draws", Modifier.weight(1f), caption = "W-L-D")
            StatCard("Win rate", "${(winRate * 100).toInt()}%", Modifier.weight(1f))
        }
        if (vsComputer.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { winRate },
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("Current focus", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(focus, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saved games", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.savedGames.isNotEmpty()) {
                OutlinedButton(onClick = { confirmClear = true }) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear")
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        if (state.savedGames.isEmpty()) {
            Text(
                "Finished games are stored locally on this device. They will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.savedGames.forEach { game ->
                OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { viewingGame = game }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val outcome = when {
                            game.mode == SavedGame.MODE_TWO_PLAYERS -> game.result
                            (game.result == "1-0" && game.playerSide == SavedGame.SIDE_WHITE) ||
                                (game.result == "0-1" && game.playerSide == SavedGame.SIDE_BLACK) -> "Win"
                            game.result == "1/2-1/2" -> "Draw"
                            else -> "Loss"
                        }
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                outcome,
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(game.mode, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(game.playedAt))} · ${game.plyCount} plies",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.deleteGame(game.id) }) { Icon(Icons.Default.Delete, "Delete game") }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    viewingGame?.let { game ->
        AlertDialog(
            onDismissRequest = { viewingGame = null },
            title = { Text("Game ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(game.playedAt))}") },
            text = {
                SelectionContainer {
                    Column {
                        Text("Result: ${game.result} · ${game.mode} · you played ${game.playerSide}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            game.pgn.ifBlank { "(no moves recorded)" },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        vm.startReview(
                            com.example.aichesscoach.chess.ChessUtils.parsePgnMoves(game.pgn),
                            "Saved game"
                        )
                        viewingGame = null
                    }) { Text("Analyze") }
                    TextButton(onClick = { viewingGame = null }) { Text("Close") }
                }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All saved games will be deleted permanently from this device.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            caption?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChessViewModel) {
    val state by vm.ui.collectAsState()
    var provider by remember(state.provider) { mutableStateOf(state.provider) }
    var endpoint by remember(state.endpoint) { mutableStateOf(state.endpoint) }
    var model by remember(state.model) { mutableStateOf(state.model) }
    var key by remember(state.apiKey) { mutableStateOf(state.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        // --- AI provider ---
        Text("AI coach", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ExposedDropdownMenuBox(expanded = providerExpanded, onExpandedChange = { providerExpanded = it }) {
            OutlinedTextField(
                provider, { provider = it }, readOnly = true,
                modifier = Modifier.menuAnchor().fillMaxWidth(),
                label = { Text("Provider") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) }
            )
            ExposedDropdownMenu(providerExpanded, { providerExpanded = false }) {
                listOf("OpenAI", "OpenAI-compatible", "Google Gemini", "Anthropic Claude").forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = {
                        provider = option
                        endpoint = when (option) {
                            "Google Gemini" -> "https://generativelanguage.googleapis.com/v1beta"
                            "Anthropic Claude" -> "https://api.anthropic.com/v1"
                            else -> "https://api.openai.com/v1"
                        }
                        model = when (option) {
                            "Google Gemini" -> "gemini-2.0-flash"
                            "Anthropic Claude" -> "claude-3-5-haiku-latest"
                            else -> "gpt-4.1-mini"
                        }
                        providerExpanded = false
                    })
                }
            }
        }
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("API base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            key, { key = it },
            label = { Text("API key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle key visibility")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { vm.saveApi(provider, endpoint, model, key) }, modifier = Modifier.fillMaxWidth()) {
            Text("Save API settings")
        }
        OutlinedButton(onClick = { vm.testApi() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.busy) "Please wait…" else "Test API connection")
        }
        Text(state.apiStatus, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()

        // --- Engine ---
        Text("Game & engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("The computer opponent, position evaluation and game review run on Stockfish 19 - the world's strongest open-source chess engine - fully on this device (GPL-3.0; © the Stockfish developers). On devices without 64-bit support the app falls back to its built-in lightweight engine.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Show position evaluation")
            Switch(checked = state.showEval, onCheckedChange = { vm.setShowEval(it) })
        }

        HorizontalDivider()

        // --- Privacy ---
        Text("Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Your API key is stored in AndroidX encrypted preferences on this device and requests are sent directly to the provider you choose. " +
                "Games and move lists stay in a local Room database. No analytics, no third-party tracking.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
fun GlobalDialogs(vm: ChessViewModel, onGoToCoach: () -> Unit) {
    val state by vm.ui.collectAsState()

    // Promotion picker
    state.pendingPromotion?.let {
        AlertDialog(
            onDismissRequest = { vm.dismissPromotion() },
            title = { Text("Promote to…") },
            text = {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    listOf("Q" to "♛", "R" to "♜", "B" to "♝", "N" to "♞").forEach { (letter, glyph) ->
                        TextButton(onClick = { vm.choosePromotion(letter) }) {
                            Text(glyph, fontSize = 34.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.dismissPromotion() }) { Text("Cancel") } }
        )
    }

    // Game over
    state.resultLabel?.let { result ->
        AlertDialog(
            onDismissRequest = { vm.dismissGameOver() },
            title = { Text("Game over") },
            text = { Text(result) },
            confirmButton = {
                TextButton(onClick = { vm.newGame() }) { Text("Rematch") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.coachReviewGame(); vm.dismissGameOver(); onGoToCoach() }) { Text("Coach review") }
                    TextButton(onClick = {
                        vm.startReview(state.sanHistory, "Your game")
                        vm.dismissGameOver()
                    }) { Text("Engine review") }
                    TextButton(onClick = { vm.dismissGameOver() }) { Text("Close") }
                }
            }
        )
    }
}
