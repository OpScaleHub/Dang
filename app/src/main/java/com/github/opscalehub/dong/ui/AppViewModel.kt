package com.github.opscalehub.dong.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.InvalidationTracker
import com.github.opscalehub.dong.DongApp
import com.github.opscalehub.dong.data.OpException
import com.github.opscalehub.dong.data.db.BuildingEntity
import com.github.opscalehub.dong.data.db.PeriodEntity
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.i18n.Fa
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Activity-scoped state shared by all screens: the selected building, live
 * lists observed from Room, a database-change ticker for derived calculations,
 * and a single error/message channel. Business rules stay in the repositories.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as DongApp).container
    val repos = container.repos
    val backup = container.backup
    val reports = container.reports
    val updater = container.updater
    private val db = container.db
    private val prefs = app.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** Incremented on every database write; screens recompute previews and balances from it. */
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes.asStateFlow()
    private val observer = object : InvalidationTracker.Observer(arrayOf("buildings", "units", "occupancy", "parking_spaces", "parking_assignments",
        "meters", "meter_readings", "categories", "suppliers", "funds", "cash_accounts", "expenses", "charge_items", "water_tariffs",
        "periods", "period_revisions", "journal_entries", "journal_lines", "bill_shares")) {
        override fun onInvalidated(tables: Set<String>) {
            _changes.value = _changes.value + 1
        }
    }

    init {
        db.invalidationTracker.addObserver(observer)
    }

    override fun onCleared() {
        db.invalidationTracker.removeObserver(observer)
    }

    val buildings = db.buildingDao().observeBuildings().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val selectedId = MutableStateFlow(prefs.getString(KEY_BUILDING, null))

    val building: StateFlow<BuildingEntity?> = combine(buildings, selectedId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun select(id: String) {
        selectedId.value = id
        prefs.edit().putString(KEY_BUILDING, id).apply()
    }

    private fun <T> per(f: (String) -> Flow<List<T>>): StateFlow<List<T>> =
        building.flatMapLatest { b -> if (b == null) flowOf(emptyList()) else f(b.id) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val units = per { db.buildingDao().observeUnits(it) }
    val occupancy = per { db.buildingDao().observeOccupancy(it) }
    val spaces = per { db.buildingDao().observeSpaces(it) }
    val assignments = per { db.buildingDao().observeAssignments(it) }
    val meters = per { db.waterDao().observeMeters(it) }
    val readings = per { db.waterDao().observeReadings(it) }
    val tariffs = per { db.waterDao().observeTariffs(it) }
    val categories = per { db.expenseDao().observeCategories(it) }
    val suppliers = per { db.expenseDao().observeSuppliers(it) }
    val expenses = per { db.expenseDao().observeExpenses(it) }
    val chargeItems = per { db.expenseDao().observeChargeItems(it) }
    val funds = per { db.financeDao().observeFunds(it) }
    val cashAccounts = per { db.financeDao().observeCashAccounts(it) }
    val periods = per { db.periodDao().observePeriods(it) }
    val entries = per { db.financeDao().observeEntries(it) }
    val audit = per { db.financeDao().observeAudit(it) }
    val billShares = per { db.periodDao().observeShares(it) }

    /** Draft period containing [today], else the most recent draft, else the most recent period. */
    fun currentPeriod(list: List<PeriodEntity>, today: Long): PeriodEntity? =
        list.firstOrNull { it.status == PeriodStatus.DRAFT && today in it.startDate..it.endDate }
            ?: list.filter { it.status == PeriodStatus.DRAFT }.maxByOrNull { it.startDate }
            ?: list.maxByOrNull { it.startDate }

    val today: Long get() = repos.clock.today()

    fun names(): Fa.Names = object : Fa.Names {
        override fun unit(id: String?) = units.value.firstOrNull { it.id == id }?.label ?: (id ?: "—")
        override fun meter(id: String?) = meters.value.firstOrNull { it.id == id }?.label ?: (id ?: "—")
        override fun source(id: String?) = expenses.value.firstOrNull { it.id == id }?.description
            ?: chargeItems.value.firstOrNull { it.id == id }?.name ?: (id ?: "—")
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    /** Runs a repository action, reporting failures in Persian. [onSuccess] runs on the main thread. */
    fun run(success: String? = null, onSuccess: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val ok = try {
                block()
                true
            } catch (e: OpException) {
                Log.i("Dang", "action rejected: ${e.code}")
                _messages.emit(Fa.error(e.code))
                false
            } catch (e: Exception) {
                // Log the type only: messages may contain resident data.
                Log.w("Dang", "action failed: ${e.javaClass.simpleName}")
                _messages.emit(S.unexpectedError)
                false
            } finally {
                _busy.value = false
            }
            // Outside the try: a UI callback failing must never be reported as a failed (rolled back) operation.
            if (ok) {
                success?.let { _messages.emit(it) }
                onSuccess()
            }
        }
    }

    companion object {
        private const val KEY_BUILDING = "building"
    }
}
