package com.example.aichesscoach.puzzles

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Puzzle(
    val id: String,
    val type: String,
    val fen: String,
    val theme: String,
    val rating: Int,
    val hint: String,
    val solution: String
) {
    companion object {
        const val TYPE_MATE1 = "mate1"
        const val TYPE_MATE2 = "mate2"
        const val TYPE_MATERIAL = "material"
    }
}

@Serializable
data class PuzzleBank(val puzzles: List<Puzzle>)

@Serializable
data class Task(val from: String, val to: String)

@Serializable
data class Exercise(
    val type: String,
    val fen: String,
    val instruction: String,
    val tasks: List<Task> = emptyList(),
    val replies: List<Task> = emptyList(),
    val alternatives: List<Task> = emptyList(),
    val hint: String = ""
) {
    companion object {
        const val TYPE_MOVE = "move"
        const val TYPE_MATE1 = "mate1"
        const val TYPE_PUZZLE = "puzzle"
    }
}

@Serializable
data class Lesson(val id: String, val title: String, val explanation: String, val exercises: List<Exercise>)

@Serializable
data class Chapter(val id: String, val title: String, val blurb: String, val lessons: List<Lesson>)

@Serializable
data class LessonBank(val chapters: List<Chapter>)

/** Loads the embedded puzzle and lesson banks. JSON parsing is exposed for JVM unit tests. */
object TrainingBank {

    private val json = Json { ignoreUnknownKeys = true }

    fun parsePuzzles(text: String): List<Puzzle> = json.decodeFromString<PuzzleBank>(text).puzzles

    fun parseLessons(text: String): List<Chapter> = json.decodeFromString<LessonBank>(text).chapters

    fun loadPuzzles(context: Context): List<Puzzle> =
        runCatching {
            parsePuzzles(context.assets.open("puzzles.json").bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())

    fun loadLessons(context: Context): List<Chapter> =
        runCatching {
            parseLessons(context.assets.open("lessons.json").bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())
}
