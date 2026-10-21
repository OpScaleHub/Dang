package com.github.opscalehub.dong.data

import com.github.opscalehub.dong.domain.allocation.QuantityKind
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import androidx.room.withTransaction
import com.github.opscalehub.dong.data.db.AuditEventEntity
import com.github.opscalehub.dong.data.db.BuildingEntity
import com.github.opscalehub.dong.data.db.CashAccountEntity
import com.github.opscalehub.dong.data.db.CashKind
import com.github.opscalehub.dong.data.db.CategoryEntity
import com.github.opscalehub.dong.data.db.DongDatabase
import com.github.opscalehub.dong.data.db.FundEntity
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.db.JournalEntryEntity
import com.github.opscalehub.dong.data.db.JournalLineEntity
import com.github.opscalehub.dong.data.db.MeterEntity
import com.github.opscalehub.dong.data.db.MeterReadingEntity
import com.github.opscalehub.dong.data.db.OccupancyEntity
import com.github.opscalehub.dong.data.db.ParkingAssignmentEntity
import com.github.opscalehub.dong.data.db.ParkingSpaceEntity
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.data.db.UnitEntity
import com.github.opscalehub.dong.data.db.WaterTariffEntity
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.data.db.ChargeItemEntity
import com.github.opscalehub.dong.domain.model.ChargeMode
import com.github.opscalehub.dong.domain.allocation.Basis
import com.github.opscalehub.dong.domain.allocation.CommonWater
import com.github.opscalehub.dong.domain.allocation.SimpleBasis
import com.github.opscalehub.dong.domain.allocation.SpaceFilter
import com.github.opscalehub.dong.domain.ledger.EntryType
import com.github.opscalehub.dong.domain.ledger.LedgerException
import com.github.opscalehub.dong.domain.ledger.Posting
import com.github.opscalehub.dong.domain.ledger.PostingRules
import com.github.opscalehub.dong.domain.model.MeterRole
import com.github.opscalehub.dong.domain.model.ParkingRelation
import com.github.opscalehub.dong.domain.model.ReadingKind
import com.github.opscalehub.dong.domain.model.TariffTier
import com.github.opscalehub.dong.domain.water.TariffCalculator

/** Entry point to all repositories; shares the database, clock and journal writer. */
class Repos(val db: DongDatabase, val clock: Clock = SystemClock) {
    val buildings = BuildingRepository(this)
    val water = WaterRepository(this)
    val expenses = ExpenseRepository(this)
    val finance = FinanceRepository(this)
    val periods = PeriodRepository(this)
    val bills = BillRepository(this)
    internal val journal = JournalWriter(this)

    suspend fun <T> tx(block: suspend () -> T): T = db.withTransaction(block)

    /** True if [date] falls inside a finalized period of the building. */
    internal suspend fun locked(buildingId: String, date: Long): Boolean =
        db.periodDao().periods(buildingId).any { it.status == PeriodStatus.FINALIZED && date in it.startDate..it.endDate }

    internal suspend fun lastFinalizedEnd(buildingId: String): Long? =
        db.periodDao().periods(buildingId).filter { it.status == PeriodStatus.FINALIZED }.maxOfOrNull { it.endDate }
}

/** The only writer of journal entries: validates balance, appends, and records an audit event. */
class JournalWriter(private val r: Repos) {

    suspend fun post(
        buildingId: String,
        type: EntryType,
        date: Long,
        postings: List<Posting>,
        memo: String = "",
        reference: String = "",
        periodId: String? = null,
        expenseId: String? = null,
        unitId: String? = null,
        supplierId: String? = null,
        reversesEntryId: String? = null,
        method: String = "",
    ): String {
        val balanced = try {
            PostingRules.balanced(postings)
        } catch (e: LedgerException) {
            fail(OpError.NOT_BALANCED, e.message ?: "")
        }
        val id = newId()
        val dao = r.db.financeDao()
        dao.insertEntry(JournalEntryEntity(id, buildingId, date, type, memo, reference, periodId, expenseId, unitId, supplierId, reversesEntryId, r.clock.now(), method))
        dao.insertLines(balanced.map {
            JournalLineEntity(newId(), id, buildingId, it.account, it.amount, it.cashAccountId, it.unitId, it.supplierId, it.fundId, it.categoryId)
        })
        audit(buildingId, "POST_${type.name}", "journal_entry", id, memo)
        return id
    }

    suspend fun audit(buildingId: String, action: String, entityType: String, entityId: String, summary: String) {
        r.db.financeDao().insertAudit(AuditEventEntity(newId(), buildingId, r.clock.now(), action, entityType, entityId, summary.take(500)))
    }
}

/** Most parking spaces one unit can have in the simple screens. */
const val MAX_PARKING = 9

/** The water bill split by each unit's meter; the main meter's remainder (common use) equally. */
val METERED_WATER = AllocationPolicy.single(Basis.Consumption(CommonWater.Separate(SimpleBasis.EQUAL)))

/** Name of the monthly charge created by the setup wizard (fixed-charge buildings). */
const val MONTHLY_CHARGE = "شارژ ماهانه"
private const val S_BASE = "پایه"
private const val S_PEOPLE = "نفرات"
private const val S_AREA = "متراژ"

class BuildingRepository(private val r: Repos) {
    private val dao get() = r.db.buildingDao()

    /** Creates a building with default funds, a cash box and editable default categories. */
    /** New buildings store Rial exactly and display Toman (÷10) unless told otherwise. */
    suspend fun create(
        name: String, address: String = "", roundingIncrement: Long = 1, currencyName: String = "ریال",
        displayCurrencyName: String = "تومان", displayDivisor: Long = 10, plan: ChargePlan? = null,
        bills: SharedBills = SharedBills(), areasKnown: Boolean = false,
    ): String = r.tx {
        if (name.isBlank()) fail(OpError.INVALID_INPUT, "name")
        if (roundingIncrement < 1) fail(OpError.INVALID_INPUT, "rounding")
        if (displayDivisor !in setOf(1L, 10L, 100L, 1000L)) fail(OpError.INVALID_INPUT, "display divisor")
        val id = newId()
        dao.upsertBuilding(BuildingEntity(id, name.trim(), address.trim(), currencyName = currencyName, roundingIncrement = roundingIncrement,
            createdAt = r.clock.now(), displayCurrencyName = displayCurrencyName, displayDivisor = displayDivisor))
        val fin = r.db.financeDao()
        val op = FundEntity(newId(), id, "صندوق عملیاتی", FundKind.OPERATING)
        val res = FundEntity(newId(), id, "صندوق اندوخته", FundKind.RESERVE)
        fin.upsertFund(op)
        fin.upsertFund(res)
        fin.upsertCashAccount(CashAccountEntity(newId(), id, "صندوق نقدی", CashKind.CASH_BOX))
        (if (plan == null) defaultCategories(op.id, res.id) else simpleCategories(op.id, plan, bills, areasKnown)).forEachIndexed { i, c ->
            r.db.expenseDao().upsertCategory(CategoryEntity(newId(), id, c.name, c.policy.encode(), c.fund, c.water, c.active, i))
        }
        r.journal.audit(id, "CREATE_BUILDING", "building", id, name)
        id
    }

    data class UnitDraft(val label: String, val floor: Int, val areaCentiM2: Long, val occupants: Int, val parking: Int = 0)

    /** How the building's water bill works; asked in plain words by the setup wizard. */
    enum class WaterSetup {
        /** Each unit has its own official meter and pays its own bill: no water expense type. */
        OWN,
        /** One meter for the whole building: the bill is split like any other bill. */
        SHARED,
        /** One main meter plus an internal meter per unit: each unit pays by its own reading. */
        METERED,
    }

    /** Which bills the building pays together; everything else each unit pays itself. */
    data class SharedBills(val water: WaterSetup = WaterSetup.SHARED, val gas: Boolean = false)

    /** How the building charges residents; asked in plain words by the setup wizard. */
    sealed interface ChargePlan {
        /**
         * The same amounts every month, added together: [perUnit] (times the unit's multiplier from
         * [unitWeights], one per unit in order, when given) + [perPerson] × residents + [perM2] × area.
         * Bills are paid from the fund, not charged again.
         */
        data class Fixed(val perUnit: Long = 0, val perPerson: Long = 0, val perM2: Long = 0, val unitWeights: List<Long>? = null) : ChargePlan {
            constructor(basis: FixedBasis, rateMinor: Long) : this(
                perUnit = if (basis == FixedBasis.PER_UNIT) rateMinor else 0,
                perPerson = if (basis == FixedBasis.PER_PERSON) rateMinor else 0,
                perM2 = if (basis == FixedBasis.PER_M2) rateMinor else 0,
            )
        }
        /** Each month's actual bills are split among the units. */
        data object SplitBills : ChargePlan
    }

    /** The wizard's choice; COMBINED fills several rates of [ChargePlan.Fixed] at once. */
    enum class FixedBasis { PER_UNIT, PER_PERSON, PER_M2, COMBINED }

    /**
     * Setup wizard in one transaction: building, units with occupancy, optional opening
     * cash/reserve, and the current month as the first (draft) period.
     */
    suspend fun setup(
        name: String, address: String, displayToman: Boolean, roundingIncrement: Long,
        units: List<UnitDraft>, openingCash: Long, openingReserve: Long, plan: ChargePlan = ChargePlan.SplitBills,
        bills: SharedBills = SharedBills(),
    ): String = r.tx {
        if (units.isEmpty() || units.any { it.label.isBlank() || it.areaCentiM2 < 0 || it.occupants < 0 || it.parking !in 0..MAX_PARKING }) fail(OpError.INVALID_INPUT, "units")
        if (units.map { it.label.trim() }.toSet().size != units.size) fail(OpError.INVALID_INPUT, "duplicate label")
        if (plan is ChargePlan.Fixed) {
            val rates = listOf(plan.perUnit, plan.perPerson, plan.perM2)
            if (rates.any { it < 0 } || rates.sum() <= 0) fail(OpError.INVALID_AMOUNT, "monthly charge")
            if (plan.perM2 > 0 && units.any { it.areaCentiM2 <= 0 }) fail(OpError.INVALID_INPUT, "area")
            plan.unitWeights?.let { w -> if (w.size != units.size || w.any { it < 0 } || w.all { it == 0L }) fail(OpError.INVALID_INPUT, "multipliers") }
        }
        val b = create(name, address, roundingIncrement, displayCurrencyName = if (displayToman) "تومان" else "", displayDivisor = if (displayToman) 10 else 1,
            plan = plan, bills = bills, areasKnown = units.all { it.areaCentiM2 > 0 })
        val month = SampleData.month(r.clock.today())
        units.forEachIndexed { i, u ->
            val e = UnitEntity(newId(), b, u.floor, u.label.trim(), u.areaCentiM2, sortOrder = i + 1)
            saveUnit(e)
            setOccupancy(e.id, month.start, u.occupants)
            if (u.parking > 0) setParkingCount(e.id, u.parking, month.start)
        }
        // The water category already splits by meter (simpleCategories); only the meters are missing.
        if (bills.water == WaterSetup.METERED) r.water.generateMeters(b, splitWaterByMeter = false)
        if (openingCash != 0L || openingReserve != 0L) {
            val fin = r.db.financeDao()
            val cash = fin.cashAccounts(b).first().id
            val reserve = fin.funds(b).first { it.kind == FundKind.RESERVE }.id
            r.finance.postOpening(b, month.start - 1, mapOf(cash to openingCash), emptyMap(), emptyMap(), mapOf(reserve to openingReserve))
        }
        if (plan is ChargePlan.Fixed) {
            val cat = r.db.expenseDao().categories(b).first { !it.active }
            val ids = r.db.buildingDao().units(b).sortedBy { it.sortOrder }.map { it.id }
            val perUnitBasis = plan.unitWeights?.let { w -> Basis.Weighted(ids.zip(w).toMap()) } ?: Basis.Equal
            // One charge per non-zero rate, all of type «شارژ ماهانه» so a bill shows them as one line.
            val parts = listOf(Triple(S_BASE, plan.perUnit, perUnitBasis), Triple(S_PEOPLE, plan.perPerson, Basis.Occupants), Triple(S_AREA, plan.perM2, Basis.Area))
                .filter { it.second > 0 }
            parts.forEach { (part, rate, basis) ->
                val label = if (parts.size == 1) MONTHLY_CHARGE else "$MONTHLY_CHARGE — $part"
                r.expenses.saveChargeItem(ChargeItemEntity(newId(), b, label, cat.id, cat.defaultFundId!!, ChargeMode.RATE, rate,
                    AllocationPolicy.single(basis).encode(), activeFrom = month.start))
            }
        }
        r.periods.create(b, month.name, month.start, month.end, month.end + 10)
        b
    }

    private data class Cat(val name: String, val policy: AllocationPolicy, val fund: String, val water: Boolean = false, val active: Boolean = true)

    /**
     * The short list a new building starts with (the demo keeps the full one below). With a fixed
     * monthly charge every bill is paid from the fund by default, so nothing is charged twice; the
     * inactive «شارژ ماهانه» type only carries the monthly charge and is hidden from the expense form.
     * Bills each unit pays itself get no type at all. Metered water is always charged by reading,
     * even on top of a fixed charge: that is the point of the meters.
     */
    private fun simpleCategories(op: String, plan: ChargePlan, bills: SharedBills, areasKnown: Boolean): List<Cat> {
        val fixed = plan is ChargePlan.Fixed
        fun p(b: Basis) = if (fixed) AllocationPolicy.FUND_ONLY else AllocationPolicy.single(b)
        return listOfNotNull(
            when (bills.water) {
                WaterSetup.OWN -> null
                WaterSetup.SHARED -> Cat("آب", p(Basis.Occupants), op, water = true)
                WaterSetup.METERED -> Cat("آب", METERED_WATER, op, water = true)
            },
            // Heating gas is usually fairest by area; without areas, equally.
            if (bills.gas) Cat("گاز", p(if (areasKnown) Basis.Area else Basis.Equal), op) else null,
            Cat(if (bills.gas) "برق مشاعات" else "برق و گاز مشاعات", p(Basis.Equal), op),
            Cat("نظافت", p(Basis.Occupants), op),
            Cat("آسانسور", p(Basis.Equal), op),
            Cat("تعمیرات", p(Basis.Equal), op),
            Cat("سایر", p(Basis.Equal), op),
            if (fixed) Cat(MONTHLY_CHARGE, AllocationPolicy.FUND_ONLY, op, active = false) else null,
        )
    }

    /** Examples only — every policy is editable per building and per expense. */
    private fun defaultCategories(op: String, res: String) = listOf(
        Cat("نظافت", AllocationPolicy.single(Basis.Occupants), op),
        Cat("تعمیر و نگهداری ساختمان", AllocationPolicy.single(Basis.Area), op),
        Cat("سرویس آسانسور", AllocationPolicy.single(Basis.Equal), op),
        Cat("برق مشاعات", AllocationPolicy.single(Basis.Equal), op),
        Cat("آب", AllocationPolicy.single(Basis.Consumption(CommonWater.Separate(SimpleBasis.EQUAL))), op, water = true),
        Cat("لوله‌کشی", AllocationPolicy.single(Basis.Equal), op),
        Cat("هزینه‌های پارکینگ", AllocationPolicy.single(Basis.Parking(SpaceFilter.IN_USE)), op),
        Cat("فضای سبز", AllocationPolicy.single(Basis.Equal), op),
        Cat("نگهبانی و امنیت", AllocationPolicy.single(Basis.Equal), op),
        Cat("هزینه‌های اداری", AllocationPolicy.single(Basis.Equal), op),
        Cat("حق اندوخته", AllocationPolicy.single(Basis.Equal), res),
        Cat("تعمیرات اضطراری", AllocationPolicy.FUND_ONLY, res),
        Cat("سایر هزینه‌ها", AllocationPolicy.single(Basis.Equal), op),
    )

    suspend fun updateSettings(b: BuildingEntity) = r.tx {
        val old = dao.building(b.id) ?: fail(OpError.NOT_FOUND)
        if (b.name.isBlank() || b.roundingIncrement < 1 || b.minorDigits !in 0..4 || b.displayDivisor !in setOf(1L, 10L, 100L, 1000L)) fail(OpError.INVALID_INPUT)
        dao.upsertBuilding(b.copy(createdAt = old.createdAt))
        // Finalized periods keep their own settings snapshot; only drafts see the change.
        r.journal.audit(b.id, "UPDATE_SETTINGS", "building", b.id,
            "گرد کردن ${Fmt.number(old.roundingIncrement)}←${Fmt.number(b.roundingIncrement)}، مبنای ساکنان: ${Fa.occupancyBasis(b.occupancyBasis)}، تقویم: ${Fa.calendar(b.calendar)}")
    }

    suspend fun delete(buildingId: String) = r.tx { dao.deleteBuilding(buildingId) }

    suspend fun saveUnit(u: UnitEntity): String = r.tx {
        if (u.label.isBlank() || u.areaCentiM2 < 0) fail(OpError.INVALID_INPUT)
        val others = dao.units(u.buildingId).filter { it.id != u.id }
        if (others.any { it.label.trim() == u.label.trim() }) fail(OpError.INVALID_INPUT, "duplicate label")
        val existing = dao.unit(u.id)
        dao.upsertUnit(u.copy(label = u.label.trim()))
        r.journal.audit(u.buildingId, if (existing == null) "CREATE_UNIT" else "UPDATE_UNIT", "unit", u.id,
            "واحد ${u.label}: متراژ ${Fa.quantity(QuantityKind.AREA_CENTI_M2, u.areaCentiM2)}، ${if (u.active) "فعال" else "غیرفعال"}")
        u.id
    }

    suspend fun nextUnitSortOrder(buildingId: String) = (dao.units(buildingId).maxOfOrNull { it.sortOrder } ?: 0) + 1

    /** Records an occupancy change effective from [effectiveFrom]. History is kept; finalized snapshots are unaffected. */
    suspend fun setOccupancy(unitId: String, effectiveFrom: Long, count: Int, note: String = ""): String = r.tx {
        if (count < 0) fail(OpError.INVALID_INPUT, "count")
        val unit = dao.unit(unitId) ?: fail(OpError.NOT_FOUND)
        val id = newId()
        dao.insertOccupancy(OccupancyEntity(id, unitId, effectiveFrom, count, note, r.clock.now()))
        val backdated = r.lastFinalizedEnd(unit.buildingId)?.let { effectiveFrom <= it } == true
        r.journal.audit(unit.buildingId, "SET_OCCUPANCY", "unit", unitId,
            "واحد ${unit.label}: ${Fmt.number(count.toLong())} نفر از ${Fmt.date(effectiveFrom)}" + if (backdated) " (تاریخ در دوره قطعی‌شده؛ با اصلاحیه اعمال شود)" else "")
        id
    }

    suspend fun deleteOccupancy(id: String) = r.tx {
        val o = dao.occupancyRecord(id) ?: fail(OpError.NOT_FOUND)
        val unit = dao.unit(o.unitId) ?: fail(OpError.NOT_FOUND)
        if (r.lastFinalizedEnd(unit.buildingId)?.let { o.effectiveFrom <= it } == true) fail(OpError.LOCKED_BY_FINALIZED_PERIOD)
        dao.deleteOccupancy(id)
        r.journal.audit(unit.buildingId, "DELETE_OCCUPANCY", "unit", unit.id, "واحد ${unit.label}: حذف سابقه از ${Fmt.date(o.effectiveFrom)}")
    }

    suspend fun saveSpace(s: ParkingSpaceEntity): String = r.tx {
        if (s.label.isBlank()) fail(OpError.INVALID_INPUT)
        dao.upsertSpace(s.copy(label = s.label.trim()))
        r.journal.audit(s.buildingId, "SAVE_PARKING_SPACE", "parking_space", s.id, s.label)
        s.id
    }

    /** Assigns a space from [from]; the space's previous open assignment ends the day before. */
    suspend fun assignSpace(spaceId: String, unitId: String, relation: ParkingRelation, inUse: Boolean, vehicle: String, from: Long): String = r.tx {
        val unit = dao.unit(unitId) ?: fail(OpError.NOT_FOUND)
        dao.assignments(unit.buildingId).filter { it.spaceId == spaceId && it.effectiveTo == null }.forEach {
            if (it.effectiveFrom >= from) fail(OpError.INVALID_INPUT, "overlapping assignment")
            dao.upsertAssignment(it.copy(effectiveTo = from - 1))
        }
        val id = newId()
        dao.upsertAssignment(ParkingAssignmentEntity(id, spaceId, unitId, relation, inUse, vehicle, from))
        r.journal.audit(unit.buildingId, "ASSIGN_PARKING", "parking_space", spaceId, "به واحد ${unit.label} (${Fa.relation(relation)}، ${if (inUse) "در استفاده" else "بلااستفاده"}) از ${Fmt.date(from)}")
        id
    }

    /**
     * Gives a unit exactly [count] parking spaces from [from], for when a resident buys or sells one.
     * Extra spaces are released (they stay in the list, free for another unit); missing ones reuse
     * free spaces first, then new spaces numbered after the existing ones.
     */
    suspend fun setParkingCount(unitId: String, count: Int, from: Long) = r.tx {
        if (count !in 0..MAX_PARKING) fail(OpError.INVALID_INPUT, "parking")
        val unit = dao.unit(unitId) ?: fail(OpError.NOT_FOUND)
        val all = dao.assignments(unit.buildingId)
        val mine = all.filter { it.unitId == unitId && it.effectiveTo == null }.sortedBy { it.effectiveFrom }
        mine.drop(count).forEach { a ->
            // An assignment that starts today is simply cut to nothing; older ones end the day before.
            dao.upsertAssignment(a.copy(effectiveTo = maxOf(a.effectiveFrom, from - 1)))
            r.journal.audit(unit.buildingId, "END_PARKING", "parking_space", a.spaceId, "واحد ${unit.label} از ${Fmt.date(from)}")
        }
        var need = count - mine.size
        if (need <= 0) return@tx
        val spaces = dao.spaces(unit.buildingId)
        val taken = all.filter { it.effectiveTo == null }.map { it.spaceId }.toSet()
        spaces.filter { it.active && it.id !in taken }.take(need).forEach { s ->
            assignSpace(s.id, unitId, ParkingRelation.OWNED, true, "", from)
            need--
        }
        var next = (spaces.mapNotNull { Fmt.parseLong(it.label) }.maxOrNull() ?: spaces.size.toLong()) + 1
        repeat(need) {
            val id = saveSpace(ParkingSpaceEntity(newId(), unit.buildingId, Fmt.digits((next++).toString())))
            assignSpace(id, unitId, ParkingRelation.OWNED, true, "", from)
        }
    }

    suspend fun endAssignment(assignmentId: String, to: Long) = r.tx {
        val a = dao.assignment(assignmentId) ?: fail(OpError.NOT_FOUND)
        if (to < a.effectiveFrom) fail(OpError.INVALID_INPUT)
        dao.upsertAssignment(a.copy(effectiveTo = to))
    }
}

class WaterRepository(private val r: Repos) {
    private val dao get() = r.db.waterDao()

    suspend fun saveMeter(m: MeterEntity): String = r.tx {
        if (m.label.isBlank()) fail(OpError.INVALID_INPUT)
        if (m.role == MeterRole.UNIT && m.unitId == null) fail(OpError.INVALID_INPUT, "unit meter needs a unit")
        if (m.rolloverLimitLiters != null && m.rolloverLimitLiters <= 0) fail(OpError.INVALID_INPUT)
        dao.upsertMeter(m)
        r.journal.audit(m.buildingId, "SAVE_METER", "meter", m.id, "${m.label} (${Fa.meterRole(m.role)})")
        m.id
    }

    /** Adds a reading. Readings dated inside a finalized period must be entered as corrections instead. */
    suspend fun addReading(meterId: String, date: Long, valueLiters: Long, kind: ReadingKind = ReadingKind.REGULAR, note: String = ""): String = r.tx {
        val m = dao.meter(meterId) ?: fail(OpError.NOT_FOUND)
        if (valueLiters < 0) fail(OpError.INVALID_AMOUNT)
        if (r.locked(m.buildingId, date)) fail(OpError.LOCKED_BY_FINALIZED_PERIOD)
        // A meter's first reading is its baseline (INITIAL), and its install date follows its earliest
        // reading — so a new meter neither blocks its first period nor appears in earlier ones.
        val earlier = dao.readings(m.buildingId).any { it.meterId == meterId && it.readingDate <= date }
        val effectiveKind = if (!earlier && kind == ReadingKind.REGULAR) ReadingKind.INITIAL else kind
        if (m.installedOn == null || date < m.installedOn) dao.upsertMeter(m.copy(installedOn = date))
        val id = newId()
        dao.insertReading(MeterReadingEntity(id, meterId, date, valueLiters, effectiveKind, null, note, r.clock.now()))
        r.journal.audit(m.buildingId, "ADD_READING", "meter", meterId, "${m.label}: ${Fmt.cubicMeters(valueLiters)} م³ در ${Fmt.date(date)} (${Fa.readingKind(kind)})")
        id
    }

    data class ReadingInput(val meterId: String, val valueLiters: Long, val kind: ReadingKind = ReadingKind.REGULAR)

    /** Saves several readings dated [date] atomically: one invalid reading rolls back the whole batch. */
    suspend fun addReadings(date: Long, items: List<ReadingInput>, note: String = ""): List<String> = r.tx {
        if (items.map { it.meterId }.toSet().size != items.size) fail(OpError.INVALID_INPUT, "duplicate meter")
        items.map { addReading(it.meterId, date, it.valueLiters, it.kind, note) }
    }

    /** Corrects a reading by superseding it. Allowed in finalized periods; applied there only through a correction run. */
    suspend fun correctReading(readingId: String, newValueLiters: Long, reason: String): String = r.tx {
        if (reason.isBlank()) fail(OpError.REASON_REQUIRED)
        if (newValueLiters < 0) fail(OpError.INVALID_AMOUNT)
        val old = dao.reading(readingId) ?: fail(OpError.NOT_FOUND)
        if (dao.supersededCount(readingId) > 0) fail(OpError.ALREADY_REVERSED)
        val m = dao.meter(old.meterId) ?: fail(OpError.NOT_FOUND)
        val id = newId()
        dao.insertReading(old.copy(id = id, valueLiters = newValueLiters, supersedesId = old.id, note = reason, enteredAt = r.clock.now()))
        r.journal.audit(m.buildingId, "CORRECT_READING", "meter", m.id, "${m.label}: ${Fmt.cubicMeters(old.valueLiters)} ← ${Fmt.cubicMeters(newValueLiters)} م³ (${Fmt.date(old.readingDate)}): $reason")
        id
    }

    suspend fun deleteReading(readingId: String) = r.tx {
        val rd = dao.reading(readingId) ?: fail(OpError.NOT_FOUND)
        val m = dao.meter(rd.meterId) ?: fail(OpError.NOT_FOUND)
        if (r.lastFinalizedEnd(m.buildingId)?.let { rd.readingDate <= it } == true) fail(OpError.LOCKED_BY_FINALIZED_PERIOD)
        if (dao.supersededCount(readingId) > 0) fail(OpError.LOCKED_BY_FINALIZED_PERIOD)
        dao.deleteReading(readingId)
        r.journal.audit(m.buildingId, "DELETE_READING", "meter", m.id, "${m.label}: حذف قرائت ${Fmt.cubicMeters(rd.valueLiters)} م³ (${Fmt.date(rd.readingDate)})")
    }

    /** Replaces a meter: FINAL reading on the old one, a new meter with an INITIAL reading, same unit. */
    suspend fun replaceMeter(oldMeterId: String, date: Long, finalValueLiters: Long, newLabel: String, newSerial: String, initialValueLiters: Long): String = r.tx {
        val old = dao.meter(oldMeterId) ?: fail(OpError.NOT_FOUND)
        if (old.retiredOn != null) fail(OpError.INVALID_INPUT, "already retired")
        addReading(oldMeterId, date, finalValueLiters, ReadingKind.FINAL, "تعویض کنتور")
        dao.upsertMeter(old.copy(retiredOn = date))
        val newId = newId()
        dao.upsertMeter(MeterEntity(newId, old.buildingId, old.unitId, old.role, newLabel.ifBlank { old.label }, newSerial, old.rolloverLimitLiters, date, null, old.id))
        addReading(newId, date, initialValueLiters, ReadingKind.INITIAL, "قرائت اولیه کنتور جدید")
        r.journal.audit(old.buildingId, "REPLACE_METER", "meter", old.id, "${old.label} ← $newLabel در ${Fmt.date(date)}")
        newId
    }

    /**
     * Creates the building's main meter (if it has none) and one meter for every active unit that
     * has none, so nobody adds them one at a time. With [splitWaterByMeter], the water expense type
     * (created if missing) is split by these meters from now on. Returns how many meters were made.
     */
    suspend fun generateMeters(buildingId: String, splitWaterByMeter: Boolean = true): Int = r.tx {
        val meters = dao.meters(buildingId).filter { it.retiredOn == null }
        var made = 0
        if (meters.none { it.role == MeterRole.MAIN }) {
            saveMeter(MeterEntity(newId(), buildingId, null, MeterRole.MAIN, "کنتور اصلی ساختمان"))
            made++
        }
        r.db.buildingDao().units(buildingId).filter { it.active }.sortedBy { it.sortOrder }
            .filter { u -> meters.none { it.role == MeterRole.UNIT && it.unitId == u.id } }
            .forEach { u ->
                saveMeter(MeterEntity(newId(), buildingId, u.id, MeterRole.UNIT, "کنتور واحد ${u.label}"))
                made++
            }
        if (splitWaterByMeter) {
            val cats = r.db.expenseDao().categories(buildingId)
            val water = cats.filter { it.isWater && it.active }
            if (water.isEmpty()) {
                val op = r.db.financeDao().funds(buildingId).first { it.kind == FundKind.OPERATING }.id
                r.expenses.saveCategory(CategoryEntity(newId(), buildingId, "آب", METERED_WATER.encode(), op, isWater = true, sortOrder = -1))
            } else water.forEach { r.expenses.saveCategory(it.copy(defaultPolicyJson = METERED_WATER.encode())) }
        }
        made
    }

    suspend fun saveTariff(t: WaterTariffEntity): String = r.tx {
        if (!TariffCalculator.validate(t.toDomain())) fail(OpError.INVALID_INPUT, "tariff")
        dao.upsertTariff(t)
        r.journal.audit(t.buildingId, "SAVE_TARIFF", "water_tariff", t.id, "${t.name} از ${Fmt.date(t.effectiveFrom)}")
        t.id
    }

    fun tariffOf(buildingId: String, name: String, from: Long, fixedFee: Long, tiers: List<TariffTier>, surchargeBp: Int, commonRate: Long, id: String = newId()) =
        WaterTariffEntity(id, buildingId, name, from, fixedFee, encodeTiers(tiers), surchargeBp, commonRate)
}
