package com.scorchedphoto.app.tts

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_voices")
data class CustomVoice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val voiceId: String?,
    val pitch: Float,
    val speechRate: Float,
)
