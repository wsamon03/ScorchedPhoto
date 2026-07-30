package com.scorchedphoto.engine.terrain

/**
 * A permanently growing circular region of open floor - see GameEngine.growRegion, seeded by
 * FloorType.VOID/FloorType.LAVA when an explosion first carves through to the map's true
 * bottom in a spot no existing region already covers. [depthCapGroundY] is null for Void
 * (unbounded - grows down to the map's own bottom) or a fixed absolute groundY for Lava
 * (captured once at region creation: never carves past 4% of terrain.height below the
 * region's own origin depth, however wide the region has since grown horizontally).
 */
data class FloorRegion(val centerX: Float, var radius: Float, val depthCapGroundY: Int?)
