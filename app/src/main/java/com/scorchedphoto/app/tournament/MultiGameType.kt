package com.scorchedphoto.app.tournament

/**
 * A multi-game tournament mode - several matches played back-to-back against the same
 * starting roster, with score/elimination rules carried across matches (see
 * [TournamentState]/[TournamentScorer]) until one tank is declared the overall victor.
 * `null` (not a member of this enum) means an ordinary Single Game - see
 * [TournamentRepository.config].
 */
enum class MultiGameType(val displayName: String, val description: String, val usesPointsToWin: Boolean) {
    KNOCKOUT(
        displayName = "Knockout",
        description = "After every game, the first tank to die is knocked out and plays no " +
            "more games. Play until only one tank remains - they're the overall victor. A " +
            "tie for who died first knocks nobody out that game.",
        usesPointsToWin = false,
    ),
    SURVIVOR(
        displayName = "Survivor",
        description = "Same as Knockout, plus the winner of each game gets immunity for the " +
            "next one - if they're the first to die, they aren't knocked out. Immunity lasts " +
            "one game only.",
        usesPointsToWin = false,
    ),
    LAST_MAN_STANDING(
        displayName = "Last Man Standing",
        description = "The last tank remaining in each game wins a point - ties count as 0. " +
            "First tank to reach the chosen point total wins it all.",
        usesPointsToWin = true,
    ),
    KILL_COUNT(
        displayName = "Kill Count",
        description = "Tanks gain a point for each kill and lose a point for each death. A " +
            "self-kill voids credit for any other tanks killed by that same shot. First tank " +
            "to reach the chosen point total wins it all.",
        usesPointsToWin = true,
    ),
    STANDING(
        displayName = "Standing",
        description = "After each game, tanks are ranked by death order - first to die scores " +
            "0, and each later tank scores the number of tanks that died before it (ties share " +
            "the lower score). First tank to reach the chosen point total wins it all.",
        usesPointsToWin = true,
    ),
}
