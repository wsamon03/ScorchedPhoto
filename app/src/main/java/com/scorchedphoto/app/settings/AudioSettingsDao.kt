package com.scorchedphoto.app.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioSettingsDao {
    @Query("SELECT * FROM audio_settings WHERE id = 0")
    fun observe(): Flow<AudioSettings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: AudioSettings)
}
