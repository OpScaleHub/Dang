package com.github.opscalehub.dong.domain

import com.github.opscalehub.dong.domain.allocation.AllocationContext
import com.github.opscalehub.dong.domain.allocation.QuantityKind
import com.github.opscalehub.dong.domain.calendar.Jalali
import com.github.opscalehub.dong.domain.model.BuildingSettings
import com.github.opscalehub.dong.domain.model.Meter
import com.github.opscalehub.dong.domain.model.MeterReading
import com.github.opscalehub.dong.domain.model.MeterRole
import com.github.opscalehub.dong.domain.model.OccupancyBasis
import com.github.opscalehub.dong.domain.model.OccupancyRecord
import com.github.opscalehub.dong.domain.model.ParkingAssignment
import com.github.opscalehub.dong.domain.model.ParkingRelation
import com.github.opscalehub.dong.domain.model.ParkingSpace
import com.github.opscalehub.dong.domain.model.PeriodInput
import com.github.opscalehub.dong.domain.model.ReadingKind
import com.github.opscalehub.dong.domain.model.UnitInfo
import com.github.opscalehub.dong.domain.water.WaterCalculator
import com.github.opscalehub.dong.domain.water.WaterReport

/** Deterministic synthetic data shared by domain tests (same 9 units as the workbook). */
object Fixtures {
    val start = Jalali.toEpochDay(1405, 7, 1)
    val end = Jalali.toEpochDay(1405, 7, 30)
    val before = start - 1

    /** id, floor, area m², occupants (sample) */
    private val raw = listOf(
        Triple("1-1", 1, 60 to 2), Triple("1-2", 1, 60 to 1), Triple("1-3", 1, 70 to 3),
        Triple("2-1", 2, 156 to 4), Triple("2-2", 2, 100 to 2),
        Triple("3-1", 3, 156 to 5), Triple("3-2", 3, 100 to 3),
        Triple("4-1", 4, 156 to 4), Triple("4-2", 4, 100 to 2),
    )
    val units = raw.mapIndexed { i, (id, floor, ao) -> UnitInfo(id, id, floor, i + 1, ao.first * 100L) }
    val unitIds = units.map { it.id }
    val occupancy = raw.map { (id, _, ao) -> OccupancyRecord(id, before - 100, ao.second) }

    val spaces = (1..8).map { ParkingSpace("P$it", "P$it") }
    val assignments = listOf(
        ParkingAssignment("a1", "P1", "2-1", ParkingRelation.OWNED, true, before),
        ParkingAssignment("a2", "P2", "2-1", ParkingRelation.OWNED, false, before),
        ParkingAssignment("a3", "P3", "3-1", ParkingRelation.OWNED, true, before),
        ParkingAssignment("a4", "P4", "3-1", ParkingRelation.OWNED, true, before),
        ParkingAssignment("a5", "P5", "4-1", ParkingRelation.OWNED, true, before),
        ParkingAssignment("a6", "P6", "2-2", ParkingRelation.ASSIGNED, true, before),
        ParkingAssignment("a7", "P7", "3-2", ParkingRelation.RENTED, true, before),
    )

    /** meter id, role, unit, previous m³×1000, current m³×1000, rollover, limit */
    val meters = listOf(
        Meter("M-MAIN", "main", MeterRole.MAIN),
        Meter("M-11", "1-1", MeterRole.UNIT, "1-1"),
        Meter("M-12", "1-2", MeterRole.UNIT, "1-2"),
        Meter("M-13", "1-3", MeterRole.UNIT, "1-3", rolloverLimitLiters = 10_000_000),
        Meter("M-21", "2-1", MeterRole.UNIT, "2-1"),
        Meter("M-22-OLD", "2-2 old", MeterRole.UNIT, "2-2"),
        Meter("M-22-NEW", "2-2 new", MeterRole.UNIT, "2-2"),
        Meter("M-31", "3-1", MeterRole.UNIT, "3-1"),
        Meter("M-32", "3-2", MeterRole.UNIT, "3-2"),
        Meter("M-41", "4-1", MeterRole.UNIT, "4-1"),
        Meter("M-42", "4-2", MeterRole.UNIT, "4-2"),
    )

    private fun r(id: String, meter: String, date: Long, liters: Long, kind: ReadingKind = ReadingKind.REGULAR) =
        MeterReading(id, meter, date, liters, kind)

    val readings = listOf(
        r("r0", "M-MAIN", before, 5_120_000), r("r1", "M-MAIN", end, 5_262_000),
        r("r2", "M-11", before, 310_200), r("r3", "M-11", end, 322_700),
        r("r4", "M-12", before, 205_000), r("r5", "M-12", end, 205_000),
        r("r6", "M-13", before, 9_998_500), r("r7", "M-13", end, 3_200, ReadingKind.ROLLOVER),
        r("r8", "M-21", before, 1_402_300), r("r9", "M-21", end, 1_421_100),
        r("r10", "M-22-OLD", before, 812_000), r("r11", "M-22-OLD", start + 14, 815_400, ReadingKind.FINAL),
        r("r12", "M-22-NEW", start + 14, 0, ReadingKind.INITIAL), r("r13", "M-22-NEW", end, 6_100),
        r("r14", "M-31", before, 1_650_000), r("r15", "M-31", end, 1_672_400),
        r("r16", "M-32", before, 980_500), r("r17", "M-32", end, 994_200),
        r("r18", "M-41", before, 1_710_000), r("r19", "M-41", end, 1_731_300),
        r("r20", "M-42", before, 1_005_000), r("r21", "M-42", end, 1_017_600),
    )

    val settings = BuildingSettings(roundingIncrement = 1000)

    fun water(): WaterReport = WaterCalculator.report(meters, readings, unitIds, start, end)

    fun ctx(
        increment: Long = 1000,
        unitList: List<UnitInfo> = units,
        occ: List<OccupancyRecord> = occupancy,
        basis: OccupancyBasis = OccupancyBasis.DAY_WEIGHTED,
        water: WaterReport? = water(),
    ) = AllocationContext(
        units = unitList,
        occupancyQuantity = AllocationContext.occupancy(occ, unitList, start, end, basis),
        occupancyKind = if (basis == OccupancyBasis.DAY_WEIGHTED) QuantityKind.OCCUPANT_DAYS else QuantityKind.OCCUPANTS,
        periodDays = (end - start + 1).toInt(),
        water = water,
        spaces = spaces,
        effectiveAssignments = AllocationContext.effectiveAssignments(assignments, end),
        increment = increment,
    )

    fun input() = PeriodInput(
        periodId = "1405-07", startDate = start, endDate = end, settings = settings, units = units,
        occupancy = occupancy, spaces = spaces, assignments = assignments, meters = meters, readings = readings,
    )
}
