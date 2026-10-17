package com.github.opscalehub.dong.data

import com.github.opscalehub.dong.data.db.DongDatabase
import com.github.opscalehub.dong.io.BackupCodec
import com.github.opscalehub.dong.io.BackupException
import com.github.opscalehub.dong.io.BackupHeader
import com.github.opscalehub.dong.io.BackupPayload
import com.github.opscalehub.dong.io.BackupProblem
import com.github.opscalehub.dong.io.BuildingBackup

enum class RestoreMode { SKIP, ADD_MISSING, REPLACE }

enum class BuildingRestoreStatus { NEW, IDENTICAL, CONFLICT }

data class BuildingRestorePreview(
    val buildingId: String,
    val name: String,
    val status: BuildingRestoreStatus,
    val records: Int,
    /** Records whose id does not exist locally (what ADD_MISSING would insert). */
    val newRecords: Int,
    /** Records whose id exists locally but whose content differs (kept local by ADD_MISSING). */
    val differing: Int,
    val journalEntries: Int,
) {
    /** Merging is only safe when the backup is a superset of local data (same history, nothing diverged). */
    val canAddMissing: Boolean get() = differing == 0
}

data class RestorePreview(val header: BackupHeader, val payload: BackupPayload, val buildings: List<BuildingRestorePreview>, val problems: List<BackupProblem>) {
    val valid: Boolean get() = problems.isEmpty()
}

data class RestoreResult(val inserted: Int, val replacedBuildings: Int, val skipped: Int)

class BackupRepository(private val r: Repos, private val appVersion: String) {
    private val db: DongDatabase get() = r.db

    suspend fun exportBuilding(buildingId: String): BuildingBackup {
        val b = db.buildingDao().building(buildingId) ?: fail(OpError.NOT_FOUND)
        val bd = db.buildingDao()
        val wd = db.waterDao()
        val ed = db.expenseDao()
        val fd = db.financeDao()
        val pd = db.periodDao()
        return BuildingBackup(
            building = b,
            units = bd.units(buildingId), occupancy = bd.occupancy(buildingId), spaces = bd.spaces(buildingId),
            assignments = bd.assignments(buildingId), meters = wd.meters(buildingId), readings = wd.readings(buildingId),
            categories = ed.categories(buildingId), suppliers = ed.suppliers(buildingId), funds = fd.funds(buildingId),
            cashAccounts = fd.cashAccounts(buildingId), expenses = ed.expenses(buildingId), chargeItems = ed.chargeItems(buildingId),
            tariffs = wd.tariffs(buildingId), periods = pd.periods(buildingId), revisions = pd.revisionsForBuilding(buildingId),
            entries = fd.entries(buildingId), lines = fd.lines(buildingId), audit = fd.audit(buildingId),
        )
    }

    /** Serialized backup of the given buildings (all when null). Read-only, consistent snapshot. */
    suspend fun export(buildingIds: List<String>? = null): String = r.tx {
        val ids = buildingIds ?: db.buildingDao().buildings().map { it.id }
        val payload = BackupPayload(ids.map { exportBuilding(it) })
        BackupCodec.encode(payload, appVersion, com.github.opscalehub.dong.data.db.DongDatabase.VERSION, r.clock.now())
    }

    /** Decodes, validates and compares a backup with local data. Never writes. */
    suspend fun preview(text: String): RestorePreview {
        val (header, payload) = try {
            BackupCodec.decode(text, com.github.opscalehub.dong.data.db.DongDatabase.VERSION)
        } catch (e: BackupException) {
            return RestorePreview(BackupHeader(0, "", 0, 0), BackupPayload(emptyList()), emptyList(), listOf(e.problem))
        }
        val problems = BackupCodec.validate(payload)
        val previews = payload.buildings.map { incoming ->
            val local = if (db.buildingDao().building(incoming.building.id) != null) exportBuilding(incoming.building.id) else null
            if (local == null) {
                BuildingRestorePreview(incoming.building.id, incoming.building.name, BuildingRestoreStatus.NEW, incoming.recordCount, incoming.recordCount, 0, incoming.entries.size)
            } else {
                val (newCount, diff) = compare(incoming, local)
                val status = if (newCount == 0 && diff == 0) BuildingRestoreStatus.IDENTICAL else BuildingRestoreStatus.CONFLICT
                BuildingRestorePreview(incoming.building.id, incoming.building.name, status, incoming.recordCount, newCount, diff, incoming.entries.size)
            }
        }
        return RestorePreview(header, payload, previews, problems)
    }

    private fun compare(a: BuildingBackup, local: BuildingBackup): Pair<Int, Int> {
        var newCount = 0
        var diff = 0
        fun <T> cmp(incoming: List<T>, existing: List<T>, id: (T) -> String) {
            val ex = existing.associateBy(id)
            for (x in incoming) {
                val l = ex[id(x)]
                if (l == null) newCount++ else if (l != x) diff++
            }
        }
        cmp(listOf(a.building), listOf(local.building)) { it.id }
        cmp(a.units, local.units) { it.id }
        cmp(a.occupancy, local.occupancy) { it.id }
        cmp(a.spaces, local.spaces) { it.id }
        cmp(a.assignments, local.assignments) { it.id }
        cmp(a.meters, local.meters) { it.id }
        cmp(a.readings, local.readings) { it.id }
        cmp(a.categories, local.categories) { it.id }
        cmp(a.suppliers, local.suppliers) { it.id }
        cmp(a.funds, local.funds) { it.id }
        cmp(a.cashAccounts, local.cashAccounts) { it.id }
        cmp(a.expenses, local.expenses) { it.id }
        cmp(a.chargeItems, local.chargeItems) { it.id }
        cmp(a.tariffs, local.tariffs) { it.id }
        cmp(a.periods, local.periods) { it.id }
        cmp(a.revisions, local.revisions) { it.id }
        cmp(a.entries, local.entries) { it.id }
        cmp(a.lines, local.lines) { it.id }
        cmp(a.audit, local.audit) { it.id }
        return newCount to diff
    }

    /**
     * Applies a validated backup atomically. ADD_MISSING inserts only unknown ids (re-importing
     * the same file adds nothing); REPLACE deletes the local building and inserts the backup.
     */
    suspend fun apply(preview: RestorePreview, modes: Map<String, RestoreMode>): RestoreResult = r.tx {
        if (!preview.valid) fail(OpError.INVALID_INPUT, preview.problems.joinToString { it.code.name })
        var inserted = 0
        var replaced = 0
        var skipped = 0
        val rd = db.restoreDao()
        for (b in preview.payload.buildings) {
            when (modes[b.building.id] ?: RestoreMode.SKIP) {
                RestoreMode.SKIP -> {
                    skipped++
                    continue
                }
                RestoreMode.REPLACE -> {
                    db.buildingDao().deleteBuilding(b.building.id)
                    replaced++
                }
                RestoreMode.ADD_MISSING -> {
                    // Divergent histories (e.g. edited or finalized on another copy) cannot be merged safely.
                    if (preview.buildings.first { it.buildingId == b.building.id }.differing > 0) fail(OpError.INVALID_INPUT, "diverged; use replace")
                }
            }
            fun List<Long>.count() = count { it != -1L }
            inserted += rd.buildings(listOf(b.building)).count()
            inserted += rd.units(b.units).count()
            inserted += rd.occupancy(b.occupancy).count()
            inserted += rd.spaces(b.spaces).count()
            inserted += rd.assignments(b.assignments).count()
            inserted += rd.meters(b.meters).count()
            inserted += rd.readings(b.readings).count()
            inserted += rd.categories(b.categories).count()
            inserted += rd.suppliers(b.suppliers).count()
            inserted += rd.funds(b.funds).count()
            inserted += rd.cashAccounts(b.cashAccounts).count()
            inserted += rd.expenses(b.expenses).count()
            inserted += rd.chargeItems(b.chargeItems).count()
            inserted += rd.tariffs(b.tariffs).count()
            inserted += rd.periods(b.periods).count()
            inserted += rd.revisions(b.revisions).count()
            inserted += rd.entries(b.entries).count()
            inserted += rd.lines(b.lines).count()
            inserted += rd.audit(b.audit).count()
            r.journal.audit(b.building.id, "RESTORE_${modes[b.building.id]}", "building", b.building.id,
                "backup ${preview.header.appVersion} @${preview.header.createdAt}")
        }
        // Merging can never unbalance the ledger (entries and their lines travel together), but verify anyway.
        for (b in preview.payload.buildings) {
            if (modes[b.building.id] == RestoreMode.SKIP || modes[b.building.id] == null) continue
            val bal = r.finance.balances(b.building.id)
            if (!bal.reconciles) fail(OpError.NOT_BALANCED, b.building.name)
        }
        RestoreResult(inserted, replaced, skipped)
    }
}
