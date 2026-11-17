package com.example.aichesscoach.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "games")
data class SavedGame(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playedAt: Long,
    val pgn: String,
    val result: String,
    val plyCount: Int,
    val fen: String,
    val mode: String = MODE_TWO_PLAYERS,
    val playerSide: String = SIDE_WHITE
) {
    companion object {
        const val MODE_VS_COMPUTER = "vs Computer"
        const val MODE_TWO_PLAYERS = "Two players"
        const val MODE_CHESS_COM = "Chess.com"
        const val SIDE_WHITE = "White"
        const val SIDE_BLACK = "Black"
    }
}

@Entity(tableName = "lesson_progress")
data class LessonProgress(
    @PrimaryKey val lessonId: String,
    val completedAt: Long
)

@Entity(tableName = "puzzle_stats")
data class PuzzleStats(
    @PrimaryKey val id: Int = 1,
    val rating: Int = 400,
    val solved: Int = 0,
    val failed: Int = 0,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val recent: String = ""
)

@Entity(tableName = "player_profile")
data class PlayerProfile(
    @PrimaryKey val id: Int = 1,
    val updatedAt: Long = 0,
    val gamesReviewed: Int = 0,
    /** Sum of per-move accuracy values; divide by plyCount for the average. */
    val accuracySum: Double = 0.0,
    val plyCount: Int = 0,
    val brilliant: Int = 0,
    val great: Int = 0,
    val best: Int = 0,
    val excellent: Int = 0,
    val good: Int = 0,
    val book: Int = 0,
    val inaccuracy: Int = 0,
    val mistake: Int = 0,
    val blunder: Int = 0,
    val miss: Int = 0,
    val openingLossSum: Double = 0.0,
    val openingPlies: Int = 0,
    val middleLossSum: Double = 0.0,
    val middlePlies: Int = 0,
    val endLossSum: Double = 0.0,
    val endPlies: Int = 0,
    val captures: Int = 0,
    /** Aggregated mistake themes, stored as "themeId:count,themeId:count". */
    val themes: String = ""
)

@Dao
interface GameDao {
    @Insert suspend fun insert(game: SavedGame)
    @Query("SELECT * FROM games ORDER BY playedAt DESC") fun observeAll(): Flow<List<SavedGame>>
    @Query("SELECT COUNT(*) FROM games") fun observeCount(): Flow<Int>
    @Query("SELECT COALESCE(SUM(plyCount), 0) FROM games") fun observeMoves(): Flow<Int>
    @Query("DELETE FROM games WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM games") suspend fun deleteAll()
}

@Dao
interface LessonDao {
    @Query("SELECT * FROM lesson_progress") fun observeAll(): Flow<List<LessonProgress>>
    @Query("SELECT * FROM lesson_progress WHERE lessonId = :id") suspend fun get(id: String): LessonProgress?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(progress: LessonProgress)
    @Query("DELETE FROM lesson_progress") suspend fun deleteAll()
}

@Dao
interface PuzzleDao {
    @Query("SELECT * FROM puzzle_stats WHERE id = 1") fun observe(): Flow<PuzzleStats?>
    @Query("SELECT * FROM puzzle_stats WHERE id = 1") suspend fun get(): PuzzleStats?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(stats: PuzzleStats)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM player_profile WHERE id = 1") fun observe(): Flow<PlayerProfile?>
    @Query("SELECT * FROM player_profile WHERE id = 1") suspend fun get(): PlayerProfile?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(profile: PlayerProfile)
    @Query("DELETE FROM player_profile") suspend fun deleteAll()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE games ADD COLUMN mode TEXT NOT NULL DEFAULT 'Two players'")
        db.execSQL("ALTER TABLE games ADD COLUMN playerSide TEXT NOT NULL DEFAULT 'White'")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS lesson_progress (" +
                "lessonId TEXT NOT NULL, completedAt INTEGER NOT NULL, PRIMARY KEY(lessonId))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS puzzle_stats (" +
                "id INTEGER NOT NULL, rating INTEGER NOT NULL, solved INTEGER NOT NULL, " +
                "failed INTEGER NOT NULL, streak INTEGER NOT NULL, bestStreak INTEGER NOT NULL, " +
                "recent TEXT NOT NULL, PRIMARY KEY(id))"
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS player_profile (" +
                "id INTEGER NOT NULL, updatedAt INTEGER NOT NULL, gamesReviewed INTEGER NOT NULL, " +
                "accuracySum REAL NOT NULL, plyCount INTEGER NOT NULL, brilliant INTEGER NOT NULL, " +
                "great INTEGER NOT NULL, best INTEGER NOT NULL, excellent INTEGER NOT NULL, " +
                "good INTEGER NOT NULL, book INTEGER NOT NULL, inaccuracy INTEGER NOT NULL, " +
                "mistake INTEGER NOT NULL, blunder INTEGER NOT NULL, miss INTEGER NOT NULL, " +
                "openingLossSum REAL NOT NULL, openingPlies INTEGER NOT NULL, " +
                "middleLossSum REAL NOT NULL, middlePlies INTEGER NOT NULL, " +
                "endLossSum REAL NOT NULL, endPlies INTEGER NOT NULL, captures INTEGER NOT NULL, " +
                "themes TEXT NOT NULL, PRIMARY KEY(id))"
        )
    }
}

@Database(
    entities = [SavedGame::class, LessonProgress::class, PuzzleStats::class, PlayerProfile::class],
    version = 4,
    exportSchema = false
)
abstract class ChessDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun lessons(): LessonDao
    abstract fun puzzles(): PuzzleDao
    abstract fun profiles(): ProfileDao

    companion object {
        @Volatile private var INSTANCE: ChessDatabase? = null
        fun get(context: Context): ChessDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, ChessDatabase::class.java, "chess-coach.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { INSTANCE = it }
        }
    }
}
