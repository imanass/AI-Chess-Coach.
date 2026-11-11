package com.example.aichesscoach

import com.example.aichesscoach.chess.ChessUtils
import com.example.aichesscoach.engine.BuiltinEngine
import com.example.aichesscoach.puzzles.Puzzle
import com.example.aichesscoach.puzzles.TrainingBank
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import org.junit.Assert.assertTrueimport org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Validates the embedded training content with the real engine on the JVM.
 * If any puzzle is broken (no solution exists / wrong type) or any lesson
 * FEN is illegal, this test fails the CI build so it can never reach users.
 */
class PuzzleValidationTest {

    private fun assetText(name: String): String {
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
            File("../app/src/main/assets/$name")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $name; looked in ${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    private fun board(fen: String): Board {
        val board = Board()
        board.loadFromFen(fen)
        return board
    }

    private fun allPuzzles(): List<Puzzle> = TrainingBank.parsePuzzles(assetText("puzzles.json"))

    @Test
    fun puzzleBankIsNotEmpty() {
        assertTrue("Puzzle bank must not be empty", allPuzzles().size >= 10)
    }

    @Test
    fun everyPuzzleIsSolvable() {
        val failures = mutableListOf<String>()
        for (puzzle in allPuzzles()) {
            try {
                val b = board(puzzle.fen)
                when (puzzle.type) {
                    Puzzle.TYPE_MATE1 -> {
                        if (!BuiltinEngine.hasMatingMove(b)) {
                            failures.add("${puzzle.id}: no mating move exists in '${puzzle.fen}'")
                        }
                    }
                    Puzzle.TYPE_MATE2 -> {
                        if (!BuiltinEngine.hasForcedMate(b, budgetMs = 4000, maxDepth = 3)) {
                            failures.add("${puzzle.id}: no forced mate found in '${puzzle.fen}'")
                        }
                    }
                    Puzzle.TYPE_MATERIAL -> {
                        val winning = b.legalMoves().any { move ->
                            b.doMove(move)
                            // Side to move is the opponent; negate for the solver's perspective.
                            val moverScore = -BuiltinEngine.analyzePosition(b, budgetMs = 500, maxDepth = 4).second
                            b.undoMove()
                            moverScore >= 250
                        }
                        if (!winning) {
                            failures.add("${puzzle.id}: no winning move found in '${puzzle.fen}'")
                        }
                    }
                    else -> failures.add("${puzzle.id}: unknown type '${puzzle.type}'")
                }
            } catch (e: Exception) {
                failures.add("${puzzle.id}: exception ${e.message}")
            }
        }
        if (failures.isNotEmpty()) fail("Broken puzzles:\n" + failures.joinToString("\n"))
    }

    @Test
    fun authoredSolutionsActuallyWork() {
        val failures = mutableListOf<String>()
        for (puzzle in allPuzzles()) {
            try {
                val b = board(puzzle.fen)
                val solution = puzzle.solution
                if (solution.length < 4) {
                    failures.add("${puzzle.id}: bad solution '${solution}'")
                    continue
                }
                val from = solution.substring(0, 2).uppercase()
                val to = solution.substring(2, 4).uppercase()
                val promo = if (solution.length >= 5) solution.substring(4, 5).uppercase() else ""
                val promoPiece = when (promo) {
                    "" -> null
                    "Q" -> Piece.WHITE_QUEEN
                    "R" -> Piece.WHITE_ROOK
                    "B" -> Piece.WHITE_BISHOP
                    "N" -> Piece.WHITE_KNIGHT
                    else -> null
                }
                val move = b.legalMoves().firstOrNull {
                    it.from.name == from && it.to.name == to &&
                        (promoPiece == null || it.promotion == promoPiece)
                }
                if (move == null) {
                    failures.add("${puzzle.id}: solution '$solution' is not legal in '${puzzle.fen}'")
                    continue
                }
                b.doMove(move)
                val solved = when (puzzle.type) {
                    Puzzle.TYPE_MATE1 -> b.isMated()
                    Puzzle.TYPE_MATE2 -> b.isMated() || BuiltinEngine.isGettingMated(b, budgetMs = 2000, maxDepth = 3)
                    Puzzle.TYPE_MATERIAL -> {
                        // Side to move is the opponent; negate for the solver's perspective.
                        val moverScore = -BuiltinEngine.analyzePosition(b, budgetMs = 500, maxDepth = 4).second
                        moverScore >= 250
                    }
                    else -> false
                }
                if (!solved) {
                    failures.add("${puzzle.id}: authored solution '$solution' does not solve the puzzle")
                }
            } catch (e: Exception) {
                failures.add("${puzzle.id}: exception ${e.message}")
            }
        }
        if (failures.isNotEmpty()) fail("Authored solutions broken:\n" + failures.joinToString("\n"))
    }

    @Test
    fun lessonExercisesAreValid() {
        val chapters = TrainingBank.parseLessons(assetText("lessons.json"))
        assertTrue("Lesson bank must not be empty", chapters.isNotEmpty())
        val failures = mutableListOf<String>()
        val lessonIds = HashSet<String>()
        for (chapter in chapters) {
            for (lesson in chapter.lessons) {
                if (!lessonIds.add(lesson.id)) failures.add("duplicate lesson id ${lesson.id}")
                if (lesson.exercises.isEmpty()) failures.add("${lesson.id}: no exercises")
                for ((index, exercise) in lesson.exercises.withIndex()) {
                    try {
                        val b = board(exercise.fen)
                        when (exercise.type) {
                            "move" -> {
                                if (exercise.tasks.isEmpty()) failures.add("${lesson.id}#$index: move exercise without tasks")
                                val legalNames = b.legalMoves().map {
                                    ChessUtils.indexName(ChessUtils.squareIndex(it.from)) +
                                        ChessUtils.indexName(ChessUtils.squareIndex(it.to))
                                }.toSet()
                                val required = (exercise.tasks + exercise.alternatives).map { it.from + it.to }
                                if (exercise.tasks.isNotEmpty() && exercise.alternatives.isEmpty()) {
                                    val first = required.first()
                                    if (first !in legalNames) {
                                        failures.add("${lesson.id}#$index: first task $first not legal in '${exercise.fen}'")
                                    }
                                }
                            }
                            "mate1" -> {
                                if (!BuiltinEngine.hasMatingMove(b)) {
                                    failures.add("${lesson.id}#$index: mate1 exercise without mating move '${exercise.fen}'")
                                }
                            }
                            "puzzle" -> {
                                val winning = b.legalMoves().any { move ->
                                    b.doMove(move)
                                    // Side to move is the opponent; negate for the solver's perspective.
                                    val moverScore = -BuiltinEngine.analyzePosition(b, budgetMs = 500, maxDepth = 4).second
                                    b.undoMove()
                                    moverScore >= 250
                                }
                                if (!winning) failures.add("${lesson.id}#$index: puzzle exercise has no winning move '${exercise.fen}'")
                            }
                            else -> failures.add("${lesson.id}#$index: unknown exercise type '${exercise.type}'")
                        }
                    } catch (e: Exception) {
                        failures.add("${lesson.id}#$index: exception ${e.message}")
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail("Broken lesson exercises:\n" + failures.joinToString("\n"))
    }
}
