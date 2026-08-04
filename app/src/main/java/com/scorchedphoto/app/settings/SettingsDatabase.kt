package com.scorchedphoto.app.settings

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The app's persisted settings: user-manageable death/attack [Phrase]s, [AudioSettings]
 * volume levels, and [MatchDefaults] starting values for a new match - kept in their own
 * database (separate from `custom_voices.db`) so this feature's schema can evolve
 * independently of the pre-existing custom-voice one. */
@Database(entities = [Phrase::class, AudioSettings::class, MatchDefaults::class], version = 2, exportSchema = false)
abstract class SettingsDatabase : RoomDatabase() {
    abstract fun phraseDao(): PhraseDao
    abstract fun audioSettingsDao(): AudioSettingsDao
    abstract fun matchDefaultsDao(): MatchDefaultsDao

    companion object {
        /** Adds [MatchDefaults] for installs that already had a version-1 `settings.db` -
         * `onCreate`'s callback below only fires for a brand-new database file, so an
         * existing player's phrases/audio settings must be carried forward untouched while
         * this new table is created and seeded alongside them. The `CREATE TABLE` here must
         * match exactly what Room itself would generate for the [MatchDefaults] entity. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `match_defaults` (`id` INTEGER NOT NULL, " +
                        "`playerCount` INTEGER NOT NULL, `wallType` TEXT, `ceilingType` TEXT, " +
                        "`floorType` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "INSERT INTO match_defaults (id, playerCount, wallType, ceilingType, floorType) " +
                        "VALUES (0, 2, 'NONE', 'NONE', 'GROUND')",
                )
            }
        }

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
                db.execSQL(
                    "INSERT INTO match_defaults (id, playerCount, wallType, ceilingType, floorType) " +
                        "VALUES (0, 2, 'NONE', 'NONE', 'GROUND')",
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
