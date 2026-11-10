package com.example.aichesscoach.chess

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlin.math.abs

/** Shared helpers for board rendering, square indexing and SAN notation. */
object ChessUtils {

    /** Board square index (0 = a8, 7 = h8, 56 = a1, 63 = h1) for a chesslib square. */
    fun squareIndex(square: Square): Int {
        val name = square.name
        val file = name[0] - 'A'
        val rank = name[1].digitToInt()
        return (8 - rank) * 8 + file
    }

    /** UCI-style square name like "e4" from a board index. */
    fun indexName(index: Int): String {
        val file = 'a' + (index % 8)
        val rank = 8 - (index / 8)
        return "$file$rank"
    }

    /** Renders the board as 64 codes: "" for empty, "wK"/"bP"-style otherwise. */
    fun renderBoard(board: Board): List<String> = (0 until 64).map { index ->
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

    /** Standard algebraic notation for a move in the given position, without check suffixes. */
    fun sanFor(board: Board, move: Move): String {
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

    /** SAN with the +/# suffix based on the position after the move has been applied. */
    fun sanWithSuffix(sanBase: String, boardAfter: Board): String {
        val inCheck = boardAfter.isKingAttacked()
        return sanBase + if (inCheck) (if (boardAfter.isMated()) "#" else "+") else ""
    }

    /** Parses a simple PGN move text ("1. e4 e5 2. Nf3 ...") into a SAN list. */
    fun parsePgnMoves(pgn: String): List<String> =
        pgn.replace(Regex("\\d+\\.(\\s*)"), " ")
            .replace(Regex("1-0|0-1|1/2-1/2|\\*"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    /** Builds PGN move text from a SAN list. */
    fun toPgn(sans: List<String>): String = buildString {
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

    /** Material balance in centipawns from White's perspective for the rendered code list. */
    fun materialWhiteCp(board: Board): Int {
        var total = 0
        for (square in Square.values()) {
            val name = board.getPiece(square).name
            if (name == "NONE") continue
            val value = when (name.substringAfter('_')) {
                "PAWN" -> 100; "KNIGHT" -> 300; "BISHOP" -> 320
                "ROOK" -> 500; "QUEEN" -> 900; else -> 0
            }
            total += if (name.startsWith("WHITE_")) value else -value
        }
        return total
    }

    private fun promotionLetter(piece: Piece): String = when (piece.name.substringAfter('_')) {
        "QUEEN" -> "Q"; "ROOK" -> "R"; "BISHOP" -> "B"; "KNIGHT" -> "N"; else -> ""
    }

    /** White's side constant used by callers that compare sides. */
    val WHITE: Side = Side.WHITE
}
