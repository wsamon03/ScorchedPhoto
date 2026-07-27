package com.scorchedphoto.app.settings

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Which taunt list a [Phrase] belongs to - stored as [PhraseCategory.name] in the
 * `category` column rather than a Room [androidx.room.TypeConverter], keeping the schema
 * plain text. */
enum class PhraseCategory { DEATH, ATTACK }

/** A single user-manageable taunt line - shown/spoken when a tank dies ([PhraseCategory.DEATH])
 * or fires ([PhraseCategory.ATTACK]), see [PhraseRepository]. [enabled] lets the player turn a
 * line off without deleting it; a tank stays silent whenever a category has no enabled phrases
 * at all (see [PhraseRepository.getEnabledTexts]). */
@Entity(tableName = "phrases")
data class Phrase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val text: String,
    val enabled: Boolean = true,
)
