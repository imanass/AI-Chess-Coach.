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

@Database(
    entities = [SavedGame::class, LessonProgress::class, PuzzleStats::class],
    version = 3,
    exportSchema = false
)
abstract class ChessDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun lessons(): LessonDao
    abstract fun puzzles(): PuzzleDao

    companion object {
        @Volatile private var INSTANCE: ChessDatabase? = null
        fun get(context: Context): ChessDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, ChessDatabase::class.java, "chess-coach.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { INSTANCE = it }
        }
    }
}
