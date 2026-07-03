package com.unciv.logic.multiplayer

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.UpgradeUnitAction
import com.unciv.ui.screens.worldscreen.WorldScreen

/** Lightweight hook that intercepts unit actions in simultaneous multiplayer mode.
 * Non-host players have their actions routed through the [ActionBroadcastManager]
 * instead of executing locally.
 *
 * The host applies actions immediately and broadcasts the result. */
object SimultaneousModeInterceptor {
    /** Called before a unit move is executed locally.
     * @return true if the action was intercepted (broadcast mode) — caller should skip local execution */
    fun interceptMove(
        worldScreen: WorldScreen,
        unit: MapUnit,
        targetTile: Tile,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false

        val broadcastManager = worldScreen.actionBroadcastManager ?: return false
        // Calculate the reachable tile this turn instead of sending the final far-away destination
        val tileToMoveTo = try {
            unit.movement.getTileToMoveToThisTurn(targetTile)
        } catch (_: Exception) {
            return false // Cancel the move if nothing reachable this turn
        }

        broadcastManager.sendMoveAction(
            unitId = unit.id,
            toX = tileToMoveTo.position.x, toY = tileToMoveTo.position.y,
            civName = unit.civ.civName,
        )
        return !broadcastManager.isHost() // Let host execute locally
    }

    /** Intercept a unit action (found city, etc).
     * The caller should return the replacement action (wrapped in broadcast)
     * or null to continue with original action.
     *
     * Host: sends validated=true and lets local execution proceed (returns null).
     * Non-host: sends validated=false and blocks (returns {}). */
    fun interceptUnitAction(
        worldScreen: WorldScreen,
        unit: MapUnit,
        action: UnitAction,
        originalAction: () -> Unit,
    ): (() -> Unit)? {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return null

        val broadcastManager = worldScreen.actionBroadcastManager ?: return null
        val isHost = broadcastManager.isHost()

        when (action.type) {
            UnitActionType.FoundCity -> {
                broadcastManager.sendFoundCityAction(unit.id, unit.civ.civName)
                return ({})
            }
            UnitActionType.HurryResearch,
            UnitActionType.HurryPolicy,
            UnitActionType.HurryWonder,
            UnitActionType.HurryBuilding,
            UnitActionType.ConductTradeMission -> {
                broadcastManager.sendGreatPersonAction(
                    unit.id, action.type.name, unit.civ.civName
                )
                return if (isHost) null else ({})
            }
            UnitActionType.Upgrade -> {
                val upgradeAction = action as? UpgradeUnitAction ?: return null
                broadcastManager.sendUpgradeAction(
                    unit.id, upgradeAction.unitToUpgradeTo.name, unit.civ.civName
                )
                return if (isHost) null else ({})
            }
            UnitActionType.Fortify -> {
                broadcastManager.sendFortifyAction(unit.id, "Fortify", unit.civ.civName)
                return if (isHost) null else ({})
            }
            UnitActionType.FortifyUntilHealed -> {
                broadcastManager.sendFortifyAction(unit.id, "FortifyUntilHealed", unit.civ.civName)
                return if (isHost) null else ({})
            }
            UnitActionType.Pillage -> {
                broadcastManager.sendPillageAction(unit.id, unit.civ.civName)
                return ({})  // block ALL players — apply only via broadcast echo
            }
            UnitActionType.FoundReligion -> {
                broadcastManager.sendFoundReligionAction(unit.id, unit.civ.civName)
                return ({})
            }
            UnitActionType.EnhanceReligion -> {
                broadcastManager.sendEnhanceReligionAction(unit.id, unit.civ.civName)
                return ({})
            }
            else -> return null  // don't intercept other actions
        }
    }

    /** Intercept a purchase action (buying construction in a city).
     * @return true if the action was intercepted — caller should skip local execution */
    fun interceptPurchase(
        worldScreen: WorldScreen,
        constructionName: String,
        cityId: String,
        queuePosition: Int,
        stat: String,
        tileX: Int?,
        tileY: Int?,
        civName: String,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false

        broadcastManager.sendPurchaseAction(constructionName, cityId, queuePosition, stat, tileX, tileY, civName)
        return true
    }

    /** Intercept a buy-tile action. Both host and non-host block local execution. */
    fun interceptBuyTile(
        worldScreen: WorldScreen,
        cityId: String,
        tileX: Int,
        tileY: Int,
        civName: String,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false
        broadcastManager.sendBuyTileAction(cityId, tileX, tileY, civName)
        return true
    }

    /** Intercept a city bombardment action. Both host and non-host block local execution. */
    fun interceptCityBombard(
        worldScreen: WorldScreen,
        cityId: String,
        targetTile: Tile,
        civName: String,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false

        broadcastManager.sendCityBombardAction(
            cityId = cityId,
            targetTileX = targetTile.position.x,
            targetTileY = targetTile.position.y,
            civName = civName,
        )
        return true
    }

    /** Intercept a declare war action. Returns true if the action was intercepted. */
    fun interceptDeclareWar(
        worldScreen: WorldScreen,
        civName: String,
        otherCivName: String,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false

        broadcastManager.sendDeclareWarAction(civName, otherCivName)
        return true
    }

    /** Intercept an attack action. Both host and non-host block local execution. */
    fun interceptAttack(
        worldScreen: WorldScreen,
        unit: com.unciv.logic.map.mapunit.MapUnit,
        targetTile: com.unciv.logic.map.tile.Tile,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false

        broadcastManager.sendAttackAction(
            unitId = unit.id,
            targetTileX = targetTile.position.x,
            targetTileY = targetTile.position.y,
            civName = unit.civ.civName,
        )
        return true
    }
}