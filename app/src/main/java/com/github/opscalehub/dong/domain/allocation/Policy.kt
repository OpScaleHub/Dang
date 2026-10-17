package com.github.opscalehub.dong.domain.allocation

import com.github.opscalehub.dong.domain.model.ParkingRelation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How an amount is shared among units. Stored as JSON on categories (default),
 * expenses and charge items (snapshot at creation), and inside finalized
 * period snapshots — so changing a category's default never alters history.
 */
@Serializable
data class AllocationPolicy(
    /** Expense is borne by its fund; nothing is charged to units. */
    val fundOnly: Boolean = false,
    val components: List<AllocationComponent> = listOf(AllocationComponent()),
) {
    companion object {
        val FUND_ONLY = AllocationPolicy(fundOnly = true, components = emptyList())
        fun single(basis: Basis, eligibility: Eligibility = Eligibility.AllActive) =
            AllocationPolicy(components = listOf(AllocationComponent(Portion.Remainder, basis, eligibility)))

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
        fun decode(text: String): AllocationPolicy = json.decodeFromString(serializer(), text)
    }

    fun encode(): String = json.encodeToString(serializer(), this)
}

@Serializable
data class AllocationComponent(
    val portion: Portion = Portion.Remainder,
    val basis: Basis = Basis.Equal,
    val eligibility: Eligibility = Eligibility.AllActive,
)

/** Which part of the amount a component takes. Fixed first, then percentages, remainder last. */
@Serializable
sealed interface Portion {
    @Serializable @SerialName("remainder")
    data object Remainder : Portion

    @Serializable @SerialName("percent")
    data class Percent(val basisPoints: Int) : Portion

    @Serializable @SerialName("fixed")
    data class Fixed(val amountMinor: Long) : Portion
}

@Serializable
sealed interface Basis {
    @Serializable @SerialName("equal")
    data object Equal : Basis

    @Serializable @SerialName("occupants")
    data object Occupants : Basis

    @Serializable @SerialName("area")
    data object Area : Basis

    @Serializable @SerialName("consumption")
    data class Consumption(val common: CommonWater = CommonWater.Proportional) : Basis

    @Serializable @SerialName("weighted")
    data class Weighted(val weights: Map<String, Long>) : Basis

    @Serializable @SerialName("explicit")
    data class Explicit(val amounts: Map<String, Long>) : Basis

    @Serializable @SerialName("parking")
    data class Parking(
        val filter: SpaceFilter = SpaceFilter.IN_USE,
        val relations: Set<ParkingRelation> = ParkingRelation.entries.toSet(),
        /** Only used with [SpaceFilter.SELECTED]. */
        val spaceIds: Set<String> = emptySet(),
        /** Optional per-space weight (default 1). */
        val spaceWeights: Map<String, Long> = emptyMap(),
    ) : Basis
}

@Serializable
enum class SpaceFilter { ALL_ASSIGNED, IN_USE, SELECTED }

/** What to do with the part of a water bill that unit meters do not explain (main − Σ units). */
@Serializable
sealed interface CommonWater {
    /** Whole amount by unit consumption; the unexplained part is implicitly spread pro rata. */
    @Serializable @SerialName("proportional")
    data object Proportional : CommonWater

    /** amount·Σunits/main by consumption; the rest by [basis]. Requires a main meter. */
    @Serializable @SerialName("separate")
    data class Separate(val basis: SimpleBasis) : CommonWater

    /** amount·Σunits/main by consumption; the rest is borne by the fund. Requires a main meter. */
    @Serializable @SerialName("fund_borne")
    data object FundBorne : CommonWater
}

@Serializable
enum class SimpleBasis { EQUAL, OCCUPANTS, AREA }

@Serializable
sealed interface Eligibility {
    @Serializable @SerialName("all")
    data object AllActive : Eligibility

    @Serializable @SerialName("selected")
    data class Selected(val unitIds: Set<String>) : Eligibility
}

fun SimpleBasis.toBasis(): Basis = when (this) {
    SimpleBasis.EQUAL -> Basis.Equal
    SimpleBasis.OCCUPANTS -> Basis.Occupants
    SimpleBasis.AREA -> Basis.Area
}
