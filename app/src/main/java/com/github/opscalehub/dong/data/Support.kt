package com.github.opscalehub.dong.data

import com.github.opscalehub.dong.data.db.BuildingEntity
import com.github.opscalehub.dong.data.db.CategoryEntity
import com.github.opscalehub.dong.data.db.ChargeItemEntity
import com.github.opscalehub.dong.data.db.ExpenseEntity
import com.github.opscalehub.dong.data.db.JournalLineEntity
import com.github.opscalehub.dong.data.db.MeterEntity
import com.github.opscalehub.dong.data.db.MeterReadingEntity
import com.github.opscalehub.dong.data.db.OccupancyEntity
import com.github.opscalehub.dong.data.db.ParkingAssignmentEntity
import com.github.opscalehub.dong.data.db.ParkingSpaceEntity
import com.github.opscalehub.dong.data.db.UnitEntity
import com.github.opscalehub.dong.data.db.WaterTariffEntity
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.ledger.Posting
import com.github.opscalehub.dong.domain.model.BuildingSettings
import com.github.opscalehub.dong.domain.model.Category
import com.github.opscalehub.dong.domain.model.ChargeItem
import com.github.opscalehub.dong.domain.model.Expense
import com.github.opscalehub.dong.domain.model.Meter
import com.github.opscalehub.dong.domain.model.MeterReading
import com.github.opscalehub.dong.domain.model.OccupancyRecord
import com.github.opscalehub.dong.domain.model.ParkingAssignment
import com.github.opscalehub.dong.domain.model.ParkingSpace
import com.github.opscalehub.dong.domain.model.TariffTier
import com.github.opscalehub.dong.domain.model.UnitInfo
import com.github.opscalehub.dong.domain.model.WaterTariff
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** Injectable time source so tests are deterministic. */
interface Clock {
    fun now(): Long
    fun today(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
    override fun today(): Long = LocalDate.now().toEpochDay()
}

/** User-facing failure of a repository operation; [code] is rendered in Persian by the UI. */
class OpException(val code: OpError, val detail: String = "") : Exception("$code $detail")

enum class OpError {
    NOT_FOUND, INVALID_AMOUNT, INVALID_INPUT, REASON_REQUIRED,
    PERIOD_FINALIZED, PERIOD_OVERLAP, PERIOD_HAS_BLOCKING_ISSUES, PERIOD_NOT_FINALIZED, NOTHING_TO_CORRECT,
    LOCKED_BY_FINALIZED_PERIOD, ALREADY_REVERSED, CANNOT_REVERSE, HAS_PAYMENTS, OPENING_EXISTS, NOT_BALANCED,
}

fun fail(code: OpError, detail: String = ""): Nothing = throw OpException(code, detail)

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val tiersSerializer = ListSerializer(TariffTier.serializer())
fun encodeTiers(t: List<TariffTier>): String = json.encodeToString(tiersSerializer, t)
fun decodeTiers(s: String): List<TariffTier> = json.decodeFromString(tiersSerializer, s)

fun BuildingEntity.settings() = BuildingSettings(currencyCode, currencyName, minorDigits, roundingIncrement, occupancyBasis, calendar)
fun UnitEntity.toDomain() = UnitInfo(id, label, floor, sortOrder, areaCentiM2, active)
fun OccupancyEntity.toDomain() = OccupancyRecord(unitId, effectiveFrom, occupantCount)
fun ParkingSpaceEntity.toDomain() = ParkingSpace(id, label, active)
fun ParkingAssignmentEntity.toDomain() = ParkingAssignment(id, spaceId, unitId, relation, inUse, effectiveFrom, effectiveTo)
fun MeterEntity.toDomain() = Meter(id, label, role, unitId, rolloverLimitLiters, installedOn, retiredOn)
fun MeterReadingEntity.toDomain() = MeterReading(id, meterId, readingDate, valueLiters, kind, supersedesId, enteredAt)
fun CategoryEntity.toDomain() = Category(id, name, isWater)
fun CategoryEntity.policy() = AllocationPolicy.decode(defaultPolicyJson)
fun ExpenseEntity.policy() = AllocationPolicy.decode(policyJson)
fun ExpenseEntity.toDomain() = Expense(id, categoryId, fundId, description, amountMinor, policy(), date)
fun ChargeItemEntity.policy() = AllocationPolicy.decode(policyJson)
fun ChargeItemEntity.toDomain() = ChargeItem(id, name, categoryId, fundId, mode, amountMinor, policy(), tariffId)
fun WaterTariffEntity.toDomain() = WaterTariff(id, name, effectiveFrom, fixedFeeMinor, decodeTiers(tiersJson), surchargeBp, commonRateMinorPerM3)
fun JournalLineEntity.toPosting() = Posting(account, amountMinor, cashAccountId, unitId, supplierId, fundId, categoryId)
