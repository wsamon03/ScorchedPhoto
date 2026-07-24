package com.scorchedphoto.app.tts

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomVoiceDao {
    @Query("SELECT * FROM custom_voices ORDER BY id ASC")
    fun observeAll(): Flow<List<CustomVoice>>

    @Insert
    suspend fun insert(customVoice: CustomVoice): Long
}
