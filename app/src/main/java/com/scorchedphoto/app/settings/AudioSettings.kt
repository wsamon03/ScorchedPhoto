package com.scorchedphoto.app.settings

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The app's audio volume levels (0f..1f each), a single persisted row ([id] fixed at 0 -
 * see [SettingsDatabase]'s seeding callback, which inserts the initial row so [AudioSettingsDao.observe]
 * never has to fall back to defaults in practice). [musicVolume] has nothing to drive yet -
 * there's no music system in the game today - but is wired up and persisted ahead of one. */
@Entity(tableName = "audio_settings")
data class AudioSettings(
    @PrimaryKey val id: Int = 0,
    val musicVolume: Float = 1f,
    val sfxVolume: Float = 1f,
    val voiceVolume: Float = 1f,
)
