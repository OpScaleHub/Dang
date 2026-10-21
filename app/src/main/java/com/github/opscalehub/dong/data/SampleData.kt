package com.github.opscalehub.dong.data

import com.github.opscalehub.dong.data.db.ChargeItemEntity
import com.github.opscalehub.dong.data.db.ExpenseEntity
import com.github.opscalehub.dong.data.db.FundKind
import com.github.opscalehub.dong.data.db.MeterEntity
import com.github.opscalehub.dong.data.db.ParkingSpaceEntity
import com.github.opscalehub.dong.data.db.SupplierEntity
import com.github.opscalehub.dong.data.db.UnitEntity
import com.github.opscalehub.dong.domain.allocation.AllocationPolicy
import com.github.opscalehub.dong.domain.allocation.Basis
import com.github.opscalehub.dong.domain.allocation.CommonWater
import com.github.opscalehub.dong.domain.allocation.SimpleBasis
import com.github.opscalehub.dong.domain.allocation.SpaceFilter
import com.github.opscalehub.dong.domain.calendar.Jalali
import com.github.opscalehub.dong.domain.model.ChargeMode
import com.github.opscalehub.dong.domain.model.MeterRole
import com.github.opscalehub.dong.domain.model.ParkingRelation
import com.github.opscalehub.dong.domain.model.ReadingKind
import com.github.opscalehub.dong.i18n.Fmt

/**
 * The 9-unit sample building from the brief. Occupant counts, parking, meters,
 * readings, expenses and payments are SYNTHETIC and labelled as such; nothing
 * describes a real building or person.
 */
object SampleData {
    const val NOTE = "داده نمونه (ساختگی) — قابل ویرایش"

    /** floor, unit number, area m², sample occupants */
    private val UNITS = listOf(
        Triple(1, 1, 60 to 2), Triple(1, 2, 60 to 1), Triple(1, 3, 70 to 3),
        Triple(2, 1, 156 to 4), Triple(2, 2, 100 to 2),
        Triple(3, 1, 156 to 5), Triple(3, 2, 100 to 3),
        Triple(4, 1, 156 to 4), Triple(4, 2, 100 to 2),
    )

    data class Month(val start: Long, val end: Long, val name: String)

    fun month(epochDay: Long, offset: Int = 0): Month {
        val j = Jalali.fromEpochDay(epochDay)
        var y = j.year
        var m = j.month + offset
        while (m < 1) { m += 12; y-- }
        while (m > 12) { m -= 12; y++ }
        val start = Jalali.toEpochDay(y, m, 1)
        val end = Jalali.toEpochDay(y, m, Jalali.monthLength(y, m))
        return Month(start, end, "${Jalali.monthNames[m - 1]} ${Fmt.digits(y.toString())}")
    }

    /** The in-app demo: short, so it is easy to find and delete when the trial is over. */
    const val DEMO_NAME = "نمونه"

    /** Months of finalized history before the brief's two-month scenario, so the in-app demo covers a year. */
    const val DEMO_HISTORY = 10

    /** Creates the sample building (structure + sample occupancy). Returns its id. [history] as in [addDemoData]. */
    suspend fun createBuilding(r: Repos, name: String = "ساختمان نمونه (ساختگی)", history: Int = 0): String = r.tx {
        val b = r.buildings.create(name, "نشانی نمونه", roundingIncrement = 1000)
        val from = month(r.clock.today(), -2 - history).start
        UNITS.forEachIndexed { i, (floor, no, ao) ->
            val u = UnitEntity(newId(), b, floor, Fmt.digits("$floor-$no"), ao.first * 100L, notes = NOTE, sortOrder = i + 1)
            r.buildings.saveUnit(u)
            r.buildings.setOccupancy(u.id, from, ao.second, NOTE)
        }
        b
    }

    /**
     * Adds synthetic demo activity covering every feature of the brief's sample list. With [history] > 0,
     * that many earlier months come first, each finalized, with everyday expenses, seasonal water and
     * different payment habits (on time, partial, late, ahead, a bounced cheque), so reports and debts
     * look like a real year. [history] = 0 is the brief's reference scenario that the workbook checks.
     */
    suspend fun addDemoData(r: Repos, b: String, history: Int = 0): Unit = r.tx {
        val db = r.db
        val units = db.buildingDao().units(b).associateBy { Fmt.latin(it.label) }
        fun u(label: String) = units.getValue(label).id
        val funds = db.financeDao().funds(b)
        val op = funds.first { it.kind == FundKind.OPERATING }.id
        val res = funds.first { it.kind == FundKind.RESERVE }.id
        val cash = db.financeDao().cashAccounts(b).first().id
        val cats = db.expenseDao().categories(b).associateBy { it.name }
        val prev = month(r.clock.today(), -1)
        val cur = month(r.clock.today(), 0)
        val past = (history downTo 1).map { month(r.clock.today(), -1 - it) }
        val since = past.firstOrNull()?.start ?: (prev.start - 30)

        // Parking (synthetic): ownership vs. use differ on purpose.
        val spaces = (1..8).associateWith { n -> r.buildings.saveSpace(ParkingSpaceEntity(newId(), b, "P$n", NOTE)) }
        val assign = listOf(
            Triple(1, "2-1", true), Triple(2, "2-1", false), Triple(3, "3-1", true), Triple(4, "3-1", true),
            Triple(5, "4-1", true), Triple(6, "2-2", true), Triple(7, "3-2", true),
        )
        assign.forEach { (n, unit, inUse) ->
            val rel = when (n) { 6 -> ParkingRelation.ASSIGNED; 7 -> ParkingRelation.RENTED; else -> ParkingRelation.OWNED }
            r.buildings.assignSpace(spaces.getValue(n), u(unit), rel, inUse, if (inUse) "خودرو نمونه" else "", since)
        }

        // Meters and readings (synthetic, litres). Unit 1-3 rolls over; 2-2's meter is replaced mid-month.
        val main = r.water.saveMeter(MeterEntity(newId(), b, null, MeterRole.MAIN, "کنتور اصلی", notes = NOTE))
        val readings = mapOf(
            "1-1" to (310_200L to 322_700L), "1-2" to (205_000L to 205_000L), "1-3" to (9_998_500L to 3_200L),
            "2-1" to (1_402_300L to 1_421_100L), "2-2" to (812_000L to 815_400L), "3-1" to (1_650_000L to 1_672_400L),
            "3-2" to (980_500L to 994_200L), "4-1" to (1_710_000L to 1_731_300L), "4-2" to (1_005_000L to 1_017_600L),
        )
        val meters = mutableMapOf<String, String>()
        for ((label, rd) in readings) {
            meters[label] = r.water.saveMeter(MeterEntity(newId(), b, u(label), MeterRole.UNIT, "کنتور ${Fmt.digits(label)}", rolloverLimitLiters = if (label == "1-3") 10_000_000 else null, notes = NOTE))
        }
        // Readings at each history month's end, counted back from the scenario's opening values; more water in summer.
        val rnd = kotlin.random.Random(1405)
        val use = past.map { m ->
            val summer = Jalali.fromEpochDay(m.start).month in 2..6
            readings.keys.associateWith { (USE.getValue(it) * (if (summer) 1.3 else 1.0) * (0.85 + rnd.nextDouble() * 0.3)).toLong() / 100 * 100 }
        }
        val common = past.map { 8_000L + rnd.nextInt(6) * 1_000L }
        for (i in 0..past.size) {
            val date = if (i < past.size) past[i].start - 1 else prev.start - 1
            val later = use.drop(i)
            r.water.addReading(main, date, 5_120_000 - later.sumOf { it.values.sum() } - common.drop(i).sum())
            for ((label, rd) in readings) r.water.addReading(meters.getValue(label), date, rd.first - later.sumOf { it.getValue(label) })
        }
        r.water.addReading(main, prev.end, 5_262_000)
        for ((label, rd) in readings) {
            val m = meters.getValue(label)
            when (label) {
                "1-3" -> r.water.addReading(m, prev.end, rd.second, ReadingKind.ROLLOVER, "گردش کنتور (نمونه)")
                "2-2" -> meters[label] = r.water.replaceMeter(m, prev.start + 14, rd.second, "کنتور جدید ${Fmt.digits("2-2")}", "", 0).also { r.water.addReading(it, prev.end, 6_100) }
                else -> r.water.addReading(m, prev.end, rd.second)
            }
        }

        val sup = r.expenses.saveSupplier(SupplierEntity(newId(), b, "تامین‌کننده نمونه", notes = NOTE))
        r.expenses.saveChargeItem(ChargeItemEntity(newId(), b, "حق اندوخته ماهانه", cats.getValue("حق اندوخته").id, res, ChargeMode.TOTAL, 9_000_000,
            AllocationPolicy.single(Basis.Equal).encode(), activeFrom = since))
        val openUnits = mapOf(u("1-2") to 1_200_000L, u("3-1") to -500_000L)
        if (past.isNotEmpty()) {
            r.finance.postOpening(b, since - 1, mapOf(cash to 60_000_000), openUnits, emptyMap(), mapOf(res to 40_000_000L))
            past.forEachIndexed { i, m -> addHistoryMonth(r, b, i, past.lastIndex, m, use[i].values.sum() + common[i], sup, op, res, cash, ::u) }
        }

        // Previous month: full scenario, finalized.
        val p1 = r.periods.create(b, prev.name + " (نمونه)", prev.start, prev.end, prev.end + 10)
        fun exp(cat: String, amount: Long, desc: String, policy: AllocationPolicy? = null, fund: String = op, day: Long = prev.end) =
            ExpenseEntity(newId(), b, p1, cats.getValue(cat).id, fund, sup, day, "$desc (نمونه)", amount,
                policyJson = (policy ?: AllocationPolicy.decode(cats.getValue(cat).defaultPolicyJson)).encode(), createdAt = 0)
        r.expenses.create(exp("نظافت", 9_000_000, "نظافت راه‌پله"), cash)
        r.expenses.create(exp("هزینه‌های اداری", 4_500_000, "هزینه‌های عمومی", AllocationPolicy.single(Basis.Equal)), cash)
        val maint = exp("تعمیر و نگهداری ساختمان", 12_000_000, "سرویس موتورخانه")
        r.expenses.create(maint)
        r.expenses.paySupplier(maint.id, 6_000_000, cash, prev.end)
        r.expenses.create(exp("آب", 18_000_000, "قبض آب", AllocationPolicy.single(Basis.Consumption(CommonWater.Separate(SimpleBasis.EQUAL)))))
        r.expenses.create(exp("هزینه‌های پارکینگ", 1_600_000, "برق پارکینگ", AllocationPolicy.single(Basis.Parking(SpaceFilter.IN_USE))), cash)
        val weights = mapOf(u("2-1") to 1L, u("2-2") to 1L, u("3-1") to 2L, u("3-2") to 2L, u("4-1") to 3L, u("4-2") to 3L)
        r.expenses.create(exp("سرویس آسانسور", 6_000_000, "سرویس آسانسور (وزن‌ها نمونه)", AllocationPolicy.single(Basis.Weighted(weights))), cash)
        r.expenses.create(exp("سایر هزینه‌ها", 2_500_000, "تعمیر درب واحدها", AllocationPolicy.single(Basis.Explicit(mapOf(u("2-1") to 1_500_000L, u("4-2") to 1_000_000L)))), cash)
        r.expenses.create(exp("تعمیرات اضطراری", 3_000_000, "تعمیر اضطراری لوله اصلی", AllocationPolicy.FUND_ONLY, res), cash)
        if (past.isEmpty()) r.finance.postOpening(b, prev.start - 1, mapOf(cash to 60_000_000), openUnits, emptyMap(), mapOf(res to 40_000_000L))
        r.periods.finalize(p1)
        listOf("1-1" to 3_000_000L, "1-2" to 2_000_000L, "2-1" to 9_000_000L, "2-2" to 4_500_000L, "3-1" to 10_000_000L,
            "3-2" to 4_000_000L, "4-1" to 5_000_000L, "4-2" to 4_600_000L).forEachIndexed { i, (label, amt) ->
            r.finance.receivePayment(b, u(label), amt, cash, minOf(prev.end + 1 + i, r.clock.today()), "نمونه-${i + 1}", "دریافت نمونه", "کارت به کارت")
        }
        r.finance.adjust(b, u("1-2"), -300_000, op, cats.getValue("نظافت").id, prev.end + 1, "اصلاح نمونه: کسر هزینه نظافت")
        r.finance.refund(b, u("3-1"), 500_000, cash, prev.end + 1, "استرداد بستانکاری (نمونه)")
        r.finance.transferFunds(b, op, res, 5_000_000, prev.end + 1, "انتقال نمونه به اندوخته")

        // Current month: a draft with one expense and no closing readings yet (shows missing readings).
        val p2 = r.periods.create(b, cur.name + " (نمونه)", cur.start, cur.end, cur.end + 10)
        r.expenses.create(ExpenseEntity(newId(), b, p2, cats.getValue("نظافت").id, op, sup, cur.start, "نظافت راه‌پله (نمونه)", 9_000_000,
            policyJson = cats.getValue("نظافت").defaultPolicyJson, createdAt = 0))
        r.journal.audit(b, "DEMO_DATA", "building", b, NOTE)
    }

    /** Typical monthly water use per unit in litres (synthetic). 1-2 is often away. */
    private val USE = mapOf(
        "1-1" to 12_000L, "1-2" to 4_000L, "1-3" to 5_000L, "2-1" to 18_000L, "2-2" to 9_000L,
        "3-1" to 22_000L, "3-2" to 13_000L, "4-1" to 21_000L, "4-2" to 12_000L,
    )

    /**
     * One finalized history month, then each unit pays in the next month in its own way:
     * most pay the full bill, 1-2 pays part, 3-1 rounds up and finally pays a few months ahead, 4-2 pays every
     * other month and late, 2-1 skips one month, and 1-3's cheque in month 5 bounces.
     */
    private suspend fun addHistoryMonth(r: Repos, b: String, i: Int, last: Int, m: Month, waterLiters: Long, sup: String, op: String, res: String, cash: String, u: (String) -> String) {
        val cats = r.db.expenseDao().categories(b).associateBy { it.name }
        val p = r.periods.create(b, m.name + " (نمونه)", m.start, m.end, m.end + 10)
        suspend fun exp(cat: String, amount: Long, desc: String, day: Long = m.end, fund: String = op, paid: Boolean = true) =
            ExpenseEntity(newId(), b, p, cats.getValue(cat).id, fund, sup, day, "$desc (نمونه)", amount,
                policyJson = cats.getValue(cat).defaultPolicyJson, createdAt = 0).also { r.expenses.create(it, if (paid) cash else null) }
        val winter = Jalali.fromEpochDay(m.start).month in 9..12
        exp("نظافت", if (i < 5) 8_000_000 else 9_000_000, "نظافت راه‌پله", day = m.start + 14)
        exp("آب", waterLiters * 125 / 1000 * 1000, "قبض آب")
        exp("برق مشاعات", if (winter) 4_200_000 else 2_800_000, "قبض برق مشاعات")
        exp("سرویس آسانسور", 6_000_000, "سرویس ماهانه آسانسور", day = m.start + 9)
        when (i) {
            2 -> exp("تعمیر و نگهداری ساختمان", 15_000_000, "رنگ‌آمیزی راه‌پله", paid = false).also { r.expenses.paySupplier(it.id, 15_000_000, cash, m.end + 12) }
            4 -> exp("فضای سبز", 3_000_000, "هرس درخت‌ها")
            6 -> exp("تعمیرات اضطراری", 7_000_000, "تعویض پمپ آب", fund = res)
            8 -> exp("نگهبانی و امنیت", 4_000_000, "دوربین ورودی")
        }
        r.periods.finalize(p)

        val pay = m.end + 1
        for (label in listOf("1-1", "1-2", "1-3", "2-1", "2-2", "3-1", "3-2", "4-1", "4-2")) {
            val owed = r.finance.unitStatement(b, u(label)).closing
            val amount = when (label) {
                "1-2" -> owed * 6 / 10
                "3-1" -> if (i == last) owed + 15_000_000 else (owed + 5_000_000) / 5_000_000 * 5_000_000
                "4-2" -> if (i % 2 == 0) owed else 0
                "2-1" -> if (i == 4) 0 else owed
                else -> owed
            }.let { (it + 9_999) / 10_000 * 10_000 }
            if (amount <= 0) continue
            val day = pay + when (label) { "4-2" -> 22; "1-2" -> 15; else -> 2 + label.hashCode().mod(8) }
            val method = when (label) { "1-3" -> "چک"; "2-2" -> "نقدی"; else -> "کارت به کارت" }
            val id = r.finance.receivePayment(b, u(label), amount, cash, day, "نمونه-${Fmt.digits("${i + 1}-$label")}", "دریافت نمونه", method)
            if (label == "1-3" && i == 4) r.finance.reverse(id, "چک برگشت خورد (نمونه)")
        }
    }
}
