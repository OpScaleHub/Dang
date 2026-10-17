package com.github.opscalehub.dong.io

import com.github.opscalehub.dong.data.ReportData
import com.github.opscalehub.dong.data.UnitStatement
import com.github.opscalehub.dong.data.db.ExpenseStatus
import com.github.opscalehub.dong.data.db.PeriodEntity
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.data.policy
import com.github.opscalehub.dong.domain.allocation.ComponentPart
import com.github.opscalehub.dong.domain.allocation.QuantityKind
import com.github.opscalehub.dong.domain.ledger.AccountKind
import com.github.opscalehub.dong.domain.ledger.EntryType
import com.github.opscalehub.dong.domain.period.PeriodResult
import com.github.opscalehub.dong.i18n.Fa
import com.github.opscalehub.dong.i18n.Fmt
import com.github.opscalehub.dong.io.XlsxWriter.Cell
import com.github.opscalehub.dong.io.XlsxWriter.Style
import java.io.OutputStream

private fun t(s: String, st: Style = Style.NORMAL) = Cell.Text(s, st)
private fun n(v: Long) = Cell.Num(v)

/** Builds the exported accounting workbook (values from the ledger; totals as live formulas). */
object WorkbookExporter {

    fun write(d: ReportData, out: OutputStream) = build(d).write(out)

    fun build(d: ReportData): XlsxWriter {
        val cal = d.building.calendar
        fun date(e: Long) = Fmt.date(e, cal, persian = false)
        val x = XlsxWriter()

        // 1. Summary
        val s = x.sheet("خلاصه").width(0, 46.0).width(1, 20.0)
        s.row(t("گزارش حسابداری ساختمان «${d.building.name}»", Style.TITLE))
        s.row(t("تاریخ تهیه"), t(Fmt.dateTime(d.generatedAt, cal)))
        s.row(t("واحد پول"), t(d.building.currencyName))
        s.row(Cell.Empty)
        val b = d.balances
        val r0 = s.rowCount + 1
        s.row(t("موجودی نقد و بانک"), n(b.cashTotal))
        s.row(t("مطالبات خالص از واحدها (بدهی − بستانکاری ساکنان)"), n(b.units.values.sum()))
        s.row(t("بدهی به تامین‌کنندگان"), n(b.payablesTotal))
        d.funds.forEach { f -> s.row(t("مانده ${f.name}"), n(b.funds[f.id] ?: 0)) }
        val rFundsEnd = s.rowCount
        s.row(t("تطبیق: نقد + مطالبات − بدهی − صندوق‌ها (باید صفر باشد)", Style.BOLD), Cell.Formula("B$r0+B${r0 + 1}-B${r0 + 2}-SUM(B${r0 + 3}:B$rFundsEnd)"))
        s.row(Cell.Empty)
        s.row(t("جمع بدهی واحدهای بدهکار"), n(b.outstandingDebts))
        s.row(t("جمع بستانکاری واحدها"), n(b.residentCredits))
        s.row(t("توضیح: بدهی ساکنان با موجودی نقد ساختمان متفاوت است؛ دریافت از ساکن درآمد جدید نیست بلکه بدهی را کم می‌کند."))

        // 2. Unit balances
        val ub = x.sheet("مانده واحدها")
        ub.header("واحد", "طبقه", "متراژ (m²)", "ساکنان فعلی", "مانده افتتاحیه", "شارژ کل", "شارژ آب", "سایر شارژها", "دریافتی", "بستانکاری و تعدیل", "استرداد", "مانده (مثبت=بدهکار)")
        d.units.forEach { u ->
            val st = d.statements[u.id] ?: return@forEach
            ub.row(t(u.label), n(u.floor.toLong()), Cell.Dec(u.areaCentiM2 / 100.0, Style.MONEY), n((d.occupants[u.id] ?: 0).toLong()),
                n(st.opening), n(st.charges), n(st.waterCharges), n(st.otherCharges), n(st.payments), n(st.creditsAndAdjustments), n(st.refunds), n(st.closing))
        }
        totals(ub, from = 2, firstCol = 4, lastCol = 11)

        // 3. Periods
        val ps = x.sheet("دوره‌ها")
        ps.header("دوره", "شروع", "پایان", "مهلت پرداخت", "وضعیت", "جمع شارژ", "سهم صندوق", "خطاهای باز", "اصلاحیه‌ها")
        d.periods.forEach { p ->
            val res = d.periodResults[p.id]
            ps.row(t(p.name), t(date(p.startDate)), t(date(p.endDate)), t(p.dueDate?.let(::date) ?: ""), t(statusLabel(p)),
                n(res?.total ?: 0), n(res?.fundBorneTotal ?: 0), n((res?.blockingIssues?.size ?: 0).toLong()),
                n(d.entries.count { it.type == EntryType.PERIOD_CORRECTION && it.periodId == p.id }.toLong()))
        }

        // 4. Charges by unit per period
        val cs = x.sheet("شارژ دوره‌ها")
        cs.header("دوره", "وضعیت", "واحد", "منبع", "دسته", "صندوق", "مبلغ")
        d.periods.forEach { p ->
            d.periodResults[p.id]?.lines?.forEach { l ->
                cs.row(t(p.name), t(statusLabel(p)), t(d.unitLabel(l.unitId)), t(d.sourceName(l.sourceId)), t(d.categoryName(l.categoryId)), t(d.fundName(l.fundId)), n(l.amount))
            }
        }
        totals(cs, from = 2, firstCol = 6, lastCol = 6)

        // 5. Allocation explanations
        val ex = x.sheet("توضیح تسهیم")
        ex.header("دوره", "منبع", "مبلغ منبع", "بخش", "مبنا", "واحد", "کمیت", "وزن", "مجموع وزن", "سهم", "تعدیل گرد کردن")
        d.periods.forEach { p ->
            d.periodResults[p.id]?.sources?.forEach { src ->
                src.allocation.components.forEach { c ->
                    c.units.forEach { u ->
                        ex.row(t(p.name), t(src.name), n(src.allocation.amount), t(Fa.part(c.part)), t(Fa.basisKind(c.basis)), t(d.unitLabel(u.unitId)),
                            t(Fa.quantity(u.quantityKind, u.quantity)), n(u.weight), n(c.totalWeight), n(u.share), n(u.roundingAdjustment))
                    }
                    if (c.fundBorne != 0L) ex.row(t(p.name), t(src.name), n(src.allocation.amount), t(Fa.part(c.part)), t("از محل صندوق"), t("—"), t(""), Cell.Empty, Cell.Empty, n(c.fundBorne), Cell.Empty)
                }
                if (src.allocation.components.isEmpty() && src.allocation.fundBorne != 0L) {
                    ex.row(t(p.name), t(src.name), n(src.allocation.amount), t(""), t("از محل صندوق (بدون تسهیم)"), t("—"), t(""), Cell.Empty, Cell.Empty, n(src.allocation.fundBorne), Cell.Empty)
                }
            }
        }

        // 6. Expenses
        val es = x.sheet("هزینه‌ها")
        es.header("تاریخ", "شرح", "دسته", "تامین‌کننده", "صندوق", "دوره", "مبلغ", "پرداخت‌شده", "مانده بدهی", "وضعیت", "سیاست تسهیم", "مرجع سند")
        d.expenses.forEach { e ->
            val paid = d.expensePaid[e.id] ?: 0
            val pol = e.policy()
            es.row(t(date(e.date)), t(e.description), t(d.categoryName(e.categoryId)), t(d.supplierName(e.supplierId)), t(d.fundName(e.fundId)),
                t(d.periods.firstOrNull { it.id == e.periodId }?.name ?: ""), n(e.amountMinor), n(paid),
                n(if (e.status == ExpenseStatus.ACTIVE) e.amountMinor - paid else 0),
                t(if (e.status == ExpenseStatus.VOIDED) "باطل‌شده" else when { paid == 0L -> "پرداخت‌نشده"; paid < e.amountMinor -> "پرداخت جزئی"; else -> "پرداخت‌شده" }),
                t(if (pol.fundOnly) "از محل صندوق" else pol.components.joinToString(" + ") { Fa.basis(it.basis) }), t(e.documentRef))
        }
        totals(es, from = 2, firstCol = 6, lastCol = 8)

        // 7. Journal
        val js = x.sheet("دفتر روزنامه")
        js.header("تاریخ", "شماره سند", "نوع", "شرح", "مرجع", "روش", "حساب", "واحد", "تامین‌کننده", "صندوق", "دسته", "حساب نقد", "بدهکار", "بستانکار", "برگشت‌خورده")
        val byEntry = d.lines.groupBy { it.entryId }
        d.entries.forEach { e ->
            byEntry[e.id].orEmpty().forEach { l ->
                js.row(t(date(e.entryDate)), t(e.id.take(8)), t(Fa.entryType(e.type)), t(e.memo), t(e.reference), t(e.method), t(Fa.account(l.account)),
                    t(if (l.unitId != null) d.unitLabel(l.unitId) else ""), t(if (l.account == AccountKind.PAYABLE || l.supplierId != null) d.supplierName(l.supplierId) else ""),
                    t(if (l.fundId != null) d.fundName(l.fundId) else ""), t(if (l.categoryId != null) d.categoryName(l.categoryId) else ""),
                    t(if (l.cashAccountId != null) d.cashName(l.cashAccountId) else ""),
                    if (l.amountMinor > 0) n(l.amountMinor) else Cell.Empty, if (l.amountMinor < 0) n(-l.amountMinor) else Cell.Empty,
                    t(if (e.id in d.reversedEntryIds) "بله" else ""))
            }
        }
        val jn = js.rowCount
        js.row(t("جمع (باید برابر باشند)", Style.BOLD), Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty, Cell.Empty,
            Cell.Formula("SUM(M2:M$jn)"), Cell.Formula("SUM(N2:N$jn)"))

        // 8. Meter readings
        val ms = x.sheet("قرائت کنتورها")
        ms.header("کنتور", "نوع کنتور", "واحد", "تاریخ", "قرائت (m³)", "نوع قرائت", "اصلاح‌شده با قرائت بعدی", "یادداشت")
        val superseded = d.readings.mapNotNull { it.supersedesId }.toSet()
        d.readings.forEach { rd ->
            val m = d.meters.firstOrNull { it.id == rd.meterId }
            ms.row(t(m?.label ?: ""), t(m?.let { Fa.meterRole(it.role) } ?: ""), t(m?.unitId?.let(d::unitLabel) ?: ""), t(date(rd.readingDate)),
                Cell.Dec(rd.valueLiters / 1000.0), t(Fa.readingKind(rd.kind)), t(if (rd.id in superseded) "بله" else ""), t(rd.note))
        }

        // 9. Water reconciliation
        val ws = x.sheet("تطبیق آب")
        ws.header("دوره", "وضعیت", "کنتور اصلی (m³)", "مجموع واحدها (m³)", "تخصیص‌نیافته (m³)", "درصد تخصیص‌نیافته")
        d.periods.forEach { p ->
            val w = d.periodResults[p.id]?.water ?: return@forEach
            if (w.meters.isEmpty()) return@forEach
            val row = ws.rowCount + 1
            ws.row(t(p.name), t(statusLabel(p)), w.mainLiters?.let { Cell.Dec(it / 1000.0) } ?: t("نامشخص"), Cell.Dec(w.unitSumLiters / 1000.0),
                w.unallocatedLiters?.let { Cell.Dec(it / 1000.0) } ?: t("نامشخص"),
                if (w.mainLiters != null && w.unallocatedLiters != null) Cell.Formula("IF(C$row=0,0,E$row/C$row)", Style.DECIMAL3) else Cell.Empty)
        }

        // 10. Funds, cash and suppliers
        val fs = x.sheet("صندوق و نقد")
        fs.header("نوع", "عنوان", "مانده")
        d.funds.forEach { f -> fs.row(t("صندوق"), t(f.name), n(b.funds[f.id] ?: 0)) }
        d.cashAccounts.forEach { c -> fs.row(t("نقد / بانک"), t(c.name), n(b.cash[c.id] ?: 0)) }
        b.suppliers.filterValues { it != 0L }.forEach { (sid, v) -> fs.row(t("بدهی به تامین‌کننده"), t(if (sid == null) "عمومی" else d.supplierName(sid)), n(v)) }
        return x
    }

    private fun statusLabel(p: PeriodEntity) = if (p.status == PeriodStatus.FINALIZED) "قطعی" else "پیش‌نویس"

    private fun totals(s: XlsxWriter.Sheet, from: Int, firstCol: Int, lastCol: Int) {
        val last = s.rowCount
        if (last < from) return
        val cells = MutableList<Cell>(lastCol + 1) { Cell.Empty }
        cells[0] = t("جمع", Style.BOLD)
        for (c in firstCol..lastCol) {
            val col = XlsxWriter.colName(c)
            cells[c] = Cell.Formula("SUM(${col}$from:${col}$last)")
        }
        s.row(cells)
    }
}

/** Journal export as CSV (UTF-8 with BOM so spreadsheet apps detect Persian text). */
object CsvExporter {
    fun journal(d: ReportData): String = buildString {
        append('﻿')
        appendLine(listOf("entry_id", "date_iso", "date_local", "type", "memo", "reference", "method", "period", "account", "unit", "supplier", "fund", "category", "cash_account", "debit", "credit", "reverses_entry_id").joinToString(","))
        val byEntry = d.lines.groupBy { it.entryId }
        for (e in d.entries) for (l in byEntry[e.id].orEmpty()) {
            appendLine(listOf(
                e.id, java.time.LocalDate.ofEpochDay(e.entryDate).toString(), Fmt.date(e.entryDate, d.building.calendar, persian = false),
                e.type.name, e.memo, e.reference, e.method, d.periods.firstOrNull { it.id == e.periodId }?.name ?: "",
                l.account.name, l.unitId?.let(d::unitLabel) ?: "", l.supplierId?.let(d::supplierName) ?: "",
                l.fundId?.let(d::fundName) ?: "", l.categoryId?.let(d::categoryName) ?: "", l.cashAccountId?.let(d::cashName) ?: "",
                if (l.amountMinor > 0) l.amountMinor.toString() else "", if (l.amountMinor < 0) (-l.amountMinor).toString() else "",
                e.reversesEntryId ?: "",
            ).joinToString(",") { csv(it) })
        }
    }

    /** RFC 4180 quoting; cells starting with = + - @ are prefixed to prevent formula injection. */
    fun csv(v: String): String {
        val safe = if (v.isNotEmpty() && v[0] in "=+-@" && v.toLongOrNull() == null) "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}

/** Human-readable, printable monthly statement (RTL HTML) explaining every charge. */
object StatementRenderer {

    fun html(d: ReportData, period: PeriodEntity, statements: Map<String, UnitStatement>, unitIds: List<String> = d.units.map { it.id }): String {
        val cal = d.building.calendar
        val result: PeriodResult? = d.periodResults[period.id]
        val cur = esc(d.building.currencyName)
        fun money(v: Long) = Fmt.money(v, d.building.minorDigits)
        return buildString {
            append("<!doctype html><html lang=\"fa\" dir=\"rtl\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>").append(esc("صورتحساب ${period.name} — ${d.building.name}")).append("</title><style>")
            append(CSS)
            append("</style></head><body>")
            for (uid in unitIds) {
                val u = d.units.firstOrNull { it.id == uid } ?: continue
                val st = statements[uid] ?: continue
                append("<section class=\"page\">")
                append("<header><h1>").append(esc(d.building.name)).append("</h1>")
                if (d.building.address.isNotBlank()) append("<div class=\"muted\">").append(esc(d.building.address)).append("</div>")
                append("<h2>صورتحساب واحد ").append(esc(u.label)).append(" — ").append(esc(period.name)).append("</h2>")
                append("<div class=\"meta\">از ").append(Fmt.longDate(period.startDate, cal)).append(" تا ").append(Fmt.longDate(period.endDate, cal))
                period.dueDate?.let { append("، مهلت پرداخت: ").append(Fmt.longDate(it, cal)) }
                append("، وضعیت دوره: ").append(if (period.status == PeriodStatus.FINALIZED) "قطعی" else "<b class=\"warn\">پیش‌نویس (قابل تغییر)</b>")
                append("</div><div class=\"meta\">متراژ: ").append(Fa.quantity(QuantityKind.AREA_CENTI_M2, u.areaCentiM2))
                append("، مبالغ به ").append(cur).append("</div></header>")

                append("<table><thead><tr><th>شرح</th><th>مبنای محاسبه و سهم این واحد</th><th class=\"num\">مبلغ</th></tr></thead><tbody>")
                var periodCharges = 0L
                result?.sources?.forEach { src ->
                    val share = src.allocation.shares[uid] ?: 0L
                    if (share == 0L && src.allocation.components.none { c -> c.units.any { it.unitId == uid } }) return@forEach
                    periodCharges += share
                    append("<tr><td>").append(esc(src.name)).append("<div class=\"muted\">").append(esc(d.categoryName(src.categoryId))).append("، ")
                        .append(esc(d.fundName(src.fundId))).append("</div></td><td>")
                    src.allocation.components.forEach { c ->
                        val us = c.units.firstOrNull { it.unitId == uid } ?: return@forEach
                        if (c.part != ComponentPart.WHOLE) append("<b>").append(Fa.part(c.part)).append(":</b> ")
                        append(Fa.basisKind(c.basis)).append(" — مبلغ ").append(money(c.amount))
                        if (us.tariff != null) {
                            append("؛ مصرف شما ").append(Fa.quantity(QuantityKind.LITERS, us.quantity)).append("؛ ")
                            us.tariff.tiers.forEach { tu -> append(Fmt.cubicMeters(tu.liters)).append(" م³ × ").append(money(tu.priceMinorPerM3)).append("، ") }
                            if (us.tariff.surchargeMinor != 0L) append("عوارض ").append(money(us.tariff.surchargeMinor)).append("، ")
                            if (us.tariff.fixedFeeMinor != 0L) append("آبونمان ").append(money(us.tariff.fixedFeeMinor))
                        } else if (us.quantityKind != QuantityKind.EXPLICIT_AMOUNT) {
                            append("؛ سهم شما ").append(Fa.quantity(us.quantityKind, us.quantity))
                            if (c.totalWeight > 0) append(" (وزن ").append(Fmt.number(us.weight)).append(" از ").append(Fmt.number(c.totalWeight)).append(")")
                        } else {
                            append("؛ مبلغ مشخص‌شده برای این واحد")
                        }
                        if (us.roundingAdjustment != 0L) append(" <span class=\"muted\">(گرد کردن: ").append(money(us.roundingAdjustment)).append(")</span>")
                        append("<br>")
                    }
                    append("</td><td class=\"num\">").append(money(share)).append("</td></tr>")
                }
                append("<tr class=\"total\"><td colspan=\"2\">جمع شارژ این دوره</td><td class=\"num\">").append(money(periodCharges)).append("</td></tr>")
                append("</tbody></table>")

                append("<table class=\"summary\"><tbody>")
                row(this, "مانده از قبل (مثبت = بدهکار)", money(st.opening))
                row(this, "شارژ ثبت‌شده در این بازه (شامل اصلاحیه‌ها)", money(st.charges))
                row(this, "پرداختی شما در این بازه", money(st.payments))
                if (st.creditsAndAdjustments != 0L) row(this, "بستانکاری و تعدیل (کاهش بدهی)", money(st.creditsAndAdjustments))
                if (st.refunds != 0L) row(this, "استرداد وجه", money(st.refunds))
                append("<tr class=\"total\"><td>").append(if (st.closing > 0) "مبلغ قابل پرداخت" else if (st.closing < 0) "بستانکاری شما" else "تسویه")
                    .append("</td><td class=\"num\">").append(money(kotlin.math.abs(st.closing))).append("</td></tr>")
                append("</tbody></table>")

                if (st.rows.isNotEmpty()) {
                    append("<details open><summary>ریز گردش حساب</summary><table><thead><tr><th>تاریخ</th><th>شرح</th><th class=\"num\">بدهکار</th><th class=\"num\">بستانکار</th><th class=\"num\">مانده</th></tr></thead><tbody>")
                    st.rows.forEach { rw ->
                        append("<tr><td>").append(Fmt.date(rw.date, cal)).append("</td><td>").append(esc(Fa.entryType(rw.type)))
                        if (rw.memo.isNotBlank()) append(" — ").append(esc(rw.memo))
                        append("</td><td class=\"num\">").append(if (rw.amount > 0) money(rw.amount) else "").append("</td><td class=\"num\">")
                            .append(if (rw.amount < 0) money(-rw.amount) else "").append("</td><td class=\"num\">").append(money(rw.balance)).append("</td></tr>")
                    }
                    append("</tbody></table></details>")
                }
                result?.water?.takeIf { it.meters.isNotEmpty() }?.let { w ->
                    append("<p class=\"muted\">آب ساختمان در این دوره: کنتور اصلی ")
                    append(w.mainLiters?.let { Fmt.cubicMeters(it) } ?: "نامشخص").append(" م³، مجموع کنتورهای واحدها ").append(Fmt.cubicMeters(w.unitSumLiters))
                    append(" م³، مصرف تخصیص‌نیافته ").append(w.unallocatedLiters?.let { Fmt.cubicMeters(it) } ?: "نامشخص").append(" م³.")
                    w.unitLiters[uid]?.let { append(" مصرف واحد شما: ").append(Fmt.cubicMeters(it)).append(" م³.") }
                    append("</p>")
                }
                append("<footer class=\"muted\">تهیه‌شده با برنامه «دنگ» در ").append(Fmt.dateTime(d.generatedAt, cal)).append("</footer>")
                append("</section>")
            }
            append("</body></html>")
        }
    }

    private fun row(sb: StringBuilder, label: String, value: String) {
        sb.append("<tr><td>").append(esc(label)).append("</td><td class=\"num\">").append(value).append("</td></tr>")
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val CSS = """
body{font-family:Vazirmatn,Vazir,Tahoma,'Noto Sans Arabic',sans-serif;margin:0;background:#f4f6f6;color:#1b2424;line-height:1.7}
.page{background:#fff;max-width:820px;margin:16px auto;padding:24px 28px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.12)}
h1{font-size:20px;margin:0}h2{font-size:17px;margin:8px 0 4px;color:#00695c}
.meta,.muted{color:#5b6b6b;font-size:13px}.warn{color:#b3261e}
table{width:100%;border-collapse:collapse;margin:14px 0;font-size:14px}
th,td{border-bottom:1px solid #dfe5e5;padding:6px 8px;text-align:right;vertical-align:top}
th{background:#eef3f2;font-weight:600}.num{text-align:left;white-space:nowrap;font-variant-numeric:tabular-nums}
tr.total td{font-weight:700;border-top:2px solid #00695c}
table.summary{max-width:460px}
footer{margin-top:16px}
@media print{body{background:#fff}.page{box-shadow:none;margin:0 auto;page-break-after:always}}
"""
}
