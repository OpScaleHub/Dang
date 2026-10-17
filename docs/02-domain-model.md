# 02 — Domain Model, Calculation Rules, Schema and Invariants

## 1. Layering

```
ui (Compose, Persian strings)            ──► app/src/main/java/.../ui
  │ ViewModels
data (Room entities, DAOs, repositories) ──► .../data
  │ maps to/from
domain (pure Kotlin, no android.*)       ──► .../domain
  ├─ money        Money math, rounding, largest-remainder allocator
  ├─ calendar     Jalali ⇄ Gregorian conversion
  ├─ water        meter-chain consumption, reconciliation, tariffs
  ├─ allocation   policy model + allocation engine with explanations
  ├─ period       period calculator (inputs → charge lines + issues)
  └─ ledger       double-entry posting rules, balances, invariants
io (xlsx writer, csv, html statement, backup codec) ──► .../io
sync (interfaces only: ExportTarget, future SyncProvider) ──► .../sync
```

The domain package has **no Android imports** and is covered by plain JVM unit
tests. The same rules are mirrored (for validation only) in the Excel
prototype workbook.

## 2. Money and sign convention

- `Long` minor units everywhere (`amountMinor`). No floating point for money.
- Ratios use `BigInteger` arithmetic; nothing is rounded until the final step.
- **Ledger sign:** a journal line's `amount > 0` is a **debit**, `< 0` a
  **credit**. Every entry's lines sum to exactly 0.
- **Unit balance** (receivable) is shown as *debit balance*: `> 0` means the
  unit **owes** the building ("بدهکار"), `< 0` means the unit has **credit**
  ("بستانکار").
- **Supplier balance** is shown as *credit balance*: `> 0` means the building
  owes the supplier.
- **Fund balance** is shown as *credit balance*: `> 0` means money available to
  the fund.

## 3. Entities (Room schema v2)

All IDs are random UUID strings (stable across export/import, enabling
duplicate detection). Dates are `epochDay` (Long, ISO calendar); instants are
epoch millis.

| Table | Key columns | Notes |
|---|---|---|
| `buildings` | id, name, address, currencyCode, currencyName, minorDigits, roundingIncrement, occupancyBasis, calendar, createdAt | Settings snapshot into each finalized period |
| `units` | id, buildingId, floor, label, areaCentiM2, active, notes, sortOrder | Area in 1/100 m² (exact) |
| `occupancy` | id, unitId, effectiveFrom, occupantCount, note | History; count holds from effectiveFrom until the next record |
| `parking_spaces` | id, buildingId, label, notes, active | Independent of units |
| `parking_assignments` | id, spaceId, unitId, relation, inUse, vehicleNote, effectiveFrom, effectiveTo? | relation ∈ OWNED/ASSIGNED/RENTED |
| `meters` | id, buildingId, unitId?, role, label, serial, rolloverLimitLiters?, installedOn, retiredOn?, replacesMeterId? | role ∈ MAIN/UNIT/COMMON |
| `meter_readings` | id, meterId, readingDate, valueLiters, kind, supersedesId?, note, enteredAt | kind ∈ REGULAR/INITIAL/FINAL/ROLLOVER; never edited, corrections supersede |
| `categories` | id, buildingId, name, defaultPolicyJson, defaultFundId?, isWater, active, sortOrder | User-configurable |
| `suppliers` | id, buildingId, name, phone, notes | |
| `funds` | id, buildingId, name, kind | kind ∈ OPERATING/RESERVE/OTHER |
| `cash_accounts` | id, buildingId, name, kind | CASH_BOX / BANK |
| `expenses` | id, buildingId, periodId?, categoryId, fundId, supplierId?, date, description, amountMinor, documentRef, policyJson, notes, status, createdAt | status ∈ ACTIVE/VOIDED; policy copied from category at creation |
| `charge_items` | id, buildingId, name, categoryId, fundId, mode, amountMinor, policyJson, tariffId?, activeFrom, activeTo?, active | Recurring schedule |
| `water_tariffs` | id, buildingId, name, effectiveFrom, fixedFeeMinor, tiersJson, surchargeBp, commonRateMinorPerM3 | Versioned by effectiveFrom |
| `periods` | id, buildingId, name, startDate, endDate, dueDate?, status, finalizedAt?, snapshotJson? | DRAFT/FINALIZED |
| `period_revisions` | id, periodId, revision, createdAt, reason, snapshotJson, journalEntryId | Correction runs after finalization |
| `journal_entries` | id, buildingId, entryDate, type, memo, reference, periodId?, expenseId?, unitId?, supplierId?, reversesEntryId?, createdAt | Append-only |
| `journal_lines` | id, entryId, account, amountMinor, cashAccountId?, unitId?, supplierId?, fundId?, categoryId? | Append-only |
| `audit_events` | id, buildingId, at, action, entityType, entityId, summary | Who/what/when/why trail |

### Ledger accounts (fixed kinds, dimensioned)

| Account kind | Dimension | Normal side |
|---|---|---|
| `CASH` | cashAccountId | debit |
| `RECEIVABLE` | unitId | debit |
| `PAYABLE` | supplierId (nullable = "general") | credit |
| `FUND_EQUITY` | fundId | credit |
| `INCOME` | fundId, categoryId | credit |
| `EXPENSE` | fundId, categoryId | debit |

### Posting rules

| Event | Debit | Credit |
|---|---|---|
| Opening balances | CASH, RECEIVABLE(unit) | PAYABLE(supplier), FUND_EQUITY(fund) |
| Expense incurred | EXPENSE(fund, cat) | PAYABLE(supplier) |
| Supplier payment | PAYABLE(supplier) | CASH |
| Charge assessed (period finalization) | RECEIVABLE(unit) | INCOME(fund, cat) |
| Resident payment | CASH | RECEIVABLE(unit) |
| Refund to resident | RECEIVABLE(unit) | CASH |
| Credit adjustment to unit | INCOME(fund, cat) | RECEIVABLE(unit) |
| Debit adjustment to unit | RECEIVABLE(unit) | INCOME(fund, cat) |
| Fund transfer (e.g. operating → reserve) | FUND_EQUITY(from) | FUND_EQUITY(to) |
| Cash transfer (box → bank) | CASH(to) | CASH(from) |
| Reversal of any entry | negated lines of the original | |

Consequences: a payment is never income (it settles a receivable); a reserve
transfer is never an expense (equity → equity); an expense allocated to
residents nets to zero in the fund (expense + income) while a fund-borne
expense reduces the fund.

## 4. Financial invariants (tested)

1. **I1** Every journal entry balances: Σ lines = 0.
2. **I2** Trial balance: Σ of all lines in a building = 0.
3. **I3** Net assets: `cash + receivables − payables = Σ fund balances`
   (fund balance = −(FUND_EQUITY + INCOME + EXPENSE lines of that fund)).
4. **I4** Every allocation's shares sum exactly to the amount allocated.
5. **I5** A finalized period's snapshot and charge entries are never modified;
   later changes appear only as entries/revisions linked to it.
6. **I6** An expense belongs to at most one period and is allocated once.
7. **I7** A reversal references its original; an entry can be reversed at most
   once; a reversal cannot itself be reversed.
8. **I8** Water: main − Σ unit consumption = unallocated consumption is always
   reported; it is allocated only by an explicit policy option.

## 5. Allocation engine

`AllocationPolicy` (JSON-serialized, snapshotted per expense/charge item):

```
AllocationPolicy(fundOnly: Boolean, components: List<Component>)
Component(portion: Remainder | Percent(bp) | Fixed(minor),
          basis: Equal | Occupants | Area | Consumption(common) |
                 Weighted(weights) | Explicit(amounts) | Parking(filter, relations, spaceWeights),
          eligibility: AllActive | Selected(unitIds))
Consumption.common: Proportional | Separate(basis) | FundBorne
```

Algorithm for an amount `A`:

1. Resolve component amounts: `Fixed` first, then `Percent` of `A` (largest
   remainder over components), then `Remainder` gets the rest. Errors if the
   fixed + percent parts exceed `A` or no remainder component absorbs the rest.
2. For each component, build per-unit **weights** (integers): 1 (equal),
   occupant-days (occupants), centi-m² (area), litres (consumption), configured
   weight, explicit amount, Σ eligible space weights (parking).
3. Validate: no eligible units → `NO_ELIGIBLE_UNITS`; Σ weights = 0 →
   `ZERO_BASIS`; missing area/meter/reading → specific error; explicit amounts
   must sum to the component amount.
4. **Largest remainder with increment `q`**: raw share `rᵢ = A·wᵢ/W` (exact
   rational). `sᵢ = ⌊rᵢ/q⌋·q`. Leftover `L = A − Σsᵢ`; `⌊L/q⌋` increments of `q`
   go to the units with the largest fractional parts (ties → unit sort order);
   the final sub-increment residue `L mod q` goes to the first unit in that
   ranking. Σsᵢ = A always.
5. Each share carries an explanation (basis quantity, weight, raw share,
   rounding adjustment) rendered by the UI and exports.

Rate-based charge items (`rate × quantity` per unit) round each unit
independently to the increment (half-up); there is no total to preserve.

## 6. Water

- Effective readings = readings not superseded by a correction.
- Meter chain for a period: opening (last reading before start, or `INITIAL`
  inside) → readings inside the period in date order → stops at `FINAL`.
- Delta = `cur − prev`; if negative it is valid **only** when `cur` is a
  `ROLLOVER` reading on a meter with `rolloverLimitLiters`
  (delta = `limit − prev + cur`); otherwise `NEGATIVE_CONSUMPTION` (blocking).
- Unit consumption = Σ chains of the unit's meters in the period (covers
  replacement: old meter `FINAL` + new meter `INITIAL`).
- Reconciliation: `main − Σ unit = unallocated` (negative → warning
  `UNIT_METERS_EXCEED_MAIN`).
- Tariff charge per unit: `fixedFee + Σ tiers(price × litres/1000)` then
  `+ surcharge bp`; rounded to increment. Optional common component:
  `unallocated × commonRate` allocated by a chosen basis.
- **Double-charge guard**: a period containing a water-tariff charge item may
  not also allocate a water-category expense to residents
  (`DOUBLE_WATER_CHARGE`, blocking) — record the supplier bill as fund-borne.

## 7. Billing period lifecycle

```
DRAFT ──(preview: recalculated live)──► FINALIZED (snapshot + CHARGE entry)
                                             │
                                             └─► correction run (revision n):
                                                 recompute with snapshot rules +
                                                 current readings/occupancy,
                                                 post per-unit differences as a
                                                 PERIOD_CORRECTION entry
```

Finalization validates: no blocking issues, period not already finalized, no
overlapping finalized period, all expenses valid. The snapshot stores the full
calculation input (settings, units, occupancy, spaces, assignments, meters,
readings used, expenses with policies, charge items, tariff) and output
(charge lines with explanations, water report, issues).

## 8. Backup format

`dong-backup` JSON: `{format: "dong-backup", formatVersion, appVersion,
createdAt, payloadSha256, payload: {buildings: [...all tables...]}}`. Restore
validates format/version/checksum/referential integrity/ledger balance, then
shows a preview: per building *new* / *identical* / *conflicting*. Modes:
**add missing records only** (never overwrites, idempotent — re-importing the
same file adds nothing) or **replace building** (requires typed
confirmation).
