package com.github.opscalehub.dong.data

import androidx.test.core.app.ApplicationProvider
import com.github.opscalehub.dong.data.db.CategoryEntity
import com.github.opscalehub.dong.data.db.DongDatabase
import com.github.opscalehub.dong.data.db.ExpenseEntity
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.db.MeterEntity
import com.github.opscalehub.dong.data.db.UnitEntity
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.calendar.Jalali
import com.github.opscalehub.dong.domain.model.MeterRole

class FixedClock(var day: Long = Jalali.toEpochDay(1405, 8, 5), var millis: Long = 1_760_000_000_000) : Clock {
    override fun now(): Long = millis++
    override fun today(): Long = day
}

/** A building with 9 sample units (958 m²), occupancy, one main + one meter per unit, and readings. */
class World(name: String? = null) {
    val clock = FixedClock()
    val db: DongDatabase = DongDatabase.build(ApplicationProvider.getApplicationContext(), name)
    val r = Repos(db, clock)
    lateinit var b: String
    lateinit var units: List<UnitEntity>
    lateinit var op: String
    lateinit var res: String
    lateinit var cash: String
    lateinit var categories: Map<String, CategoryEntity>
    val start = Jalali.toEpochDay(1405, 7, 1)
    val end = Jalali.toEpochDay(1405, 7, 30)

    suspend fun setup(buildingName: String = "ساختمان آزمون"): World {
        b = r.buildings.create(buildingName, roundingIncrement = 1000)
        val spec = listOf(1 to 60, 1 to 60, 1 to 70, 2 to 156, 2 to 100, 3 to 156, 3 to 100, 4 to 156, 4 to 100)
        val occ = listOf(2, 1, 3, 4, 2, 5, 3, 4, 2)
        val ids = spec.mapIndexed { i, (floor, area) ->
            val no = if (i < 3) i + 1 else (i - 3) % 2 + 1
            val u = UnitEntity(newId(), b, floor, "$floor-$no", area * 100L, sortOrder = i + 1)
            r.buildings.saveUnit(u)
            r.buildings.setOccupancy(u.id, start - 365, occ[i])
            u
        }
        units = ids
        val funds = db.financeDao().funds(b)
        op = funds.first { it.kind == FundKind.OPERATING }.id
        res = funds.first { it.kind == FundKind.RESERVE }.id
        cash = db.financeDao().cashAccounts(b).first().id
        categories = db.expenseDao().categories(b).associateBy { it.name }
        return this
    }

    suspend fun addMeters(): Map<String, String> {
        val main = r.water.saveMeter(MeterEntity(newId(), b, null, MeterRole.MAIN, "اصلی"))
        r.water.addReading(main, start - 1, 1_000_000)
        r.water.addReading(main, end, 1_100_000)
        val ids = units.mapIndexed { i, u ->
            val m = r.water.saveMeter(MeterEntity(newId(), b, u.id, MeterRole.UNIT, u.label))
            r.water.addReading(m, start - 1, 100_000)
            r.water.addReading(m, end, 100_000 + (i + 1) * 1_000L)
            u.id to m
        }.toMap()
        return ids + ("main" to main)
    }

    fun unit(label: String) = units.first { it.label == label }

    fun expense(category: String, amount: Long, periodId: String?, policy: AllocationPolicy? = null, supplierId: String? = null, date: Long = end) =
        categories.getValue(category).let { c ->
            ExpenseEntity(newId(), b, periodId, c.id, c.defaultFundId ?: op, supplierId, date, category, amount,
                policyJson = (policy ?: AllocationPolicy.decode(c.defaultPolicyJson)).encode(), createdAt = 0)
        }
}
