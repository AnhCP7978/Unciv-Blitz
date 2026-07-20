package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.ui.screens.worldscreen.WorldScreen
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionModifiers
import com.unciv.ui.screens.worldscreen.bottombar.BattleTableHelpers.battleAnimationDeferred
import com.unciv.utils.debug
import com.unciv.utils.Concurrency
import com.unciv.logic.city.City
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
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.UnitActionType
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

// ──────────────────────────────────────
//  Trade data Ã¢â€ â€ domain object conversion
// ──────────────────────────────────────

internal fun TradeOffer.toTradeOfferData() = GameAction.TradeOfferData(name, type.name, amount, duration)
internal fun GameAction.TradeOfferData.toTradeOffer() = TradeOffer(name, TradeOfferType.valueOf(type), amount, duration)

internal fun Trade.toTradeData() = GameAction.TradeData(
    theirOffers.map { it.toTradeOfferData() },
    ourOffers.map { it.toTradeOfferData() }
)
internal fun GameAction.TradeData.toTrade(): Trade {
    val t = Trade()
    t.theirOffers.addAll(theirOffers.map { it.toTradeOffer() })
    t.ourOffers.addAll(ourOffers.map { it.toTradeOffer() })
    return t
}

/**
 * Orchestrates the 2-phase broadcast protocol for simultaneous multiplayer:
 *
 * **Non-host** players send actions via WebSocket Ã¢â€ â€™ server relays to all
 * (including host) Ã¢â€ â€™ host validates Ã¢â€ â€™ host broadcasts acceptance/rejection.
 *
 * **Host** listens for all "end turn" signals Ã¢â€ â€™ runs [SimultaneousTurnProcessor.processAdvance]
 * Ã¢â€ â€™ uploads game file Ã¢â€ â€™ broadcasts [Response.TurnAdvanced] so everyone downloads.
 */
class ActionBroadcastManager(private val worldScreen: WorldScreen) {
    private val gameId get() = worldScreen.gameInfo.gameId

    /** Prevents the local player from double-sending EndTurn */
    @Volatile
    var hasEndedTurn = false

    /** Host-only: pending CivTurnChoices from non-host players, keyed by civName */
    private val pendingChoices = mutableMapOf<String, String>()

    /** Look up a unit by globally-unique ID across all civs. */
    private fun findUnitById(unitId: Int): MapUnit? = worldScreen.gameInfo.getUnitById(unitId)

    /** Look up a city by globally-unique UUID across all civs. */
    private fun findCityById(cityId: String): City? =
        worldScreen.gameInfo.civilizations.asSequence()
            .flatMap { it.cities.asSequence() }
            .firstOrNull { it.id == cityId }

    // ──────────────────────────────────────
    //  Send packet when [SimultaneousModeInterceptor] intercepted an action
    // ──────────────────────────────────────

    internal fun sendGameAction(action: GameAction) {
        val validated = isHost()
        if (validated) {
            // Host applies locally before sending since server won't echo back to host
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
            is GameAction.UnitAttackAction -> applyRemoteUnitAttack(action)
            is GameAction.CityAttackAction -> applyRemoteCityAttack(action)

            is GameAction.InvokeUnitAction -> applyRemoteInvokeUnit(action)
            is GameAction.UpgradeUnitAction -> applyRemoteUpgradeUnit(action)
            is GameAction.TriggerUniqueAction -> applyRemoteTriggerUnique(action)

            is GameAction.PromoteAction -> applyRemotePromote(action)

            is GameAction.CreateImprovementAction -> applyRemoteCreateImprovement(action)
            is GameAction.BuyTileAction -> applyRemoteBuyTile(action)
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
            is GameAction.TakeTributeAction -> applyRemoteTakeTribute(action)
            is GameAction.GoldGiftAction -> applyRemoteGoldGift(action)
            is GameAction.SetProtectionAction -> applyRemoteSetProtection(action)
            is GameAction.GiftImprovementAction -> applyRemoteGiftImprovement(action)
            is GameAction.DiplomaticMarriageAction -> applyRemoteDiplomaticMarriage(action)
            else -> {}
        }
    }

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
    //  Apply remote actions (all clients) - Run when received a validated packet. Since all moves technically have to be validated by host, I remove all safe-check on applyRemote...
    // ──────────────────────────────────────

    // Optimized with inline because it takes lambdas! 🚀
    private inline fun applyRemoteActionHelper(
        packet: GameActionPacket,
        hostValidateFunc: (GameActionPacket) -> Unit, 
        applyRemoteFunc: (GameAction) -> Unit,
        debugStr: String? = null
    ) {
        if (!packet.validated)
            hostValidateFunc(packet) // IF design work as intented, ONLY host would receive unvalidated packet, hence no check "if (isHost())" here
        else {
            if (debugStr != null) debug(debugStr)
            applyRemoteFunc(packet.action)
        }
    }

    private fun applyRemoteAction(packet: GameActionPacket) {
        when (val action = packet.action) {
            is GameAction.MoveAction -> applyRemoteActionHelper(
                packet, ::hostValidateMove, { applyRemoteMove(it as GameAction.MoveAction) },
                "Applying remote move: unit ${action.unitId} -> (${action.toX}, ${action.toY})"
            )
            is GameAction.UnitAttackAction -> applyRemoteActionHelper(
                packet, ::hostValidateUnitAttack, { applyRemoteUnitAttack(it as GameAction.UnitAttackAction) },
                "Applying remote attack: unit ${action.unitId} -> (${action.targetX}, ${action.targetY})"
            )
            is GameAction.CityAttackAction -> applyRemoteActionHelper(
                packet, ::hostValidateCityAttack, { applyRemoteCityAttack(it as GameAction.CityAttackAction) },
                "Applying remote attack: city ${action.cityId} -> (${action.targetX}, ${action.targetY})"
            )

            is GameAction.InvokeUnitAction -> applyRemoteActionHelper(
                packet, ::hostValidateInvokeUnit, { applyRemoteInvokeUnit(it as GameAction.InvokeUnitAction) },
                "Applying remote action: unit ${action.unitId} do ${action.actionType.value}"
            )
            is GameAction.UpgradeUnitAction -> applyRemoteActionHelper(
                packet, ::hostValidateUpgradeUnit, { applyRemoteUpgradeUnit(it as GameAction.UpgradeUnitAction) },
                "Applying upgrade: upgrade unit ${action.unitId} to ${action.unitToUpgradeTo}"
            )
            is GameAction.TriggerUniqueAction -> applyRemoteActionHelper(
                packet, ::hostValidateTriggerUnique, { applyRemoteTriggerUnique(it as GameAction.TriggerUniqueAction) },
                "Applying unique action: unit ${action.unitId} do ${action.uniqueText}"
            )

            is GameAction.PromoteAction -> applyRemoteActionHelper(
                packet, ::hostValidatePromote, { applyRemotePromote(it as GameAction.PromoteAction) },
                "Applying remote promote: unit ${action.unitId} <- ${action.promotionName}"
            )

            is GameAction.BuyTileAction -> applyRemoteActionHelper(
                packet, ::hostValidateBuyTile, { applyRemoteBuyTile(it as GameAction.BuyTileAction) },
                "Applying remote buy tile: tile (${action.tileX}, ${action.tileY})"
            )
            is GameAction.DeclareWarAction -> applyRemoteActionHelper(
                packet, ::hostValidateDeclareWar, { applyRemoteDeclareWar(it as GameAction.DeclareWarAction) },
                "Applying remote declare war: ${action.civName} vs ${action.otherCivName}"
            )
            is GameAction.PurchaseAction -> applyRemoteActionHelper(
                packet, ::hostValidatePurchase, { applyRemotePurchase(it as GameAction.PurchaseAction) },
                "Applying remote purchase: ${action.constructionName} in ${action.cityId}"
            )
            is GameAction.AdoptPolicyAction -> applyRemoteActionHelper(
                packet, ::hostValidateAdoptPolicy, { applyRemoteAdoptPolicy(it as GameAction.AdoptPolicyAction) },
                "Applying remote adopt policy: ${action.policyName} for ${action.civName}"
            )
            is GameAction.FoundPantheonAction -> applyRemoteActionHelper(
                packet, ::hostValidateFoundPantheon, { applyRemoteFoundPantheon(it as GameAction.FoundPantheonAction) },
                "Applying remote found pantheon: ${action.beliefName} for ${action.civName}"
            )
            is GameAction.ChooseFreeTechAction -> applyRemoteActionHelper(
                packet, ::hostValidateChooseFreeTech, { applyRemoteChooseFreeTech(it as GameAction.ChooseFreeTechAction) },
                "Applying remote choose free tech: ${action.techName} for ${action.civName}"
            )
            is GameAction.CreateImprovementAction -> applyRemoteActionHelper(
                packet, ::hostValidateCreateImprovement, { applyRemoteCreateImprovement(it as GameAction.CreateImprovementAction) },
                "Applying remote create improvement: ${action.improvementName}"
            )
            is GameAction.SpawnUnitAction -> applyRemoteActionHelper(
                packet, ::hostValidateSpawnUnit, { applyRemoteSpawnUnit(it as GameAction.SpawnUnitAction) },
                "Applying remote spawn unit: ${action.unitName} for ${action.civName}"
            )
            is GameAction.SendTradeRequestAction -> applyRemoteActionHelper(
                packet, ::hostValidateSendTradeRequest, { applyRemoteSendTradeRequest(it as GameAction.SendTradeRequestAction) },
                "Applying remote send trade request: ${action.requestingCiv} -> ${action.targetCiv}"
            )
            is GameAction.AcceptTradeAction -> applyRemoteActionHelper(
                packet, ::hostValidateAcceptTrade, { applyRemoteAcceptTrade(it as GameAction.AcceptTradeAction) },
                "Applying remote accept trade: ${action.acceptingCiv} accepts from ${action.requestingCiv}"
            )
            is GameAction.TakeTributeAction -> applyRemoteActionHelper(
                packet, ::hostValidateTakeTribute, { applyRemoteTakeTribute(it as GameAction.TakeTributeAction) },
                "Applying remote take tribute: ${action.civName} -> ${action.cityStateName} (${action.tributeType})"
            )
            is GameAction.GoldGiftAction -> applyRemoteActionHelper(
                packet, ::hostValidateGoldGift, { applyRemoteGoldGift(it as GameAction.GoldGiftAction) },
                "Applying remote gold gift: ${action.civName} -> ${action.cityStateName} (${action.giftAmount} gold)"
            )
            is GameAction.SetProtectionAction -> applyRemoteActionHelper(
                packet, ::hostValidateSetProtection, { applyRemoteSetProtection(it as GameAction.SetProtectionAction) },
                "Applying remote set protection: ${action.civName} ${if (action.protect) "pledges" else "revokes"} ${action.cityStateName}"
            )
            is GameAction.GiftImprovementAction -> applyRemoteActionHelper(
                packet, ::hostValidateGiftImprovement, { applyRemoteGiftImprovement(it as GameAction.GiftImprovementAction) },
                "Applying remote gift improvement: ${action.civName} -> ${action.cityStateName} (${action.improvementName})"
            )
            is GameAction.DiplomaticMarriageAction -> applyRemoteActionHelper(
                packet, ::hostValidateDiplomaticMarriage, { applyRemoteDiplomaticMarriage(it as GameAction.DiplomaticMarriageAction) },
                "Applying remote diplomatic marriage: ${action.civName} <- ${action.cityStateName}"
            )
            is GameAction.CompleteFoundReligionAction -> applyRemoteActionHelper(
                packet, ::hostValidateCompleteFoundReligion, { applyRemoteCompleteFoundReligion(it as GameAction.CompleteFoundReligionAction) },
                "Applying remote complete found religion: ${action.civName} -> ${action.religionName}"
            )
            is GameAction.CompleteEnhanceReligionAction -> applyRemoteActionHelper(
                packet, ::hostValidateCompleteEnhanceReligion, { applyRemoteCompleteEnhanceReligion(it as GameAction.CompleteEnhanceReligionAction) },
                "Applying remote complete enhance religion: ${action.civName}"
            )
            is GameAction.CaptureCityAction -> applyRemoteActionHelper(
                packet, ::hostValidateCaptureCity, { applyRemoteCaptureCity(it as GameAction.CaptureCityAction) },
                "Applying remote capture city: city ${action.cityId} by ${action.civName}"
            )
            is GameAction.ReturnCapturedUnitAction -> applyRemoteActionHelper(
                packet, ::hostValidateReturnCapturedUnit, { applyRemoteReturnCapturedUnit(it as GameAction.ReturnCapturedUnitAction) },
                "Applying remote return captured unit: unit ${action.unitId} returnToOwner=${action.returnToOwner}"
            )
            else -> {}
        }
        Gdx.app.postRunnable { worldScreen.shouldUpdate = true } // Shared update-screen-after-apply line so I don't have to paste it dozens times...
    }

    // ──────────────────────────────────────
    //  Move ─ Intercept in [WorldMapHolder]
    // ──────────────────────────────────────
    private fun hostValidateMove(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.MoveAction ?: return
        val unit = findUnitById(action.unitId)
        if (unit == null || unit.currentMovement <= 0f) {
            debug("Host rejected move: unit ${action.unitId} is invalid or has no movement point left")
            return
        }
        val targetTile = worldScreen.gameInfo.tileMap[action.toX, action.toY]
        if (!unit.movement.canMoveTo(targetTile)) {
            debug("Host rejected move: unit ${action.unitId} cannot move to (${action.toX}, ${action.toY})")
            return
        }
        unit.movement.moveToTile(targetTile)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }
    private fun applyRemoteMove(action: GameAction.MoveAction) {
        val unit = findUnitById(action.unitId)!!
        unit.movement.moveToTile(worldScreen.gameInfo.tileMap[action.toX, action.toY])
    }

    // ──────────────────────────────────────
    //  Attack (Unit & City) ─ Intercept in [WorldMapHolder] & [BattleTable]
    // ──────────────────────────────────────

    /* Shared validate function ─ host decides whether to accept & relay the attack */
    private fun validateAttack(attacker: ICombatant, targetX: Int, targetY: Int, envelope: GameActionPacket) {
        if (!attacker.canAttack()) return

        val tileToAttack = worldScreen.gameInfo.tileMap[targetX, targetY]
        // have to check for 2 cases since movePreparingAttack() auto set to true if attacker is city
        val isValidAttack = if (attacker.isCity()) {
            val city = (attacker as CityCombatant).city
            tileToAttack.isVisible(city.civ) // Can see the tile
                && attacker.getTile().aerialDistanceTo(tileToAttack) <= city.getBombardRange() // target tile is within the range
                && TargetHelper.containsAttackableEnemy(tileToAttack, attacker) // and there is enemy on it
        } else {
            val attackableTile = AttackableTile(
                attacker.getTile(),
                tileToAttack,
                0f, // Unused by movePreparingAttack/attackOrNuke for validation
                Battle.getMapCombatantOfTile(tileToAttack)
            )
            Battle.movePreparingAttack(attacker, attackableTile)
        }

        if (isValidAttack) {
            applyAttack(attacker, targetX, targetY)
            val validatedEnvelope = envelope.copy(validated = true)
            ChatWebSocket.requestMessageSend(
                com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
            )
        }
    }
    private fun applyAttack(attacker: ICombatant, targetX: Int, targetY: Int) {
        val tileToAttack = worldScreen.gameInfo.tileMap[targetX, targetY]
        val attackableTile = AttackableTile(
            attacker.getTile(),
            tileToAttack,
            0f, // May wrong, but this is not used in attackOrNuke(), so okay I guess...
            Battle.getMapCombatantOfTile(tileToAttack)
        )

        val (damageToDefender, damageToAttacker) = Battle.attackOrNuke(attacker, attackableTile)
        // Show animation if attacker or defender belong to current player
        val defender = attackableTile.combatant!!
        if (attacker.getCivInfo() == worldScreen.viewingCiv || defender.getCivInfo() == worldScreen.viewingCiv)
            worldScreen.battleAnimationDeferred(attacker, damageToAttacker, defender, damageToDefender)
    }

    private fun hostValidateUnitAttack(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.UnitAttackAction ?: return
        val unit = findUnitById(action.unitId) ?: return
        validateAttack(MapUnitCombatant(unit), action.targetX, action.targetY, envelope)
    }
    private fun hostValidateCityAttack(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.CityAttackAction ?: return
        val city = findCityById(action.cityId) ?: return
        validateAttack(CityCombatant(city), action.targetX, action.targetY, envelope)
    }

    private fun applyRemoteUnitAttack(action: GameAction.UnitAttackAction) {
        val unit = findUnitById(action.unitId)!!
        applyAttack(MapUnitCombatant(unit), action.targetX, action.targetY)
    }
    private fun applyRemoteCityAttack(action: GameAction.CityAttackAction) {
        val city = findCityById(action.cityId)!!
        applyAttack(CityCombatant(city), action.targetX, action.targetY)
    }

    // ──────────────────────────────────────
    //  UnitAction ─ Intercept in [UnitActionsTable]
    // ──────────────────────────────────────
    private fun hostValidateInvokeUnit(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.InvokeUnitAction ?: return
        val unit = findUnitById(action.unitId) ?: return
        if (!UnitActions.invokeUnitAction(unit, action.actionType)) {
            debug("Host rejected action: unit ${action.unitId} cannot do ${action.actionType.value}")
            return
        }
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }
    private fun applyRemoteInvokeUnit(action: GameAction.InvokeUnitAction) {
        val unit = findUnitById(action.unitId)!!
        UnitActions.invokeUnitAction(unit, action.actionType)
    }

    private fun hostValidateUpgradeUnit(envelope: GameActionPacket) {
        try {
            val action = envelope.action as? GameAction.UpgradeUnitAction ?: return
            val unit = findUnitById(action.unitId) ?: return
            val upgradedUnit = unit.civ.getEquivalentUnit(action.unitToUpgradeTo)

            val isValidUpgrade = !( // Unit is alive, can act/upgrade and we have enough gold to upgrade
                unit.isDestroyed || !unit.hasMovement() || !unit.upgrade.canUpgrade(unitToUpgradeTo = upgradedUnit) ||
                unit.civ.gold < unit.upgrade.getCostOfUpgrade(upgradedUnit)
            )
            if (isValidUpgrade) {
                unit.upgrade.performUpgrade(upgradedUnit, isFree = false)
                val validatedEnvelope = envelope.copy(validated = true)
                ChatWebSocket.requestMessageSend(
                    com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
                )
            }
        } catch (_: Exception) { return }
    }
    private fun applyRemoteUpgradeUnit(action: GameAction.UpgradeUnitAction) {
        val unit = findUnitById(action.unitId)!!
        val upgradedUnit = unit.civ.getEquivalentUnit(action.unitToUpgradeTo)
        unit.upgrade.performUpgrade(upgradedUnit, isFree = false)
    }

    private fun hostValidateTriggerUnique(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.TriggerUniqueAction ?: return
        val unit = findUnitById(action.unitId) ?: return
        if (unit.isDestroyed) return

        // Find the unique by matching its text against all unit uniques
        val unique = unit.getUniques().firstOrNull { it.text == action.uniqueText } ?: return
        val triggerFunction = UniqueTriggerActivation.getTriggerFunction(unique, unit.civ, unit = unit, tile = unit.currentTile) ?: return
        val gameContext = GameContext(unit.civ, null, unit, unit.currentTile)

        repeat(unique.getUniqueMultiplier(gameContext)) { triggerFunction.invoke() }
        UnitActionModifiers.activateSideEffects(unit, unique)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }
    private fun applyRemoteTriggerUnique(action: GameAction.TriggerUniqueAction) {
        val unit = findUnitById(action.unitId)!!

        val unique = unit.getUniques().firstOrNull { it.text == action.uniqueText }!!
        val gameContext = GameContext(unit.civ, null, unit, unit.currentTile)
        val triggerFunction = UniqueTriggerActivation.getTriggerFunction(unique, unit.civ, unit = unit, tile = unit.currentTile)!!

        repeat(unique.getUniqueMultiplier(gameContext)) { triggerFunction.invoke() }
        UnitActionModifiers.activateSideEffects(unit, unique)
    }

    // ──────────────────────────────────────
    //  Promote
    // ──────────────────────────────────────
    private fun hostValidatePromote(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.PromoteAction ?: return
        val unit = findUnitById(action.unitId)
        if (unit == null || unit.isDestroyed || unit.promotions.getAvailablePromotions().none { it.name == action.promotionName }) return

        unit.promotions.addPromotion(action.promotionName)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }
    private fun applyRemotePromote(action: GameAction.PromoteAction) {
        val unit = findUnitById(action.unitId)!!
        unit.promotions.addPromotion(action.promotionName)
    }

    // ──────────────────────────────────────
    //  Declare War
    // ──────────────────────────────────────
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
    }
    private fun applyRemoteDeclareWar(action: GameAction.DeclareWarAction) {
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName }!!
        val otherCiv = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.otherCivName }!!
        val diplomacyManager = civ.getDiplomacyManager(otherCiv)!!
        diplomacyManager.declareWar()
    }

    // ──────────────────────────────────────
    //  Purchase
    // ──────────────────────────────────────
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
    }
    private fun applyRemotePurchase(action: GameAction.PurchaseAction) {
        val city = findCityById(action.cityId) ?: return
        val civ = city.civ
        val stat = try { Stat.valueOf(action.stat) } catch (_: Exception) { return }
        // queuePosition removed (was only for queue ordering, not essential for broadcast)
        // tileX/tileY removed (BuyTileAction covers tile purchases)
        city.cityConstructions.purchaseConstruction(action.constructionName, -1, false, stat, null)
    }

    // ──────────────────────────────────────
    //  Buy Tile
    // ──────────────────────────────────────
    private fun hostValidateBuyTile(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.BuyTileAction ?: return
        val city = findCityById(action.cityId) ?: return
        val tile = worldScreen.gameInfo.tileMap[action.tileX, action.tileY]
        if (!city.expansion.canBuyTile(tile)) {
            debug("Host rejected purchase: city %s can't buy tile (%s, %s)", action.cityId, action.tileX, action.tileY)
            return
        }
        city.expansion.buyTile(tile)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }
    private fun applyRemoteBuyTile(action: GameAction.BuyTileAction) {
        val city = findCityById(action.cityId)!!
        city.expansion.buyTile(worldScreen.gameInfo.tileMap[action.tileX, action.tileY])
    }

    // ──────────────────────────────────────
    //  Capture City
    // ──────────────────────────────────────
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
        if (city.civ.civName == action.civName) return // déjà vu ─ already under this civ

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
    }

    // ──────────────────────────────────────
    //  Return Captured Unit (rescue unit from barbarian)
    // ──────────────────────────────────────
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
    }

    // ──────────────────────────────────────
    //  Policy adoption
    // ──────────────────────────────────────
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
    }

    // ──────────────────────────────────────
    //  Choose Free Tech (Great Library, etc.)
    // ──────────────────────────────────────
    private fun hostValidateChooseFreeTech(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.ChooseFreeTechAction ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val tech = worldScreen.gameInfo.ruleset.technologies[action.techName] ?: return
        if (!civ.tech.canBeResearched(tech.name)) return
        // Decrement on authoritative host state ─ sender already decremented locally
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
        // Use addTechnology directly ─ the sender already decremented freeTechs locally
        civ.tech.addTechnology(tech.name)
        civ.tech.updateResearchProgress()
    }

    // ──────────────────────────────────────
    //  Pantheon founding
    // ──────────────────────────────────────
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
    }

    // ──────────────────────────────────────
    //  Create Improvement (work boat, instant)
    // ──────────────────────────────────────
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
    }

    // ──────────────────────────────────────
    //  Spawn Unit (great person picker, etc.)
    // ──────────────────────────────────────
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
        // Apply great person counter decrements ─ these were incremented on both host and non-host
        // by unique triggers from policy adoption / wonder completion, so both must decrement.
        if (action.freeGreatPeopleDecrement > 0)
            civ.greatPeople.freeGreatPeople -= action.freeGreatPeopleDecrement
        if (action.mayaLimitedFreeGPDecrement > 0)
            civ.greatPeople.mayaLimitedFreeGP -= action.mayaLimitedFreeGPDecrement
        for (unitName in action.longCountGPPoolRemoval)
            civ.greatPeople.longCountGPPool.remove(unitName)
    }

    // ──────────────────────────────────────
    //  Trade / Diplomacy
    // ──────────────────────────────────────
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
    }

    // ──────────────────────────────────────
    //  City-State: Take tribute (Gold or Worker)
    // ──────────────────────────────────────
    private fun hostValidateTakeTribute(envelope: GameActionPacket) {
        val action = envelope.action as? GameAction.TakeTributeAction ?: return
        applyRemoteTakeTribute(action)
        val validatedEnvelope = envelope.copy(validated = true)
        ChatWebSocket.requestMessageSend(
            com.unciv.logic.multiplayer.chat.Message.GameActionRelay(validatedEnvelope)
        )
    }
    private fun applyRemoteTakeTribute(action: GameAction.TakeTributeAction) {
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        when (action.tributeType) {
            "Gold" -> cs.cityStateFunctions.tributeGold(civ)
            "Worker" -> cs.cityStateFunctions.tributeWorker(civ)
        }
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
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        cs.cityStateFunctions.receiveGoldGift(civ, action.giftAmount)
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
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        if (action.protect) cs.cityStateFunctions.addProtectorCiv(civ)
        else cs.cityStateFunctions.removeProtectorCiv(civ)
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
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        val tile = worldScreen.gameInfo.tileMap[action.tileX, action.tileY]
        val improvement = worldScreen.gameInfo.tileMap.ruleset!!.tileImprovements[action.improvementName] ?: return
        civ.addGold(-200)
        tile.stopWorkingOnImprovement()
        tile.setImprovement(improvement)
        cs.cache.updateCivResources()
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
        val cs = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.cityStateName } ?: return
        val civ = worldScreen.gameInfo.civilizations.firstOrNull { it.civName == action.civName } ?: return
        cs.cityStateFunctions.diplomaticMarriage(civ)
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
        debug("All players finished: advancing turn")
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

            // Upload the new game state (existing pipeline ─ suspend function, OK in coroutine)
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

    /** Called when TurnAdvanced is received ─ non-host clients download the new game */
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