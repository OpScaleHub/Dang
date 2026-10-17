package com.github.opscalehub.dong.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Every schema change gets an explicit, tested migration (see MigrationTest). Never destructive. */
object Migrations {
    /** v2: payment method on journal entries; index for per-unit statement queries. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE journal_entries ADD COLUMN method TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_lines_unitId ON journal_lines(unitId)")
        }
    }

    /** v3: display currency (e.g. show Toman while storing Rial). Existing buildings keep their display. */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE buildings ADD COLUMN displayCurrencyName TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE buildings ADD COLUMN displayDivisor INTEGER NOT NULL DEFAULT 1")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
