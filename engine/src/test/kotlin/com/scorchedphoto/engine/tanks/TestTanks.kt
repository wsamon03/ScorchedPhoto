package com.scorchedphoto.engine.tanks

fun testTank(
    id: Int,
    ownerId: Int = id,
    x: Float = 0f,
    y: Float = 0f,
    alive: Boolean = true,
    health: Int = Tank.MAX_HEALTH,
): Tank = Tank(
    id = id,
    ownerId = ownerId,
    name = "Tank $id",
    color = 0xFF0000,
    isCpu = false,
    x = x,
    y = y,
    alive = alive,
    health = health,
)
