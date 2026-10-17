package com.github.opscalehub.dong.domain.model

import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import kotlinx.serialization.Serializable

/*
 * Locale-independent domain model. Dates are ISO epoch days (Long), money is
 * Long minor units, area is centi-square-metres, water is litres. Every class
 * is @Serializable because a finalized period stores its complete input as a
 * JSON snapshot.
 */

@Serializable
enum class OccupancyBasis { DAY_WEIGHTED, PERIOD_START, PERIOD_END }

@Serializable
enum class CalendarSystem { JALALI, GREGORIAN }

@Serializable
data class BuildingSettings(
    val currencyCode: String = "IRR",
    val currencyName: String = "ریال",
    val minorDigits: Int = 0,
    val roundingIncrement: Long = 1,
    val occupancyBasis: OccupancyBasis = OccupancyBasis.DAY_WEIGHTED,
    val calendar: CalendarSystem = CalendarSystem.JALALI,
)

@Serializable
data class UnitInfo(
    val id: String,
    val label: String,
    val floor: Int,
    val sortOrder: Int,
    val areaCentiM2: Long,
    val active: Boolean = true,
)

@Serializable
data class OccupancyRecord(val unitId: String, val effectiveFrom: Long, val occupantCount: Int)

@Serializable
enum class ParkingRelation { OWNED, ASSIGNED, RENTED }

@Serializable
data class ParkingSpace(val id: String, val label: String, val active: Boolean = true)

@Serializable
data class ParkingAssignment(
    val id: String,
    val spaceId: String,
    val unitId: String,
    val relation: ParkingRelation,
    val inUse: Boolean,
    val effectiveFrom: Long,
    val effectiveTo: Long? = null,
)

@Serializable
enum class MeterRole { MAIN, UNIT, COMMON }

@Serializable
data class Meter(
    val id: String,
    val label: String,
    val role: MeterRole,
    val unitId: String? = null,
    /** Value (litres) at which the register wraps to zero, e.g. 100000 m³ → 100_000_000. */
    val rolloverLimitLiters: Long? = null,
    val installedOn: Long? = null,
    val retiredOn: Long? = null,
)

@Serializable
enum class ReadingKind {
    /** Ordinary periodic reading. */
    REGULAR,
    /** First reading of a new or reset meter; starts a new baseline (no consumption counted up to it). */
    INITIAL,
    /** Last reading before a meter is removed/replaced. */
    FINAL,
    /** The register wrapped past [Meter.rolloverLimitLiters] since the previous reading. */
    ROLLOVER,
}

@Serializable
data class MeterReading(
    val id: String,
    val meterId: String,
    val date: Long,
    val valueLiters: Long,
    val kind: ReadingKind = ReadingKind.REGULAR,
    /** When set, this reading corrects (replaces) the referenced reading. */
    val supersedesId: String? = null,
    val enteredAt: Long = 0,
)

@Serializable
data class Category(val id: String, val name: String, val isWater: Boolean = false)

@Serializable
data class Expense(
    val id: String,
    val categoryId: String,
    val fundId: String,
    val description: String,
    val amountMinor: Long,
    val policy: AllocationPolicy,
    val date: Long,
)

@Serializable
enum class ChargeMode {
    /** amountMinor is a total split among units by the policy. */
    TOTAL,
    /** amountMinor is a rate per basis quantity (per unit, per m², per occupant, per space). */
    RATE,
    /** Charges each unit by the water tariff applied to its metered consumption. */
    WATER_TARIFF,
}

@Serializable
data class ChargeItem(
    val id: String,
    val name: String,
    val categoryId: String,
    val fundId: String,
    val mode: ChargeMode,
    val amountMinor: Long,
    val policy: AllocationPolicy,
    val tariffId: String? = null,
)

@Serializable
data class TariffTier(
    /** Upper bound of this tier in litres per unit per period; null = unbounded (last tier). */
    val upToLiters: Long? = null,
    val priceMinorPerM3: Long,
)

@Serializable
data class WaterTariff(
    val id: String,
    val name: String,
    val effectiveFrom: Long,
    val fixedFeeMinor: Long = 0,
    val tiers: List<TariffTier>,
    /** Surcharge (e.g. sewage, tax) in basis points of the consumption charge. 3000 = 30%. */
    val surchargeBp: Int = 0,
    /** Price applied to unallocated (main − units) consumption; 0 = do not charge it. */
    val commonRateMinorPerM3: Long = 0,
)

@Serializable
data class PeriodInput(
    val periodId: String,
    val startDate: Long,
    val endDate: Long,
    val settings: BuildingSettings,
    val units: List<UnitInfo>,
    val occupancy: List<OccupancyRecord> = emptyList(),
    val spaces: List<ParkingSpace> = emptyList(),
    val assignments: List<ParkingAssignment> = emptyList(),
    val meters: List<Meter> = emptyList(),
    val readings: List<MeterReading> = emptyList(),
    val categories: List<Category> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val chargeItems: List<ChargeItem> = emptyList(),
    val tariffs: List<WaterTariff> = emptyList(),
) {
    val days: Int get() = (endDate - startDate + 1).toInt()
}
