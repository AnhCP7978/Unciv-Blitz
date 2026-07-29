package com.unciv.logic.multiplayer

import kotlinx.serialization.Serializable

/**
 * Payload sent by a non-host player with their EndTurn signal.
 * The host applies these choices to the authoritative game state before advancing the turn.
 */
@Serializable
data class CivTurnChoices(
    val civName: String,
    /** cityId -> construction name (null = nothing queued) */
    val cityConstructions: Map<String, String?>,
    /** current tech being researched, null means none */
    val currentTechResearch: String?,
    /** full set of adopted policy names */
    val adoptedPolicies: List<String> = emptyList(),
    /** how many policies were paid for with culture */
    val numberOfAdoptedPolicies: Int = 0,
    /** unspent free policies */
    val freePolicies: Int = 0,
    /** accumulated culture towards next policy */
    val storedCulture: Int = 0,
    /** hexCoord -> improvementName for tiles where this civ's workers are building improvements */
    val tileImprovements: Map<String, String> = emptyMap(),
)
