package com.scorchedphoto.engine

/**
 * A short-lived visual marker at a wall/ceiling bounce point, for the renderer to draw as
 * a brief fading flash keyed to [edgeType] (color/shape/animation) - see
 * GameEngine.handleEdgeBounce. Never created for EdgeType.NONE or EdgeType.BLAST_STEEL
 * (the latter reuses ImpactEffect/GameEvent.Impact instead, matching a real explosion).
 */
data class BounceEffect(val x: Float, val y: Float, val edgeType: EdgeType, var age: Float = 0f)
