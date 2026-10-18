package com.github.opscalehub.dong.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BuildingEntity::class, UnitEntity::class, OccupancyEntity::class, ParkingSpaceEntity::class,
        ParkingAssignmentEntity::class, MeterEntity::class, MeterReadingEntity::class, CategoryEntity::class,
        SupplierEntity::class, FundEntity::class, CashAccountEntity::class, ExpenseEntity::class,
        ChargeItemEntity::class, WaterTariffEntity::class, PeriodEntity::class, PeriodRevisionEntity::class,
        JournalEntryEntity::class, JournalLineEntity::class, AuditEventEntity::class, BillShareEntity::class,
    ],
    version = DongDatabase.VERSION,
    exportSchema = true,
)
abstract class DongDatabase : RoomDatabase() {
    abstract fun buildingDao(): BuildingDao
    abstract fun waterDao(): WaterDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun financeDao(): FinanceDao
    abstract fun periodDao(): PeriodDao
    abstract fun restoreDao(): RestoreDao

    companion object {
        const val VERSION = 4
        const val NAME = "dong.db"

        fun build(context: Context, name: String? = NAME): DongDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context, DongDatabase::class.java)
            } else {
                Room.databaseBuilder(context, DongDatabase::class.java, name)
            }
            // No destructive fallback: losing financial data silently is never acceptable.
            return builder.addMigrations(*Migrations.ALL).build()
        }
    }
}
