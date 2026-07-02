package com.unciv.logic.multiplayer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Actions a player can perform in simultaneous multiplayer mode.
 * Sent from client → host via WebSocket, then relayed by host → all.
 */
@Serializable
sealed class GameAction {
    abstract val civName: String

    @Serializable
    @SerialName("move")
    data class MoveAction(
        val unitId: Int,
        val toX: Int,
        val toY: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("attack")
    data class AttackAction(
        val unitId: Int,
        val targetTileX: Int,
        val targetTileY: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("foundCity")
    data class FoundCityAction(
        val unitId: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("buyTile")
    data class BuyTileAction(
        val cityId: String,
        val tileX: Int,
        val tileY: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("declareWar")
    data class DeclareWarAction(
        val otherCivName: String,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("cityBombard")
    data class CityBombardAction(
        val cityId: String,
        val targetTileX: Int,
        val targetTileY: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("greatPerson")
    data class GreatPersonAction(
        val unitId: Int,
        val actionType: String, // "HurryResearch", "HurryPolicy", "HurryWonder", "HurryBuilding", "ConstructImprovement", "ConductTradeMission"
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("upgrade")
    data class UpgradeAction(
        val unitId: Int,
        val upgradeToUnitName: String,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("promote")
    data class PromoteAction(
        val unitId: Int,
        val promotionName: String,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("purchase")
    data class PurchaseAction(
        val constructionName: String,
        val cityId: String,
        val queuePosition: Int = -1,
        val stat: String,   // "Gold" or "Faith"
        val tileX: Int? = null,
        val tileY: Int? = null,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("fortify")
    data class FortifyAction(
        val unitId: Int,
        val fortifyType: String, // "Fortify" or "FortifyUntilHealed"
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("endTurn")
    data class EndTurnAction(
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("turnAdvanced")
    data class TurnAdvanced(
        val newTurns: Int,
        val gameId: String,
        override val civName: String = "",
    ) : GameAction()

    @Serializable
    @SerialName("pillage")
    data class PillageAction(
        val unitId: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("adoptPolicy")
    data class AdoptPolicyAction(
        val policyName: String,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("disbandUnit")
    data class DisbandUnitAction(
        val unitId: Int,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("foundPantheon")
    data class FoundPantheonAction(
        val beliefName: String,
        override val civName: String,
    ) : GameAction()

    @Serializable
    @SerialName("createImprovement")
    data class CreateImprovementAction(
        val unitId: Int,
        val improvementName: String,
        override val civName: String,
    ) : GameAction()

    // ──────────────────────────────────────
    //  Trade / Diplomacy actions
    // ──────────────────────────────────────

    @Serializable
    @SerialName("sendTradeRequest")
    data class SendTradeRequestAction(
        val requestingCiv: String,
        val targetCiv: String,
        val trade: TradeData,
    ) : GameAction() {
        override val civName: String get() = requestingCiv
    }

    @Serializable
    @SerialName("retractTradeRequest")
    data class RetractTradeRequestAction(
        val requestingCiv: String,
        val targetCiv: String,
    ) : GameAction() {
        override val civName: String get() = requestingCiv
    }

    @Serializable
    @SerialName("acceptTrade")
    data class AcceptTradeAction(
        val acceptingCiv: String,
        val requestingCiv: String,
        val trade: TradeData,
    ) : GameAction() {
        override val civName: String get() = acceptingCiv
    }

    @Serializable
    @SerialName("declineTradeRequest")
    data class DeclineTradeRequestAction(
        val decliningCiv: String,
        val requestingCiv: String,
        val trade: TradeData,
    ) : GameAction() {
        override val civName: String get() = decliningCiv
    }
}

// ──────────────────────────────────────
//  Serializable trade data classes
// ──────────────────────────────────────

@Serializable
data class TradeOfferData(
    val name: String,
    val type: String, // TradeOfferType.name
    val amount: Int = 1,
    val duration: Int,
)

@Serializable
data class TradeData(
    val theirOffers: List<TradeOfferData> = emptyList(),
    val ourOffers: List<TradeOfferData> = emptyList(),
)

/**
 * Wrapper sent over the wire so the recipient knows which game this belongs to.
 * Non-host send packet (validated=false) to server -> relay to host
 * Host send packet (validated=true) -> broadcast to all except host
*/
@Serializable
data class GameActionPacket(val gameId: String, val action: GameAction, val validated: Boolean = false)