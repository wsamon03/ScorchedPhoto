package com.scorchedphoto.app.tts

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [CustomVoice::class], version = 1, exportSchema = false)
abstract class CustomVoiceDatabase : RoomDatabase() {
    abstract fun customVoiceDao(): CustomVoiceDao
}
