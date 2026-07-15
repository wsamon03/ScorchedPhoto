package com.scorchedphoto.app.terrainpreview

import com.scorchedphoto.terrain.HeightMap
import javax.inject.Inject
import javax.inject.Singleton

/** Holds the accepted terrain between the preview screen and game setup/construction. */
@Singleton
class TerrainRepository @Inject constructor() {
    var heightMap: HeightMap? = null
}
