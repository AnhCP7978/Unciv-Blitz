package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsPillage
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsReligion
import com.unciv.ui.screens.worldscreen.bottombar.BattleTableHelpers.battleAnimationDeferred
import com.unciv.utils.debug
import com.unciv.utils.Concurrency
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.civilization.LocationAction
import com.unciv.logic.civilization.DiplomacyAction
import com.unciv.logic.civilization.CivilopediaAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeRequest
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.multiplayer.chat.Response
import com.unciv.logic.multiplayer.chat.ChatStore
import com.unciv.logic.multiplayer.chat.ChatWebSocket
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.stats.Stat
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.UnitActionType
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

// ──────────────────────────────────────
//  Trade data ↔ domain object conversion
// ──────────────────────────────────────

private fun TradeOffer.toTradeOfferData() = GameAction.TradeOfferData(name, type.name, amount, duration)
private fun GameAction.TradeOfferData.toTradeOffer() = TradeOffer(name, TradeOfferType.valueOf(type), amount, duration)

private fun Trade.toTradeData() = GameAction.TradeData(
    theirOffers.map { it.toTradeOfferData() },
    ourOffers.map { it.toTradeOfferData() }
)
private fun GameAction.TradeData.toTrade(): Trade {
    val t = Trade()
    t.theirOffers.addAll(theirOffers.map { it.toTradeOffer() })
    t.ourOffers.addAll(ourOffers.map { it.toTradeOffer() })
    return t
}

/**
 * Orchestrates the 2-phase broadcast protocol for simultaneous multiplayer:
 *
 * **Non-host** players send actions via WebSocket → server relays to all
 * (including host) → host validates → host broadcasts acceptance/rejection.
 *
 * **Host** listens for all "end turn" signals → runs [SimultaneousTurnProcessor.processAdvance]
 * → uploads game file → broadcasts [Response.TurnAdvanced] so everyone downloads.
 */
class ActionBroadcastManager(private val worldScreen: WorldScreen) {
    private val gameId get() = worldScreen.gameInfo.gameId

    /** Prevents the local player from double-sending EndTurn */
    @Volatile
    var hasEndedTurn = false

    /** Host-only: pending CivTurnChoices from non-host players, keyed by civName */
    private val pendingChoices = mutableMapOf<String, String>()

    /** Look up a unit by globally-unique ID across all civs. */
    private fun findUnitById(unitId: Int): MapUnit? =
        worldScreen.gameInfo.getUnitById(unitId)

    /** Look up a city by globally-unique UUID across all civs. */
    private fun findCityById(cityId: String) =
        worldScreen.gameInfo.civilizations.asSequence()
            .flatMap { it.cities.asSequence() }
            .firstOrNull { it.id == cityId }

    // ──────────────────────────────────────
    //  Send (called when local player acts)
    // ──────────────────────────────────────

    /** Construct a [GameActionPacket] and send it.
     *  Host applies locally first (won't receive an echo in the new routing),
     *  then sends with validated=true so server broadcasts to all others.
     *  Non-host sends with validated=false so server routes only to host. */
    private fun sendGameAction(action: GameAction) {
        val validated = isHost()
        if (validated) {
            // Host applies locally before sending — server won't echo back to host
            applyActionLocally(action)
        }
        val packet = GameActionPacket(gameId, action, validated)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(packet)
        )
    }

    /** Apply an action directly on the local game state (host only, before sending). */
    private fun applyActionLocally(action: GameAction) {
        when (action) {
            is GameAction.MoveAction -> applyRemoteMove(action)
            is GameAction.AttackAction -> applyRemoteAttack(action)
            is GameAction.PromoteAction -> applyRemotePromote(action)
            is GameAction.ConsumeUnitAction -> applyRemoteConsumeUnit(action)
            is GameAction.UpdateUnitAction -> applyRemoteUpdateUnit(action)
            is GameAction.CreateImprovementAction -> applyRemoteCreateImprovement(action)
            is GameAction.TriggerUnitAction -> applyRemoteTriggerUnit(action)
            is GameAction.BuyTileAction -> applyRemoteBuyTile(action)
            is GameAction.CityBombardAction -> applyRemoteCityBombard(action)
            is GameAction.PurchaseAction -> applyRemotePurchase(action)
            is GameAction.DeclareWarAction -> applyRemoteDeclareWar(action)
            is GameAction.CaptureCityAction -> applyRemoteCaptureCity(action)
            is GameAction.AdoptPolicyAction -> applyRemoteAdoptPolicy(action)
            is GameAction.ChooseFreeTechAction -> applyRemoteChooseFreeTech(action)
            is GameAction.FoundPantheonAction -> applyRemoteFoundPantheon(action)
            is GameAction.SpawnUnitAction -> applyRemoteSpawnUnit(action)
            is GameAction.CompleteFoundReligionAction -> applyRemoteCompleteFoundReligion(action)
            is GameAction.CompleteEnhanceReligionAction -> applyRemoteCompleteEnhanceReligion(action)
            is GameAction.SendTradeRequestAction -> applyRemoteSendTradeRequest(action)
            is GameAction.AcceptTradeAction -> applyRemoteAcceptTrade(action)
            is GameAction.TributeGoldAction -> applyRemoteTributeGold(action)
            is GameAction.TributeWorkerAction -> applyRemoteTributeWorker(action)
            is GameAction.GoldGiftAction -> applyRemoteGoldGift(action)
            is GameAction.SetProtectionAction -> applyRemoteSetProtection(action)
            is GameAction.GiftImprovementAction -> applyRemoteGiftImprovement(action)
            is GameAction.DiplomaticMarriageAction -> applyRemoteDiplomaticMarriage(action)
            else -> {}
        }
    }

    // ──────────────────────────────────────
    //  Send helpers — match new GameAction data classes
    // ──────────────────────────────────────

    fun sendMoveAction(unitId: Int, toX: Int, toY: Int) =
        sendGameAction(GameAction.MoveAction(unitId, toX, toY))

    fun sendAttackAction(unitId: Int, targetX: Int, targetY: Int) =
        sendGameAction(GameAction.AttackAction(unitId, targetX, targetY))

    fun sendConsumeUnitAction(unitId: Int, actionType: String) =
        sendGameAction(GameAction.ConsumeUnitAction(unitId, actionType))

    fun sendUpdateUnitAction(unitId: Int, actionType: String, param: String? = null) =
        sendGameAction(GameAction.UpdateUnitAction(unitId, actionType, param))

    fun sendBuyTileAction(cityId: String, tileX: Int, tileY: Int) =
        sendGameAction(GameAction.BuyTileAction(cityId, tileX, tileY))

    fun sendDeclareWarAction(civName: String, otherCivName: String) =
        sendGameAction(GameAction.DeclareWarAction(civName, otherCivName))

    fun sendCaptureCityAction(cityId: String, civName: String, mode: String) =
        sendGameAction(GameAction.CaptureCityAction(cityId, civName, mode))

    fun sendCityBombardAction(cityId: String, targetTileX: Int, targetTileY: Int) =
        sendGameAction(GameAction.CityBombardAction(cityId, targetTileX, targetTileY))

    fun sendPromoteAction(unitId: Int, promotionName: String) =
        sendGameAction(GameAction.PromoteAction(unitId, promotionName))

    fun sendReturnCapturedUnitAction(unitId: Int, returnToOwner: Boolean) =
        sendGameAction(GameAction.ReturnCapturedUnitAction(unitId, returnToOwner))

    fun sendCreateImprovementAction(unitId: Int, improvementName: String) =
        sendGameAction(GameAction.CreateImprovementAction(unitId, improvementName))

    fun sendTriggerUnitAction(unitId: Int, uniqueText: String) =
        sendGameAction(GameAction.TriggerUnitAction(unitId, uniqueText))

    fun sendPurchaseAction(constructionName: String, cityId: String, stat: String) =
        sendGameAction(GameAction.PurchaseAction(constructionName, cityId, stat))

    fun sendAdoptPolicyAction(policyName: String, civName: String) =
        sendGameAction(GameAction.AdoptPolicyAction(policyName, civName))

    fun sendChooseFreeTechAction(techName: String, civName: String) =
        sendGameAction(GameAction.ChooseFreeTechAction(techName, civName))

    fun sendFoundPantheonAction(beliefName: String, civName: String) =
        sendGameAction(GameAction.FoundPantheonAction(beliefName, civName))

    fun sendSpawnUnitAction(
        unitName: String, cityId: String?, civName: String,
        freeGreatPeopleDecrement: Int = 0,
        mayaLimitedFreeGPDecrement: Int = 0,
        longCountGPPoolRemoval: List<String> = emptyList(),
    ) = sendGameAction(
        GameAction.SpawnUnitAction(
            unitName, cityId, civName,
            freeGreatPeopleDecrement, mayaLimitedFreeGPDecrement, longCountGPPoolRemoval,
        )
    )

    fun sendSendTradeRequestAction(trade: Trade, targetCiv: String, civName: String) =
        sendGameAction(GameAction.SendTradeRequestAction(civName, targetCiv, trade.toTradeData()))

    fun sendAcceptTradeAction(trade: Trade, requestingCiv: String, civName: String) =
        sendGameAction(GameAction.AcceptTradeAction(civName, requestingCiv, trade.toTradeData()))

    // ──────────────────────────────────────
    //  City-State interaction sends
    // ──────────────────────────────────────

    fun sendTributeGoldAction(cityStateCivName: String, civName: String) =
        sendGameAction(GameAction.TributeGoldAction(cityStateCivName, civName))

    fun sendTributeWorkerAction(cityStateCivName: String, civName: String) =
        sendGameAction(GameAction.TributeWorkerAction(cityStateCivName, civName))

    fun sendGoldGiftAction(cityStateCivName: String, giftAmount: Int, civName: String) =
        sendGameAction(GameAction.GoldGiftAction(cityStateCivName, giftAmount, civName))

    fun sendSetProtectionAction(cityStateCivName: String, protect: Boolean, civName: String) =
        sendGameAction(GameAction.SetProtectionAction(cityStateCivName, protect, civName))

    fun sendGiftImprovementAction(cityStateCivName: String, tileX: Int, tileY: Int, improvementName: String, civName: String) =
        sendGameAction(GameAction.GiftImprovementAction(cityStateCivName, tileX, tileY, improvementName, civName))

    fun sendDiplomaticMarriageAction(cityStateCivName: String, civName: String) =
        sendGameAction(GameAction.DiplomaticMarriageAction(cityStateCivName, civName))

    // ──────────────────────────────────────
    //  Religion sends
    // ──────────────────────────────────────

    fun sendCompleteFoundReligionAction(civName: String, displayName: String, religionName: String, beliefNames: List<String>) =
        sendGameAction(GameAction.CompleteFoundReligionAction(civName, displayName, religionName, beliefNames))

    fun sendCompleteEnhanceReligionAction(civName: String, beliefNames: List<String>) =
        sendGameAction(GameAction.CompleteEnhanceReligionAction(civName, beliefNames))

    init {
        // Register as the action response handler in ChatStore
        ChatStore.onActionResponse = { response ->
            onActionResponse(response)
        }

        // Ensure WebSocket subscription for this game (handles reconnect/resume)
        ChatStore.getChatByGameId(gameId)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.Join(listOf(gameId))
        )

        // If this player is the host, inform the server
        if (isHost()) {
            ChatWebSocket.requestMessageSend(
                com.unciv.logic.multiplayer.chat.Message.SetHost(gameId)
            )
        }
    }

    /** Returns "Waiting (finishedCount/totalCount)" for display in the NextTurnButton */
    fun getWaitingStatus(): String {
        val allHumans = worldScreen.gameInfo.civilizations
            .filter { it.isAlive() && it.playerType == PlayerType.Human }
        val finishedPlayers = worldScreen.gameInfo.simultaneousTurnState.playersFinishedTurn
        val finished = allHumans.count { it.civName in finishedPlayers }
        return "Waiting ($finished/${allHumans.size})"
    }

    /** Cleanup on WorldScreen dispose */
    fun dispose() {
        if (ChatStore.onActionResponse == this::onActionResponse)
            ChatStore.onActionResponse = null
    }

    // ──────────────────────────────────────
    //  Response dispatch
    // ──────────────────────────────────────

    private fun onActionResponse(response: Response) {
        Concurrency.run("HandleActionResponse") {
            when (response) {
                is Response.GameActionRelay -> {
                    applyRemoteAction(response.packet)
                }
                is Response.GameActionRejected -> {
                    debug("Action rejected: %s", response.reason)
                }
                is Response.HostSet -> {
                    debug("Host set for game %s (userId: %s)", response.gameId, response.hostUserId)
                }
                is Response.PlayerEndedTurn -> {
                    onRemotePlayerEndedTurn(response)
                }
                is Response.TurnAdvanced -> {
                    onTurnAdvanced(response)
                }
                else -> {}
            }
        }
    }

    /** Called when the local player clicks "End Turn" */
    fun sendEndTurn(civName: String) {
        if (hasEndedTurn) return // prevent double-submit
        hasEndedTurn = true

        var choicesJson: String? = null
        // Host tracks itself immediately instead of waiting for server echo
        if (isHost()) {
            worldScreen.gameInfo.simultaneousTurnState.playersFinishedTurn.add(civName)
            debug("Host %s ended turn (%d/?)", civName, worldScreen.gameInfo.simultaneousTurnState.playersFinishedTurn.size)
        }
        else { // Gather civ choices for batch sync
            choicesJson = try {
                val civ = worldScreen.gameInfo.civilizations.first { it.civName == civName }
                val cityConstructions = civ.cities.associate { it.id to it.cityConstructions.currentConstructionName() }
                val techResearch = civ.tech.techsToResearch.firstOrNull()
                val choices = CivTurnChoices(
                    civName = civName,
                    cityConstructions = cityConstructions,
                    currentTechResearch = techResearch,
                    adoptedPolicies = civ.policies.getAdoptedPolicies().toList(),
                    numberOfAdoptedPolicies = civ.policies.getNumberOfAdoptedPolicies(),
                    freePolicies = civ.policies.freePolicies,
                    storedCulture = civ.policies.storedCulture,
                    tileImprovements = civ.gameInfo.tileMap.tileList
                        .filter { it.improvementInProgress != null } // may make you be able to work in other civ's tile?
                        .associate { "${it.position.x},${it.position.y}" to it.improvementInProgress!! },
                )
                Json.encodeToString(choices)
            } catch (_: Exception) { null }
        }

        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.EndTurn(gameId, civName, choicesJson)
        )
    }

    // ──────────────────────────────────────
    //  Apply remote actions (all clients)
    // ──────────────────────────────────────

    private fun applyRemoteAction(packet: GameActionPacket) {
        when (val action = packet.action) {
            is GameAction.MoveAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateMove(packet)
                    return
                }
                debug("Applying remote move: unit %s -> (%s, %s)",
                    action.unitId, action.toX, action.toY)
                applyRemoteMove(action)
            }
            is GameAction.BuyTileAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateBuyTile(packet)
                    return
                }
                debug("Applying remote buy tile: tile (%s,%s)", action.tileX, action.tileY)
                applyRemoteBuyTile(action)
            }
            is GameAction.DeclareWarAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateDeclareWar(packet)
                    return
                }
                debug("Applying remote declare war: %s vs %s",
                    action.civName, action.otherCivName)
                applyRemoteDeclareWar(action)
            }
            is GameAction.AttackAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateAttack(packet)
                    return
                }
                debug("Applying remote attack: unit %s -> (%s, %s)",
                    action.unitId, action.targetX, action.targetY)
                applyRemoteAttack(action)
            }
            is GameAction.CityBombardAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateCityBombard(packet)
                    return
                }
                debug("Applying remote city bombard: city %s -> (%s, %s)",
                    action.cityId, action.targetTileX, action.targetTileY)
                applyRemoteCityBombard(action)
            }
            is GameAction.PromoteAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidatePromote(packet)
                    return
                }
                debug("Applying remote promote: unit %s <- %s",
                    action.unitId, action.promotionName)
                applyRemotePromote(action)
            }
            is GameAction.PurchaseAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidatePurchase(packet)
                    return
                }
                debug("Applying remote purchase: %s in %s",
                    action.constructionName, action.cityId)
                applyRemotePurchase(action)
            }
            is GameAction.AdoptPolicyAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateAdoptPolicy(packet)
                    return
                }
                debug("Applying remote adopt policy: %s for %s",
                    action.policyName, action.civName)
                applyRemoteAdoptPolicy(action)
            }
            is GameAction.FoundPantheonAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateFoundPantheon(packet)
                    return
                }
                debug("Applying remote found pantheon: %s for %s",
                    action.beliefName, action.civName)
                applyRemoteFoundPantheon(action)
            }
            is GameAction.ChooseFreeTechAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateChooseFreeTech(packet)
                    return
                }
                debug("Applying remote choose free tech: %s for %s",
                    action.techName, action.civName)
                applyRemoteChooseFreeTech(action)
            }
            is GameAction.CreateImprovementAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateCreateImprovement(packet)
                    return
                }
                debug("Applying remote create improvement: %s", action.improvementName)
                applyRemoteCreateImprovement(action)
            }
            is GameAction.TriggerUnitAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateTriggerUnit(packet)
                    return
                }
                debug("Applying remote trigger unit: unit %s unique %s",
                    action.unitId, action.uniqueText)
                applyRemoteTriggerUnit(action)
            }
            is GameAction.SpawnUnitAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateSpawnUnit(packet)
                    return
                }
                debug("Applying remote spawn unit: %s for %s",
                    action.unitName, action.civName)
                applyRemoteSpawnUnit(action)
            }
            is GameAction.SendTradeRequestAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateSendTradeRequest(packet)
                    return
                }
                debug("Applying remote send trade request: %s -> %s",
                    action.requestingCiv, action.targetCiv)
                applyRemoteSendTradeRequest(action)
            }
            is GameAction.AcceptTradeAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateAcceptTrade(packet)
                    return
                }
                debug("Applying remote accept trade: %s accepts from %s",
                    action.acceptingCiv, action.requestingCiv)
                applyRemoteAcceptTrade(action)
            }
            is GameAction.TributeGoldAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateTributeGold(packet)
                    return
                }
                debug("Applying remote tribute gold: %s -> %s",
                    action.civName, action.cityStateCivName)
                applyRemoteTributeGold(action)
            }
            is GameAction.TributeWorkerAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateTributeWorker(packet)
                    return
                }
                debug("Applying remote tribute worker: %s -> %s",
                    action.civName, action.cityStateCivName)
                applyRemoteTributeWorker(action)
            }
            is GameAction.GoldGiftAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateGoldGift(packet)
                    return
                }
                debug("Applying remote gold gift: %s -> %s (%s gold)",
                    action.civName, action.cityStateCivName, action.giftAmount)
                applyRemoteGoldGift(action)
            }
            is GameAction.SetProtectionAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateSetProtection(packet)
                    return
                }
                debug("Applying remote set protection: %s %s %s",
                    action.civName, if (action.protect) "pledges" else "revokes", action.cityStateCivName)
                applyRemoteSetProtection(action)
            }
            is GameAction.GiftImprovementAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateGiftImprovement(packet)
                    return
                }
                debug("Applying remote gift improvement: %s -> %s (%s)",
                    action.civName, action.cityStateCivName, action.improvementName)
                applyRemoteGiftImprovement(action)
            }
            is GameAction.DiplomaticMarriageAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateDiplomaticMarriage(packet)
                    return
                }
                debug("Applying remote diplomatic marriage: %s <- %s",
                    action.civName, action.cityStateCivName)
                applyRemoteDiplomaticMarriage(action)
            }
            is GameAction.CompleteFoundReligionAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateCompleteFoundReligion(packet)
                    return
                }
                debug("Applying remote complete found religion: %s -> %s",
                    action.civName, action.religionName)
                applyRemoteCompleteFoundReligion(action)
            }
            is GameAction.CompleteEnhanceReligionAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateCompleteEnhanceReligion(packet)
                    return
                }
                debug("Applying remote complete enhance religion: %s",
                    action.civName)
                applyRemoteCompleteEnhanceReligion(action)
            }
            is GameAction.ConsumeUnitAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateConsumeUnit(packet)
                    return
                }
                debug("Applying remote consume unit: %s type %s",
                    action.unitId, action.actionType)
                applyRemoteConsumeUnit(action)
            }
            is GameAction.UpdateUnitAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateUpdateUnit(packet)
                    return
                }
                debug("Applying remote update unit: %s type %s",
                    action.unitId, action.actionType)
                applyRemoteUpdateUnit(action)
            }
            is GameAction.CaptureCityAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateCaptureCity(packet)
                    return
                }
                debug("Applying remote capture city: city %s by %s",
                    action.cityId, action.civName)
                applyRemoteCaptureCity(action)
            }
            is GameAction.ReturnCapturedUnitAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateReturnCapturedUnit(packet)
                    return
                }
                debug("Applying remote return captured unit: unit %s returnToOwner=%s",
                    action.unitId, action.returnToOwner)
                applyRemoteReturnCapturedUnit(action)
            }
            else -> {}
        }
    }

    // ════════════════════════════════════════
    //  Move
    // ════════════════════════════════════════

    private fun applyRemoteMove(action: GameAction.MoveAction) {
        val unit = findUnitById(action.unitId) ?: run {
            debug("applyRemoteMove: unit %s not found", action.unitId)
            return
        }
        try {
            unit.movement.moveToTile(worldScreen.gameInfo.tileMap[action.toX, action.toY])
        } catch (_: Exception) {
            debug("applyRemoteMove: could not move unit %s to (%s,%s)",
                action.unitId, action.toX, action.toY)
        }
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun hostValidateMove(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.MoveAction ?: return
        val unit = findUnitById(action.unitId)
        if (unit == null || unit.currentMovement <= 0f) {
            debug("Host rejected move: unit %s is invalid or has no movement point left", action.unitId)
            return
        }
        val targetTile = worldScreen.gameInfo.tileMap[action.toX, action.toY]
        if (!unit.movement.canMoveTo(targetTile)) {
            debug("Host rejected move: cannot move to (${action.toX}, ${action.toY})")
            return
        }
        unit.movement.moveToTile(targetTile)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Buy Tile
    // ════════════════════════════════════════

    private fun hostValidateBuyTile(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.BuyTileAction ?: return
        val city = findCityById(action.cityId) ?: return
        val tile = worldScreen.gameInfo.tileMap[action.tileX, action.tileY] ?: return
        if (!city.expansion.canBuyTile(tile)) {
            debug("Host rejected buy tile: tile (%s,%s) for city %s", action.tileX, action.tileY, action.cityId)
            return
        }
        applyRemoteBuyTile(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemoteBuyTile(action: GameAction.BuyTileAction) {
        val city = findCityById(action.cityId) ?: return
        val tile = worldScreen.gameInfo.tileMap[action.tileX, action.tileY] ?: return
        city.expansion.buyTile(tile)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Declare War
    // ════════════════════════════════════════

    private fun applyRemoteDeclareWar(action: GameAction.DeclareWarAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val otherCiv = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.otherCivName } ?: return
        val diplomacyManager = civ.getDiplomacyManager(otherCiv) ?: return
        if (diplomacyManager.canDeclareWar()) {
            diplomacyManager.declareWar()
        }
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun hostValidateDeclareWar(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.DeclareWarAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val otherCiv = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.otherCivName } ?: return
        val diplomacyManager = civ.getDiplomacyManager(otherCiv) ?: return
        if (!diplomacyManager.canDeclareWar()) {
            debug("Host rejected declare war: %s vs %s", action.civName, action.otherCivName)
            return
        }
        diplomacyManager.declareWar()
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Attack
    // ════════════════════════════════════════

    private fun applyRemoteAttack(action: GameAction.AttackAction) {
        val tileMap = worldScreen.gameInfo.tileMap
        val unit = findUnitById(action.unitId) ?: return
        if (!unit.canAttack()) return
        val attackableTile = TargetHelper
            .getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
            .firstOrNull { it.tileToAttack == tileMap[action.targetX, action.targetY] } ?: return
        val attacker = MapUnitCombatant(unit)
        if (!Battle.movePreparingAttack(attacker, attackableTile)) return
        val (damageToDefender, damageToAttacker) = Battle.attackOrNuke(attacker, attackableTile)
        val defender = attackableTile.combatant
        if (defender != null) worldScreen.battleAnimationDeferred(attacker, damageToAttacker, defender, damageToDefender)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun hostValidateAttack(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.AttackAction ?: return
        val tileMap = worldScreen.gameInfo.tileMap
        val unit = findUnitById(action.unitId)
        if (unit == null || !unit.canAttack()) {
            debug("Host rejected attack: unit %s is invalid or cannot attack", action.unitId)
            return
        }
        val attackableTile = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
            .firstOrNull { it.tileToAttack == tileMap[action.targetX, action.targetY] }
        if (attackableTile == null) {
            debug("Host rejected attack: no valid target at (%s, %s)", action.targetX, action.targetY)
            return
        }
        val attacker = MapUnitCombatant(unit)
        if (!Battle.movePreparingAttack(attacker, attackableTile)) return
        Battle.attackOrNuke(attacker, attackableTile)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  City Bombard
    // ════════════════════════════════════════

    private fun applyRemoteCityBombard(action: GameAction.CityBombardAction) {
        val tileMap = worldScreen.gameInfo.tileMap
        val targetTile = tileMap[action.targetTileX, action.targetTileY]
        val city = findCityById(action.cityId) ?: return
        if (!city.canBombard()) return
        val attacker = CityCombatant(city)
        val attackableTile = AttackableTile(attacker.getTile(), targetTile, 0f,
            getMapCombatantOfTile(targetTile))
        if (!Battle.movePreparingAttack(attacker, attackableTile)) return
        val defender = attackableTile.combatant
        val (damageToDefender, damageToAttacker) = Battle.attackOrNuke(attacker, attackableTile)
        if (defender != null) {
            worldScreen.battleAnimationDeferred(attacker, damageToAttacker, defender, damageToDefender)
        }
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun hostValidateCityBombard(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.CityBombardAction ?: return
        val tileMap = worldScreen.gameInfo.tileMap
        val targetTile = tileMap[action.targetTileX, action.targetTileY]
        val city = findCityById(action.cityId) ?: return
        if (!city.canBombard()) return
        val attacker = CityCombatant(city)
        val attackableTile = AttackableTile(attacker.getTile(), targetTile, 0f,
            getMapCombatantOfTile(targetTile))
        if (!Battle.movePreparingAttack(attacker, attackableTile)) return
        Battle.attackOrNuke(attacker, attackableTile)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  ConsumeUnit — dispatches by actionType
    // ════════════════════════════════════════

    /** Central handler for all unit-consuming actions.
     *  Both host-validate and remote-apply paths converge here.
     *  Returns true if the action was applied. */
    private fun executeConsumeUnit(action: GameAction.ConsumeUnitAction): Boolean {
        val unit = findUnitById(action.unitId) ?: return false
        if (unit.isDestroyed || !unit.hasMovement()) return false
        return when (action.actionType) {
            "FoundCity" -> {
                val tile = unit.currentTile
                if (tile.isCityCenter() || !tile.canBeSettled(unit.civ)) return false
                unit.civ.addCity(tile.position, unit)
                unit.destroy()
                true
            }
            // Delegate to original game logic — no manual reimplementation
            "HurryResearch" -> UnitActions.invokeUnitAction(unit, UnitActionType.HurryResearch)
            "HurryPolicy" -> UnitActions.invokeUnitAction(unit, UnitActionType.HurryPolicy)
            "HurryWonder" -> UnitActions.invokeUnitAction(unit, UnitActionType.HurryWonder)
            "HurryBuilding" -> UnitActions.invokeUnitAction(unit, UnitActionType.HurryBuilding)
            "ConductTradeMission" -> UnitActions.invokeUnitAction(unit, UnitActionType.ConductTradeMission)
            "DisbandUnit" -> {
                unit.disband()
                unit.civ.updateStatsForNextTurn()
                true
            }
            "FoundReligion" -> {
                unit.civ.religionManager.foundReligion(unit)
                unit.consume()
                true
            }
            "EnhanceReligion" -> {
                unit.civ.religionManager.useProphetForEnhancingReligion(unit)
                unit.consume()
                true
            }
            else -> false
        }
    }

    private fun hostValidateConsumeUnit(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.ConsumeUnitAction ?: return
        if (!executeConsumeUnit(action)) return
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemoteConsumeUnit(action: GameAction.ConsumeUnitAction) {
        executeConsumeUnit(action)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  UpdateUnit — dispatches by actionType
    // ════════════════════════════════════════

    /** Central handler for all unit state-change actions (unit survives). */
    private fun executeUpdateUnit(action: GameAction.UpdateUnitAction): Boolean {
        val unit = findUnitById(action.unitId) ?: return false
        if (unit.isDestroyed) return false
        return when (action.actionType) {
            "Fortify" -> { unit.fortify(); true }
            "FortifyUntilHealed" -> { unit.fortifyUntilHealed(); true }
            "Pillage" -> {
                val tile = unit.currentTile
                if (!tile.canPillageTile() || tile.getImprovementToPillageName() == null) return false
                val pillageAction = UnitActionsPillage.getPillageAction(unit, tile)?.action ?: return false
                pillageAction()
                true
            }
            "Upgrade" -> {
                val upgradeTargetName = action.param ?: return false
                if (!unit.hasMovement()) return false
                val upgradedUnit = unit.civ.getEquivalentUnit(upgradeTargetName) ?: return false
                if (!unit.upgrade.canUpgrade(unitToUpgradeTo = upgradedUnit)) return false
                if (unit.civ.gold < unit.upgrade.getCostOfUpgrade(upgradedUnit)) return false
                unit.upgrade.performUpgrade(upgradedUnit, isFree = false)
                true
            }
            "SpreadReligion" -> {
                val tile = unit.currentTile
                val spreadReligion = UnitActionsReligion.getSpreadReligionActions(unit, tile)
                    .firstOrNull()?.action ?: return false
                spreadReligion()
                true
            }
            "RemoveHeresy" -> {
                val tile = unit.currentTile
                val removeHeresy = UnitActionsReligion.getRemoveHeresyActions(unit, tile)
                    .firstOrNull()?.action ?: return false
                removeHeresy()
                true
            }
            else -> false
        }
    }

    private fun hostValidateUpdateUnit(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.UpdateUnitAction ?: return
        if (!executeUpdateUnit(action)) return
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemoteUpdateUnit(action: GameAction.UpdateUnitAction) {
        executeUpdateUnit(action)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  TriggerUnit — generic unique activation
    // ════════════════════════════════════════

    private fun hostValidateTriggerUnit(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.TriggerUnitAction ?: return
        val unit = findUnitById(action.unitId) ?: return
        if (unit.isDestroyed) return
        // Find the unique by matching its text against all unit uniques
        val unique = unit.getUniques().firstOrNull { it.text == action.uniqueText } ?: return
        val tile = unit.currentTile
        val gameContext = com.unciv.models.ruleset.unique.GameContext(unit.civ, null, unit, tile)
        val triggerFunction = com.unciv.models.ruleset.unique.UniqueTriggerActivation
            .getTriggerFunction(unique, unit.civ, unit = unit, tile = tile) ?: return
        repeat(unique.getUniqueMultiplier(gameContext)) { triggerFunction.invoke() }
        com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers.activateSideEffects(unit, unique)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemoteTriggerUnit(action: GameAction.TriggerUnitAction) {
        val unit = findUnitById(action.unitId) ?: return
        if (unit.isDestroyed) return
        val unique = unit.getUniques().firstOrNull { it.text == action.uniqueText } ?: return
        val tile = unit.currentTile
        val gameContext = com.unciv.models.ruleset.unique.GameContext(unit.civ, null, unit, tile)
        val triggerFunction = com.unciv.models.ruleset.unique.UniqueTriggerActivation
            .getTriggerFunction(unique, unit.civ, unit = unit, tile = tile) ?: return
        repeat(unique.getUniqueMultiplier(gameContext)) { triggerFunction.invoke() }
        com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers.activateSideEffects(unit, unique)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    /** Get the [ICombatant] on a tile for city bombardment target resolution */
    private fun getMapCombatantOfTile(tile: Tile): ICombatant? {
        return (tile.getUnits().firstOrNull()?.let { MapUnitCombatant(it) }
            ?: tile.getCity()?.let { CityCombatant(it) })
    }

    // ════════════════════════════════════════
    //  Capture City
    // ════════════════════════════════════════

    private fun hostValidateCaptureCity(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.CaptureCityAction ?: return
        val city = findCityById(action.cityId) ?: run {
            debug("Host rejected capture: city %s not found", action.cityId)
            return
        }
        val conquerer = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName }
        if (conquerer == null) {
            debug("Host rejected capture: civ %s not found", action.civName)
            return
        }
        if (city.civ.civName == action.civName) return // déjà vu
        applyRemoteCaptureCity(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteCaptureCity(action: GameAction.CaptureCityAction) {
        val city = findCityById(action.cityId) ?: return
        val conquerer = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        if (city.civ.civName == action.civName) return // déjà vu — already under this civ

        when (action.mode) {
            "Puppet" -> city.puppetCity(conquerer)
            "Annex" -> {
                city.puppetCity(conquerer)
                city.annexCity()
            }
            "Raze" -> {
                city.puppetCity(conquerer)
                city.annexCity()
                city.isBeingRazed = true
            }
            "Liberate" -> city.liberateCity(conquerer)
            "Destroy" -> city.destroyCity(true)
        }
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Return Captured Unit (barbarian settler rescue)
    // ════════════════════════════════════════

    private fun hostValidateReturnCapturedUnit(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.ReturnCapturedUnitAction ?: return
        val unit = findUnitById(action.unitId) ?: return
        if (unit.isDestroyed) return
        if (unit.originalOwningCiv == null) return
        applyRemoteReturnCapturedUnit(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteReturnCapturedUnit(action: GameAction.ReturnCapturedUnitAction) {
        val unit = findUnitById(action.unitId) ?: return
        if (unit.isDestroyed) return
        val tile = unit.currentTile
        val captor = unit.civ

        if (action.returnToOwner) {
            val originalOwner = unit.originalOwningCiv ?: return
            val unitName = unit.baseUnit.name
            unit.destroy()
            val closestCity = originalOwner.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
            if (closestCity != null) {
                originalOwner.units.placeUnitNearTile(closestCity.location.toHexCoord(), unitName)
            }
            if (originalOwner.isCityState) {
                originalOwner.getDiplomacyManagerOrMeet(captor).addInfluence(45f)
            } else if (originalOwner.isMajorCiv()) {
                originalOwner.getDiplomacyManagerOrMeet(captor)
                    .setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 20f)
            }
            val notificationSequence = sequence {
                yield(LocationAction(tile.position))
                if (closestCity != null)
                    yield(LocationAction(closestCity.location))
                yield(DiplomacyAction(captor))
                yield(CivilopediaAction("Tutorial/Barbarians"))
            }
            originalOwner.addNotification(
                "Your captured [${unitName}] has been returned by [${captor.civName}]",
                notificationSequence, NotificationCategory.Diplomacy,
                NotificationIcon.Trade, unitName, captor.civName
            )
        } else {
            BattleUnitCapture.captureOrConvertToWorker(unit, captor)
        }
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Promote
    // ════════════════════════════════════════

    private fun performPromoteAction(action: GameAction.PromoteAction): Boolean {
        val unit = findUnitById(action.unitId) ?: return false
        if (unit.isDestroyed) return false
        if (unit.promotions.getAvailablePromotions().none { it.name == action.promotionName }) return false
        unit.promotions.addPromotion(action.promotionName)
        return true
    }

    private fun hostValidatePromote(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.PromoteAction ?: return
        if (!performPromoteAction(action)) return
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemotePromote(action: GameAction.PromoteAction) {
        performPromoteAction(action)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Purchase
    // ════════════════════════════════════════

    private fun hostValidatePurchase(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.PurchaseAction ?: return
        val city = findCityById(action.cityId) ?: return
        val civ = city.civ
        val stat = try { Stat.valueOf(action.stat) } catch (_: Exception) { return }
        val construction = city.cityConstructions
            .getConstruction(action.constructionName) as? INonPerpetualConstruction ?: return
        val constructionBuyCost = construction.getStatBuyCost(city, stat) ?: return
        if (!city.cityConstructions.isConstructionPurchaseAllowed(construction, stat, constructionBuyCost)) {
            debug("Host rejected purchase: %s in %s", action.constructionName, action.cityId)
            return
        }
        applyRemotePurchase(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemotePurchase(action: GameAction.PurchaseAction) {
        val city = findCityById(action.cityId) ?: return
        val civ = city.civ
        val stat = try { Stat.valueOf(action.stat) } catch (_: Exception) { return }
        // queuePosition removed (was only for queue ordering, not essential for broadcast)
        // tileX/tileY removed (BuyTileAction covers tile purchases)
        city.cityConstructions.purchaseConstruction(action.constructionName, -1, false, stat, null)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Policy adoption
    // ════════════════════════════════════════

    private fun hostValidateAdoptPolicy(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.AdoptPolicyAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val policy = worldScreen.gameInfo.ruleset.policies[action.policyName] ?: return
        if (!civ.policies.isAdoptable(policy)) return
        applyRemoteAdoptPolicy(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteAdoptPolicy(action: GameAction.AdoptPolicyAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val policy = worldScreen.gameInfo.ruleset.policies[action.policyName] ?: return
        if (civ.policies.isAdopted(policy.name)) return
        civ.policies.adopt(policy)
        civ.policies.shouldOpenPolicyPicker = false
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Choose Free Tech (Great Library, etc.)
    // ════════════════════════════════════════

    private fun hostValidateChooseFreeTech(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.ChooseFreeTechAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val tech = worldScreen.gameInfo.ruleset.technologies[action.techName] ?: return
        if (!civ.tech.canBeResearched(tech.name)) return
        // Decrement on authoritative host state — sender already decremented locally
        civ.tech.freeTechs--
        applyRemoteChooseFreeTech(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteChooseFreeTech(action: GameAction.ChooseFreeTechAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val tech = worldScreen.gameInfo.ruleset.technologies[action.techName] ?: return
        if (civ.tech.isResearched(tech.name)) return
        // Use addTechnology directly — the sender already decremented freeTechs locally
        civ.tech.addTechnology(tech.name)
        civ.tech.updateResearchProgress()
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Pantheon founding
    // ════════════════════════════════════════

    private fun hostValidateFoundPantheon(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.FoundPantheonAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val belief = worldScreen.gameInfo.ruleset.beliefs[action.beliefName] ?: return
        if (civ.religionManager.religionState >= ReligionState.Pantheon) return
        applyRemoteFoundPantheon(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteFoundPantheon(action: GameAction.FoundPantheonAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val belief = worldScreen.gameInfo.ruleset.beliefs[action.beliefName] ?: return
        if (civ.religionManager.religionState >= ReligionState.Pantheon) return
        civ.religionManager.chooseBeliefs(listOf(belief), useFreeBeliefs = true)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Create Improvement (work boat, instant)
    // ════════════════════════════════════════

    private fun hostValidateCreateImprovement(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.CreateImprovementAction ?: return
        val unit = findUnitById(action.unitId) ?: return
        if (unit.isDestroyed || !unit.hasMovement()) return
        val improvement = worldScreen.gameInfo.tileMap.ruleset!!.tileImprovements[action.improvementName] ?: return
        if (!unit.currentTile.improvementFunctions.canBuildImprovement(improvement, unit.cache.state)) return
        applyRemoteCreateImprovement(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteCreateImprovement(action: GameAction.CreateImprovementAction) {
        val unit = findUnitById(action.unitId) ?: return
        if (unit.isDestroyed) return
        val improvement = worldScreen.gameInfo.tileMap.ruleset!!.tileImprovements[action.improvementName] ?: return
        val tile = unit.currentTile
        tile.setImprovement(improvement, unit.civ, unit)
        unit.destroy()
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Spawn Unit (great person picker, etc.)
    // ════════════════════════════════════════

    private fun hostValidateSpawnUnit(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.SpawnUnitAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        if (civ.cities.isEmpty()) return
        val baseUnit = worldScreen.gameInfo.ruleset.units[action.unitName] ?: return
        civ.getEquivalentUnit(baseUnit)
        applyRemoteSpawnUnit(action)
        // Apply great person counter decrements on authoritative host state
        // Non-host already does this locally, so the validated echo must not double-decrement
        if (action.freeGreatPeopleDecrement > 0)
            civ.greatPeople.freeGreatPeople -= action.freeGreatPeopleDecrement
        if (action.mayaLimitedFreeGPDecrement > 0)
            civ.greatPeople.mayaLimitedFreeGP -= action.mayaLimitedFreeGPDecrement
        for (unitName in action.longCountGPPoolRemoval)
            civ.greatPeople.longCountGPPool.remove(unitName)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteSpawnUnit(action: GameAction.SpawnUnitAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        if (civ.cities.isEmpty()) return
        val city = action.cityId?.let { id -> civ.cities.firstOrNull { it.id == id } }
        civ.units.addUnit(action.unitName, city ?: civ.getCapital() ?: civ.cities.firstOrNull())
        // Apply great person counter decrements — these were incremented on both host and non-host
        // by unique triggers from policy adoption / wonder completion, so both must decrement.
        if (action.freeGreatPeopleDecrement > 0)
            civ.greatPeople.freeGreatPeople -= action.freeGreatPeopleDecrement
        if (action.mayaLimitedFreeGPDecrement > 0)
            civ.greatPeople.mayaLimitedFreeGP -= action.mayaLimitedFreeGPDecrement
        for (unitName in action.longCountGPPoolRemoval)
            civ.greatPeople.longCountGPPool.remove(unitName)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ════════════════════════════════════════
    //  Trade / Diplomacy
    // ════════════════════════════════════════

    private fun hostValidateSendTradeRequest(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.SendTradeRequestAction ?: return
        applyRemoteSendTradeRequest(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteSendTradeRequest(action: GameAction.SendTradeRequestAction) {
        val targetCiv = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.targetCiv } ?: return
        targetCiv.tradeRequests.removeAll { it.requestingCiv == action.requestingCiv }
        targetCiv.tradeRequests.add(
            TradeRequest(action.requestingCiv, action.trade.toTrade())
        )
    }

    private fun hostValidateAcceptTrade(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.AcceptTradeAction ?: return
        applyRemoteAcceptTrade(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteAcceptTrade(action: GameAction.AcceptTradeAction) {
        val acceptingCiv = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.acceptingCiv } ?: return
        val requestingCiv = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.requestingCiv } ?: return
        val trade = action.trade.toTrade()
        val tradeLogic = TradeLogic(acceptingCiv, requestingCiv)
        tradeLogic.currentTrade.set(trade)
        tradeLogic.acceptTrade()
        acceptingCiv.tradeRequests.removeAll { it.requestingCiv == action.requestingCiv }
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  City-State: Tribute Gold
    // ──────────────────────────────────────

    private fun hostValidateTributeGold(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.TributeGoldAction ?: return
        applyRemoteTributeGold(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteTributeGold(action: GameAction.TributeGoldAction) {
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateCivName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        cs.cityStateFunctions.tributeGold(civ)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  City-State: Tribute Worker
    // ──────────────────────────────────────

    private fun hostValidateTributeWorker(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.TributeWorkerAction ?: return
        applyRemoteTributeWorker(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteTributeWorker(action: GameAction.TributeWorkerAction) {
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateCivName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        cs.cityStateFunctions.tributeWorker(civ)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  City-State: Gold Gift
    // ──────────────────────────────────────

    private fun hostValidateGoldGift(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.GoldGiftAction ?: return
        applyRemoteGoldGift(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteGoldGift(action: GameAction.GoldGiftAction) {
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateCivName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        cs.cityStateFunctions.receiveGoldGift(civ, action.giftAmount)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  City-State: Set Protection
    // ──────────────────────────────────────

    private fun hostValidateSetProtection(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.SetProtectionAction ?: return
        applyRemoteSetProtection(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteSetProtection(action: GameAction.SetProtectionAction) {
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateCivName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        if (action.protect) cs.cityStateFunctions.addProtectorCiv(civ)
        else cs.cityStateFunctions.removeProtectorCiv(civ)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  City-State: Gift Improvement
    // ──────────────────────────────────────

    private fun hostValidateGiftImprovement(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.GiftImprovementAction ?: return
        applyRemoteGiftImprovement(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteGiftImprovement(action: GameAction.GiftImprovementAction) {
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateCivName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val tile = worldScreen.gameInfo.tileMap[action.tileX, action.tileY] ?: return
        val improvement = worldScreen.gameInfo.tileMap.ruleset!!.tileImprovements[action.improvementName] ?: return
        civ.addGold(-200)
        tile.stopWorkingOnImprovement()
        tile.setImprovement(improvement)
        cs.cache.updateCivResources()
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  City-State: Diplomatic Marriage
    // ──────────────────────────────────────

    private fun hostValidateDiplomaticMarriage(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.DiplomaticMarriageAction ?: return
        applyRemoteDiplomaticMarriage(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteDiplomaticMarriage(action: GameAction.DiplomaticMarriageAction) {
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateCivName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        cs.cityStateFunctions.diplomaticMarriage(civ)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Religion: Complete Found Religion (picker completion)
    // ──────────────────────────────────────

    private fun hostValidateCompleteFoundReligion(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.CompleteFoundReligionAction ?: return
        applyRemoteCompleteFoundReligion(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteCompleteFoundReligion(action: GameAction.CompleteFoundReligionAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        civ.religionManager.foundReligion(action.displayName, action.religionName)
        val beliefs = action.beliefNames.mapNotNull { name ->
            worldScreen.gameInfo.ruleset.beliefs[name]
        }
        civ.religionManager.chooseBeliefs(beliefs)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Religion: Complete Enhance Religion (picker completion)
    // ──────────────────────────────────────

    private fun hostValidateCompleteEnhanceReligion(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.CompleteEnhanceReligionAction ?: return
        applyRemoteCompleteEnhanceReligion(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteCompleteEnhanceReligion(action: GameAction.CompleteEnhanceReligionAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val beliefs = action.beliefNames.mapNotNull { name ->
            worldScreen.gameInfo.ruleset.beliefs[name]
        }
        civ.religionManager.chooseBeliefs(beliefs)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Host-only: end-turn tracking
    // ──────────────────────────────────────

    fun isHost(): Boolean {
        val hostId = worldScreen.gameInfo.gameParameters.hostPlayerId
        return UncivGame.Current.settings.multiplayer.getUserId() == hostId
    }

    /**
     * Called on the **host** when they receive a [Response.PlayerEndedTurn] relayed
     * from the server. The server broadcasts this to ALL subscribers, including
     * the host. The host tracks who's done and triggers turn advancement when
     * all are finished.
     */
    private fun onRemotePlayerEndedTurn(response: Response.PlayerEndedTurn) {
        if (!isHost()) return  // non-hosts don't track this

        val finishedPlayers = worldScreen.gameInfo.simultaneousTurnState.playersFinishedTurn
        if (response.civName !in finishedPlayers)
            finishedPlayers.add(response.civName)

        // Store pending choices for batch sync
        if (response.choicesJson != null)
            pendingChoices[response.civName] = response.choicesJson

        val allHumans = worldScreen.gameInfo.civilizations
            .filter { it.isAlive() && it.playerType == PlayerType.Human }
            .map { it.civName }
            .toSet()

        debug("Player %s ended turn (%d/%d)", response.civName,
            finishedPlayers.size, allHumans.size)

        if (allHumans.all { it in finishedPlayers })
            hostAdvanceTurn()
    }

    /** Host: run turn advancement, upload, and broadcast */
    private fun hostAdvanceTurn() {
        debug("All players finished — advancing turn")
        Concurrency.runOnNonDaemonThreadPool("SimultaneousTurnAdvance") {
            val gameClone = worldScreen.gameInfo.clone()
            gameClone.setTransients()

            // Apply batched civ choices (constructions, tech) before advancing
            for ((civName, choicesJson) in pendingChoices) {
                try {
                    val choices = Json.decodeFromString<CivTurnChoices>(choicesJson)
                    val civ = gameClone.civilizations.firstOrNull { it.civName == choices.civName } ?: continue
                    for ((cityId, construction) in choices.cityConstructions) {
                        val city = civ.cities.firstOrNull { it.id == cityId } ?: continue
                        if (construction != null) city.cityConstructions.setCurrentConstruction(construction)
                        else city.cityConstructions.constructionQueue.clear()
                    }
                    if (choices.currentTechResearch != null) {
                        civ.tech.techsToResearch.clear()
                        civ.tech.techsToResearch.add(choices.currentTechResearch)
                    }
                    // Apply policies
                    if (choices.adoptedPolicies.isNotEmpty()) {
                        civ.policies.applyChoices(
                            policies = choices.adoptedPolicies,
                            numberOfAdopted = choices.numberOfAdoptedPolicies,
                            free = choices.freePolicies,
                            culture = choices.storedCulture,
                        )
                    }
                    // Apply tile improvements (worker builds) from non-host
                    for ((coordStr, improvement) in choices.tileImprovements) {
                        val parts = coordStr.split(',')
                        if (parts.size != 2) continue
                        val x = parts[0].toIntOrNull() ?: continue
                        val y = parts[1].toIntOrNull() ?: continue
                        val tile = gameClone.tileMap.tileList.firstOrNull {
                            it.position.x == x && it.position.y == y
                        } ?: continue
                        // Only queue if not already queued or completed
                        if (tile.improvementInProgress != improvement) {
                            val improvementObj = tile.ruleset.tileImprovements[improvement]
                            if (improvementObj != null) {
                                // Try full calculation using the worker on this tile
                                val worker = tile.civilianUnit
                                if (worker != null && worker.civ.civName == civ.civName) {
                                    tile.queueImprovement(improvementObj, civ, worker)
                                } else {
                                    // Fallback: raw turns * game speed modifier
                                    val base = improvementObj.turnsToBuild
                                    val adjusted = if (base <= 0) 1
                                    else (gameClone.speed.improvementBuildLengthModifier * base).roundToInt().coerceAtLeast(1)
                                    tile.queueImprovement(improvement, adjusted)
                                }
                            } else tile.queueImprovement(improvement, 1)
                        }
                    }
                } catch (e: Exception) {
                    debug("Failed to apply choices for %s: %s", civName, e.message)
                }
            }
            pendingChoices.clear()

            SimultaneousTurnProcessor.processAdvance(gameClone)

            // Upload the new game state (existing pipeline — suspend function, OK in coroutine)
            UncivGame.Current.onlineMultiplayer.updateGame(gameClone)

            // Broadcast to all that the turn has advanced
            ChatWebSocket.requestMessageSend(
                com.unciv.logic.multiplayer.chat.Message.TurnAdvance(
                    gameId = gameId,
                    newTurns = gameClone.turns,
                )
            )

            // Load the new state locally (host)
            UncivGame.Current.loadGame(gameClone)

            // Reset tracking for the new turn
            resetTurnTracking()
        }
    }

    /** Called when TurnAdvanced is received — non-host clients download the new game */
    private fun onTurnAdvanced(response: Response.TurnAdvanced) {
        if (isHost()) return // host already loaded it
        debug("Turn advanced to %s, downloading new game state", response.newTurns)
        Concurrency.runOnNonDaemonThreadPool("SimultaneousDownloadGame") {
            UncivGame.Current.onlineMultiplayer.downloadGame(response.gameId)
        }
    }

    /** Reset the turn-end tracking (called when a new turn starts) */
    fun resetTurnTracking() {
        worldScreen.gameInfo.simultaneousTurnState.reset()
        pendingChoices.clear()
        hasEndedTurn = false
    }
}