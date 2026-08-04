package com.scorchedphoto.app.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MatchDefaultsDao {
    @Query("SELECT * FROM match_defaults WHERE id = 0")
    fun observe(): Flow<MatchDefaults?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(defaults: MatchDefaults)
}
