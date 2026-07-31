package com.unciv.logic.multiplayer

import com.unciv.GUI
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.translations.getPlaceholderParameters

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

    // ──────────────────────────────────────
    //  Move / Attack
    // ──────────────────────────────────────
    fun interceptMove(unit: MapUnit, targetTile: Tile): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        try {
            // Calculate the reachable tile this turn (If choose a far away tile, we send this turn destination tile instead)
            val tileToMoveTo = unit.movement.getTileToMoveToThisTurn(targetTile)

            broadcastManager.sendGameAction(GameAction.MoveAction(unit.id, tileToMoveTo.position.x, tileToMoveTo.position.y))
            return true
        } catch (_: Exception) {
            return false // Cancel the move (if nothing reachable this turn?)
        }
    }

    // Use for both unit fight & city bombardment
    fun interceptAttack(attacker: ICombatant, targetTile: Tile): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false

        return when (attacker) {
            is MapUnitCombatant -> {
                broadcastManager.sendGameAction(GameAction.UnitAttackAction(attacker.unit.id, targetTile.position.x, targetTile.position.y))
                true
            }
            is CityCombatant -> {
                broadcastManager.sendGameAction(GameAction.CityAttackAction(attacker.city.id, targetTile.position.x, targetTile.position.y))
                true
            }
            else -> false
        }
    }

    // ──────────────────────────────────────
    //  Unit action dispatch (generic + dedicated)
    // ──────────────────────────────────────
    fun interceptUnitAction(unitId: Int, action: UnitAction): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false

        when (action.type) {
            // Pure UI actions — must never be intercepted
            UnitActionType.ShowAdditionalActions, UnitActionType.HideAdditionalActions -> return false
            // These open picker screens — intercepted at the picker's finalize instead
            UnitActionType.ConstructImprovement, UnitActionType.Promote -> return false
            // Should require confirmation, use interceptDisbandAction()
            UnitActionType.DisbandUnit -> return false

            // Dedicated GameAction types (need extra params from the action)
            UnitActionType.CreateImprovement -> {
                val improvementName = action.title.getPlaceholderParameters().firstOrNull() ?: return false
                broadcastManager.sendGameAction(GameAction.CreateImprovementAction(unitId, improvementName))
            }
            UnitActionType.TriggerUnique -> {
                val uniqueText = action.associatedUnique?.text ?: return false
                broadcastManager.sendGameAction(GameAction.TriggerUniqueAction(unitId, uniqueText))
            }
            UnitActionType.Upgrade -> {
                val unitToUpgradeTo = (action as UpgradeUnitAction).unitToUpgradeTo.name
                broadcastManager.sendGameAction(GameAction.UpgradeUnitAction(unitId, unitToUpgradeTo))
            }
            UnitActionType.Transform -> {
                // Transform actions carry the target unit name in the title via "[unitName]" pattern
                val unitToTransformTo = action.title.getPlaceholderParameters().firstOrNull() ?: return false
                broadcastManager.sendGameAction(GameAction.TransformUnitAction(unitId, unitToTransformTo))
            }

            // All other unit actions: Fortify, Sleep, Pillage, Found City, Set Up, Gift Unit, Transform, Paradrop, AirSweep, etc.
            // The host regenerates the action closure via UnitActions.invokeUnitAction() and validates before broadcasting.
            else -> {
                broadcastManager.sendGameAction(GameAction.InvokeUnitAction(unitId, action.type))
            }
        }
        return true
    }

    fun interceptDisbandAction(unitId: Int): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.InvokeUnitAction(unitId, UnitActionType.DisbandUnit))
        return true
    }

    // Intercept an unit promotion
    fun interceptPromoteAction(unitId: Int, promotionNames: List<String>): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.PromoteAction(unitId, promotionNames))
        return true
    }

    // ──────────────────────────────────────
    //  One-shot improvement (work boats, etc.) - Currently not in used
    // ──────────────────────────────────────
    fun interceptCreateImprovement(unitId: Int, improvementName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.CreateImprovementAction(unitId, improvementName))
        return true
    }

    // ──────────────────────────────────────
    //  City actions
    // ──────────────────────────────────────

    // Intercept a purchase action (buying building/unit in city)
    fun interceptPurchase(constructionName: String, cityId: String, stat: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.PurchaseAction(constructionName, cityId, stat))
        return true
    }

    // Intercept a city buy tile action
    fun interceptBuyTile(cityId: String, tileX: Int, tileY: Int): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.BuyTileAction(cityId, tileX, tileY))
        return true
    }

    fun interceptCaptureCity(cityId: String, civName: String, mode: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.CaptureCityAction(cityId, civName, mode))
        return true
    }

    fun interceptAnnexCity(cityId: String, civName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.AnnexCityAction(cityId, civName))
        return true
    }

    // ──────────────────────────────────────
    //  Civ / Policy / Tech
    // ──────────────────────────────────────
    fun interceptAdoptPolicy(policyName: String, civName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.AdoptPolicyAction(policyName, civName))
        return true
    }

    fun interceptChooseFreeTech(techName: String, civName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.ChooseFreeTechAction(techName, civName))
        return true
    }

    // Intercept a declare war action
    fun interceptDeclareWar(civName: String, otherCivName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.DeclareWarAction(civName, otherCivName))
        return true
    }

    // Intercept a spawn unit action (great person picker)
    fun interceptSpawnUnit(unitName: String, civName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.SpawnUnitAction(unitName, civName))
        return true
    }

    // Intercept a recaptured civilian decision (return to original owner or keep as worker)
    fun interceptReturnCapturedUnit(unitId: Int, returnToOwner: Boolean): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.ReturnCapturedUnitAction(unitId, returnToOwner))
        return true
    }

    // ──────────────────────────────────────
    //  Religion
    // ──────────────────────────────────────
    fun interceptCompleteFoundReligion(civName: String, displayName: String, religionName: String, beliefNames: List<String>): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.CompleteFoundReligionAction(civName, displayName, religionName, beliefNames))
        return true
    }

    fun interceptCompleteEnhanceReligion(civName: String, beliefNames: List<String>): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.CompleteEnhanceReligionAction(civName, beliefNames))
        return true
    }

    fun interceptFoundPantheon(beliefName: String, civName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.FoundPantheonAction(beliefName, civName))
        return true
    }

    // ──────────────────────────────────────
    //  Trade
    // ──────────────────────────────────────
    fun interceptSendTradeRequest(requestingCiv: String, targetCiv: String, trade: GameAction.TradeData): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.SendTradeRequestAction(requestingCiv, targetCiv, trade))
        return true
    }

    fun interceptAcceptTrade(acceptingCiv: String, requestingCiv: String, trade: GameAction.TradeData): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.AcceptTradeAction(acceptingCiv, requestingCiv, trade))
        return true
    }

    // ──────────────────────────────────────
    //  City-State interaction
    // ──────────────────────────────────────
    fun interceptTakeTribute(cityStateName: String, civName: String, tributeType: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.TakeTributeAction(cityStateName, civName, tributeType))
        return true
    }

    fun interceptGoldGift(cityStateName: String, giftAmount: Int, civName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.GoldGiftAction(cityStateName, giftAmount, civName))
        return true
    }

    fun interceptSetProtection(cityStateName: String, protect: Boolean, civName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.SetProtectionAction(cityStateName, protect, civName))
        return true
    }

    fun interceptGiftImprovement(cityStateName: String, tileX: Int, tileY: Int, improvementName: String, civName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.GiftImprovementAction(cityStateName, tileX, tileY, improvementName, civName))
        return true
    }

    fun interceptDiplomaticMarriage(cityStateName: String, civName: String): Boolean {
        val broadcastManager = getBroadcastManager() ?: return false
        broadcastManager.sendGameAction(GameAction.DiplomaticMarriageAction(cityStateName, civName))
        return true
    }
}