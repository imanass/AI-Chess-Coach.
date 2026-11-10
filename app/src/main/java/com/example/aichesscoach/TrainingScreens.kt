package com.example.aichesscoach

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aichesscoach.review.GameReview

// ---------------------------------------------------------------------------
// Learn
// ---------------------------------------------------------------------------

@Composable
fun LearnScreen(vm: LearnViewModel) {
    val state by vm.ui.collectAsState()

    if (state.activeLesson == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Learn chess", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Work through the chapters in order. Every exercise is interactive - you make the moves.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
            state.chapters.forEachIndexed { index, chapter ->
                val unlocked = vm.isChapterUnlocked(chapter, index)
                val done = chapter.lessons.count { it.id in state.completedLessons }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (unlocked) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${index + 1}. ${chapter.title}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.weight(1f))
                            if (!unlocked) Icon(Icons.Default.Lock, "Locked", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(chapter.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "$done / ${chapter.lessons.size} lessons done",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        if (unlocked) {
                            chapter.lessons.forEach { lesson ->
                                val lessonDone = lesson.id in state.completedLessons
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.startLesson(lesson) }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Icon(
                                        if (lessonDone) Icons.Default.Check else Icons.Default.Refresh,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = if (lessonDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(lesson.title, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${lesson.exercises.size} drills",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    } else {
        LessonRunner(vm)
    }
}

@Composable
fun LessonRunner(vm: LearnViewModel) {
    val state by vm.ui.collectAsState()
    val exercise = state.currentExercise ?: return

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.exitLesson() }) { Icon(Icons.Default.Close, "Exit lesson") }
            Text(state.activeLesson?.title ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "Drill ${state.exerciseIndex + 1} / ${state.activeLesson?.exercises?.size ?: 0}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(exercise.instruction, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        if (state.showHint && exercise.hint.isNotBlank()) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Hint: ${exercise.hint}",
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        ChessBoard(
            board = state.board,
            selected = state.selected,
            targets = state.legalTargets,
            lastMoveFrom = null,
            lastMoveTo = null,
            checkedIndex = null,
            flip = false,
            enabled = state.phase == "exercise" && state.pendingPromotion == null,
            onTap = { vm.tapSquare(it) }
        )

        Spacer(Modifier.height(10.dp))
        Text(state.message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        when (state.phase) {
            "lesson_complete" -> {
                Button(onClick = { vm.exitLesson() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to chapters")
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.showHint() }, enabled = !state.showHint) { Text("Hint") }
                }
            }
        }
    }

    if (state.pendingPromotion != null) {
        PromotionDialog(
            onPick = { vm.choosePromotion(it) },
            onDismiss = { vm.dismissPromotion() }
        )
    }
}

// ---------------------------------------------------------------------------
// Puzzles
// ---------------------------------------------------------------------------

@Composable
fun PuzzleScreen(vm: PuzzleViewModel) {
    val state by vm.ui.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Puzzles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    "Rating ${state.stats.rating}  ·  streak ${state.stats.streak}",
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Text(
            "${state.stats.solved} solved  ·  ${state.stats.failed} failed  ·  best streak ${state.stats.bestStreak}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )

        if (!state.loaded) {
            Text(state.message, modifier = Modifier.padding(top = 20.dp))
            return@Column
        }

        val puzzle = state.puzzle
        Text(
            "Theme: ${puzzle?.theme ?: ""}  ·  difficulty ~${puzzle?.rating ?: 0}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        if (state.showHint && puzzle?.hint?.isNotBlank() == true && state.phase == "solving") {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Text(
                    "Hint: ${puzzle.hint}",
                    Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        ChessBoard(
            board = state.board,
            selected = state.selected,
            targets = state.legalTargets,
            lastMoveFrom = state.lastMoveFrom,
            lastMoveTo = state.lastMoveTo,
            checkedIndex = null,
            flip = false,
            enabled = state.phase == "solving" && state.pendingPromotion == null,
            onTap = { vm.tapSquare(it) }
        )

        Spacer(Modifier.height(10.dp))
        Text(state.message, style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.phase == "solving") {
                OutlinedButton(onClick = { vm.showHint() }, enabled = !state.showHint) { Text("Hint") }
            }
            Button(onClick = { vm.nextPuzzle() }) {
                Text(if (state.phase == "solving") "Skip" else "Next puzzle")
            }
        }
    }

    if (state.pendingPromotion != null) {
        PromotionDialog(
            onPick = { vm.choosePromotion(it) },
            onDismiss = { vm.dismissPromotion() }
        )
    }
}

@Composable
fun PromotionDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Promote to…") },
        text = {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                listOf("Q" to "♛", "R" to "♜", "B" to "♝", "N" to "♞").forEach { (letter, glyph) ->
                    TextButton(onClick = { onPick(letter) }) { Text(glyph, fontSize = 34.sp) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------------------
// Engine review overlay
// ---------------------------------------------------------------------------

private val qualityColors = mapOf(
    "Brilliant" to Color(0xFF26C2A3),
    "Best" to Color(0xFF95BB4A),
    "Good" to Color(0xFF96AF8B),
    "Inaccuracy" to Color(0xFFF7C255),
    "Mistake" to Color(0xFFE58F2A),
    "Blunder" to Color(0xFFE54040)
)

@Composable
fun ReviewOverlay(state: ReviewUiState, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close review") }
                Column {
                    Text("Game review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(state.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (state.busy) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "The engine is thinking about every move… ${state.completed}/${state.total}",
                    style = MaterialTheme.typography.bodyMedium
                )
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.completed.toFloat() / state.total else 0.2f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                return@Column
            }

            val report = state.report
            if (report == null) {
                Text("Review could not be computed for this game.", modifier = Modifier.padding(top = 16.dp))
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                StatCard("White accuracy", "${report.whiteAccuracy}%", Modifier.weight(1f))
                StatCard("Black accuracy", "${report.blackAccuracy}%", Modifier.weight(1f))
            }
            Text(
                "Blunders: ${report.blunders.size}  ·  Mistakes: ${report.mistakes.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                report.moves.forEach { move ->
                    val color = qualityColors[move.quality.label] ?: MaterialTheme.colorScheme.onSurface
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            "${(move.ply + 1) / 2}${if (move.ply % 2 == 1) "." else "…"} ${move.san}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(120.dp)
                        )
                        Surface(shape = CircleShape, color = color.copy(alpha = 0.18f)) {
                            Text(
                                "${move.quality.symbol} ${move.quality.label}",
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = color
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (move.bestSan != null && move.quality != GameReview.Quality.BEST) {
                            Text(
                                "Best: ${move.bestSan}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
