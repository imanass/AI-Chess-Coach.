package com.example.aichesscoach.engine

import android.content.Context
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One engine query result. Scores are always reported from the perspective
 * of the side to move (negamax convention), matching BuiltinEngine.
 */
data class EngineAnalysis(
    val bestMoveUci: String?,
    val scoreCpStm: Int,
    val mateInStm: Int?,
    val depth: Int,
    val pvUci: List<String>
) {
    /** Score mapped onto the app's mate-ceiling scale used by review and puzzles. */
    fun scoreStm(mateCeiling: Int): Int = when {
        (mateInStm ?: 0) > 0 -> mateCeiling - 1
        (mateInStm ?: 0) < 0 -> -(mateCeiling - 1)
        else -> scoreCpStm
    }
}

/**
 * Runs the official Stockfish 19 binary (GPL-3.0, © the Stockfish developers)
 * as a subprocess over the UCI protocol. The binary ships inside the APK as
 * jniLibs/arm64-v8a/libstockfish.so and is executed from the read-only
 * native library directory, which Android permits for app-private executables.
 *
 * Every query is serialized through a mutex and guarded by timeouts. If the
 * engine cannot start or dies mid-query (unsupported ABI, strict FEN
 * validation), all methods return null and callers must fall back to
 * [BuiltinEngine].
 */
object StockfishEngine {

    private const val POISON = "\u0000engine-stopped"
    private const val QUERY_TIMEOUT_MS = 30_000

    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var lines: LinkedBlockingQueue<String>? = null
    private val mutex = Mutex()

    /** The packaged engine executable, when present and runnable on this ABI. */
    fun binaryFile(context: Context): File? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        val file = File(dir, "libstockfish.so")
        return if (file.exists() && file.canExecute()) file else null
    }

    fun isAvailable(context: Context): Boolean = binaryFile(context) != null

    private fun ensureStarted(context: Context): Boolean {
        if (process != null && writer != null && lines != null) return true
        shutdown()
        val binary = binaryFile(context) ?: return false
        return try {
            val builder = ProcessBuilder(binary.absolutePath)
            builder.redirectErrorStream(true)
            val p = builder.start()
            val queue = LinkedBlockingQueue<String>()
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(p.inputStream))
                    while (true) {
                        val line = reader.readLine() ?: break
                        queue.put(line)
                    }
                } catch (_: Exception) {
                }
                queue.offer(POISON)
            }.apply { isDaemon = true }.start()

            val out = BufferedWriter(OutputStreamWriter(p.outputStream))
            out.write("uci"); out.newLine(); out.flush()
            if (awaitLine(queue, "uciok", 20_000) == null) { shutdown(); return false }
            out.write("setoption name Threads value 2"); out.newLine()
            out.write("setoption name Hash value 128"); out.newLine()
            out.write("isready"); out.newLine(); out.flush()
            if (awaitLine(queue, "readyok", 10_000) == null) { shutdown(); return false }
            process = p
            writer = out
            lines = queue
            true
        } catch (_: Exception) {
            shutdown()
            false
        }
    }

    /** Waits for a line containing [marker]; returns the line or null on timeout/death. */
    private fun awaitLine(queue: LinkedBlockingQueue<String>, marker: String, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val line = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (line == POISON) return null
            if (line.contains(marker)) return line
        }
        return null
    }

    /**
     * Runs one search. [limitElo] enables UCI_LimitStrength (1320-3190) for the
     * computer opponent; [skillLevel] (0-20) adds blunder noise on top.
     */
    suspend fun analyse(
        context: Context,
        fen: String,
        depth: Int = 12,
        movetimeMs: Long? = null,
        limitElo: Int? = null,
        skillLevel: Int? = null
    ): EngineAnalysis? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!ensureStarted(context)) return@runCatching null
                val out = writer ?: return@runCatching null
                val queue = lines ?: return@runCatching null

                if (limitElo != null) {
                    out.write("setoption name UCI_LimitStrength value true"); out.newLine()
                    out.write("setoption name UCI_Elo value ${limitElo.coerceIn(1320, 3190)}"); out.newLine()
                } else {
                    out.write("setoption name UCI_LimitStrength value false"); out.newLine()
                }
                if (skillLevel != null) {
                    out.write("setoption name Skill Level value ${skillLevel.coerceIn(0, 20)}"); out.newLine()
                }
                out.write("position fen $fen"); out.newLine()
                out.write(if (movetimeMs != null) "go movetime $movetimeMs" else "go depth $depth")
                out.newLine()
                out.flush()

                val deadline = System.currentTimeMillis() + QUERY_TIMEOUT_MS
                var best: String? = null
                var scoreCp = 0
                var mate: Int? = null
                var reachedDepth = depth
                var pv: List<String> = emptyList()

                while (System.currentTimeMillis() < deadline) {
                    val line = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    when {
                        line == POISON -> { shutdown(); return@runCatching null }
                        line.startsWith("bestmove") -> { best = line.split(" ").getOrNull(1); break }
                        line.startsWith("info") && line.contains(" pv ") -> {
                            parseInfo(line)?.let { parsed ->
                                reachedDepth = parsed.depth
                                scoreCp = parsed.scoreCp
                                mate = parsed.mateIn
                                pv = parsed.pv
                            }
                        }
                    }
                }
                if (best == null) { shutdown(); return@runCatching null }
                EngineAnalysis(best, scoreCp, mate, reachedDepth, pv)
            }.getOrNull()
        }
    }

    private data class InfoLine(val depth: Int, val scoreCp: Int, val mateIn: Int?, val pv: List<String>)

    private fun parseInfo(line: String): InfoLine? {
        val tokens = line.split(" ")
        var depth = 0
        var cp = 0
        var mate: Int? = null
        var pv: List<String> = emptyList()
        var i = 1
        while (i < tokens.size) {
            when (tokens[i]) {
                "depth" -> depth = tokens.getOrNull(i + 1)?.toIntOrNull() ?: 0
                "score" -> when (tokens.getOrNull(i + 1)) {
                    "cp" -> { cp = tokens.getOrNull(i + 2)?.toIntOrNull() ?: 0; mate = null }
                    "mate" -> { mate = tokens.getOrNull(i + 2)?.toIntOrNull() }
                }
                "pv" -> { pv = tokens.drop(i + 1); i = tokens.size }
            }
            i++
        }
        return if (pv.isNotEmpty()) InfoLine(depth, cp, mate, pv) else null
    }

    /** Converts a UCI move (e2e4 / e7e8q) into a legal chesslib [Move] for [board]. */
    fun uciToMove(board: Board, uci: String?): Move? {
        if (uci.isNullOrBlank() || uci.length < 4) return null
        val from = runCatching { Square.valueOf(uci.substring(0, 2).uppercase()) }.getOrNull() ?: return null
        val to = runCatching { Square.valueOf(uci.substring(2, 4).uppercase()) }.getOrNull() ?: return null
        val prefix = if (board.sideToMove == Side.WHITE) "WHITE" else "BLACK"
        val promotion = if (uci.length >= 5) Piece.valueOf(prefix + "_" + when (uci[4]) {
            'q' -> "QUEEN"; 'r' -> "ROOK"; 'b' -> "BISHOP"; else -> "KNIGHT"
        }) else Piece.NONE
        val legal = board.legalMoves()
        return legal.firstOrNull { it.from == from && it.to == to && it.promotion == promotion }
            ?: legal.firstOrNull { it.from == from && it.to == to }
    }

    /** Renders an engine PV as SAN, e.g. "Nf3 d5 d4 Bg4". */
    fun pvToSan(fen: String, pvUci: List<String>, maxMoves: Int = 6): String {
        if (pvUci.isEmpty()) return ""
        val board = Board()
        runCatching { board.loadFromFen(fen) }.getOrNull() ?: return ""
        val sans = ArrayList<String>(maxMoves)
        for (uci in pvUci.take(maxMoves)) {
            val move = uciToMove(board, uci) ?: break
            runCatching { sans.add(com.example.aichesscoach.chess.ChessUtils.sanFor(board, move)) }
            if (board.doMove(move) == false) break
        }
        return sans.joinToString(" ")
    }

    private fun shutdown() {
        runCatching { process?.destroy() }
        process = null
        writer = null
        lines = null
    }
}
