package com.example.aichesscoach.review

/**
 * A compact offline openings book used by game review to tag early moves as
 * "Book". Lines are stored as SAN move sequences without check/mate suffixes,
 * matching the notation produced by ChessUtils.sanFor. A move is a book move
 * when the sequence played so far extends one of these lines exactly.
 */
object Openings {

    private val lines: List<Pair<String, String>> = listOf(
        "King's Pawn Game" to "e4",
        "King's Pawn Game" to "e4 e5",
        "King's Knight Opening" to "e4 e5 Nf3",
        "Ruy López" to "e4 e5 Nf3 Nc6 Bb5",
        "Ruy López, Morphy Defence" to "e4 e5 Nf3 Nc6 Bb5 a6",
        "Ruy López, Berlin Defence" to "e4 e5 Nf3 Nc6 Bb5 Nf6",
        "Ruy López, Exchange" to "e4 e5 Nf3 Nc6 Bb5 a6 Bxc6",
        "Italian Game" to "e4 e5 Nf3 Nc6 Bc4",
        "Italian Game, Giuoco Piano" to "e4 e5 Nf3 Nc6 Bc4 Bc5",
        "Italian Game, Two Knights Defence" to "e4 e5 Nf3 Nc6 Bc4 Nf6",
        "Italian Game, Evans Gambit" to "e4 e5 Nf3 Nc6 Bc4 Bc5 b4",
        "Scotch Game" to "e4 e5 Nf3 Nc6 d4",
        "Scotch Gambit" to "e4 e5 Nf3 Nc6 d4 exd4 Bc4",
        "Four Knights Game" to "e4 e5 Nf3 Nc6 Nc3 Nf6",
        "Centre Game" to "e4 e5 d4",
        "Danish Gambit" to "e4 e5 d4 exd4 c3",
        "King's Gambit" to "e4 e5 f4",
        "King's Gambit Accepted" to "e4 e5 f4 exf4",
        "Bishop's Opening" to "e4 e5 Bc4",
        "Vienna Game" to "e4 e5 Nc3",
        "Vienna Gambit" to "e4 e5 Nc3 Nf6 f4",
        "Petrov's Defence" to "e4 e5 Nf3 Nf6",
        "Philidor Defence" to "e4 e5 Nf3 d6",
        "Légal Trap line" to "e4 e5 Nf3 d6 Bc4 Bg4",
        "Latvian Gambit" to "e4 e5 Nf3 f5",
        "Sicilian Defence" to "e4 c5",
        "Sicilian, Najdorf" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 a6",
        "Sicilian, Dragon" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 g6",
        "Sicilian, Classical" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 Nc6",
        "Sicilian, Richter-Rauzer" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6 Nc3 Nc6 Bg5",
        "Sicilian, Open" to "e4 c5 Nf3 d6 d4 cxd4 Nxd4 Nf6",
        "Sicilian, Taimanov" to "e4 c5 Nf3 e6 d4 cxd4 Nxd4 Nc6",
        "Sicilian, Kan" to "e4 c5 Nf3 e6 d4 cxd4 Nxd4 a6",
        "Sicilian, Accelerated Dragon" to "e4 c5 Nf3 g6 d4 cxd4 Nxd4 Nc6",
        "Sicilian, Smith-Morra Gambit" to "e4 c5 d4",
        "Sicilian, Alapin Variation" to "e4 c5 c3",
        "French Defence" to "e4 e6 d4 d5",
        "French, Winawer" to "e4 e6 d4 d5 Nc3 Bb4",
        "French, Classical" to "e4 e6 d4 d5 Nc3 Nf6",
        "French, Advance" to "e4 e6 d4 d5 e5",
        "French, Tarrasch" to "e4 e6 d4 d5 Nd2",
        "Caro-Kann Defence" to "e4 c6 d4 d5",
        "Caro-Kann, Advance" to "e4 c6 d4 d5 e5",
        "Caro-Kann, Classical" to "e4 c6 d4 d5 Nc3 dxe4 Nxe4 Bf5",
        "Scandinavian Defence" to "e4 d5",
        "Scandinavian, Modern" to "e4 d5 exd5 Qxd5",
        "Pirc Defence" to "e4 d6 d4 Nf6 Nc3 g6",
        "Modern Defence" to "e4 g6",
        "Alekhine's Defence" to "e4 Nf6",
        "Nimzowitsch Defence" to "e4 Nc6",
        "Owen's Defence" to "e4 b6",
        "Queen's Pawn Game" to "d4",
        "Queen's Gambit" to "d4 d5 c4",
        "Queen's Gambit Declined" to "d4 d5 c4 e6",
        "Queen's Gambit Declined, Exchange" to "d4 d5 c4 e6 cxd5 exd5",
        "Queen's Gambit Accepted" to "d4 d5 c4 dxc4",
        "Slav Defence" to "d4 d5 c4 c6",
        "Semi-Slav Defence" to "d4 d5 c4 e6 Nc3 c6",
        "King's Indian Defence" to "d4 Nf6 c4 g6 Nc3 Bg7",
        "King's Indian, Classical" to "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6",
        "King's Indian, Orthodox" to "d4 Nf6 c4 g6 Nc3 Bg7 e4 d6 Nf3 O-O",
        "Nimzo-Indian Defence" to "d4 Nf6 c4 e6 Nc3 Bb4",
        "Nimzo-Indian, Rubinstein" to "d4 Nf6 c4 e6 Nc3 Bb4 e3",
        "Queen's Indian Defence" to "d4 Nf6 c4 e6 Nf3 b6",
        "Bogo-Indian Defence" to "d4 Nf6 c4 e6 Nf3 Bb4+",
        "Grünfeld Defence" to "d4 Nf6 c4 g6 Nc3 d5",
        "Grünfeld, Exchange" to "d4 Nf6 c4 g6 Nc3 d5 cxd5 Nxd5 e4 Nxc3 bxc3",
        "Old Indian Defence" to "d4 Nf6 c4 d6",
        "Benoni Defence" to "d4 Nf6 c4 c5",
        "Modern Benoni" to "d4 Nf6 c4 c5 d5 e6",
        "Catalan Opening" to "d4 Nf6 c4 e6 g3",
        "Dutch Defence" to "d4 f5",
        "London System" to "d4 d5 Nf3 Nf6 Bf4",
        "London System" to "d4 Nf6 Nf3 e6 Bf4",
        "Colle System" to "d4 d5 Nf3 Nf6 e3",
        "Trompowsky Attack" to "d4 Nf6 Bf5",
        "Richter-Veresov Attack" to "d4 Nf6 Nc3 d5 Bg5",
        "English Opening" to "c4",
        "English, Symmetrical" to "c4 c5",
        "English, Reversed Sicilian" to "c4 e5",
        "English, Anglo-Indian" to "c4 Nf6",
        "Réti Opening" to "Nf3",
        "Réti Opening" to "Nf3 d5 c4",
        "King's Indian Attack" to "Nf3 d5 g3",
        "Bird's Opening" to "f4",
        "Grob Attack" to "g4",
        "Sokolsky Opening" to "b4",
        "Van Geet Opening" to "Nc3"
    )

    private val distinct: List<String> = lines.distinctBy { it.second }.map { it.second }

    /** True when the SAN sequence played so far is an exact prefix of a known opening line. */
    fun isBookMove(sans: List<String>): Boolean {
        if (sans.isEmpty()) return false
        val prefix = sans.joinToString(" ")
        return distinct.any { text -> text == prefix || text.startsWith("$prefix ") }
    }

    /** The name of the longest known opening that matches the whole sequence, if any. */
    fun nameFor(sans: List<String>): String? {
        val text = sans.joinToString(" ")
        return lines.filter { it.second == text }
            .maxByOrNull { it.second.length }
            ?.first
    }
}
