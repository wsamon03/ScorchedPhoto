package com.scorchedphoto.app.settings

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhraseDao {
    @Query("SELECT * FROM phrases WHERE category = :category ORDER BY id ASC")
    fun observeByCategory(category: String): Flow<List<Phrase>>

    @Query("SELECT text FROM phrases WHERE category = :category AND enabled = 1")
    suspend fun getEnabledTexts(category: String): List<String>

    @Insert
    suspend fun insert(phrase: Phrase): Long

    @Query("UPDATE phrases SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM phrases WHERE id = :id")
    suspend fun deleteById(id: Long)
}
