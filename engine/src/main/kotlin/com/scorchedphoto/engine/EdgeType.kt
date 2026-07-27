package com.scorchedphoto.engine

/**
 * What happens when an in-flight projectile reaches the left/right world edge (as a wall
 * type) or the top world edge (as a ceiling type) - see GameEngine.tickProjectiles's
 * handleEdgeBounce. Shared by both settings since the option set is identical; NONE means
 * "no interception" (existing fizzle-far-off-into-the-void / gravity-always-returns-it
 * behavior, unchanged).
 */
enum class EdgeType(val displayName: String) {
    NONE("None"),
    BLAST_STEEL("Blast Steel"),
    PADDED("Padded"),
    RUBBER("Rubber"),
    SPRING("Spring"),
    REFLECTIVE("Reflective"),
    WRAP("Wrap"),
}
