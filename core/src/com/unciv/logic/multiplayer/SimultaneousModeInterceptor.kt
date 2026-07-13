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
            // ── Unit-consumed actions (unit is destroyed/consumed) ──
            UnitActionType.FoundCity -> {
                broadcastManager.sendConsumeUnitAction(unit.id, "FoundCity")
                return ({})
            }
            UnitActionType.HurryResearch -> {
                broadcastManager.sendConsumeUnitAction(unit.id, "HurryResearch")
                return if (isHost) null else ({})
            }
            UnitActionType.HurryPolicy -> {
                broadcastManager.sendConsumeUnitAction(unit.id, "HurryPolicy")
                return if (isHost) null else ({})
            }
            UnitActionType.HurryWonder,
            UnitActionType.HurryBuilding -> {
                broadcastManager.sendConsumeUnitAction(unit.id, "HurryWonder")
                return if (isHost) null else ({})
            }
            UnitActionType.ConductTradeMission -> {
                broadcastManager.sendConsumeUnitAction(unit.id, "ConductTradeMission")
                return if (isHost) null else ({})
            }
            UnitActionType.FoundReligion -> {
                broadcastManager.sendConsumeUnitAction(unit.id, "FoundReligion")
                return ({})
            }
            UnitActionType.EnhanceReligion -> {
                broadcastManager.sendConsumeUnitAction(unit.id, "EnhanceReligion")
                return ({})
            }

            // ── Unit-update actions (unit survives) ──
            UnitActionType.Upgrade -> {
                val upgradeAction = action as? com.unciv.models.UpgradeUnitAction ?: return null
                broadcastManager.sendUpdateUnitAction(unit.id, "Upgrade", upgradeAction.unitToUpgradeTo.name)
                return if (isHost) null else ({})
            }
            UnitActionType.Fortify -> {
                broadcastManager.sendUpdateUnitAction(unit.id, "Fortify")
                return if (isHost) null else ({})
            }
            UnitActionType.FortifyUntilHealed -> {
                broadcastManager.sendUpdateUnitAction(unit.id, "FortifyUntilHealed")
                return if (isHost) null else ({})
            }
            UnitActionType.Pillage -> {
                broadcastManager.sendUpdateUnitAction(unit.id, "Pillage")
                return ({})  // block ALL players — apply only via broadcast echo
            }

            // ── Religion actions (unit survives, tile/city state changes) ──
            UnitActionType.SpreadReligion -> {
                broadcastManager.sendUpdateUnitAction(unit.id, "SpreadReligion")
                return ({})
            }
            UnitActionType.RemoveHeresy -> {
                broadcastManager.sendUpdateUnitAction(unit.id, "RemoveHeresy")
                return ({})
            }

            // ── Generic unique-trigger actions (Enter Golden Age, stat bulbs, etc.) ──
            UnitActionType.TriggerUnique -> {
                val uniqueText = action.associatedUnique?.text ?: return null
                broadcastManager.sendTriggerUnitAction(unit.id, uniqueText)
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
        stat: String,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false

        broadcastManager.sendPurchaseAction(constructionName, cityId, stat)
        return true
    }

    /** Intercept a buy-tile action. Both host and non-host block local execution. */
    fun interceptBuyTile(
        worldScreen: WorldScreen,
        cityId: String,
        tileX: Int,
        tileY: Int,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false
        broadcastManager.sendBuyTileAction(cityId, tileX, tileY)
        return true
    }

    /** Intercept a city bombardment action. Both host and non-host block local execution. */
    fun interceptCityBombard(
        worldScreen: WorldScreen,
        cityId: String,
        targetTile: Tile,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false

        broadcastManager.sendCityBombardAction(
            cityId = cityId,
            targetTileX = targetTile.position.x,
            targetTileY = targetTile.position.y,
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
        unitId: Int,
        targetTile: com.unciv.logic.map.tile.Tile,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false

        broadcastManager.sendAttackAction(
            unitId = unitId,
            targetX = targetTile.position.x,
            targetY = targetTile.position.y,
        )
        return true
    }

    /** Intercept a unit promotion. Both host and non-host block local execution. */
    fun interceptPromote(
        worldScreen: WorldScreen,
        unitId: Int,
        promotionName: String,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false
        broadcastManager.sendPromoteAction(unitId, promotionName)
        return true
    }

    /** Intercept a disband action. */
    fun interceptDisbandUnit(
        worldScreen: WorldScreen,
        unitId: Int,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false
        broadcastManager.sendConsumeUnitAction(unitId, "DisbandUnit")
        return true
    }

    /** Intercept a spawn unit action (great person picker). */
    fun interceptSpawnUnit(
        worldScreen: WorldScreen,
        unitName: String,
        cityId: String?,
        civName: String,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false
        broadcastManager.sendSpawnUnitAction(unitName, cityId, civName)
        return true
    }

    /** Intercept a recaptured civilian decision (return to original owner or keep as worker). */
    fun interceptReturnCapturedUnit(
        worldScreen: WorldScreen,
        unitId: Int,
        returnToOwner: Boolean,
    ): Boolean {
        val gameInfo = worldScreen.gameInfo
        if (!gameInfo.gameParameters.isSimultaneousGame) return false
        val broadcastManager = worldScreen.actionBroadcastManager ?: return false
        broadcastManager.sendReturnCapturedUnitAction(unitId, returnToOwner)
        return true
    }
}