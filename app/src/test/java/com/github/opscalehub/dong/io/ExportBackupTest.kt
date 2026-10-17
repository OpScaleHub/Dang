package com.github.opscalehub.dong.io

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.opscalehub.dong.data.BackupRepository
import com.github.opscalehub.dong.data.BuildingRestoreStatus
import com.github.opscalehub.dong.data.FixedClock
import com.github.opscalehub.dong.data.OpException
import com.github.opscalehub.dong.data.ReportRepository
import com.github.opscalehub.dong.data.Repos
import com.github.opscalehub.dong.data.RestoreMode
import com.github.opscalehub.dong.data.SampleData
import com.github.opscalehub.dong.data.db.DongDatabase
import com.github.opscalehub.dong.data.db.PeriodStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class ExportBackupTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: DongDatabase
    private lateinit var r: Repos
    private lateinit var b: String
    private val opened = mutableListOf<DongDatabase>()

    private fun freshRepos(): Repos = Repos(DongDatabase.build(ctx, null).also { opened += it }, FixedClock())

    @Before
    fun setup() = runBlocking {
        db = DongDatabase.build(ctx, null)
        r = Repos(db, FixedClock())
        b = SampleData.createBuilding(r)
        SampleData.addDemoData(r, b)
    }

    @After
    fun tearDown() {
        db.close()
        opened.forEach { it.close() }
    }

    @Test
    fun `demo data reproduces the workbook scenario end to end`() = runBlocking {
        val expected = Json.parseToJsonElement(listOf("../workbook/expected-results.json", "workbook/expected-results.json").map(::File).first { it.exists() }.readText()).jsonObject
        val bal = r.finance.balances(b)
        assertTrue(bal.reconciles)
        val units = db.buildingDao().units(b).associateBy { com.github.opscalehub.dong.i18n.Fmt.latin(it.label) }
        expected.getValue("unitClosingBalances").jsonObject.forEach { (label, v) ->
            assertEquals("unit $label", v.jsonPrimitive.long, bal.units.getValue(units.getValue(label).id))
        }
        assertEquals(expected.getValue("cashClosing").jsonPrimitive.long, bal.cashTotal)
        // The demo adds one unpaid expense in the current (draft) month beyond the workbook's single month.
        val draftExpenses = db.periodDao().periods(b).filter { it.status == PeriodStatus.DRAFT }
            .sumOf { p -> db.expenseDao().activeExpensesForPeriod(p.id).sumOf { it.amountMinor } }
        assertEquals(9_000_000L, draftExpenses)
        assertEquals(expected.getValue("payableClosing").jsonPrimitive.long + draftExpenses, bal.payablesTotal)
        val funds = db.financeDao().funds(b)
        val fundsExp = expected.getValue("funds").jsonObject
        assertEquals(fundsExp.getValue("عملیاتی").jsonPrimitive.long - draftExpenses, bal.funds.getValue(funds.first { it.name.contains("عملیاتی") }.id))
        assertEquals(fundsExp.getValue("اندوخته").jsonPrimitive.long, bal.funds.getValue(funds.first { it.name.contains("اندوخته") }.id))
        // The current month is a draft blocked only by missing readings.
        val draft = db.periodDao().periods(b).first { it.status == PeriodStatus.DRAFT }
        val preview = r.periods.preview(draft.id)
        assertFalse(preview.canFinalize)
        assertTrue(preview.blockingIssues.all { it.code.name.startsWith("MISSING") })
    }

    @Test
    fun `backup round trip restores identical balances and history`() = runBlocking {
        val backup = BackupRepository(r, "test")
        val text = backup.export()
        val other = freshRepos()
        val target = BackupRepository(other, "test")
        val preview = target.preview(text)
        assertTrue(preview.problems.toString(), preview.valid)
        assertEquals(BuildingRestoreStatus.NEW, preview.buildings.single().status)
        val res = target.apply(preview, mapOf(b to RestoreMode.ADD_MISSING))
        assertEquals(preview.buildings.single().records, res.inserted)
        assertEquals(r.finance.balances(b), other.finance.balances(b))
        val units = db.buildingDao().units(b)
        units.forEach { assertEquals(r.finance.unitStatement(b, it.id), other.finance.unitStatement(b, it.id)) }
        val p = db.periodDao().periods(b).first { it.status == PeriodStatus.FINALIZED }
        assertEquals(r.periods.snapshot(p.id), other.periods.snapshot(p.id))
        // Re-importing the same file is idempotent.
        val again = target.preview(text)
        assertEquals(BuildingRestoreStatus.IDENTICAL, again.buildings.single().status)
        assertEquals(0, target.apply(again, mapOf(b to RestoreMode.ADD_MISSING)).inserted)
        assertEquals(r.finance.balances(b), other.finance.balances(b))
    }

    @Test
    fun `tampered or foreign files are rejected before any write`() = runBlocking {
        val text = BackupRepository(r, "test").export()
        val target = BackupRepository(freshRepos(), "test")
        val tampered = text.replace("\"amountMinor\":9000000", "\"amountMinor\":9000001")
        assertTrue(tampered != text)
        assertEquals(ProblemCode.CHECKSUM_MISMATCH, target.preview(tampered).problems.single().code)
        assertEquals(ProblemCode.NOT_A_BACKUP, target.preview("PK\u0003\u0004 not json").problems.single().code)
        assertEquals(ProblemCode.NOT_A_BACKUP, target.preview("{\"format\":\"other\"}").problems.single().code)
        val future = text.replaceFirst("\"formatVersion\":1", "\"formatVersion\":99")
        assertEquals(ProblemCode.UNSUPPORTED_VERSION, target.preview(future).problems.single().code)
        val bad = target.preview(text.replaceFirst("\"schemaVersion\":${DongDatabase.VERSION}", "\"schemaVersion\":${DongDatabase.VERSION + 1}"))
        assertEquals(ProblemCode.UNSUPPORTED_VERSION, bad.problems.single().code)
        try {
            target.apply(target.preview(tampered), mapOf(b to RestoreMode.ADD_MISSING))
            org.junit.Assert.fail("must refuse")
        } catch (e: OpException) { /* expected */ }
    }

    @Test
    fun `structurally broken payload with valid checksum is rejected`() = runBlocking {
        val export = BackupRepository(r, "test").exportBuilding(b)
        val broken = export.copy(lines = export.lines.drop(1)) // one entry no longer balances
        val text = BackupCodec.encode(BackupPayload(listOf(broken)), "test", DongDatabase.VERSION, 0)
        val problems = BackupRepository(freshRepos(), "test").preview(text).problems.map { it.code }.toSet()
        assertTrue(problems.toString(), ProblemCode.UNBALANCED_ENTRY in problems)
        assertTrue(ProblemCode.TRIAL_BALANCE in problems)
        val orphan = export.copy(readings = export.readings + export.readings.first().copy(id = "x", meterId = "missing"))
        val p2 = BackupCodec.validate(BackupPayload(listOf(orphan))).map { it.code }
        assertTrue(ProblemCode.BROKEN_REFERENCE in p2)
        val dup = export.copy(units = export.units + export.units.first())
        assertTrue(ProblemCode.DUPLICATE_ID in BackupCodec.validate(BackupPayload(listOf(dup))).map { it.code })
    }

    @Test
    fun `replace mode overwrites diverged local data only on request`() = runBlocking {
        val backup = BackupRepository(r, "test")
        val text = backup.export()
        val before = r.finance.balances(b)
        val unit = db.buildingDao().units(b).first()
        r.finance.receivePayment(b, unit.id, 777_000, db.financeDao().cashAccounts(b).first().id, r.clock.today())
        val pv = backup.preview(text)
        // A newer local payment is not in the backup, but nothing diverged → still "identical" for existing ids.
        assertEquals(BuildingRestoreStatus.IDENTICAL, pv.buildings.single().status)
        // Diverge: edit a unit locally.
        r.buildings.saveUnit(unit.copy(notes = "تغییر محلی"))
        val pv2 = backup.preview(text)
        assertEquals(BuildingRestoreStatus.CONFLICT, pv2.buildings.single().status)
        assertFalse(pv2.buildings.single().canAddMissing)
        try {
            backup.apply(pv2, mapOf(b to RestoreMode.ADD_MISSING))
            org.junit.Assert.fail("merge of diverged data must be refused")
        } catch (e: OpException) { /* expected */ }
        backup.apply(pv2, mapOf(b to RestoreMode.REPLACE))
        assertEquals(before, r.finance.balances(b))
        assertEquals(unit.notes, db.buildingDao().unit(unit.id)!!.notes)
    }

    @Test
    fun `xlsx export is well formed ooxml with rtl sheets`() = runBlocking {
        val data = ReportRepository(r).load(b)
        val bytes = ByteArrayOutputStream().also { WorkbookExporter.write(data, it) }.toByteArray()
        File("build/test-exports").mkdirs()
        File("build/test-exports/dong-export.xlsx").writeBytes(bytes)
        val parts = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            generateSequence { z.nextEntry }.forEach { parts[it.name] = z.readBytes() }
        }
        assertTrue("[Content_Types].xml" in parts && "xl/workbook.xml" in parts && "xl/styles.xml" in parts)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, content) ->
            factory.newDocumentBuilder().parse(ByteArrayInputStream(content)).also { assertTrue(name, it.documentElement != null) }
        }
        val wb = String(parts.getValue("xl/workbook.xml"), Charsets.UTF_8)
        listOf("خلاصه", "مانده واحدها", "دفتر روزنامه", "تطبیق آب", "توضیح تسهیم").forEach { assertTrue(it, wb.contains(it)) }
        val sheet1 = String(parts.getValue("xl/worksheets/sheet1.xml"), Charsets.UTF_8)
        assertTrue(sheet1.contains("rightToLeft=\"1\""))
        assertFalse(parts.keys.any { it.contains("vba", ignoreCase = true) })
    }

    @Test
    fun `csv and html statement`() = runBlocking {
        val data = ReportRepository(r).load(b)
        val csv = CsvExporter.journal(data)
        assertTrue(csv.startsWith("﻿"))
        assertEquals(data.lines.size + 1, csv.trimEnd().lines().size)
        assertEquals("'=1+1", CsvExporter.csv("=1+1"))
        assertEquals("-500", CsvExporter.csv("-500"))
        assertEquals("\"a,b\"", CsvExporter.csv("a,b"))
        val p = db.periodDao().periods(b).first { it.status == PeriodStatus.FINALIZED }
        val html = StatementRenderer.html(data, p, ReportRepository(r).periodStatements(b, p.id))
        assertTrue(html.contains("dir=\"rtl\""))
        db.buildingDao().units(b).forEach { assertTrue(html.contains("واحد ${it.label}")) }
        assertTrue(html.contains("بر اساس تعداد ساکنان"))
        assertFalse(html.contains("<script"))
        File("build/test-exports").mkdirs()
        File("build/test-exports/statement.html").writeText(html)
    }
}
