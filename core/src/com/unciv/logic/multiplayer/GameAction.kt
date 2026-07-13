package com.unciv.logic.multiplayer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 *  Actions a player can perform in simultaneous multiplayer mode.
 *  Sent from client → host via WebSocket, then relayed by host → all.
*/
@Serializable
sealed interface GameAction {
    // ═══════════════════════════════════════════
    //  Unit actions
    //  unitId is globally unique
    // ═══════════════════════════════════════════

    @Serializable
    @SerialName("move")
    data class MoveAction(
        val unitId: Int,
        val toX: Int,
        val toY: Int
    ) : GameAction

    @Serializable
    @SerialName("attack")
    data class AttackAction(
        val unitId: Int,
        val targetX: Int,
        val targetY: Int
    ) : GameAction

    @Serializable
    @SerialName("promote")
    data class PromoteAction(
        val unitId: Int,
        val promotionName: String
    ) : GameAction

    @Serializable
    @SerialName("returnCapturedUnit")
    data class ReturnCapturedUnitAction(
        val unitId: Int,
        val returnToOwner: Boolean
    ) : GameAction

    /*  Unit-destroying actions (unit is consumed/destroyed):
     *  Disband, FoundCity, FoundReligion, EnhanceReligion, HurryResearch/Policy/Wonder/Building, ConductTradeMission, etc. */
    @Serializable
    @SerialName("consumeUnit")
    data class ConsumeUnitAction(
        val unitId: Int,
        val actionType: String
    ) : GameAction
    /*  Unit state-change actions (unit survives, state modified):
     *  Fortify, Pillage, Upgrade, etc.
     *  Could be merged into consumeUnit somehow? For now keep it as currently then. */
    @Serializable
    @SerialName("updateUnit")
    data class UpdateUnitAction(
        val unitId: Int,
        val actionType: String,
        /** e.g. upgrade target unit name for "Upgrade" action */
        val param: String? = null,
    ) : GameAction
    /*  Create a tile improvement (work boat / work folk).
     *  Consumes the unit after applying the improvement.
     *  Could have been folded into [ConsumeUnitAction] with an extra param,
     *  but kept separate so improvementName has a dedicated non-nullable field. */
    @Serializable
    @SerialName("createImprovement")
    data class CreateImprovementAction(
        val unitId: Int,
        val improvementName: String
    ) : GameAction

    /*  Activate a unit's unique ability (Enter Golden Age, stat bulbs, etc.).
     *  The uniqueText carries the triggered unique's rule text so the host
     *  can re-derive the trigger function and execute it authoritatively. */
    @Serializable
    @SerialName("triggerUnit")
    data class TriggerUnitAction(
        val unitId: Int,
        val uniqueText: String,
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
        val tileY: Int,
    ) : GameAction

    @Serializable
    @SerialName("cityBombard")
    data class CityBombardAction(
        val cityId: String,
        val targetTileX: Int,
        val targetTileY: Int,
    ) : GameAction

    @Serializable
    @SerialName("purchase")
    data class PurchaseAction(
        val constructionName: String,
        val cityId: String,
        val stat: String, // "Gold" or "Faith" — the stat used to pay. Since each construction actually store its cost, could we remove this?
    ) : GameAction

    // ═══════════════════════════════════════════
    //  Diplomacy actions (warfare, city capture)
    // ═══════════════════════════════════════════

    @Serializable
    @SerialName("declareWar")
    data class DeclareWarAction(
        val civName: String,
        val otherCivName: String,
    ) : GameAction

    @Serializable
    @SerialName("captureCity")
    data class CaptureCityAction(
        val cityId: String,
        val civName: String, // conquerer civName
        val mode: String, // "Puppet", "Annex", "Raze", "Liberate", ("Destroy" for one-city challenge mode)
    ) : GameAction

    // ═══════════════════════════════════════════
    //  Civ-scoped actions (no entity ID)
    // ═══════════════════════════════════════════

    @Serializable
    @SerialName("endTurn")
    data class EndTurnAction(
        val civName: String,
    ) : GameAction

    @Serializable
    @SerialName("turnAdvanced")
    data class TurnAdvanced(
        val newTurns: Int,
        val gameId: String,
        val civName: String = "",
    ) : GameAction

    @Serializable
    @SerialName("adoptPolicy")
    data class AdoptPolicyAction(
        val policyName: String,
        val civName: String,
    ) : GameAction

    @Serializable
    @SerialName("chooseFreeTech")
    data class ChooseFreeTechAction(
        val techName: String,
        val civName: String,
    ) : GameAction

    @Serializable
    @SerialName("foundPantheon")
    data class FoundPantheonAction(
        val beliefName: String,
        val civName: String,
    ) : GameAction

    @Serializable
    @SerialName("spawnUnit")
    data class SpawnUnitAction(
        val unitName: String,
        val cityId: String?,
        val civName: String,
        val freeGreatPeopleDecrement: Int = 0,
        val mayaLimitedFreeGPDecrement: Int = 0,
        val longCountGPPoolRemoval: List<String> = emptyList(),
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
        val beliefNames: List<String>,
    ) : GameAction

    /* This one fired after enhanced religion */
    @Serializable
    @SerialName("completeEnhanceReligion")
    data class CompleteEnhanceReligionAction(
        val civName: String,
        val beliefNames: List<String>,
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
        val trade: TradeData,
    ) : GameAction {
        val civName: String get() = requestingCiv
    }

    @Serializable
    @SerialName("acceptTrade")
    data class AcceptTradeAction(
        val acceptingCiv: String,
        val requestingCiv: String,
        val trade: TradeData,
    ) : GameAction {
        val civName: String get() = acceptingCiv
    }

    // ═══════════════════════════════════════════
    //  City-State interaction actions
    // ═══════════════════════════════════════════

    /* civName here refer to the civ that interact with cityStateCivName */
    @Serializable
    @SerialName("tributeGold")
    data class TributeGoldAction(
        val cityStateCivName: String,
        val civName: String,
    ) : GameAction

    @Serializable
    @SerialName("tributeWorker")
    data class TributeWorkerAction(
        val cityStateCivName: String,
        val civName: String,
    ) : GameAction

    @Serializable
    @SerialName("goldGift")
    data class GoldGiftAction(
        val cityStateCivName: String,
        val giftAmount: Int,
        val civName: String,
    ) : GameAction

    @Serializable
    @SerialName("setProtection")
    data class SetProtectionAction(
        val cityStateCivName: String,
        val protect: Boolean,
        val civName: String,
    ) : GameAction

    @Serializable
    @SerialName("giftImprovement")
    data class GiftImprovementAction(
        val cityStateCivName: String,
        val tileX: Int,
        val tileY: Int,
        val improvementName: String,
        val civName: String,
    ) : GameAction

    @Serializable
    @SerialName("diplomaticMarriage")
    data class DiplomaticMarriageAction(
        val cityStateCivName: String,
        val civName: String,
    ) : GameAction

    // ═══════════════════════════════════════════
    //  Data classes shared by trade actions
    // ═══════════════════════════════════════════

    @Serializable
    data class TradeOfferData(
        val name: String,
        val type: String,
        val amount: Int = 1,
        val duration: Int,
    )

    @Serializable
    data class TradeData(
        val theirOffers: List<TradeOfferData> = emptyList(),
        val ourOffers: List<TradeOfferData> = emptyList(),
    )
}

/**
 * Wrapper sent over the wire so the recipient knows which game this belongs to.
 * Non-host sends packet (validated=false) to server → relay to host.
 * Host sends packet (validated=true) → broadcast to all except host.
*/
@Serializable
data class GameActionPacket(val gameId: String, val action: GameAction, val validated: Boolean = false)