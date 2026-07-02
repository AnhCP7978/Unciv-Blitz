package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.multiplayer.chat.ChatWebSocket
import com.unciv.logic.multiplayer.chat.ChatStore
import com.unciv.logic.multiplayer.chat.Response
import com.unciv.models.stats.Stat
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.debug
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.ruleset.Belief
import com.unciv.ui.screens.worldscreen.bottombar.BattleTableHelpers.battleAnimationDeferred
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeRequest
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

// ──────────────────────────────────────
//  Trade data ↔ domain object conversion
// ──────────────────────────────────────

private fun TradeOffer.toTradeOfferData() = TradeOfferData(name, type.name, amount, duration)
private fun TradeOfferData.toTradeOffer() = TradeOffer(name, TradeOfferType.valueOf(type), amount, duration)

private fun Trade.toTradeData() = TradeData(
    theirOffers.map { it.toTradeOfferData() },
    ourOffers.map { it.toTradeOfferData() }
)
private fun TradeData.toTrade(): Trade {
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
            is GameAction.FoundCityAction -> applyRemoteFoundCity(action)
            is GameAction.BuyTileAction -> applyRemoteBuyTile(action)
            is GameAction.DeclareWarAction -> applyRemoteDeclareWar(action)
            is GameAction.AttackAction -> applyRemoteAttack(action)
            is GameAction.CityBombardAction -> applyRemoteCityBombard(action)
            is GameAction.GreatPersonAction -> executeGreatPersonAction(action)
            is GameAction.UpgradeAction -> applyRemoteUpgrade(action)
            is GameAction.PromoteAction -> applyRemotePromote(action)
            is GameAction.PurchaseAction -> applyRemotePurchase(action)
            is GameAction.FortifyAction -> applyRemoteFortify(action)
            is GameAction.PillageAction -> applyRemotePillage(action)
            is GameAction.AdoptPolicyAction -> applyRemoteAdoptPolicy(action)
            is GameAction.DisbandUnitAction -> applyRemoteDisbandUnit(action)
            is GameAction.FoundPantheonAction -> applyRemoteFoundPantheon(action)
            is GameAction.CreateImprovementAction -> applyRemoteCreateImprovement(action)
            is GameAction.SendTradeRequestAction -> applyRemoteSendTradeRequest(action)
            is GameAction.RetractTradeRequestAction -> applyRemoteRetractTradeRequest(action)
            is GameAction.AcceptTradeAction -> applyRemoteAcceptTrade(action)
            is GameAction.DeclineTradeRequestAction -> applyRemoteDeclineTradeRequest(action)
            else -> {}
        }
    }

    fun sendMoveAction(unitId: Int, toX: Int, toY: Int, civName: String) =
        sendGameAction(GameAction.MoveAction(unitId, toX, toY, civName))

    fun sendFoundCityAction(unitId: Int, civName: String) =
        sendGameAction(GameAction.FoundCityAction(unitId, civName))

    fun sendBuyTileAction(cityId: String, tileX: Int, tileY: Int, civName: String) =
        sendGameAction(GameAction.BuyTileAction(cityId, tileX, tileY, civName))

    fun sendDeclareWarAction(civName: String, otherCivName: String) =
        sendGameAction(GameAction.DeclareWarAction(otherCivName, civName))

    fun sendAttackAction(unitId: Int, targetTileX: Int, targetTileY: Int, civName: String) =
        sendGameAction(GameAction.AttackAction(unitId, targetTileX, targetTileY, civName))

    fun sendCityBombardAction(cityId: String, targetTileX: Int, targetTileY: Int, civName: String) =
        sendGameAction(GameAction.CityBombardAction(cityId, targetTileX, targetTileY, civName))

    fun sendGreatPersonAction(unitId: Int, actionType: String, civName: String) =
        sendGameAction(GameAction.GreatPersonAction(unitId, actionType, civName))

    fun sendUpgradeAction(unitId: Int, upgradeToUnitName: String, civName: String) =
        sendGameAction(GameAction.UpgradeAction(unitId, upgradeToUnitName, civName))

    fun sendPromoteAction(unitId: Int, promotionName: String, civName: String) =
        sendGameAction(GameAction.PromoteAction(unitId, promotionName, civName))

    fun sendFortifyAction(unitId: Int, fortifyType: String, civName: String) =
        sendGameAction(GameAction.FortifyAction(unitId, fortifyType, civName))

    fun sendDisbandUnitAction(unitId: Int, civName: String) =
        sendGameAction(GameAction.DisbandUnitAction(unitId, civName))

    fun sendPillageAction(unitId: Int, civName: String) =
        sendGameAction(GameAction.PillageAction(unitId, civName))

    fun sendAdoptPolicyAction(policyName: String, civName: String) =
        sendGameAction(GameAction.AdoptPolicyAction(policyName, civName))

    fun sendFoundPantheonAction(beliefName: String, civName: String) =
        sendGameAction(GameAction.FoundPantheonAction(beliefName, civName))

    fun sendCreateImprovementAction(unitId: Int, improvementName: String, civName: String) =
        sendGameAction(GameAction.CreateImprovementAction(unitId, improvementName, civName))

    fun sendPurchaseAction(
        constructionName: String, cityId: String, queuePosition: Int = -1,
        stat: String, tileX: Int? = null, tileY: Int? = null, civName: String,
    ) = sendGameAction(GameAction.PurchaseAction(constructionName, cityId, queuePosition, stat, tileX, tileY, civName))

    fun sendSendTradeRequestAction(trade: Trade, targetCiv: String, civName: String) =
        sendGameAction(GameAction.SendTradeRequestAction(civName, targetCiv, trade.toTradeData()))

    fun sendRetractTradeRequestAction(targetCiv: String, civName: String) =
        sendGameAction(GameAction.RetractTradeRequestAction(civName, targetCiv))

    fun sendAcceptTradeAction(trade: Trade, requestingCiv: String, civName: String) =
        sendGameAction(GameAction.AcceptTradeAction(civName, requestingCiv, trade.toTradeData()))

    fun sendDeclineTradeRequestAction(trade: Trade, requestingCiv: String, civName: String) =
        sendGameAction(GameAction.DeclineTradeRequestAction(civName, requestingCiv, trade.toTradeData()))

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
                        .filter { it.improvementInProgress != null && it.getOwner() == civ }
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
            is GameAction.FoundCityAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateFoundCity(packet)
                    return
                }
                debug("Applying remote found city: unit %s", action.unitId)
                applyRemoteFoundCity(action)
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
                    action.unitId, action.targetTileX, action.targetTileY)
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
            is GameAction.GreatPersonAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateGreatPerson(packet)
                    return
                }
                debug("Applying remote great person action: unit %s type %s",
                    action.unitId, action.actionType)
                executeGreatPersonAction(action)
            }
            is GameAction.UpgradeAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateUpgrade(packet)
                    return
                }
                debug("Applying remote upgrade: unit %s -> %s",
                    action.unitId, action.upgradeToUnitName)
                applyRemoteUpgrade(action)
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
            is GameAction.FortifyAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateFortify(packet)
                    return
                }
                debug("Applying remote fortify: unit %s type %s",
                    action.unitId, action.fortifyType)
                applyRemoteFortify(action)
            }
            is GameAction.PillageAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidatePillage(packet)
                    return
                }
                debug("Applying remote pillage: unit %s",
                    action.unitId)
                applyRemotePillage(action)
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
            is GameAction.DisbandUnitAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateDisbandUnit(packet)
                    return
                }
                debug("Applying remote disband: unit %s for %s",
                    action.unitId, action.civName)
                applyRemoteDisbandUnit(action)
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
            is GameAction.CreateImprovementAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateCreateImprovement(packet)
                    return
                }
                debug("Applying remote create improvement: %s", action.improvementName)
                applyRemoteCreateImprovement(action)
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
            is GameAction.RetractTradeRequestAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateRetractTradeRequest(packet)
                    return
                }
                debug("Applying remote retract trade request: %s -> %s",
                    action.requestingCiv, action.targetCiv)
                applyRemoteRetractTradeRequest(action)
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
            is GameAction.DeclineTradeRequestAction -> {
                if (!packet.validated) {
                    if (isHost()) hostValidateDeclineTradeRequest(packet)
                    return
                }
                debug("Applying remote decline trade request: %s declines %s",
                    action.decliningCiv, action.requestingCiv)
                applyRemoteDeclineTradeRequest(action)
            }
            else -> {}
        }
    }

    private fun applyRemoteMove(action: GameAction.MoveAction) {
        val tileMap = worldScreen.gameInfo.tileMap
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName }
        val unit = civ?.units?.getUnitById(action.unitId)
            ?: run {
                debug("applyRemoteMove: unit %s not found for civ %s", action.unitId, action.civName)
                return
            }
        try {
            unit.movement.moveToTile(tileMap[action.toX, action.toY])
        } catch (_: Exception) {
            debug("applyRemoteMove: could not move unit %s to (%s,%s)",
                action.unitId, action.toX, action.toY)
        }
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    /**
     * Host-only: validates a non-host player's move on the authoritative game state.
     * If valid, applies it locally and echoes the [GameActionPacket] with
     * [GameActionPacket.validated] = true so all clients apply it.
     */
    private fun hostValidateMove(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.MoveAction ?: return
        val tileMap = worldScreen.gameInfo.tileMap
        val civ = tileMap.gameInfo.civilizations.firstOrNull { it.civName == action.civName }
        val unit = civ?.units?.getUnitById(action.unitId)
        if (unit == null || unit.currentMovement <= 0f) {
            debug("Host rejected move: unit %s is invalid or has no movement point left", action.unitId)
            return
        }

        val targetTile = tileMap[action.toX, action.toY]
        if (!unit.movement.canMoveTo(targetTile)) {
            debug("Host rejected move: cannot move to (${action.toX}, ${action.toY})")
            return
        }

        // Apply the move on the authoritative state
        unit.movement.moveToTile(targetTile)

        // Echo back with validated=true so all clients apply it
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )

        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemoteFoundCity(action: GameAction.FoundCityAction) {
        val unit = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName }
            ?.units?.getUnitById(action.unitId) ?: return

        val tile = unit.currentTile
        if (tile.isCityCenter()) return  // already settled (host already applied)
        unit.civ.addCity(tile.position, unit)
        unit.destroy()
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun hostValidateFoundCity(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.FoundCityAction ?: return
        val unit = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName }
            ?.units?.getUnitById(action.unitId)
        if (unit == null) {
            debug("Host rejected found city: unit %s not found", action.unitId)
            return
        }
        val tile = unit.currentTile
        if (tile.isCityCenter()) return // already settled
        if (!unit.hasMovement() || !tile.canBeSettled(unit.civ)) {
            debug("Host rejected found city: unit %s at (%s, %s) cannot settle",
                action.unitId, tile.position.x, tile.position.y)
            return
        }
        // Apply on authoritative state
        unit.civ.addCity(tile.position, unit)
        unit.destroy()
        // Echo back with validated=true so all clients apply it
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Buy Tile
    // ──────────────────────────────────────

    private fun hostValidateBuyTile(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.BuyTileAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val city = civ.cities.firstOrNull { it.id == action.cityId } ?: return
        val tile = worldScreen.gameInfo.tileMap[action.tileX, action.tileY] ?: return
        if (!city.expansion.canBuyTile(tile)) {
            debug("Host rejected buy tile: tile (%s,%s) for city %s", action.tileX, action.tileY, action.cityId)
            return
        }
        // Apply on authoritative state — host never receives the validated echo
        applyRemoteBuyTile(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemoteBuyTile(action: GameAction.BuyTileAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val city = civ.cities.firstOrNull { it.id == action.cityId } ?: return
        val tile = worldScreen.gameInfo.tileMap[action.tileX, action.tileY] ?: return
        city.expansion.buyTile(tile)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Declare War
    // ──────────────────────────────────────

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

    // ──────────────────────────────────────
    //  Attack
    // ──────────────────────────────────────

    private fun applyRemoteAttack(action: GameAction.AttackAction) {
        val tileMap = worldScreen.gameInfo.tileMap
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName }
        val unit = civ?.units?.getUnitById(action.unitId) ?: return
        if (!unit.canAttack()) return // already attacked (idempotency for host echo)

        val attackableTile = TargetHelper
            .getAttackableEnemies(unit, unit.movement.getDistanceToTiles())
            .firstOrNull { it.tileToAttack == tileMap[action.targetTileX, action.targetTileY] } ?: return
        val attacker = MapUnitCombatant(unit)
        if (!Battle.movePreparingAttack(attacker, attackableTile)) return
        val (damageToDefender, damageToAttacker) = Battle.attackOrNuke(attacker, attackableTile)

        val defender = attackableTile.combatant // Must check for null since [worldScreen.battleAnimationDeferred] expect ICombatant, not ICombatant?
        if (defender != null) worldScreen.battleAnimationDeferred(attacker, damageToAttacker, defender, damageToDefender) // Animation call for cosmetic

        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun hostValidateAttack(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.AttackAction ?: return
        val tileMap = worldScreen.gameInfo.tileMap

        // Find the attacking unit
        val civ = tileMap.gameInfo.civilizations.firstOrNull { it.civName == action.civName }
        val unit = civ?.units?.getUnitById(action.unitId)
        if (unit == null || !unit.canAttack()) {
            debug("Host rejected attack: unit %s is invalid or cannot attack", action.unitId)
            return // (idempotent — if already attacked this turn, reject)
        }
        // Verify the defender is actually within range and attackable from the unit's current position
        val attackableTile = TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles()).firstOrNull { it.tileToAttack == tileMap[action.targetTileX, action.targetTileY] }
        if (attackableTile == null) {
            debug("Host rejected attack: no valid target at (%s, %s)", action.targetTileX, action.targetTileY)
            return
        }
        // Apply battle on authoritative state
        val attacker = MapUnitCombatant(unit)
        if (!Battle.movePreparingAttack(attacker, attackableTile)) return
        Battle.attackOrNuke(attacker, attackableTile)

        // Echo validated
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  City Bombard
    // ──────────────────────────────────────

    private fun applyRemoteCityBombard(action: GameAction.CityBombardAction) {
        val tileMap = worldScreen.gameInfo.tileMap
        val targetTile = tileMap[action.targetTileX, action.targetTileY]
        val city = worldScreen.gameInfo.civilizations.asSequence()
            .flatMap { it.cities.asSequence() }
            .firstOrNull { it.id == action.cityId } ?: return
        if (!city.canBombard()) return  // already bombarded (idempotency)

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
        val city = worldScreen.gameInfo.civilizations.asSequence()
            .flatMap { it.cities.asSequence() }
            .firstOrNull { it.id == action.cityId } ?: return
        if (!city.canBombard()) return

        val attacker = CityCombatant(city)
        val attackableTile = AttackableTile(attacker.getTile(), targetTile, 0f,
            getMapCombatantOfTile(targetTile))
        if (!Battle.movePreparingAttack(attacker, attackableTile)) return
        Battle.attackOrNuke(attacker, attackableTile)
        // Echo validated
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Great Person
    // ──────────────────────────────────────

    /** Execute a great person action locally (used by both validate and apply) */
    private fun executeGreatPersonAction(action: GameAction.GreatPersonAction): Boolean {
        val tileMap = worldScreen.gameInfo.tileMap
        val unit = tileMap.values.asSequence()
            .flatMap { it.getUnits().asSequence() }
            .firstOrNull { it.id == action.unitId && it.civ.civName == action.civName } ?: return false
        if (!unit.hasMovement() || unit.isDestroyed) return false

        when (action.actionType) {
            "HurryResearch" -> hurryResearchEffect(unit)
            "HurryPolicy" -> hurryPolicyEffect(unit)
            "HurryWonder", "HurryBuilding" -> hurryWonderOrBuildingEffect(unit)
            "ConductTradeMission" -> tradeMissionEffect(unit)
            else -> return false
        }
        unit.consume()
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
        return true
    }

    /** Host validates a great person action by executing on authoritative state */
    private fun hostValidateGreatPerson(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.GreatPersonAction ?: return
        if (!executeGreatPersonAction(action)) return
        // Echo validated
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun hurryResearchEffect(unit: MapUnit) {
        val civ = unit.civ
        civ.tech.addScience(civ.tech.getScienceFromGreatScientist())
    }

    private fun hurryPolicyEffect(unit: MapUnit) {
        unit.civ.policies.addCulture(unit.civ.policies.getCultureFromGreatWriter())
    }

    private fun hurryWonderOrBuildingEffect(unit: MapUnit) {
        val tile = unit.currentTile
        if (!tile.isCityCenter()) return
        val city = tile.getCity() ?: return
        val production = ((300 + 30 * city.population.population) * unit.civ.gameInfo.speed.productionCostModifier).toInt()
        city.cityConstructions.addProductionPoints(production)
        city.cityConstructions.constructIfEnough()
    }

    private fun tradeMissionEffect(unit: MapUnit) {
        val tile = unit.currentTile
        val targetCiv = tile.owningCity?.civ ?: return
        if (!targetCiv.isCityState) return
        var goldEarned = (350 + 50 * unit.civ.getEraNumber()) * unit.civ.gameInfo.speed.goldCostModifier
        var influenceEarned = 0f
        for (goldUnique in unit.getMatchingUniques(
            com.unciv.models.ruleset.unique.UniqueType.PercentGoldFromTradeMissions,
            checkCivInfoUniques = true)) {
            goldEarned *= (1 + goldUnique.params[0].toFloat() / 100)
            influenceEarned = goldUnique.params[0].toFloat()
        }
        unit.civ.addGold(goldEarned.toInt())
        targetCiv.getDiplomacyManager(unit.civ)?.addInfluence(influenceEarned)
    }

    /** Get the [ICombatant] on a tile for city bombardment target resolution */
    private fun getMapCombatantOfTile(tile: Tile): ICombatant? {
        return (tile.getUnits().firstOrNull()?.let { MapUnitCombatant(it) }
            ?: tile.getCity()?.let { CityCombatant(it) })
    }

    // ──────────────────────────────────────
    //  Upgrade
    // ──────────────────────────────────────

    /** Perform a unit upgrade locally (shared by host-validate and remote-apply) */
    private fun performUpgradeAction(action: GameAction.UpgradeAction): Boolean {
        // Search all tiles for the unit by id
        val tileMap = worldScreen.gameInfo.tileMap
        val unit = tileMap.tileList.asSequence()
            .flatMap { it.getUnits().asSequence() }
            .firstOrNull { it.id == action.unitId && it.civ.civName == action.civName } ?: return false
        if (unit.isDestroyed || !unit.hasMovement()) return false
        val upgradedUnit = unit.civ.getEquivalentUnit(action.upgradeToUnitName)
        if (!unit.upgrade.canUpgrade(unitToUpgradeTo = upgradedUnit)) return false
        if (unit.civ.gold < unit.upgrade.getCostOfUpgrade(upgradedUnit)) return false
        unit.upgrade.performUpgrade(upgradedUnit, isFree = false)
        return true
    }

    /** Host validates the upgrade on authoritative state, then echoes validated envelope */
    private fun hostValidateUpgrade(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.UpgradeAction ?: return
        if (!performUpgradeAction(action)) return
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    /** Remote client applies an already-validated upgrade */
    private fun applyRemoteUpgrade(action: GameAction.UpgradeAction) {
        performUpgradeAction(action)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Promote
    // ──────────────────────────────────────

    /** Perform a unit promotion locally (shared by host-validate and remote-apply) */
    private fun performPromoteAction(action: GameAction.PromoteAction): Boolean {
        val tileMap = worldScreen.gameInfo.tileMap
        val unit = tileMap.tileList.asSequence()
            .flatMap { it.getUnits().asSequence() }
            .firstOrNull { it.id == action.unitId && it.civ.civName == action.civName } ?: return false
        if (unit.isDestroyed) return false
        if (unit.promotions.getAvailablePromotions().none { it.name == action.promotionName }) return false
        unit.promotions.addPromotion(action.promotionName)
        return true
    }

    /** Host validates the promotion (read-only), then echoes validated envelope.
     *  Does NOT apply — application happens in [applyRemotePromote] when validated=true echo arrives. */
    private fun hostValidatePromote(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.PromoteAction ?: return
        // Apply on authoritative state — host never receives the validated echo
        if (!performPromoteAction(action)) return
        // Valid — echo for all clients to apply once via applyRemotePromote
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    /** Remote client applies an already-validated promotion */
    private fun applyRemotePromote(action: GameAction.PromoteAction) {
        performPromoteAction(action)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Purchase
    // ──────────────────────────────────────

    /** Host validates the purchase (read-only), then echoes validated envelope.
     *  Does NOT apply — application happens in [applyRemotePurchase] when validated=true echo arrives. */
    private fun hostValidatePurchase(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.PurchaseAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val city = civ.cities.firstOrNull { it.id == action.cityId } ?: return
        val stat = try { Stat.valueOf(action.stat) } catch (_: Exception) { return }
        val construction = city.cityConstructions
            .getConstruction(action.constructionName) as? INonPerpetualConstruction ?: return
        val constructionBuyCost = construction.getStatBuyCost(city, stat) ?: return
        if (!city.cityConstructions.isConstructionPurchaseAllowed(construction, stat, constructionBuyCost)) {
            debug("Host rejected purchase: %s in %s", action.constructionName, action.cityId)
            return
        }

        // Apply on authoritative state — host never receives the validated echo
        applyRemotePurchase(action)

        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemotePurchase(action: GameAction.PurchaseAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val city = civ.cities.firstOrNull { it.id == action.cityId } ?: return
        val stat = try { Stat.valueOf(action.stat) } catch (_: Exception) { return }
        val tile = if (action.tileX != null && action.tileY != null)
            worldScreen.gameInfo.tileMap[action.tileX, action.tileY]
        else null

        city.cityConstructions.purchaseConstruction(action.constructionName, action.queuePosition, false, stat, tile)
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Fortify
    // ──────────────────────────────────────

    private fun hostValidateFortify(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.FortifyAction ?: return
        val tileMap = worldScreen.gameInfo.tileMap
        val unit = tileMap.tileList.asSequence()
            .flatMap { it.getUnits().asSequence() }
            .firstOrNull { it.id == action.unitId && it.civ.civName == action.civName } ?: return
        if (unit.isDestroyed) return
        // Apply on authoritative state — host never receives the validated echo
        applyRemoteFortify(action)
        // Valid
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemoteFortify(action: GameAction.FortifyAction) {
        val tileMap = worldScreen.gameInfo.tileMap
        val unit = tileMap.tileList.asSequence()
            .flatMap { it.getUnits().asSequence() }
            .firstOrNull { it.id == action.unitId && it.civ.civName == action.civName } ?: return
        if (unit.isDestroyed) return
        when (action.fortifyType) {
            "Fortify" -> unit.fortify()
            "FortifyUntilHealed" -> unit.fortifyUntilHealed()
        }
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Pillage
    // ──────────────────────────────────────

    private fun hostValidatePillage(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.PillageAction ?: return
        val tileMap = worldScreen.gameInfo.tileMap
        val unit = tileMap.tileList.asSequence()
            .flatMap { it.getUnits().asSequence() }
            .firstOrNull { it.id == action.unitId && it.civ.civName == action.civName } ?: return
        if (unit.isDestroyed) return
        if (!unit.hasMovement()) return
        val tile = unit.currentTile
        if (!tile.canPillageTile() || tile.getImprovementToPillageName() == null) return
        // Apply on authoritative state — host never receives the validated echo
        applyRemotePillage(action)
        // Valid
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    private fun applyRemotePillage(action: GameAction.PillageAction) {
        val tileMap = worldScreen.gameInfo.tileMap
        val unit = tileMap.tileList.asSequence()
            .flatMap { it.getUnits().asSequence() }
            .firstOrNull { it.id == action.unitId && it.civ.civName == action.civName } ?: return
        if (unit.isDestroyed) return
        val tile = unit.currentTile

        val pillagedImprovement = tile.getImprovementToPillageName() ?: return
        val pillagingImprovement = tile.canPillageTileImprovement()
        val pillageText = "An enemy [${unit.baseUnit.name}] has pillaged our [$pillagedImprovement]"
        val icon = "ImprovementIcons/$pillagedImprovement"
        tile.getOwner()?.addNotification(
            pillageText,
            tile.position,
            com.unciv.logic.civilization.NotificationCategory.War,
            icon,
            com.unciv.logic.civilization.NotificationIcon.War,
            unit.baseUnit.name
        )

        com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsPillage.pillageLooting(tile, unit)
        tile.setPillaged()
        if (tile.resource != null) tile.getOwner()?.cache?.updateCivResources()

        val freePillage = unit.hasUnique(com.unciv.models.ruleset.unique.UniqueType.NoMovementToPillage, checkCivInfoUniques = true)
        if (!freePillage) unit.useMovementPoints(1f)

        if (pillagingImprovement) {
            var healAmount = 25f
            for (unique in unit.getMatchingUniques(com.unciv.models.ruleset.unique.UniqueType.PercentHealthFromPillaging, checkCivInfoUniques = true)) {
                healAmount *= unique.params[0].toFloat() / 100f
            }
            unit.healBy(healAmount.toInt())
        }

        if (tile.getImprovementToPillage()?.hasUnique(com.unciv.models.ruleset.unique.UniqueType.DestroyedWhenPillaged) == true) {
            tile.removeImprovement()
        }

        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }


    // ──────────────────────────────────────
    //  Policy adoption
    // ──────────────────────────────────────

    private fun hostValidateAdoptPolicy(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.AdoptPolicyAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val policy = worldScreen.gameInfo.ruleset.policies[action.policyName] ?: return
        if (!civ.policies.isAdoptable(policy)) return
        // Apply on authoritative state — host never receives the validated echo
        applyRemoteAdoptPolicy(action)
        // Valid — echo for all clients to apply once via applyRemoteAdoptPolicy
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

    // ──────────────────────────────────────
    //  Pantheon founding
    // ──────────────────────────────────────

    private fun hostValidateFoundPantheon(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.FoundPantheonAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val belief = worldScreen.gameInfo.ruleset.beliefs[action.beliefName] ?: return
        if (civ.religionManager.religionState >= ReligionState.Pantheon) return
        // Apply on authoritative state — host never receives the validated echo
        applyRemoteFoundPantheon(action)
        // Valid — echo for all clients to apply via applyRemoteFoundPantheon
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

    // ──────────────────────────────────────
    //  Create Improvement (work boat, instant)
    // ──────────────────────────────────────

    private fun hostValidateCreateImprovement(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.CreateImprovementAction ?: return
        val unit = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName }
            ?.units?.getUnitById(action.unitId) ?: return

        if (unit.isDestroyed || !unit.hasMovement()) return // May not need to check unit.isDestroyed?

        val improvement = worldScreen.gameInfo.tileMap.ruleset!!.tileImprovements[action.improvementName] ?: return
        if (!unit.currentTile.improvementFunctions.canBuildImprovement(improvement, unit.cache.state)) return
        // Apply on authoritative state since host never receives the validated echo
        applyRemoteCreateImprovement(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteCreateImprovement(action: GameAction.CreateImprovementAction) {
        val unit = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName }
            ?.units?.getUnitById(action.unitId) ?: return

        if (unit.isDestroyed) return
        val improvement = worldScreen.gameInfo.tileMap.ruleset!!.tileImprovements[action.improvementName] ?: return
        val tile = unit.currentTile
        tile.setImprovement(improvement, unit.civ, unit)
        unit.destroy()
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Disband unit
    // ──────────────────────────────────────

    private fun hostValidateDisbandUnit(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.DisbandUnitAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val unit = civ.units.getCivUnits().firstOrNull { it.id == action.unitId } ?: return
        if (unit.isDestroyed) return
        // Apply on authoritative state — host never receives the validated echo
        applyRemoteDisbandUnit(action)
        // Valid — echo for all clients to apply
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteDisbandUnit(action: GameAction.DisbandUnitAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val unit = civ.units.getCivUnits().firstOrNull { it.id == action.unitId } ?: return
        if (unit.isDestroyed) return
        unit.disband()
        civ.updateStatsForNextTurn()
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true }
    }

    // ──────────────────────────────────────
    //  Trade / Diplomacy
    // ──────────────────────────────────────

    private fun hostValidateSendTradeRequest(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.SendTradeRequestAction ?: return
        // Apply on authoritative state — host never receives the validated echo
        applyRemoteSendTradeRequest(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteSendTradeRequest(action: GameAction.SendTradeRequestAction) {
        val targetCiv = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.targetCiv } ?: return
        // Replace any existing trade request from the same requesting civ
        targetCiv.tradeRequests.removeAll { it.requestingCiv == action.requestingCiv }
        targetCiv.tradeRequests.add(
            com.unciv.logic.trade.TradeRequest(action.requestingCiv, action.trade.toTrade())
        )
    }

    private fun hostValidateRetractTradeRequest(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.RetractTradeRequestAction ?: return
        applyRemoteRetractTradeRequest(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteRetractTradeRequest(action: GameAction.RetractTradeRequestAction) {
        val targetCiv = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.targetCiv } ?: return
        targetCiv.tradeRequests.removeAll { it.requestingCiv == action.requestingCiv }
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

    private fun hostValidateDeclineTradeRequest(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.DeclineTradeRequestAction ?: return
        applyRemoteDeclineTradeRequest(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }

    private fun applyRemoteDeclineTradeRequest(action: GameAction.DeclineTradeRequestAction) {
        val decliningCiv = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.decliningCiv } ?: return
        val trade = action.trade.toTrade()
        val tradeRequest = decliningCiv.tradeRequests.firstOrNull {
            it.requestingCiv == action.requestingCiv && it.trade.equalTrade(trade)
        }
        tradeRequest?.decline(decliningCiv)
        decliningCiv.tradeRequests.removeAll { it.requestingCiv == action.requestingCiv }
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