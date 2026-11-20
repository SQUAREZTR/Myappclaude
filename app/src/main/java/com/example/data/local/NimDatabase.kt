package com.example.data.local

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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "terminal_entries")
data class TerminalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val kind: String, // COMMAND, STDOUT, STDERR, SYSTEM, AGENT_TEXT, AGENT_THINKING, TOOL_CALL, TOOL_RESULT, DIFF, BANNER
    val text: String,
    val title: String = "",
    val meta: String = ""
)

@Entity(tableName = "command_history")
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "nim_models")
data class NimModelEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val displayName: String,
    val category: String, // Coding, Reasoning, Flagship, Fast, Vision, Guardrail
    val contextLength: String,
    val supportsTools: Boolean,
    val supportsVision: Boolean,
    val description: String,
    val isLiveApi: Boolean = false,
    val isFavorite: Boolean = false,
    val isNew: Boolean = false,
    val lastSyncedAt: Long = 0L
)

@Dao
interface TerminalEntryDao {
    @Query("SELECT * FROM terminal_entries ORDER BY id ASC")
    fun observeAll(): Flow<List<TerminalEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TerminalEntryEntity): Long

    @Query("UPDATE terminal_entries SET text = :text, meta = :meta WHERE id = :id")
    suspend fun updateText(id: Long, text: String, meta: String = "")

    @Query("DELETE FROM terminal_entries")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM terminal_entries")
    suspend fun count(): Int
}

@Dao
interface CommandHistoryDao {
    @Query("SELECT * FROM command_history ORDER BY id DESC LIMIT 100")
    fun observeRecent(): Flow<List<CommandHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CommandHistoryEntity)

    @Query("DELETE FROM command_history")
    suspend fun clearAll()
}

@Dao
interface NimModelDao {
    @Query("SELECT * FROM nim_models ORDER BY isFavorite DESC, isLiveApi DESC, isNew DESC, provider ASC, displayName ASC")
    fun observeAll(): Flow<List<NimModelEntity>>

    @Query("SELECT * FROM nim_models")
    suspend fun getAllOnce(): List<NimModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(models: List<NimModelEntity>)

    @Query("UPDATE nim_models SET isFavorite = :favorite WHERE id = :modelId")
    suspend fun setFavorite(modelId: String, favorite: Boolean)

    @Query("SELECT COUNT(*) FROM nim_models")
    suspend fun count(): Int
}

@Database(
    entities = [TerminalEntryEntity::class, CommandHistoryEntity::class, NimModelEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NimDatabase : RoomDatabase() {
    abstract fun terminalEntryDao(): TerminalEntryDao
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun nimModelDao(): NimModelDao

    companion object {
        @Volatile
        private var INSTANCE: NimDatabase? = null

        fun getInstance(context: Context): NimDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NimDatabase::class.java,
                    "nimterm_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
