package com.unciv.logic.multiplayer

import com.unciv.models.UnitActionType
import com.unciv.models.stats.Stat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 *  Actions a player can perform in simultaneous multiplayer mode.
 *  Sent from client → host via WebSocket, then relayed by host → all.
*/
@Serializable
sealed interface GameAction {
    @Serializable
    @SerialName("move")
    data class MoveAction(
        val unitId: Int,
        val toX: Int,
        val toY: Int
    ) : GameAction

    /* Attack */
    @Serializable
    @SerialName("unitAttack")
    data class UnitAttackAction(
        val unitId: Int,
        val targetX: Int,
        val targetY: Int
    ) : GameAction

    @Serializable
    @SerialName("cityAttack")
    data class CityAttackAction(
        val cityId: String,
        val targetX: Int,
        val targetY: Int
    ) : GameAction

    /* UnitAction */
    @Serializable
    @SerialName("invokeUnit")
    data class InvokeUnitAction(
        val unitId: Int,
        val actionType: UnitActionType
    ) : GameAction

    @Serializable
    @SerialName("upgradeUnit")
    data class UpgradeUnitAction(
        val unitId: Int,
        val unitToUpgradeTo: String // BaseUnit.name
    ) : GameAction

    @Serializable
    @SerialName("transformUnit")
    data class TransformUnitAction(
        val unitId: Int,
        val unitToTransformTo: String // BaseUnit.name
    ) : GameAction

    @Serializable
    @SerialName("triggerUnique")
    data class TriggerUniqueAction(
        val unitId: Int,
        val uniqueText: String
    ) : GameAction

    /* Promote */
    @Serializable
    @SerialName("promote")
    data class PromoteAction(
        val unitId: Int,                    
        val promotionNames: List<String> // Whenever promote action is intercepted, we only send what haven't exist yet in bulk
    ) : GameAction

    /* One-shot improvement (work boats, great people improvements, etc.) */
    @Serializable
    @SerialName("createImprovement")
    data class CreateImprovementAction(
        val unitId: Int,
        val improvementName: String
    ) : GameAction

    @Serializable
    @SerialName("returnCapturedUnit")
    data class ReturnCapturedUnitAction(
        val unitId: Int,
        val returnToOwner: Boolean
    ) : GameAction

    // ═══════════════════════════════════════════
    //  City actions
    //  cityId is globally unique UUID
    // ═══════════════════════════════════════════
    @Serializable
    @SerialName("buyTile")
    data class BuyTileAction(
        val cityId: String,
        val tileX: Int,
        val tileY: Int
    ) : GameAction

    @Serializable
    @SerialName("purchase")
    data class PurchaseAction(
        val constructionName: String,
        val cityId: String,
        val stat: String // "Gold" or "Faith" — the stat used to pay. Since each construction actually store its cost, could we remove this?
    ) : GameAction

    // ═══════════════════════════════════════════
    //  Diplomacy actions (warfare, city capture)
    // ═══════════════════════════════════════════
    @Serializable
    @SerialName("declareWar")
    data class DeclareWarAction(
        val civName: String,
        val otherCivName: String
    ) : GameAction

    @Serializable
    @SerialName("captureCity")
    data class CaptureCityAction(
        val cityId: String,
        val civName: String,    // conquerer civName
        val mode: String        // "Puppet", "Annex", "Raze", "Liberate", (& "Destroy" for one-city challenge mode)
    ) : GameAction

    @Serializable
    @SerialName("annexCity") // Sub-action for CaptureCityAction... could merge in the future?
    data class AnnexCityAction(
        val cityId: String,
        val civName: String
    ) : GameAction

    // ═══════════════════════════════════════════
    //  Civ-scoped actions (no entity ID)
    // ═══════════════════════════════════════════
    @Serializable
    @SerialName("endTurn")
    data class EndTurnAction(
        val civName: String
    ) : GameAction

    @Serializable
    @SerialName("turnAdvanced")
    data class TurnAdvanced(
        val newTurns: Int,
        val gameId: String,
        val civName: String = ""
    ) : GameAction

    @Serializable
    @SerialName("adoptPolicy")
    data class AdoptPolicyAction(
        val policyName: String,
        val civName: String
    ) : GameAction

    @Serializable
    @SerialName("chooseFreeTech")
    data class ChooseFreeTechAction(
        val techName: String,
        val civName: String
    ) : GameAction

    @Serializable
    @SerialName("foundPantheon")
    data class FoundPantheonAction(
        val beliefName: String,
        val civName: String
    ) : GameAction

    @Serializable
    @SerialName("spawnUnit")
    data class SpawnUnitAction(
        val unitName: String,
        val civName: String
    ) : GameAction

    // ═══════════════════════════════════════════
    //  Religion actions
    // ═══════════════════════════════════════════

    /* This one fired after player finish creating their religion with beliefs -> broadcast to others */
    @Serializable
    @SerialName("completeFoundReligion")
    data class CompleteFoundReligionAction(
        val civName: String,
        val displayName: String,
        val religionName: String,
        val beliefNames: List<String>
    ) : GameAction

    /* This one fired after enhanced religion */
    @Serializable
    @SerialName("completeEnhanceReligion")
    data class CompleteEnhanceReligionAction(
        val civName: String,
        val beliefNames: List<String>
    ) : GameAction

    // ═══════════════════════════════════════════
    //  Trade actions (civName computed from actor field)
    // ═══════════════════════════════════════════

    /* When a trade deal get send, we broadcast SendTradeRequestAction
     * If it get accepted, we broadcast the trade result, if rejected/re-negotiated: continue */
    @Serializable
    @SerialName("sendTradeRequest")
    data class SendTradeRequestAction(
        val requestingCiv: String,
        val targetCiv: String,
        val trade: TradeData
    ) : GameAction {
        val civName: String get() = requestingCiv
    }

    @Serializable
    @SerialName("acceptTrade")
    data class AcceptTradeAction(
        val acceptingCiv: String,
        val requestingCiv: String,
        val trade: TradeData
    ) : GameAction {
        val civName: String get() = acceptingCiv
    }

    // ═══════════════════════════════════════════
    //  City-State interaction actions
    // ═══════════════════════════════════════════

    /* civName here refer to the civ that interact with cityStateName */
    @Serializable
    @SerialName("takeTribute")
    data class TakeTributeAction(
        val cityStateName: String,
        val civName: String,
        val tributeType: String // "Gold" or "Worker"
    ) : GameAction

    @Serializable
    @SerialName("goldGift")
    data class GoldGiftAction(
        val cityStateName: String,
        val giftAmount: Int,
        val civName: String
    ) : GameAction

    @Serializable
    @SerialName("setProtection")
    data class SetProtectionAction(
        val cityStateName: String,
        val protect: Boolean,
        val civName: String
    ) : GameAction

    @Serializable
    @SerialName("giftImprovement")
    data class GiftImprovementAction(
        val cityStateName: String,
        val tileX: Int,
        val tileY: Int,
        val improvementName: String,
        val civName: String
    ) : GameAction

    @Serializable
    @SerialName("diplomaticMarriage")
    data class DiplomaticMarriageAction(
        val cityStateName: String,
        val civName: String
    ) : GameAction

    // ═══════════════════════════════════════════
    //  Data classes shared by trade actions
    // ═══════════════════════════════════════════

    @Serializable
    data class TradeOfferData(
        val name: String,
        val type: String,
        val amount: Int = 1,
        val duration: Int
    )

    @Serializable
    data class TradeData(
        val theirOffers: List<TradeOfferData> = emptyList(),
        val ourOffers: List<TradeOfferData> = emptyList()
    )
}

/**
 * Wrapper sent over the wire so the recipient knows which game this belongs to.
 * Non-host sends packet (validated=false) to server → relay to host.
 * Host sends packet (validated=true) → broadcast to all except host.
*/
@Serializable
data class GameActionPacket(val gameId: String, val action: GameAction, val validated: Boolean = false)