package com.github.opscalehub.dong.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BuildingDao {
    @Query("SELECT * FROM buildings ORDER BY createdAt")
    fun observeBuildings(): Flow<List<BuildingEntity>>

    @Query("SELECT * FROM buildings ORDER BY createdAt")
    suspend fun buildings(): List<BuildingEntity>

    @Query("SELECT * FROM buildings WHERE id = :id")
    suspend fun building(id: String): BuildingEntity?

    @Query("SELECT * FROM buildings WHERE id = :id")
    fun observeBuilding(id: String): Flow<BuildingEntity?>

    @Upsert suspend fun upsertBuilding(b: BuildingEntity)

    @Query("DELETE FROM buildings WHERE id = :id")
    suspend fun deleteBuilding(id: String)

    @Query("SELECT * FROM units WHERE buildingId = :b ORDER BY sortOrder")
    fun observeUnits(b: String): Flow<List<UnitEntity>>

    @Query("SELECT * FROM units WHERE buildingId = :b ORDER BY sortOrder")
    suspend fun units(b: String): List<UnitEntity>

    @Query("SELECT * FROM units WHERE id = :id")
    suspend fun unit(id: String): UnitEntity?

    @Upsert suspend fun upsertUnit(u: UnitEntity)

    @Query("SELECT o.* FROM occupancy o JOIN units u ON u.id = o.unitId WHERE u.buildingId = :b ORDER BY o.effectiveFrom")
    suspend fun occupancy(b: String): List<OccupancyEntity>

    @Query("SELECT o.* FROM occupancy o JOIN units u ON u.id = o.unitId WHERE u.buildingId = :b ORDER BY o.effectiveFrom")
    fun observeOccupancy(b: String): Flow<List<OccupancyEntity>>

    @Insert suspend fun insertOccupancy(o: OccupancyEntity)

    @Query("DELETE FROM occupancy WHERE id = :id")
    suspend fun deleteOccupancy(id: String)

    @Query("SELECT * FROM occupancy WHERE id = :id")
    suspend fun occupancyRecord(id: String): OccupancyEntity?

    @Query("SELECT * FROM parking_spaces WHERE buildingId = :b ORDER BY label")
    fun observeSpaces(b: String): Flow<List<ParkingSpaceEntity>>

    @Query("SELECT * FROM parking_spaces WHERE buildingId = :b ORDER BY label")
    suspend fun spaces(b: String): List<ParkingSpaceEntity>

    @Upsert suspend fun upsertSpace(s: ParkingSpaceEntity)

    @Query("SELECT a.* FROM parking_assignments a JOIN parking_spaces s ON s.id = a.spaceId WHERE s.buildingId = :b ORDER BY a.effectiveFrom")
    suspend fun assignments(b: String): List<ParkingAssignmentEntity>

    @Query("SELECT a.* FROM parking_assignments a JOIN parking_spaces s ON s.id = a.spaceId WHERE s.buildingId = :b ORDER BY a.effectiveFrom")
    fun observeAssignments(b: String): Flow<List<ParkingAssignmentEntity>>

    @Upsert suspend fun upsertAssignment(a: ParkingAssignmentEntity)

    @Query("SELECT * FROM parking_assignments WHERE id = :id")
    suspend fun assignment(id: String): ParkingAssignmentEntity?
}

@Dao
interface WaterDao {
    @Query("SELECT * FROM meters WHERE buildingId = :b ORDER BY role, label")
    fun observeMeters(b: String): Flow<List<MeterEntity>>

    @Query("SELECT * FROM meters WHERE buildingId = :b ORDER BY role, label")
    suspend fun meters(b: String): List<MeterEntity>

    @Query("SELECT * FROM meters WHERE id = :id")
    suspend fun meter(id: String): MeterEntity?

    @Upsert suspend fun upsertMeter(m: MeterEntity)

    @Query("SELECT r.* FROM meter_readings r JOIN meters m ON m.id = r.meterId WHERE m.buildingId = :b ORDER BY r.readingDate, r.enteredAt")
    suspend fun readings(b: String): List<MeterReadingEntity>

    @Query("SELECT r.* FROM meter_readings r JOIN meters m ON m.id = r.meterId WHERE m.buildingId = :b ORDER BY r.readingDate, r.enteredAt")
    fun observeReadings(b: String): Flow<List<MeterReadingEntity>>

    @Query("SELECT * FROM meter_readings WHERE id = :id")
    suspend fun reading(id: String): MeterReadingEntity?

    @Insert suspend fun insertReading(r: MeterReadingEntity)

    @Query("DELETE FROM meter_readings WHERE id = :id")
    suspend fun deleteReading(id: String)

    @Query("SELECT COUNT(*) FROM meter_readings WHERE supersedesId = :id")
    suspend fun supersededCount(id: String): Int

    @Query("SELECT * FROM water_tariffs WHERE buildingId = :b ORDER BY effectiveFrom")
    fun observeTariffs(b: String): Flow<List<WaterTariffEntity>>

    @Query("SELECT * FROM water_tariffs WHERE buildingId = :b ORDER BY effectiveFrom")
    suspend fun tariffs(b: String): List<WaterTariffEntity>

    @Upsert suspend fun upsertTariff(t: WaterTariffEntity)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM categories WHERE buildingId = :b ORDER BY sortOrder, name")
    fun observeCategories(b: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE buildingId = :b ORDER BY sortOrder, name")
    suspend fun categories(b: String): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun category(id: String): CategoryEntity?

    @Upsert suspend fun upsertCategory(c: CategoryEntity)

    @Query("SELECT * FROM suppliers WHERE buildingId = :b ORDER BY name")
    fun observeSuppliers(b: String): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers WHERE buildingId = :b ORDER BY name")
    suspend fun suppliers(b: String): List<SupplierEntity>

    @Upsert suspend fun upsertSupplier(s: SupplierEntity)

    @Query("SELECT * FROM expenses WHERE buildingId = :b ORDER BY date DESC, createdAt DESC")
    fun observeExpenses(b: String): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE buildingId = :b ORDER BY date, createdAt")
    suspend fun expenses(b: String): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE periodId = :periodId AND status = 'ACTIVE' ORDER BY date, createdAt")
    suspend fun activeExpensesForPeriod(periodId: String): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun expense(id: String): ExpenseEntity?

    @Insert suspend fun insertExpense(e: ExpenseEntity)
    @Update suspend fun updateExpense(e: ExpenseEntity)

    @Query("SELECT * FROM charge_items WHERE buildingId = :b ORDER BY name")
    fun observeChargeItems(b: String): Flow<List<ChargeItemEntity>>

    @Query("SELECT * FROM charge_items WHERE buildingId = :b ORDER BY name")
    suspend fun chargeItems(b: String): List<ChargeItemEntity>

    @Upsert suspend fun upsertChargeItem(c: ChargeItemEntity)
}

@Dao
interface FinanceDao {
    @Query("SELECT * FROM funds WHERE buildingId = :b ORDER BY kind, name")
    fun observeFunds(b: String): Flow<List<FundEntity>>

    @Query("SELECT * FROM funds WHERE buildingId = :b ORDER BY kind, name")
    suspend fun funds(b: String): List<FundEntity>

    @Upsert suspend fun upsertFund(f: FundEntity)

    @Query("SELECT * FROM cash_accounts WHERE buildingId = :b ORDER BY name")
    fun observeCashAccounts(b: String): Flow<List<CashAccountEntity>>

    @Query("SELECT * FROM cash_accounts WHERE buildingId = :b ORDER BY name")
    suspend fun cashAccounts(b: String): List<CashAccountEntity>

    @Upsert suspend fun upsertCashAccount(c: CashAccountEntity)

    @Insert suspend fun insertEntry(e: JournalEntryEntity)
    @Insert suspend fun insertLines(l: List<JournalLineEntity>)

    @Query("SELECT * FROM journal_entries WHERE id = :id")
    suspend fun entry(id: String): JournalEntryEntity?

    @Query("SELECT * FROM journal_entries WHERE buildingId = :b ORDER BY entryDate, createdAt")
    suspend fun entries(b: String): List<JournalEntryEntity>

    @Query("SELECT * FROM journal_entries WHERE buildingId = :b ORDER BY entryDate DESC, createdAt DESC")
    fun observeEntries(b: String): Flow<List<JournalEntryEntity>>

    @Query("SELECT * FROM journal_lines WHERE buildingId = :b")
    suspend fun lines(b: String): List<JournalLineEntity>

    @Query("SELECT * FROM journal_lines WHERE buildingId = :b")
    fun observeLines(b: String): Flow<List<JournalLineEntity>>

    @Query("SELECT * FROM journal_lines WHERE entryId = :entryId")
    suspend fun linesOf(entryId: String): List<JournalLineEntity>

    @Query("SELECT COUNT(*) FROM journal_entries WHERE reversesEntryId = :id")
    suspend fun reversalCount(id: String): Int

    @Query("SELECT * FROM journal_entries WHERE expenseId = :expenseId ORDER BY createdAt")
    suspend fun entriesForExpense(expenseId: String): List<JournalEntryEntity>

    @Insert suspend fun insertAudit(a: AuditEventEntity)

    @Query("SELECT * FROM audit_events WHERE buildingId = :b ORDER BY at DESC LIMIT :limit")
    fun observeAudit(b: String, limit: Int = 200): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM audit_events WHERE buildingId = :b ORDER BY at")
    suspend fun audit(b: String): List<AuditEventEntity>
}

@Dao
interface PeriodDao {
    @Query("SELECT * FROM periods WHERE buildingId = :b ORDER BY startDate DESC")
    fun observePeriods(b: String): Flow<List<PeriodEntity>>

    @Query("SELECT * FROM periods WHERE buildingId = :b ORDER BY startDate")
    suspend fun periods(b: String): List<PeriodEntity>

    @Query("SELECT * FROM periods WHERE id = :id")
    suspend fun period(id: String): PeriodEntity?

    @Query("SELECT * FROM periods WHERE id = :id")
    fun observePeriod(id: String): Flow<PeriodEntity?>

    @Insert suspend fun insertPeriod(p: PeriodEntity)
    @Update suspend fun updatePeriod(p: PeriodEntity)

    @Query("DELETE FROM periods WHERE id = :id AND status = 'DRAFT'")
    suspend fun deleteDraft(id: String): Int

    @Query("SELECT * FROM period_revisions WHERE periodId = :periodId ORDER BY revision")
    suspend fun revisions(periodId: String): List<PeriodRevisionEntity>

    @Query("SELECT r.* FROM period_revisions r JOIN periods p ON p.id = r.periodId WHERE p.buildingId = :b ORDER BY r.createdAt")
    suspend fun revisionsForBuilding(b: String): List<PeriodRevisionEntity>

    @Insert suspend fun insertRevision(r: PeriodRevisionEntity)
}

/** Bulk inserts used only by backup restore. IGNORE makes re-importing the same file idempotent. */
@Dao
interface RestoreDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun buildings(x: List<BuildingEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun units(x: List<UnitEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun occupancy(x: List<OccupancyEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun spaces(x: List<ParkingSpaceEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun assignments(x: List<ParkingAssignmentEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun meters(x: List<MeterEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun readings(x: List<MeterReadingEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun categories(x: List<CategoryEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun suppliers(x: List<SupplierEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun funds(x: List<FundEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun cashAccounts(x: List<CashAccountEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun expenses(x: List<ExpenseEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun chargeItems(x: List<ChargeItemEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun tariffs(x: List<WaterTariffEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun periods(x: List<PeriodEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun revisions(x: List<PeriodRevisionEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun entries(x: List<JournalEntryEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun lines(x: List<JournalLineEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun audit(x: List<AuditEventEntity>): List<Long>
}
