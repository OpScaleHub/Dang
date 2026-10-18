# 01 — Requirements, Assumptions and Open Decisions

Project: **Dong (دنگ)** — offline-first Android building management & accounting
("BuildingLedger" in the original brief). *Dong* is Persian for "a share of a
common cost", which is exactly what the app computes.

## 1. Starting point

- The repository (`OpScaleHub/Dong`) was empty. No existing conventions to
  preserve inside the repo; the build conventions (version catalog, Kotlin DSL,
  `com.github.opscalehub.*` namespace, JDK 17, compileSdk 35) follow the owner's
  sibling Android projects.
- **Product-owner direction (overrides §13 of the brief):** the entire
  application UI is Persian (Farsi), right-to-left, with Persian digits and the
  Solar Hijri (Jalali) calendar as the default display calendar. English UI is
  *not* shipped. The domain model stays locale-independent and all UI strings
  live in one Kotlin file (`ui/i18n/Strings.kt`), so a second language can be
  added later without touching the financial engine.

## 2. Functional scope of release 1

| Area | Delivered in release 1 |
|---|---|
| Buildings | Multiple independent buildings, each with currency, rounding, calendar and occupancy-basis settings |
| Units | Floor, label, area, active flag, notes, occupancy **history** with effective dates |
| Parking | Spaces independent of units; assignments with relation (owned/assigned/rented), *in-use* flag, vehicle note, effective dates |
| Water | Main + unit meters, historical readings, corrections (supersede), rollover, replacement (final/initial readings), reconciliation, versioned tiered tariffs with fixed fee and surcharge |
| Expenses | Configurable categories, suppliers, expense records with policy snapshot, payment status derived from supplier payments, partial payments |
| Allocation | Equal, per occupant, area, measured consumption, weighted, explicit amounts, parking spaces; subsets; fixed + percentage + remainder components; documented rounding |
| Charges | Recurring charge schedule (total-split or rate-based), water tariff charges, expense allocations |
| Ledger | Double-entry, append-only journal; reversals; opening balances; funds (operating/reserve/…); cash accounts; receivables per unit; payables per supplier |
| Periods | Draft → preview → finalize with immutable snapshot; post-finalization correction runs that post linked adjustments |
| Reports | Dashboard, unit statements, building summary, expenses by category, water reconciliation, fund balances, trial balance check |
| Files | XLSX workbook export, CSV export, HTML monthly statement, JSON backup/restore with validation, preview, checksum and duplicate detection |

## 3. Explicit assumptions (all configurable unless stated)

| # | Assumption | Where configurable |
|---|---|---|
| A1 | Money is stored as integer **minor units** (`Long`). For Iranian Rial/Toman the minor-unit digit count is 0. | Building: currency code, display name, minor digits |
| A2 | Shares are rounded to a building-level **rounding increment** (default 1 minor unit; e.g. 1,000 Rial). The remainder is distributed by the **largest-remainder** method so the sum always equals the amount allocated. | Building: rounding increment (snapshotted per period) |
| A3 | Occupant-based allocation uses **occupant-days** in the period by default (a family present 15 of 30 days counts half). Alternatives: count on the period's first day, or on its last day. | Building: occupancy basis |
| A4 | A meter reading dated *d* belongs to the period containing *d*. A period's **opening** reading for a meter is the last effective reading dated **before** the period start (or an `INITIAL` reading inside the period); its **closing** reading is the last effective reading dated inside the period. | Fixed rule, documented in the UI help |
| A5 | The unexplained difference between main-meter and unit-meter consumption is **never** distributed unless the expense's policy says how (proportional, separate basis, or borne by the fund). | Per expense / category policy |
| A6 | Parking costs are charged to the unit holding the effective assignment on the period's **last day**; which spaces are eligible (all / in use only / selected / by relation) is chosen per expense. Unassigned eligible spaces are excluded and listed in the explanation. | Per policy |
| A7 | A payment received reduces the unit's receivable (balance-forward). Payments are not matched to individual charges in release 1. | — |
| A8 | Units marked inactive are excluded from **future** calculations; finalized periods keep their snapshot. | Unit active flag |
| A9 | The building "fund" view is accrual-based (charges are income when assessed; expenses when incurred). Cash is reported separately. | — |
| A10 | Sample data (9 units, 958 m²) is synthetic. Occupant counts are representative values marked as sample; no real names, readings, payments or balances are invented. | Editable after creation |
| A11 | Water tariffs: a tariff version is selected by `effectiveFrom ≤ period end`. Tiers apply to each unit's consumption in the period. | Tariff editor |
| A12 | Only one active device/administrator per building is assumed. File export does **not** synchronize phones. | — |

## 4. Unresolved business decisions (the administrator must decide)

These are **not** encoded as rules. The app offers the options and records the
choice in each finalized period's snapshot.

1. **Who bears the unexplained water difference** (leaks, common taps, meter
   error): residents proportionally, residents by another basis, or the fund.
2. **Elevator maintenance basis**: equal, by area, by floor weights, or ground
   floor excluded — depends on the building's bylaws / owners' agreement.
3. **Parking-related liability**: owner of the space vs. the unit using it.
4. **Vacant units**: whether a vacant unit pays equal-share charges (default:
   yes, while active) and per-occupant charges (0 occupants → 0 share).
5. **Late payment penalties**: not implemented (legal/contractual question).
6. **Owner vs. tenant liability** for specific charges (e.g. reserve
   contributions usually fall on owners). Release 1 charges the *unit*; the
   statement can be handed to whoever is liable.
7. **Rounding increment** and whether rounding differences are acceptable on
   individual statements (the sum is always exact).
8. **Reserve-fund target and contribution level.**

## 5. Non-functional requirements

- Works fully offline. The only network use is the in-app updater, which reads the
  public `release.json` from the landing page and downloads the new APK; it sends
  no user or building data and can be switched off in Settings.
- No account, analytics, telemetry, ads or cloud dependency.
- Financial writes happen inside database transactions; failures roll back.
- Finalized financial history is append-only; corrections are linked reversals
  or adjustments with a mandatory reason.
- Imported files are untrusted: size-limited, schema-validated, checksum
  verified, never executed; XLSX import is not offered (export only), so no
  macro surface exists.
- Resident personal data is optional (a free-text note per unit) and never
  logged.

## 6. Out of scope for release 1 (see roadmap)

Google Sheets API, cloud sync, multi-device access, roles/permissions,
notifications/reminders, receipt image attachments (a text document reference
is stored instead), late fees, English UI.
