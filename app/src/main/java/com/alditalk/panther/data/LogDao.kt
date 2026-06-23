package com.alditalk.panther.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntry)

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC")
    fun getAll(): Flow<List<LogEntry>>

    @Query("DELETE FROM log_entries WHERE timestamp < :maxAge")
    suspend fun deleteOlderThan(maxAge: Long)

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun count(): Int
}
