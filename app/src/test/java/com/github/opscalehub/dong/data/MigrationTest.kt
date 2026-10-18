package com.github.opscalehub.dong.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.opscalehub.dong.data.db.DongDatabase
import com.github.opscalehub.dong.data.db.Migrations
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DongDatabase::class.java)

    @Test
    fun `migrate 1 to 2 keeps financial data`() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO buildings (id, name, address, currencyCode, currencyName, minorDigits, roundingIncrement, occupancyBasis, calendar, notes, createdAt) VALUES ('b1','ساختمان','', 'IRR','ریال',0,1000,'DAY_WEIGHTED','JALALI','',1)")
            execSQL("INSERT INTO journal_entries (id, buildingId, entryDate, type, memo, reference, createdAt) VALUES ('e1','b1',20000,'RESIDENT_PAYMENT','دریافت','S-1',2)")
            execSQL("INSERT INTO journal_lines (id, entryId, buildingId, account, amountMinor, unitId, cashAccountId) VALUES ('l1','e1','b1','CASH',500000,'u1','c1')")
            execSQL("INSERT INTO journal_lines (id, entryId, buildingId, account, amountMinor, unitId) VALUES ('l2','e1','b1','RECEIVABLE',-500000,'u1')")
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 2, true, *Migrations.ALL)
        db.query("SELECT method, memo FROM journal_entries WHERE id = 'e1'").use { c ->
            c.moveToFirst()
            assertEquals("", c.getString(0))
            assertEquals("دریافت", c.getString(1))
        }
        db.query("SELECT SUM(amountMinor), COUNT(*) FROM journal_lines").use { c ->
            c.moveToFirst()
            assertEquals(0L, c.getLong(0))
            assertEquals(2, c.getInt(1))
        }
        db.close()
    }

    @Test
    fun `latest schema version is covered by migrations`() {
        assertEquals(DongDatabase.VERSION, Migrations.ALL.maxOf { it.endVersion })
        helper.createDatabase("fresh.db", 1).close()
        helper.runMigrationsAndValidate("fresh.db", DongDatabase.VERSION, true, *Migrations.ALL).close()
    }

    @Test
    fun `migrate 2 to 3 adds display currency and keeps buildings`() {
        helper.createDatabase("m23.db", 2).apply {
            execSQL("INSERT INTO buildings (id, name, address, currencyCode, currencyName, minorDigits, roundingIncrement, occupancyBasis, calendar, notes, createdAt) VALUES ('b1','ساختمان','', 'IRR','ریال',0,1000,'DAY_WEIGHTED','JALALI','',1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("m23.db", 3, true, *Migrations.ALL)
        db.query("SELECT displayCurrencyName, displayDivisor, roundingIncrement FROM buildings WHERE id = 'b1'").use { c ->
            c.moveToFirst()
            assertEquals("", c.getString(0))
            assertEquals(1L, c.getLong(1))
            assertEquals(1000L, c.getLong(2))
        }
        db.close()
    }

    @Test
    fun `migrate 3 to 4 adds bill shares with cascades`() {
        helper.createDatabase("m34.db", 3).apply {
            execSQL("INSERT INTO buildings (id, name, address, currencyCode, currencyName, minorDigits, roundingIncrement, occupancyBasis, calendar, notes, createdAt, displayCurrencyName, displayDivisor) VALUES ('b1','ساختمان','', 'IRR','ریال',0,1000,'DAY_WEIGHTED','JALALI','',1,'تومان',10)")
            close()
        }
        val db = helper.runMigrationsAndValidate("m34.db", 4, true, *Migrations.ALL)
        db.query("SELECT displayDivisor FROM buildings WHERE id = 'b1'").use { c -> c.moveToFirst(); assertEquals(10L, c.getLong(0)) }
        db.query("SELECT COUNT(*) FROM bill_shares").use { c -> c.moveToFirst(); assertEquals(0L, c.getLong(0)) }
        db.close()
    }
}
