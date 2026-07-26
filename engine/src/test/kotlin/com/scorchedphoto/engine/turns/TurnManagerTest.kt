package com.scorchedphoto.engine.turns

import com.scorchedphoto.engine.tanks.testTank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TurnManagerTest {

    @Test
    fun `starts on the first tank`() {
        val tanks = listOf(testTank(1), testTank(2), testTank(3))
        val manager = TurnManager(tanks)
        assertEquals(1, manager.currentTank?.id)
    }

    @Test
    fun `round-robins through tanks in order`() {
        val tanks = listOf(testTank(1), testTank(2), testTank(3))
        val manager = TurnManager(tanks)
        assertEquals(1, manager.currentTank?.id)
        assertEquals(2, manager.advanceToNextAliveTank()?.id)
        assertEquals(3, manager.advanceToNextAliveTank()?.id)
        assertEquals(1, manager.advanceToNextAliveTank()?.id) // wraps around
    }

    @Test
    fun `skips eliminated tanks`() {
        val tanks = listOf(testTank(1), testTank(2, alive = false), testTank(3))
        val manager = TurnManager(tanks)
        assertEquals(1, manager.currentTank?.id)
        assertEquals(3, manager.advanceToNextAliveTank()?.id) // 2 is skipped
        assertEquals(1, manager.advanceToNextAliveTank()?.id)
    }

    @Test
    fun `starts on the first alive tank if the first in the list is dead`() {
        val tanks = listOf(testTank(1, alive = false), testTank(2))
        val manager = TurnManager(tanks)
        assertEquals(2, manager.currentTank?.id)
    }

    @Test
    fun `no win while multiple owners remain`() {
        val tanks = listOf(testTank(1, ownerId = 1), testTank(2, ownerId = 2))
        val manager = TurnManager(tanks)
        assertNull(manager.checkWinCondition())
    }

    @Test
    fun `win condition fires when only one owner has tanks left`() {
        val tanks = listOf(
            testTank(1, ownerId = 1),
            testTank(2, ownerId = 2, alive = false),
            testTank(3, ownerId = 1),
        )
        val manager = TurnManager(tanks)
        val result = manager.checkWinCondition()
        assertNotNull(result)
        assertEquals(listOf(1), result?.winningOwnerIds)
        assertEquals(listOf(1, 3), result?.winningTankIds)
    }

    @Test
    fun `no tanks alive returns no winner`() {
        val tanks = listOf(testTank(1, alive = false), testTank(2, alive = false))
        val manager = TurnManager(tanks)
        assertNull(manager.checkWinCondition())
        assertNull(manager.currentTank)
    }
}
