package com.example.aichesscoach.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
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

@Dao
interface GameDao {
    @Insert suspend fun insert(game: SavedGame)
    @Query("SELECT * FROM games ORDER BY playedAt DESC") fun observeAll(): Flow<List<SavedGame>>
    @Query("SELECT COUNT(*) FROM games") fun observeCount(): Flow<Int>
    @Query("SELECT COALESCE(SUM(plyCount), 0) FROM games") fun observeMoves(): Flow<Int>
    @Query("DELETE FROM games WHERE id = :id") suspend fun deleteById(id: Long)
    @Query("DELETE FROM games") suspend fun deleteAll()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE games ADD COLUMN mode TEXT NOT NULL DEFAULT 'Two players'")
        db.execSQL("ALTER TABLE games ADD COLUMN playerSide TEXT NOT NULL DEFAULT 'White'")
    }
}

@Database(entities = [SavedGame::class], version = 2, exportSchema = false)
abstract class ChessDatabase : RoomDatabase() {
    abstract fun games(): GameDao

    companion object {
        @Volatile private var INSTANCE: ChessDatabase? = null
        fun get(context: Context): ChessDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, ChessDatabase::class.java, "chess-coach.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
        }
    }
}
