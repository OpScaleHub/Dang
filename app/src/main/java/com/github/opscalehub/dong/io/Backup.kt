package com.github.opscalehub.dong.io

import com.github.opscalehub.dong.data.db.AuditEventEntity
import com.github.opscalehub.dong.data.db.BillShareEntity
import com.github.opscalehub.dong.data.db.BuildingEntity
import com.github.opscalehub.dong.data.db.CashAccountEntity
import com.github.opscalehub.dong.data.db.CategoryEntity
import com.github.opscalehub.dong.data.db.ChargeItemEntity
import com.github.opscalehub.dong.data.db.ExpenseEntity
import com.github.opscalehub.dong.data.db.FundEntity
import com.github.opscalehub.dong.data.db.JournalEntryEntity
import com.github.opscalehub.dong.data.db.JournalLineEntity
import com.github.opscalehub.dong.data.db.MeterEntity
import com.github.opscalehub.dong.data.db.MeterReadingEntity
import com.github.opscalehub.dong.data.db.OccupancyEntity
import com.github.opscalehub.dong.data.db.ParkingAssignmentEntity
import com.github.opscalehub.dong.data.db.ParkingSpaceEntity
import com.github.opscalehub.dong.data.db.PeriodEntity
import com.github.opscalehub.dong.data.db.PeriodRevisionEntity
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.data.db.SupplierEntity
import com.github.opscalehub.dong.data.db.UnitEntity
import com.github.opscalehub.dong.data.db.WaterTariffEntity
import com.github.opscalehub.dong.data.decodeTiers
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.period.PeriodSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.MessageDigest

@Serializable
data class BuildingBackup(
    val building: BuildingEntity,
    val units: List<UnitEntity> = emptyList(),
    val occupancy: List<OccupancyEntity> = emptyList(),
    val spaces: List<ParkingSpaceEntity> = emptyList(),
    val assignments: List<ParkingAssignmentEntity> = emptyList(),
    val meters: List<MeterEntity> = emptyList(),
    val readings: List<MeterReadingEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val suppliers: List<SupplierEntity> = emptyList(),
    val funds: List<FundEntity> = emptyList(),
    val cashAccounts: List<CashAccountEntity> = emptyList(),
    val expenses: List<ExpenseEntity> = emptyList(),
    val chargeItems: List<ChargeItemEntity> = emptyList(),
    val tariffs: List<WaterTariffEntity> = emptyList(),
    val periods: List<PeriodEntity> = emptyList(),
    val revisions: List<PeriodRevisionEntity> = emptyList(),
    val entries: List<JournalEntryEntity> = emptyList(),
    val lines: List<JournalLineEntity> = emptyList(),
    val audit: List<AuditEventEntity> = emptyList(),
    /** Added in schema v4; older backups have none. */
    val billShares: List<BillShareEntity> = emptyList(),
) {
    /** Every row's id, for duplicate detection and merge counting. */
    fun allIds(): List<String> = listOf(building.id) + units.map { it.id } + occupancy.map { it.id } + spaces.map { it.id } +
        assignments.map { it.id } + meters.map { it.id } + readings.map { it.id } + categories.map { it.id } +
        suppliers.map { it.id } + funds.map { it.id } + cashAccounts.map { it.id } + expenses.map { it.id } +
        chargeItems.map { it.id } + tariffs.map { it.id } + periods.map { it.id } + revisions.map { it.id } +
        entries.map { it.id } + lines.map { it.id } + audit.map { it.id } + billShares.map { it.id }

    val recordCount: Int get() = allIds().size
}

@Serializable
data class BackupPayload(val buildings: List<BuildingBackup>)

data class BackupHeader(val formatVersion: Int, val appVersion: String, val schemaVersion: Int, val createdAt: Long)

class BackupException(val problem: BackupProblem) : Exception(problem.toString())

enum class ProblemCode {
    NOT_A_BACKUP, UNSUPPORTED_VERSION, CHECKSUM_MISMATCH, MALFORMED, TOO_LARGE,
    DUPLICATE_ID, BROKEN_REFERENCE, UNBALANCED_ENTRY, TRIAL_BALANCE, INVALID_VALUE, BAD_SNAPSHOT,
}

data class BackupProblem(val code: ProblemCode, val detail: String = "")

/**
 * File format:
 * `{"format":"dong-backup","formatVersion":1,"appVersion":..,"schemaVersion":..,"createdAt":..,"payloadSha256":"..","payload":{..}}`
 * The checksum covers the compact JSON text of `payload` exactly as written.
 */
object BackupCodec {
    const val FORMAT = "dong-backup"
    const val FORMAT_VERSION = 1
    const val MAX_BYTES = 64L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }
    private val lenient = Json { ignoreUnknownKeys = true }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun encode(payload: BackupPayload, appVersion: String, schemaVersion: Int, createdAt: Long): String {
        val body = json.encodeToString(BackupPayload.serializer(), payload)
        val header = buildString {
            append("{\"format\":\"").append(FORMAT).append("\",")
            append("\"formatVersion\":").append(FORMAT_VERSION).append(',')
            append("\"appVersion\":").append(json.encodeToString(String.serializer(), appVersion)).append(',')
            append("\"schemaVersion\":").append(schemaVersion).append(',')
            append("\"createdAt\":").append(createdAt).append(',')
            append("\"payloadSha256\":\"").append(sha256(body)).append("\",")
            append("\"payload\":")
        }
        return header + body + "}"
    }

    /** Parses and verifies format, version and checksum. Throws [BackupException]. */
    fun decode(text: String, maxSchemaVersion: Int): Pair<BackupHeader, BackupPayload> {
        if (text.length > MAX_BYTES) throw BackupException(BackupProblem(ProblemCode.TOO_LARGE))
        val root: JsonObject = try {
            lenient.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw BackupException(BackupProblem(ProblemCode.NOT_A_BACKUP))
        }
        if (root["format"]?.jsonPrimitive?.content != FORMAT) throw BackupException(BackupProblem(ProblemCode.NOT_A_BACKUP))
        val header = try {
            BackupHeader(
                root.getValue("formatVersion").jsonPrimitive.int,
                root.getValue("appVersion").jsonPrimitive.content,
                root.getValue("schemaVersion").jsonPrimitive.int,
                root.getValue("createdAt").jsonPrimitive.long,
            )
        } catch (e: Exception) {
            throw BackupException(BackupProblem(ProblemCode.MALFORMED, "header"))
        }
        if (header.formatVersion > FORMAT_VERSION || header.schemaVersion > maxSchemaVersion) {
            throw BackupException(BackupProblem(ProblemCode.UNSUPPORTED_VERSION, "${header.formatVersion}/${header.schemaVersion}"))
        }
        val payloadElement: JsonElement = root["payload"] ?: throw BackupException(BackupProblem(ProblemCode.MALFORMED, "payload"))
        val body = lenient.encodeToString(JsonElement.serializer(), payloadElement)
        if (sha256(body) != root["payloadSha256"]?.jsonPrimitive?.content) throw BackupException(BackupProblem(ProblemCode.CHECKSUM_MISMATCH))
        val payload = try {
            lenient.decodeFromJsonElement(BackupPayload.serializer(), payloadElement)
        } catch (e: Exception) {
            throw BackupException(BackupProblem(ProblemCode.MALFORMED, e.message?.take(200) ?: ""))
        }
        return header to payload
    }

    /** Structural and financial validation of an untrusted payload. Empty list = valid. */
    fun validate(p: BackupPayload): List<BackupProblem> {
        val out = mutableListOf<BackupProblem>()
        val all = p.buildings.flatMap { it.allIds() }
        all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.take(5).forEach { out += BackupProblem(ProblemCode.DUPLICATE_ID, it) }
        for (b in p.buildings) {
            val bid = b.building.id
            fun ref(ok: Boolean, what: String) {
                if (!ok) out += BackupProblem(ProblemCode.BROKEN_REFERENCE, what)
            }
            if (b.building.name.isBlank() || b.building.roundingIncrement < 1) out += BackupProblem(ProblemCode.INVALID_VALUE, "building")
            val units = b.units.map { it.id }.toSet()
            val spaces = b.spaces.map { it.id }.toSet()
            val meters = b.meters.map { it.id }.toSet()
            val periods = b.periods.map { it.id }.toSet()
            val entries = b.entries.map { it.id }.toSet()
            val funds = b.funds.map { it.id }.toSet()
            val cats = b.categories.map { it.id }.toSet()
            val owned = listOf(b.units.map { it.buildingId }, b.spaces.map { it.buildingId }, b.meters.map { it.buildingId },
                b.categories.map { it.buildingId }, b.suppliers.map { it.buildingId }, b.funds.map { it.buildingId },
                b.cashAccounts.map { it.buildingId }, b.expenses.map { it.buildingId }, b.chargeItems.map { it.buildingId },
                b.tariffs.map { it.buildingId }, b.periods.map { it.buildingId }, b.entries.map { it.buildingId },
                b.lines.map { it.buildingId }, b.audit.map { it.buildingId }, b.billShares.map { it.buildingId }).flatten()
            ref(owned.all { it == bid }, "buildingId")
            ref(b.occupancy.all { it.unitId in units }, "occupancy.unitId")
            ref(b.assignments.all { it.spaceId in spaces && it.unitId in units }, "parking_assignments")
            ref(b.meters.all { it.unitId == null || it.unitId in units }, "meters.unitId")
            ref(b.readings.all { it.meterId in meters }, "meter_readings.meterId")
            ref(b.expenses.all { (it.periodId == null || it.periodId in periods) && it.fundId in funds && it.categoryId in cats }, "expenses")
            ref(b.revisions.all { it.periodId in periods }, "period_revisions")
            ref(b.lines.all { it.entryId in entries }, "journal_lines.entryId")
            ref(b.billShares.all { it.periodId in periods && it.unitId in units }, "bill_shares")
            if (b.occupancy.any { it.occupantCount < 0 } || b.units.any { it.areaCentiM2 < 0 } || b.readings.any { it.valueLiters < 0 }) {
                out += BackupProblem(ProblemCode.INVALID_VALUE, "negative quantity")
            }
            try {
                b.expenses.forEach { AllocationPolicy.decode(it.policyJson) }
                b.categories.forEach { AllocationPolicy.decode(it.defaultPolicyJson) }
                b.chargeItems.forEach { AllocationPolicy.decode(it.policyJson) }
                b.tariffs.forEach { decodeTiers(it.tiersJson) }
            } catch (e: Exception) {
                out += BackupProblem(ProblemCode.INVALID_VALUE, "policy/tariff json")
            }
            try {
                b.periods.filter { it.status == PeriodStatus.FINALIZED }.forEach { PeriodSnapshot.decode(it.snapshotJson ?: error("missing")) }
                b.revisions.forEach { PeriodSnapshot.decode(it.snapshotJson) }
            } catch (e: Exception) {
                out += BackupProblem(ProblemCode.BAD_SNAPSHOT)
            }
            val byEntry = b.lines.groupBy { it.entryId }
            b.entries.filter { (byEntry[it.id] ?: emptyList()).sumOf { l -> l.amountMinor } != 0L || byEntry[it.id].isNullOrEmpty() }
                .take(5).forEach { out += BackupProblem(ProblemCode.UNBALANCED_ENTRY, it.id) }
            if (b.lines.sumOf { it.amountMinor } != 0L) out += BackupProblem(ProblemCode.TRIAL_BALANCE, bid)
        }
        return out
    }
}
