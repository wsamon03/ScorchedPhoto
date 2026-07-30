package com.scorchedphoto.engine

/**
 * What the very bottom of the map does - see GameEngine.floorType and
 * GameEngine.tickProjectiles's handleFloorEdge. Unlike EdgeType (wall/ceiling), there is no
 * NONE: the floor is never simply "absent." The first six values reuse the same bounce/
 * detonate/teleport behaviors EdgeType's wall/ceiling settings already have (see
 * handleFloorEdge's doc for how WRAP differs there from ceiling-WRAP); the rest are new,
 * floor-specific mechanics with no wall/ceiling equivalent.
 */
enum class FloorType(val displayName: String) {
    BLAST_STEEL("Blast Steel"),
    PADDED("Padded"),
    RUBBER("Rubber"),
    SPRING("Spring"),
    REFLECTIVE("Reflective"),
    WRAP("Wrap"),
    HOLE("Hole"),
    GROUND("Ground"),
    VOID("Void"),
    WATER("Water"),
    LAVA("Lava"),
}
