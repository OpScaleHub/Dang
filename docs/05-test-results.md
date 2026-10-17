# 05 — Test Results

## UX overhaul (docs/07) — 2026-10-17

- **116 JVM/Robolectric tests, 0 failures** (adds `InsightsTest` ×10: FIFO
  collection 41,130,000 of 62,600,000 derived by hand, aging, cash-flow
  waterfall, expenses vs last month, monthly series/reserve, water,
  reversed payments, downward corrections; batch readings atomicity;
  allocation preview == finalized result; setup wizard atomicity; migration
  2→3; Toman↔Rial formatting; signed RTL numbers; bill image render).
- **Instrumented end-to-end test, 1/1 passing** on the emulator through the new
  UI: welcome → setup wizard → expense with preset and live preview →
  close the month from the home checklist → payment typed in Toman →
  reversal → reports → activity recreation.
- **Visual review** (emulator, light and dark): home checklist, units with
  debt aging, unit page, reports (ring, waterfall, bars, columns,
  sparklines), batch readings with anomaly flags, expense presets/preview,
  bill image. Defects found and fixed during the review: cyclic chart colours
  in the unit composition (now a stable building-wide mapping), minus/plus
  signs drifting right in RTL (now LTR-isolated), Latin digits in numeric
  inputs, meter order in the batch screen, empty month selected by default
  in the trend chart, non-ASCII bill file name.
- Chart palette validated with the dataviz checker against the app's card
  surfaces (categorical adjacent CVD ΔE ≥ 8.4; aging ramp light end ≥ 2:1).

## Release 0.1.0


Recorded on 2026-10-17 (1405/07/25) from a clean build (`./gradlew clean
testDebugUnitTest assembleDebug assembleRelease`), JDK 17, on Linux.

## Automated tests — 101 JVM/Robolectric tests, 0 failures

| Suite | Tests | What it covers |
|---|---:|---|
| `domain.money.AllocatorTest` | 9 | exact sums, rounding increment, largest remainder, ties, overflow, 2,000-case property test |
| `domain.allocation.AllocationEngineTest` | 22 | equal, occupants (day-weighted, start/end), area, weighted, explicit, parking (in-use/owned/selected/weights), fixed+variable, percent, subsets, zero eligible, zero basis, missing area/occupancy, inactive units, unknown units, credits, fund-only, rate charges, policy JSON |
| `domain.water.WaterTest` | 21 | normal, zero, negative, rollover, missing readings, reset, new meter, corrections, retired meters, replacement, main-meter reconciliation, unit > main, proportional / separate / fund-borne common water, tiered tariffs, fixed fee, surcharge, tariff changes, double-charge guard |
| `domain.ledger.LedgerTest` | 8 | opening/closing, partial payments, overpayment, debts, supplier payments, refunds, adjustments, reserve contributions and transfers, reversals, cash transfers, invariants |
| `domain.period.PeriodTest` | 5 | invalid dates, empty period, invalid expense blocks, missing reading blocks, correction diff |
| `domain.WorkbookScenarioTest` | 3 | every share and balance equals `workbook/expected-results.json`; snapshot round trip |
| `domain.calendar.JalaliTest` | 4 | Nowruz dates, leap years, 200-year round trip, Persian-digit parsing |
| `i18n.FormatTest` | 4 | Persian digits, grouping, unambiguous m³ display, parsing round trips |
| `data.RepositoryTest` | 15 | multi-building isolation, restart retention (file DB), transaction rollback, expense lifecycle, finalize + immutability, blocked finalize writes nothing, overlap, correction after finalization, opening/payments/refunds/adjustments/transfers, charges not reversible, occupancy history, 3 audit regressions |
| `data.MigrationTest` | 2 | v1→v2 migration preserves financial rows; all versions covered |
| `data.SampleDataTest` | 1 | demo data loads and reconciles on 7 different dates (month/year boundaries, leap Esfand) |
| `io.ExportBackupTest` | 7 | demo data end-to-end equals workbook reference; backup round trip incl. idempotent re-import; checksum/format/version rejection; structural validation; replace vs. diverged merge; XLSX well-formed + RTL; CSV injection guard; HTML statement |

Mutation checks were run on the allocator tie-break and on the correction
fix: in both cases the suite failed when the code was deliberately broken.

## Instrumented end-to-end test — 1 test, 0 failures

`EndToEndTest.fullMonthThroughTheUi` (Android 14 emulator) drives the real
Compose UI: onboarding → new building → two units with occupants → period →
expense (per-occupant policy from the category) → preview → finalize →
payment → reversal with reason → activity recreation, asserting both screen
text and ledger balances (400,000 / 600,000 split, reconciliation).

Run it on an emulator only — it wipes the app's data:
`ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`.

## Workbook

`workbook/verify_workbook.py`: 8,776 cells evaluated, **0 formula errors, 40/40
checks «درست»**. The checker was itself validated by injecting a `#DIV/0!` and a
negative meter reading (both detected).

## Manual / on-device verification

| Check | Result |
|---|---|
| Fresh install, onboarding, demo building (emulator, Android 14) | OK; balances equal the workbook (cash 69,000,000; reserve 51,000,000; net receivables 21,400,000) |
| Reading entry for 10 meters, a reading correction with reason, unit-meters-exceed-main warning | OK |
| Finalize current month through the UI, confirmation dialog | OK |
| Payment entry, balances update | OK |
| XLSX export via the system file picker, pulled and evaluated externally | OK — debits = credits (289,802,000) |
| Backup → wipe app data → restore from onboarding | OK — all unit balances, cash, row counts and period snapshots identical |
| Dark mode | OK |
| Install and launch on a physical OnePlus Nord over Wi-Fi debugging | OK |
| APK permissions (`aapt2 dump permissions`) | no INTERNET; only AndroidX's private receiver permission |

## On-device pass — OnePlus Nord (AC2003), dark mode, over Wi-Fi debugging

Non-destructive: a second demo building was added next to the existing one;
nothing else on the phone was touched.

| Step (all through the UI) | Result |
|---|---|
| Install over Wi-Fi (`adb install -r`, data kept), launch | OK, no entries in the crash log for the whole session |
| New demo building, dashboard | equals workbook: debts 21,470,000, credits 70,000, cash 69,000,000, reserve 51,000,000, ledger reconciles |
| Closing readings for 10 meters | main 158 m³, units 120.4 m³, unexplained 37.6 m³ |
| Correct a reading with a reason (335 → 336) | original kept struck through; unexplained 36.6 m³; locked readings show no delete |
| Preview → finalize with confirmation | finalized, snapshot note shown |
| Payment 1,000,000 (grouped echo «۱٬۰۰۰٬۰۰۰» shown) | unit 1-1: 3,594,000 → 2,594,000 |
| Reverse the payment with a reason | original marked «برگشت‌خورده», balance back to 3,594,000 |
| Force-stop and relaunch | all data and balances unchanged |
| XLSX export via system file picker; evaluated on the computer | debits = credits = 289,900,000; reconciliation formula = 0 |
| Backup via file picker; verified on the computer | SHA-256 matches; both buildings: 0 unbalanced entries, trial balance 0 |
| Restore preview of that backup (cancelled) | both buildings «قبلاً کامل وارد شده»: 401 / 470 records, 0 new, 0 differing |

## Defects found by these tests and fixed

1. Workbook explicit-amount check used `N()` on a range (only first cell).
2. Demo load used two transactions (an empty building could appear).
3. Middle-dot separator «·» resembled Persian zero «۰».
4. m³ shown with 3 decimals read as thousands (`۲۶٫۵۰۰`).
5. Restore unreachable on a fresh install.
6. Creating the first building showed a spurious "unexpected error".
7. Extended FAB had no accessible label.
8. Corrections could move finalized charges after a unit was deactivated.
9. A new meter's first reading blocked its first period.
10. Supplier payments against credit notes were accepted.
