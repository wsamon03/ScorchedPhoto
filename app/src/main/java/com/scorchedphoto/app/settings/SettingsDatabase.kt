package com.scorchedphoto.app.settings

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** The app's persisted settings: user-manageable death/attack [Phrase]s and [AudioSettings]
 * volume levels - kept in their own database (separate from `custom_voices.db`) so this
 * feature's schema can evolve independently of the pre-existing custom-voice one. */
@Database(entities = [Phrase::class, AudioSettings::class], version = 1, exportSchema = false)
abstract class SettingsDatabase : RoomDatabase() {
    abstract fun phraseDao(): PhraseDao
    abstract fun audioSettingsDao(): AudioSettingsDao

    companion object {
        /** Runs exactly once, the moment this database's file is first created - seeds the
         * default death/attack phrases (matching what used to be hardcoded taunt lists, so
         * existing behavior is preserved out of the box) and the initial all-100% volume
         * row, so the player always starts with a populated, editable set rather than an
         * empty one. Uses bound parameters (not string-concatenated SQL) to sidestep any
         * quote-escaping in the phrase text itself (e.g. "I'll get you next time!"). */
        val callback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                for (text in DEFAULT_DEATH_PHRASES) {
                    db.execSQL(
                        "INSERT INTO phrases (category, text, enabled) VALUES (?, ?, 1)",
                        arrayOf(PhraseCategory.DEATH.name, text),
                    )
                }
                for (text in DEFAULT_ATTACK_PHRASES) {
                    db.execSQL(
                        "INSERT INTO phrases (category, text, enabled) VALUES (?, ?, 1)",
                        arrayOf(PhraseCategory.ATTACK.name, text),
                    )
                }
                db.execSQL(
                    "INSERT INTO audio_settings (id, musicVolume, sfxVolume, voiceVolume) VALUES (0, 1.0, 1.0, 1.0)",
                )
            }
        }

        private val DEFAULT_DEATH_PHRASES = listOf(
            "Not again!",
            "Argh!!",
            "Ouch! That hurts!",
            "I'll get you next time!",
            "Why me?",
        )

        private val DEFAULT_ATTACK_PHRASES = listOf(
            "I've got you now!",
            "You're a gonner!",
            "Cowabunga!",
            "Let's do this!",
            "You made me do this!",
            "1, 2, 3, 4, my bombs are gonna score!",
            "Bombs away!",
            "Fire!",
            "Fiiirrree!",
            "Die!",
            "Death to all!",
            "Oops...",
            "My bad.",
            "Is this how it works?",
        )
    }
}
