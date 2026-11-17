package com.example.aichesscoach

import com.example.aichesscoach.chess.ChessUtils
import com.example.aichesscoach.data.PlayerProfile
import com.example.aichesscoach.engine.BuiltinEngine
import com.example.aichesscoach.review.GameReview
import com.example.aichesscoach.review.Openings
import com.example.aichesscoach.review.PlayStyle
import com.github.bhlangonijr.chesslib.Board
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameReviewTest {

    // -------------------------------------------------------------------
    // Win-probability model
    // -------------------------------------------------------------------

    @Test
    fun `win percent is 50 for equal position and saturates at mates`() {
        assertEquals(50.0, GameReview.winPercent(0), 0.5)
        assertEquals(100.0, GameReview.winPercent(BuiltinEngine.MATE_OPEN - 1), 0.001)
        assertEquals(0.0, GameReview.winPercent(-(BuiltinEngine.MATE_OPEN - 1)), 0.001)
    }

    @Test
    fun `win percent is monotonic in score`() {
        var previous = 0.0
        for (cp in intArrayOf(-800, -400, -100, 0, 100, 400, 800)) {
            val wp = GameReview.winPercent(cp)
            assertTrue("win% must increase with the score", wp >= previous)
            previous = wp
        }
    }

    @Test
    fun `accuracy stays within bounds and punishes losses`() {
        assertTrue(GameReview.moveAccuracy(0.0) <= 100.0)
        assertTrue(GameReview.moveAccuracy(50.0) < 30.0)
        assertTrue(GameReview.moveAccuracy(0.0) > GameReview.moveAccuracy(10.0))
    }

    // -------------------------------------------------------------------
    // Classification rules
    // -------------------------------------------------------------------

    @Test
    fun `classification follows chess com thresholds`() {
        fun classify(
            lossWp: Double = 0.0,
            isBest: Boolean = false,
            sacrifice: Boolean = false,
            missedMate: Boolean = false,
            winBefore: Double = 50.0,
            winAfter: Double = 50.0,
            inBook: Boolean = false,
            onlyMove: Boolean = false,
            secondGap: Double? = null
        ) = GameReview.classifyMove(
            lossWp, isBest, sacrifice, missedMate, winBefore, winAfter, inBook, onlyMove, secondGap
        )

        assertEquals(GameReview.Quality.BOOK, classify(inBook = true, lossWp = 1.0))
        assertEquals(GameReview.Quality.EXCELLENT, classify(lossWp = 1.0))
        assertEquals(GameReview.Quality.GOOD, classify(lossWp = 4.0))
        assertEquals(GameReview.Quality.INACCURACY, classify(lossWp = 8.0))
        assertEquals(GameReview.Quality.MISTAKE, classify(lossWp = 15.0))
        assertEquals(GameReview.Quality.BLUNDER, classify(lossWp = 45.0))
        assertEquals(GameReview.Quality.BEST, classify(isBest = true))
        assertEquals(GameReview.Quality.BRILLIANT, classify(isBest = true, sacrifice = true))
        assertEquals(
            "Only-move positions are simply best",
            GameReview.Quality.BEST,
            classify(onlyMove = true, lossWp = 40.0)
        )
        assertEquals(
            "A best move with a huge gap over the second-best is Great",
            GameReview.Quality.GREAT,
            classify(isBest = true, secondGap = 45.0)
        )
        assertEquals(
            "Missing a mate while still winning is a Miss, not a blunder",
            GameReview.Quality.MISS,
            classify(missedMate = true, lossWp = 20.0, winAfter = 60.0)
        )
        assertEquals(
            "Throwing away a winning position is a Miss on a big drop",
            GameReview.Quality.MISS,
            classify(winBefore = 90.0, winAfter = 60.0, lossWp = 30.0)
        )
        assertEquals(
            "Collapsing from winning to losing is a Blunder",
            GameReview.Quality.BLUNDER,
            classify(winBefore = 90.0, winAfter = 10.0, lossWp = 80.0)
        )
    }

    // -------------------------------------------------------------------
    // End-to-end review of a short forced game (built-in engine, offline)
    // -------------------------------------------------------------------

    @Test
    fun `fools mate review tags the losing move as a blunder and the mate as best`() {
        val report = runBlocking {
            GameReview.analyze(listOf("f3", "e5", "g4", "Qh4#"), budgetMs = 120, maxDepth = 2)
        }
        assertEquals(4, report.moves.size)

        val g4 = report.moves[2]
        assertEquals("g4", g4.san)
        assertEquals("The move that allows mate in one must be a blunder", GameReview.Quality.BLUNDER, g4.quality)

        val mate = report.moves[3]
        assertTrue(mate.san.startsWith("Qh4"))
        assertTrue(
            "Delivering mate must not be worse than Best",
            mate.quality in setOf(GameReview.Quality.BEST, GameReview.Quality.GREAT, GameReview.Quality.BRILLIANT)
        )

        assertTrue(report.whiteAccuracy in 30..99)
        assertTrue(report.blackAccuracy in 30..99)
        // Black played only perfect moves here; White did not.
        assertTrue("Black accuracy should be at least as high as White's", report.blackAccuracy >= report.whiteAccuracy)
    }

    @Test
    fun `review records mover side and captures`() {
        val report = runBlocking {
            GameReview.analyze(listOf("e4", "e5", "Nf3", "Nc6"), budgetMs = 80, maxDepth = 2)
        }
        assertEquals(4, report.moves.size)
        assertTrue(report.moves[0].moverSide == "White")
        assertTrue(report.moves[1].moverSide == "Black")
        assertTrue(report.moves[0].phase == "Opening")
        // Not a capture in the opening moves.
        assertFalse(report.moves.any { it.isCapture })
    }

    @Test
    fun `builtin engine reports a second best line on rich positions`() {
        val (best, score, secondScore) = BuiltinEngine.analyzePositionWithSecond(
            Board(), budgetMs = 100, maxDepth = 2
        )
        assertNotNull(best)
        assertNotNull("The start position has 20 moves, so a second-best score must exist", secondScore)
        assertTrue(secondScore!! <= score)
    }

    // -------------------------------------------------------------------
    // Openings book
    // -------------------------------------------------------------------

    @Test
    fun `book detection matches opening prefixes only`() {
        assertTrue(Openings.isBookMove(listOf("e4")))
        assertTrue(Openings.isBookMove(listOf("e4", "c5", "Nf3", "d6")))
        assertTrue(Openings.isBookMove(listOf("d4", "Nf6", "c4", "g6", "Nc3", "Bg7")))
        assertFalse("A strange move leaves the book", Openings.isBookMove(listOf("e4", "c5", "Nf3", "d6", "Ke2")))
        assertFalse("Random sequences are not book", Openings.isBookMove(listOf("h4")))
    }

    @Test
    fun `book names resolve for known lines`() {
        assertEquals("Sicilian Defence", Openings.nameFor(listOf("e4", "c5")))
        assertEquals("Nimzo-Indian Defence", Openings.nameFor(listOf("d4", "Nf6", "c4", "e6", "Nc3", "Bb4")))
        assertNull(Openings.nameFor(listOf("h4", "a5")))
    }

    // -------------------------------------------------------------------
    // Annotated PGN parsing (chess.com exports)
    // -------------------------------------------------------------------

    @Test
    fun `annotated pgn strips headers comments variations and nags`() {
        val pgn = """
            [Event "Rated Blitz game"]
            [Result "1-0"]
            [White "player1"]
            [Black "player2"]

            1. e4 {A bold opening [%clk 0:03:00]} 1... c5 $1 2. Nf3 (2. c3 is also fine) 2... d6 3. d4 1-0
        """.trimIndent()
        val sans = ChessUtils.parseAnnotatedPgn(pgn)
        assertEquals(listOf("e4", "c5", "Nf3", "d6", "d4"), sans)
    }

    @Test
    fun `simple pgn parsing still works`() {
        assertEquals(listOf("e4", "e5", "Nf3"), ChessUtils.parsePgnMoves("1. e4 e5 2. Nf3 *"))
    }

    // -------------------------------------------------------------------
    // Play-style profile
    // -------------------------------------------------------------------

    private fun review(
        ply: Int,
        quality: GameReview.Quality,
        moverSide: String,
        phase: String = "Middlegame",
        isCapture: Boolean = false,
        refutedByCapture: Boolean = false,
        lossWp: Double = 0.0
    ) = GameReview.MoveReview(
        ply = ply, san = "move", quality = quality, bestSan = null, lossCp = 0,
        evalWhiteCp = 0, lossWp = lossWp, isCapture = isCapture,
        moverSide = moverSide, phase = phase, refutedByCapture = refutedByCapture
    )

    private fun report(vararg moves: GameReview.MoveReview) = GameReview.Report(
        moves = moves.toList(),
        whiteAccuracy = 80,
        blackAccuracy = 70
    )

    @Test
    fun `extract counts only the player's side`() {
        val stats = PlayStyle.extract(
            report(
                review(1, GameReview.Quality.BEST, "White"),
                review(2, GameReview.Quality.BLUNDER, "Black", refutedByCapture = true, lossWp = 40.0),
                review(3, GameReview.Quality.MISTAKE, "White", phase = "Endgame", lossWp = 25.0),
                review(4, GameReview.Quality.MISS, "Black")
            ),
            "White"
        )
        assertEquals(2, stats.plies)
        assertEquals(1, stats.counts[GameReview.Quality.MISTAKE])
        assertEquals(0, stats.counts[GameReview.Quality.BLUNDER])
        assertNull("Black-side themes must not leak into the White profile", stats.themes["blunders"])
        assertNull(stats.themes["hanging"])
    }

    @Test
    fun `extract detects hanging pieces and misses`() {
        val stats = PlayStyle.extract(
            report(
                review(1, GameReview.Quality.BLUNDER, "White", refutedByCapture = true, lossWp = 50.0),
                review(2, GameReview.Quality.MISTAKE, "White", refutedByCapture = true, lossWp = 22.0),
                review(3, GameReview.Quality.MISS, "White")
            ),
            "White"
        )
        assertEquals(2, stats.themes["hanging"])
        assertEquals(1, stats.themes["missed_tactics"])
        assertEquals(1, stats.themes["blunders"])
    }

    @Test
    fun `merge accumulates across games and describes the style`() {
        val stats = PlayStyle.extract(
            report(
                review(1, GameReview.Quality.BLUNDER, "White", refutedByCapture = true, lossWp = 45.0),
                review(2, GameReview.Quality.BLUNDER, "White", refutedByCapture = true, lossWp = 30.0),
                review(3, GameReview.Quality.EXCELLENT, "White", lossWp = 0.5)
            ),
            "White"
        )
        var profile = PlayStyle.mergeProfile(null, stats, 1L)
        profile = PlayStyle.mergeProfile(profile, stats, 2L)

        assertEquals(2, profile.gamesReviewed)
        assertEquals(4, profile.plyCount)
        assertEquals(2, profile.blunder)
        assertEquals(2, PlayStyle.decodeThemes(profile.themes)["hanging"])

        val advice = PlayStyle.describe(profile)
        assertEquals("Sharp but loose", advice.styleLabel)
        assertTrue(advice.focus.isNotEmpty())
        assertTrue(advice.focus.any { it.title == "Leaving pieces undefended" })
    }

    @Test
    fun `empty profile builds a friendly placeholder`() {
        val advice = PlayStyle.describe(PlayerProfile(id = 1))
        assertEquals("Building your profile", advice.styleLabel)
    }
}
