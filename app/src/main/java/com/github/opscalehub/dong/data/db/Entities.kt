package com.github.opscalehub.dong.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.github.opscalehub.dong.domain.ledger.AccountKind
import com.github.opscalehub.dong.domain.ledger.EntryType
import com.github.opscalehub.dong.domain.model.CalendarSystem
import com.github.opscalehub.dong.domain.model.ChargeMode
import com.github.opscalehub.dong.domain.model.MeterRole
import com.github.opscalehub.dong.domain.model.OccupancyBasis
import com.github.opscalehub.dong.domain.model.ParkingRelation
import com.github.opscalehub.dong.domain.model.ReadingKind
import kotlinx.serialization.Serializable

/*
 * Room schema. IDs are UUID strings (stable across backup/restore). Dates are
 * ISO epoch days, instants epoch millis, money Long minor units. Every table
 * hangs off a building with ON DELETE CASCADE so a building can be replaced
 * atomically on restore. Entities are @Serializable: the backup file is a
 * direct serialization of these rows.
 */

private const val B = "buildingId"

@Serializable
@Entity(tableName = "buildings")
data class BuildingEntity(
    @PrimaryKey val id: String,
    val name: String,
    val address: String = "",
    val currencyCode: String = "IRR",
    val currencyName: String = "ریال",
    val minorDigits: Int = 0,
    val roundingIncrement: Long = 1,
    val occupancyBasis: OccupancyBasis = OccupancyBasis.DAY_WEIGHTED,
    val calendar: CalendarSystem = CalendarSystem.JALALI,
    val notes: String = "",
    val createdAt: Long,
    /** Unit shown on screen (schema v3), e.g. «تومان»; empty = use [currencyName]. Storage stays in minor units. */
    @ColumnInfo(defaultValue = "") val displayCurrencyName: String = "",
    /** Stored minor units per display unit (power of 10; Rial → Toman = 10). */
    @ColumnInfo(defaultValue = "1") val displayDivisor: Long = 1,
)

@Serializable
@Entity(
    tableName = "units",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class UnitEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val floor: Int,
    val label: String,
    val areaCentiM2: Long,
    val active: Boolean = true,
    val notes: String = "",
    val sortOrder: Int,
)

@Serializable
@Entity(
    tableName = "occupancy",
    foreignKeys = [ForeignKey(entity = UnitEntity::class, parentColumns = ["id"], childColumns = ["unitId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("unitId")],
)
data class OccupancyEntity(
    @PrimaryKey val id: String,
    val unitId: String,
    val effectiveFrom: Long,
    val occupantCount: Int,
    val note: String = "",
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "parking_spaces",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class ParkingSpaceEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val label: String,
    val notes: String = "",
    val active: Boolean = true,
)

@Serializable
@Entity(
    tableName = "parking_assignments",
    foreignKeys = [ForeignKey(entity = ParkingSpaceEntity::class, parentColumns = ["id"], childColumns = ["spaceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("spaceId"), Index("unitId")],
)
data class ParkingAssignmentEntity(
    @PrimaryKey val id: String,
    val spaceId: String,
    val unitId: String,
    val relation: ParkingRelation,
    val inUse: Boolean,
    val vehicleNote: String = "",
    val effectiveFrom: Long,
    val effectiveTo: Long? = null,
)

@Serializable
@Entity(
    tableName = "meters",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class MeterEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val unitId: String? = null,
    val role: MeterRole,
    val label: String,
    val serial: String = "",
    val rolloverLimitLiters: Long? = null,
    val installedOn: Long? = null,
    val retiredOn: Long? = null,
    val replacesMeterId: String? = null,
    val notes: String = "",
)

@Serializable
@Entity(
    tableName = "meter_readings",
    foreignKeys = [ForeignKey(entity = MeterEntity::class, parentColumns = ["id"], childColumns = ["meterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("meterId"), Index("supersedesId")],
)
data class MeterReadingEntity(
    @PrimaryKey val id: String,
    val meterId: String,
    val readingDate: Long,
    val valueLiters: Long,
    val kind: ReadingKind = ReadingKind.REGULAR,
    val supersedesId: String? = null,
    val note: String = "",
    val enteredAt: Long,
)

@Serializable
@Entity(
    tableName = "categories",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val name: String,
    val defaultPolicyJson: String,
    val defaultFundId: String? = null,
    val isWater: Boolean = false,
    val active: Boolean = true,
    val sortOrder: Int = 0,
)

@Serializable
@Entity(
    tableName = "suppliers",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class SupplierEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val name: String,
    val phone: String = "",
    val notes: String = "",
)

@Serializable
enum class FundKind { OPERATING, RESERVE, OTHER }

@Serializable
@Entity(
    tableName = "funds",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class FundEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val name: String,
    val kind: FundKind,
)

@Serializable
enum class CashKind { CASH_BOX, BANK }

@Serializable
@Entity(
    tableName = "cash_accounts",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class CashAccountEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val name: String,
    val kind: CashKind,
)

@Serializable
enum class ExpenseStatus { ACTIVE, VOIDED }

@Serializable
@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B), Index("periodId")],
)
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val periodId: String? = null,
    val categoryId: String,
    val fundId: String,
    val supplierId: String? = null,
    val date: Long,
    val description: String,
    val amountMinor: Long,
    /** Text reference to a supporting document (invoice number, file name, location). */
    val documentRef: String = "",
    /** Policy snapshot taken from the category at creation; editable only while the period is a draft. */
    val policyJson: String,
    val notes: String = "",
    val status: ExpenseStatus = ExpenseStatus.ACTIVE,
    /** Current (non-reversed) EXPENSE journal entry. */
    val journalEntryId: String? = null,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "charge_items",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class ChargeItemEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val name: String,
    val categoryId: String,
    val fundId: String,
    val mode: ChargeMode,
    val amountMinor: Long,
    val policyJson: String,
    val tariffId: String? = null,
    val activeFrom: Long,
    val activeTo: Long? = null,
    val active: Boolean = true,
)

@Serializable
@Entity(
    tableName = "water_tariffs",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class WaterTariffEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val name: String,
    val effectiveFrom: Long,
    val fixedFeeMinor: Long = 0,
    /** JSON list of [com.github.opscalehub.dong.domain.model.TariffTier]. */
    val tiersJson: String,
    val surchargeBp: Int = 0,
    val commonRateMinorPerM3: Long = 0,
)

@Serializable
enum class PeriodStatus { DRAFT, FINALIZED }

@Serializable
@Entity(
    tableName = "periods",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class PeriodEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val name: String,
    val startDate: Long,
    val endDate: Long,
    val dueDate: Long? = null,
    val status: PeriodStatus = PeriodStatus.DRAFT,
    val finalizedAt: Long? = null,
    /** [com.github.opscalehub.dong.domain.period.PeriodSnapshot] JSON, set on finalization and never changed. */
    val snapshotJson: String? = null,
    val chargeEntryId: String? = null,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "period_revisions",
    foreignKeys = [ForeignKey(entity = PeriodEntity::class, parentColumns = ["id"], childColumns = ["periodId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("periodId")],
)
data class PeriodRevisionEntity(
    @PrimaryKey val id: String,
    val periodId: String,
    val revision: Int,
    val createdAt: Long,
    val reason: String,
    val snapshotJson: String,
    val journalEntryId: String? = null,
)

@Serializable
@Entity(
    tableName = "journal_entries",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B), Index("periodId"), Index("expenseId"), Index("reversesEntryId")],
)
data class JournalEntryEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val entryDate: Long,
    val type: EntryType,
    val memo: String = "",
    val reference: String = "",
    val periodId: String? = null,
    val expenseId: String? = null,
    val unitId: String? = null,
    val supplierId: String? = null,
    val reversesEntryId: String? = null,
    val createdAt: Long,
    /** Payment method (schema v2), e.g. نقد / کارت به کارت / واریز بانکی. */
    @ColumnInfo(defaultValue = "") val method: String = "",
)

@Serializable
@Entity(
    tableName = "journal_lines",
    foreignKeys = [ForeignKey(entity = JournalEntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("entryId"), Index(B), Index("unitId")],
)
data class JournalLineEntity(
    @PrimaryKey val id: String,
    val entryId: String,
    val buildingId: String,
    val account: AccountKind,
    val amountMinor: Long,
    val cashAccountId: String? = null,
    val unitId: String? = null,
    val supplierId: String? = null,
    val fundId: String? = null,
    val categoryId: String? = null,
)

@Serializable
@Entity(
    tableName = "audit_events",
    foreignKeys = [ForeignKey(entity = BuildingEntity::class, parentColumns = ["id"], childColumns = [B], onDelete = ForeignKey.CASCADE)],
    indices = [Index(B)],
)
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val at: Long,
    val action: String,
    val entityType: String,
    val entityId: String,
    /** Human-readable summary incl. the reason for corrections. Contains no resident personal data. */
    val summary: String,
)
