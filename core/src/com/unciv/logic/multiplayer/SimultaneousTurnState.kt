package com.unciv.logic.multiplayer

/**
 * Serializable state for simultaneous multiplayer mode.
 * Stored inside [GameInfo] and synced via the game file.
 * The host manages this; others read it to see who has finished.
 */
class SimultaneousTurnState {
    /** Civ names of players who have ended their turn this round */
    var playersFinishedTurn = mutableListOf<String>()

    /** Civ name of the host player — who runs turn advancement */
    var hostCivName = ""

    fun reset() {
        playersFinishedTurn.clear()
    }
}
