package com.scorchedphoto.engine.turns

import com.scorchedphoto.engine.tanks.Tank

/** [winningOwnerIds] has one entry for a normal win, or more than one for a tie (every
 * remaining owner eliminated by the same shot - see [com.scorchedphoto.engine.GameEngine]'s
 * mutual-elimination handling). */
data class WinResult(val winningOwnerIds: List<Int>, val winningTankIds: List<Int>)

/** Round-robins turn order over a fixed tank list, skipping eliminated tanks. */
class TurnManager(private val tanks: List<Tank>) {

    private var currentIndex: Int = -1

    var currentTank: Tank? = null
        private set

    init {
        advanceToNextAliveTank()
    }

    /** Moves to the next alive tank after the current one, wrapping around. */
    fun advanceToNextAliveTank(): Tank? {
        if (tanks.isEmpty()) {
            currentTank = null
            return null
        }
        val startIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % tanks.size
        var index = startIndex
        var attempts = 0
        while (attempts <= tanks.size) {
            val candidate = tanks[index]
            if (candidate.alive) {
                currentIndex = index
                currentTank = candidate
                return candidate
            }
            index = (index + 1) % tanks.size
            attempts++
        }
        currentTank = null
        return null
    }

    /** Null while more than one owner still has a tank standing. */
    fun checkWinCondition(): WinResult? {
        val aliveTanks = tanks.filter { it.alive }
        if (aliveTanks.isEmpty()) return null
        val distinctOwners = aliveTanks.map { it.ownerId }.distinct()
        return if (distinctOwners.size == 1) {
            WinResult(distinctOwners, aliveTanks.map { it.id })
        } else {
            null
        }
    }
}
