package com.scorchedphoto.engine.economy

/**
 * Tunable numbers for the :app economy/shop feature - see [MatchEarnings] (the formula these
 * feed) and [com.scorchedphoto.engine.ai.CpuShopper]. Kept in one place, separate from per-
 * [com.scorchedphoto.engine.combat.Weapon] prices, so the whole economy's balance can be
 * retuned after playtesting without hunting through multiple files. Current values are an
 * explicit first-pass placeholder, not a tuned final balance.
 */
object EconomyConstants {
    /** Currency every tank starts a fresh game/tournament with, before any match has been
     * played - see :app's `GameSetupViewModel.commitAndStart`. */
    const val STARTING_BUDGET = 200

    /** Awarded to every tank in the roster for simply having played the match, win or lose. */
    const val PARTICIPATION_BASE = 20

    /** Awarded per other tank in the match an owner outlives - strictly outlives, not a
     * simultaneous ("tied") death - regardless of who (if anyone) actually killed it. See
     * [MatchEarnings] for the full pairwise comparison. */
    const val OUTSURVIVE_BONUS = 30

    /** Awarded on top of [OUTSURVIVE_BONUS] to the sole tank still alive when the match's
     * [com.scorchedphoto.engine.turns.WinResult] resolves - not awarded on a multi-survivor
     * tie for the win. */
    const val LAST_SURVIVOR_BONUS = 30

    /** Awarded per tank kill credited to an owner (see [com.scorchedphoto.engine.DeathRecord.killedByOwnerId]) -
     * a suicide (an owner credited with their own death) never counts. */
    const val KILL_BONUS = 30

    /** Multiplied against a match's [com.scorchedphoto.engine.GameEngine.damageDealtByOwner]
     * (floored to a whole currency amount) - see [MatchEarnings]. */
    const val DAMAGE_TO_CURRENCY_RATE = 0.4f
}
