package com.scorchedphoto.app.settings

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.FloorType

/**
 * Persisted starting values for a new match's player count / wall / ceiling / floor - a
 * single row ([id] fixed at 0, mirrors [AudioSettings]) edited from the title screen's own
 * gear menu (see [SettingsScreen]) and read once by [com.scorchedphoto.app.setup.GameSetupViewModel]'s
 * `init` to seed each new game-setup session, so a player's preferred starting values don't
 * reset every time they back out to the title screen and start a new match.
 *
 * [wallType]/[ceilingType]/[floorType] are stored as the enum's own [Enum.name] (nullable -
 * `null` means "Random", mirroring [com.scorchedphoto.app.setup.GameSetupViewModel]'s own
 * null-means-Random pattern for a single match) rather than an ordinal, so reordering those
 * enums later can never silently reinterpret an old persisted row as the wrong value.
 */
@Entity(tableName = "match_defaults")
data class MatchDefaults(
    @PrimaryKey val id: Int = 0,
    // Mirrors com.scorchedphoto.app.setup.MIN_TANKS - not imported directly, to avoid this
    // settings-package entity depending on the setup package (which already depends the
    // other way, on MatchDefaultsRepository, to seed GameSetupViewModel).
    val playerCount: Int = 2,
    val wallType: String? = EdgeType.NONE.name,
    val ceilingType: String? = EdgeType.NONE.name,
    val floorType: String? = FloorType.GROUND.name,
)

/** [MatchDefaults] with its [EdgeType]/[FloorType] columns resolved back from their stored
 * name - the shape every reader outside [MatchDefaultsRepository] itself actually wants. */
data class ResolvedMatchDefaults(
    val playerCount: Int,
    val wallType: EdgeType?,
    val ceilingType: EdgeType?,
    val floorType: FloorType?,
)
