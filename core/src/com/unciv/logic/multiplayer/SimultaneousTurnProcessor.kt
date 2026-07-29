package com.unciv.logic.multiplayer

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.managers.TurnManager

/**
 * Handles turn advancement for simultaneous multiplayer mode.
 * Called by the HOST only, when all human players have ended their turn.
 *
 * This is intentionally separate from [GameInfo.nextTurn] which is designed
 * for sequential single-currentPlayer games. This processor treats all
 * human players equally — none is "the current player".
*/
object SimultaneousTurnProcessor {
    fun processAdvance(gameInfo: GameInfo) {
        if (!gameInfo.gameParameters.isSimultaneousGame) return // Safety guard, should never be called in sequential game

        for (civ in gameInfo.civilizations) {
            if (!civ.isAlive()) continue

            civ.popupAlerts.clear()                     // Clear stale popup alerts for all alive players to prevent duplicate popups across turns
            val manager = TurnManager(civ)

            when (civ.playerType) {
                PlayerType.Human -> manager.endTurn()   // 1. End turn for all alive human players (collect gold, science, etc.)
                PlayerType.AI -> {                      // 2. Process all alive AI players (start → automate → end)
                    manager.startTurn()
                    manager.automateTurn()
                    manager.endTurn()
                }
            }
        }

        // 3. Increment world turn counter
        gameInfo.turns++

        // 4. Start the new turn for all alive human players
        for (civ in gameInfo.civilizations.filter { it.isAlive() && it.playerType == PlayerType.Human }) {
            TurnManager(civ).startTurn()
        }

        // 5. Reset the finished-turn tracking
        gameInfo.simultaneousTurnState.reset()
        gameInfo.currentTurnStartTime = System.currentTimeMillis()
    }
}