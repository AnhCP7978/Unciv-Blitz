package com.unciv.logic.multiplayer

import com.unciv.GUI
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.UpgradeUnitAction

/** Lightweight hook that intercepts unit actions in simultaneous multiplayer mode.
 * Called before a unit move is executed locally.
 * @return true if the action was intercepted successfully
*/
object SimultaneousModeInterceptor {
    // Shared function to check if using Simultaneous mode and grab actionBroadcastManager
    private fun getBroadcastManager(): ActionBroadcastManager? {
        val worldScreen = GUI.getWorldScreen()
        if (!worldScreen.gameInfo.gameParameters.isSimultaneousGame) return null
        return worldScreen.actionBroadcastManager
    }

    fun interceptMove(unit: MapUnit, targetTile: Tile): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        try {
            // Calculate the reachable tile this turn (If choose a far away tile, we send this turn destination tile instead)
            val tileToMoveTo = unit.movement.getTileToMoveToThisTurn(targetTile)

            broadcastManager.sendGameAction(
                GameAction.MoveAction(unit.id, tileToMoveTo.position.x, tileToMoveTo.position.y)
            )
            return true
        } catch (_: Exception) {
            return false // Cancel the move (if nothing reachable this turn?)
        }
    }

    // Use for both unit fight & city bombardment
    fun interceptAttack(attackerId: Any, targetTile: Tile): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false

        when (attackerId) {
            is Int -> {
                broadcastManager.sendGameAction(
                    GameAction.UnitAttackAction(attackerId, targetTile.position.x, targetTile.position.y)
                )
                return true
            }
            is String -> {
                broadcastManager.sendGameAction(
                    GameAction.CityAttackAction(attackerId, targetTile.position.x, targetTile.position.y)
                )
                return true
            }
            else -> { return false }
        }
    }

    fun interceptUnitAction(unit: MapUnit, action: UnitAction): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false

        when (action.type) {
            UnitActionType.Upgrade -> {
                broadcastManager.sendGameAction(
                    GameAction.UpgradeUnitAction(unit.id, (action as UpgradeUnitAction).unitToUpgradeTo.name)
                )
            }
            // ── Generic unique-trigger actions (Enter Golden Age, stat bulbs, etc.) ──
            UnitActionType.TriggerUnique -> {
                val uniqueText = action.associatedUnique?.text ?: return false
                broadcastManager.sendGameAction(
                    GameAction.TriggerUniqueAction(unit.id, uniqueText)
                )
            }
            // Generic actions (pillage, fortify, disband, ...)
            else -> {
                broadcastManager.sendGameAction(
                    GameAction.InvokeUnitAction(unit.id, action.type)
                )
            }
        }
        return true
    }

    // Intercept an unit promotion
    fun interceptPromote(unitId: Int, promotionName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(
            GameAction.PromoteAction(unitId, promotionName)
        )
        return true
    }

    fun interceptCreateImprovement(unitId: Int, improvementName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(
            GameAction.CreateImprovementAction(unitId, improvementName)
        )
        return true
    }

    // Intercept a purchase action (buying building/unit in city)
    fun interceptPurchase(constructionName: String, cityId: String, stat: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(
            GameAction.PurchaseAction(constructionName, cityId, stat)
        )
        return true
    }

    // Intercept a city buy tile action
    fun interceptBuyTile(cityId: String, tileX: Int, tileY: Int): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(
            GameAction.BuyTileAction(cityId, tileX, tileY)
        )
        return true
    }

    // Intercept a declare war action
    fun interceptDeclareWar(civName: String, otherCivName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(
            GameAction.DeclareWarAction(civName, otherCivName)
        )
        return true
    }

    // Intercept a spawn unit action (great person picker)
    fun interceptSpawnUnit(
        unitName: String,
        cityId: String?,
        civName: String,
        freeGreatPeopleDecrement: Int = 0,
        mayaLimitedFreeGPDecrement: Int = 0,
        longCountGPPoolRemoval: List<String> = emptyList(),
    ): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(
            GameAction.SpawnUnitAction(unitName, cityId, civName,
                freeGreatPeopleDecrement, mayaLimitedFreeGPDecrement, longCountGPPoolRemoval)
        )
        return true
    }

    // Intercept a recaptured civilian decision (return to original owner or keep as worker)
    fun interceptReturnCapturedUnit(unitId: Int, returnToOwner: Boolean): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(
            GameAction.ReturnCapturedUnitAction(unitId, returnToOwner)
        )
        return true
    }
}