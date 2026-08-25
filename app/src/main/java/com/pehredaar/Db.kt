package com.pehredaar

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val description: String,
    val ruleId: Long?,
    val severity: String,          // info | warn | alert
    val thumbnailPath: String?,
)

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun all(): Flow<List<Rule>>

    @Query("SELECT * FROM rules WHERE enabled = 1")
    suspend fun enabled(): List<Rule>

    @Query("SELECT COUNT(*) FROM rules")
    suspend fun count(): Int

    @Insert suspend fun insert(rule: Rule): Long
    @Update suspend fun update(rule: Rule)
    @Delete suspend fun delete(rule: Rule)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY timestamp DESC LIMIT 500")
    fun recent(): Flow<List<Event>>

    @Insert suspend fun insert(event: Event): Long
}

@Database(entities = [Rule::class, Event::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun rules(): RuleDao
    abstract fun events(): EventDao

    companion object {
        @Volatile private var instance: Db? = null
        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, Db::class.java, "pehredaar.db"
            ).build().also { instance = it }
        }
    }
}
