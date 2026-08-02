package com.scorchedphoto.engine.ai

import com.scorchedphoto.engine.combat.Weapon
import com.scorchedphoto.engine.combat.WeaponCatalog
import com.scorchedphoto.engine.combat.WeaponType
import com.scorchedphoto.engine.tanks.Tank
import kotlin.random.Random

/**
 * Picks which weapon a CPU tank fires this turn. [ammoFor] mirrors [com.scorchedphoto.engine.GameEngine.ammoFor]
 * (null = unlimited, otherwise shots remaining) - a weapon with 0 remaining is never selected,
 * so a CPU tank that's run out of e.g. Standard Shells always falls back to something it still
 * has, rather than silently firing nothing (see [com.scorchedphoto.engine.GameEngine.fire]'s own
 * no-op-on-empty-ammo guard). The higher [difficulty], the more the pick actually reasons about
 * the shot instead of just grabbing whatever's handy.
 */
object CpuWeaponSelector {

    // MEDIUM reasons tactically about this fraction of its shots and grabs naively the rest of
    // the time - halfway between EASY (always naive) and HARD (always tactical).
    private const val MEDIUM_TACTICAL_CHANCE = 0.5f

    // EASY mostly reaches for Standard Shell (the "obvious" choice) but occasionally grabs
    // something else at random, with no reasoning about the target at all.
    private const val EASY_RANDOM_PICK_CHANCE = 0.25f

    fun selectWeapon(
        target: Tank,
        otherEnemiesAlive: Int,
        difficulty: Difficulty,
        ammoFor: (WeaponType) -> Int?,
        rng: Random = Random.Default,
    ): WeaponType {
        val available = WeaponCatalog.all.filter { weapon ->
            val hasAmmo = ammoFor(weapon.type)?.let { it > 0 } ?: true
            // Excludes purely defensive/utility weapons (Earthmover - deals no damage) - there's
            // no defensive/terrain-shaping AI yet, so a CPU tank has nothing to reason about for
            // them and should never spend a turn on one.
            hasAmmo && weapon.maxDamage > 0
        }
        // Never actually empty - Baby Missile's ammoLimit is always null (unlimited) - but
        // Standard Shell is as safe a fallback as any if every weapon somehow reported 0.
        if (available.isEmpty()) return WeaponType.STANDARD_SHELL

        val actTactically = when (difficulty) {
            Difficulty.EASY -> false
            Difficulty.MEDIUM -> rng.nextFloat() < MEDIUM_TACTICAL_CHANCE
            Difficulty.HARD -> true
        }
        return if (actTactically) {
            selectTactically(available, target, otherEnemiesAlive)
        } else {
            selectNaively(available, rng)
        }.type
    }

    private fun selectNaively(available: List<Weapon>, rng: Random): Weapon {
        val standardShell = available.firstOrNull { it.type == WeaponType.STANDARD_SHELL }
        if (standardShell != null && rng.nextFloat() >= EASY_RANDOM_PICK_CHANCE) return standardShell
        return available.random(rng)
    }

    /**
     * Prefers to finish [target] outright with whichever available weapon is the least
     * precious to spend (most starting ammo - see [conservationOrder] - never burning a
     * scarce, high-value weapon on an already-nearly-dead tank when a more plentiful one
     * would finish it just as well). If nothing can one-shot it, prefers whichever multi-
     * warhead weapon (Cluster MIRV, Spread MIRV, Flak Burst - any [Weapon.childCount] > 1)
     * hits hardest overall whenever more than one enemy is still alive - a spread gives a
     * real chance of catching more than just [target] - and otherwise falls back to whichever
     * available weapon hits hardest, direct hit plus any lingering damage-over-time included.
     */
    private fun selectTactically(available: List<Weapon>, target: Tank, otherEnemiesAlive: Int): Weapon {
        // A single connecting child is enough to finish the target, so "can one-shot" only
        // needs to check each weapon's own per-hit maxDamage (not a multi-warhead total, and
        // not any later damage-over-time tick, which isn't instant).
        val canFinishTarget = available.filter { it.maxDamage >= target.health }
        if (canFinishTarget.isNotEmpty()) {
            return canFinishTarget.minWith(conservationOrder)
        }

        if (otherEnemiesAlive > 1) {
            val bestSplitter = available.filter { it.childCount > 1 }.maxByOrNull { it.maxDamage * it.childCount }
            if (bestSplitter != null) return bestSplitter
        }

        return available.maxBy { effectivePower(it) }
    }

    /** A weapon's single-shot punch, direct hit plus any lingering damage-over-time it sets up
     * (e.g. Nuke/Napalm) - lets the "hits hardest overall" fallback above weigh a DoT weapon's
     * real total damage instead of just its instant hit. */
    private fun effectivePower(weapon: Weapon): Float =
        weapon.maxDamage + weapon.dotFraction * weapon.dotRounds * Tank.MAX_HEALTH

    /** Ranks "safest to spend first": most starting ammo first (an unlimited weapon like Baby
     * Missile sorts ahead of everything), then - among weapons tied on ammo (Big Bertha, Cluster
     * MIRV, Spread MIRV, and Nuke all start with 1) - whichever hits softer in a single shot, so
     * a scarce, powerful weapon is never spent where a lesser one would have finished the job
     * just as well. */
    private val conservationOrder = compareByDescending<Weapon> { it.ammoLimit ?: Int.MAX_VALUE }
        .thenBy { it.maxDamage }
}
