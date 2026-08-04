package com.scorchedphoto.app.settings

import com.scorchedphoto.engine.EdgeType
import com.scorchedphoto.engine.FloorType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class MatchDefaultsRepository @Inject constructor(
    private val dao: MatchDefaultsDao,
) {
    /** Falls back to all-defaults if the seed row is somehow missing - see
     * [SettingsDatabase]'s creation callback, which normally guarantees it exists. */
    val defaults: Flow<ResolvedMatchDefaults> = dao.observe().map { (it ?: MatchDefaults()).resolve() }

    suspend fun setPlayerCount(count: Int) = update { it.copy(playerCount = count) }
    suspend fun setWallType(type: EdgeType?) = update { it.copy(wallType = type?.name) }
    suspend fun setCeilingType(type: EdgeType?) = update { it.copy(ceilingType = type?.name) }
    suspend fun setFloorType(type: FloorType?) = update { it.copy(floorType = type?.name) }

    private suspend fun update(transform: (MatchDefaults) -> MatchDefaults) {
        val current = dao.observe().first() ?: MatchDefaults()
        dao.upsert(transform(current))
    }

    private fun MatchDefaults.resolve() = ResolvedMatchDefaults(
        playerCount = playerCount,
        wallType = wallType?.let { EdgeType.valueOf(it) },
        ceilingType = ceilingType?.let { EdgeType.valueOf(it) },
        floorType = floorType?.let { FloorType.valueOf(it) },
    )
}
