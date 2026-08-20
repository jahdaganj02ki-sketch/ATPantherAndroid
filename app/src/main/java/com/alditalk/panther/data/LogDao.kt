package com.alditalk.panther.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntry)

    /** Keep the main screen lightweight on devices with limited RAM. */
    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT 300")
    fun getRecent(): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries ORDER BY timestamp ASC")
    suspend fun getAllForExport(): List<LogEntry>

    @Query("DELETE FROM log_entries WHERE timestamp < :maxAge")
    suspend fun deleteOlderThan(maxAge: Long)

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun count(): Int
}
