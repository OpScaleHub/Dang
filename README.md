# دنگ (Dong) — offline building management & accounting

**دنگ** یعنی «سهم هر نفر از یک هزینه مشترک». این برنامه اندرویدی هزینه‌های
ساختمان را بر اساس قواعدی که مدیر ساختمان تعیین می‌کند (مساوی، تعداد ساکنان،
متراژ، کنتور آب، پارکینگ، وزنی یا مبلغ مشخص) بین واحدها تقسیم می‌کند، دفتر
مالی دوطرفه نگه می‌دارد و بدون اینترنت، حساب کاربری یا سرور کار می‌کند.

Dong is an offline-first Android app (Persian, right-to-left) for residential
building management and expense accounting. It runs entirely on the phone: no
backend, no account, no network permission, no analytics.

## What it does

- **Buildings & units** — several independent buildings; units with floor,
  area, active flag and an occupancy **history** with effective dates.
- **Parking** — spaces independent of units; owned/assigned/rented and
  "in use" tracked separately, with effective dates.
- **Water** — main and unit meters, historical readings, corrections that
  keep the original, rollover, meter replacement, main-meter reconciliation
  (the unexplained difference is shown and only allocated if a policy says
  how), tiered tariffs with fixed fee and surcharge, double-charge guard.
- **Expenses** — configurable categories with default policies, suppliers,
  partial supplier payments, credit notes, void with reason.
- **Allocation engine** — equal, per occupant (occupant-days), area, measured
  consumption, weighted, explicit amounts, parking spaces; subsets of units;
  fixed + percentage + remainder components. Shares always sum exactly to the
  amount (largest-remainder rounding to a configurable increment) and every
  share is explained.
- **Billing periods** — live preview with Persian validation messages,
  finalization that freezes inputs, rules and results, and post-finalization
  **correction runs** that post only the differences, linked to the period.
- **Ledger** — double-entry, append-only, with linked reversals, opening
  balances, operating/reserve funds, cash/bank accounts and an audit log.
  Resident debt is kept separate from building cash.
- **Reports & files** — dashboard, unit statements, expenses by category,
  period comparison, water reconciliation; export an **XLSX workbook**, a
  journal **CSV**, a printable **HTML monthly statement**; **backup/restore**
  with checksum, validation, preview and duplicate-safe import.

## Install

Debug APK (built and tested): `app/build/outputs/apk/debug/app-debug.apk`

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleRelease` produces an **unsigned** release APK
(`app/build/outputs/apk/release/app-release-unsigned.apk`); configure your own
signing before distributing.

Requirements to build: JDK 17, Android SDK 35. Android 8.0 (API 26) or newer to run.

## Use

1. Open the app → **ساختمان جدید** (new building), or load the 9-unit sample
   building (synthetic data, clearly labelled) to explore.
2. **واحدها** — add units, areas and occupant counts (with effective dates).
3. **کنتورهای آب** — add meters; enter readings at the end of each period.
4. **هزینه‌ها** — record expenses; the category's policy is pre-filled and
   can be changed per expense.
5. **دوره‌های صورتحساب** — create the month, check the preview and the
   explanations, then **قطعی کردن دوره** (finalize).
6. **دریافت‌ها و مانده‌ها** — record payments, refunds and adjustments;
   open any unit's statement.
7. **گزارش‌ها** — export the workbook or CSV; the period screen exports the
   HTML statement. **تنظیمات** — back up regularly.

### Data, backup and sharing

- Data lives only on the phone and is **excluded from Android cloud backup
  and device transfer**. Take regular backups (Settings → تهیه فایل پشتیبان)
  and keep them somewhere safe; they contain financial data and unit notes.
- Restore works from the first screen too (new phone / reinstall).
- Saving a file to Google Drive is a manual, user-controlled action. **It does
  not synchronize phones**; one phone should be the source of truth for a
  building.

## Test

```bash
./gradlew testDebugUnitTest                      # 101 JVM + Robolectric tests
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest   # UI end-to-end (wipes app data!)
python workbook/verify_workbook.py               # needs openpyxl + formulas
```

Results and what they cover: [docs/05-test-results.md](docs/05-test-results.md).

## Repository layout

```
app/src/main/java/com/github/opscalehub/dong/
  domain/   pure-Kotlin financial engine (allocation, water, period, ledger, calendar)
  data/     Room entities/DAOs/migrations, repositories, sample data, backup
  io/       XLSX writer, CSV, HTML statement, backup codec
  i18n/     Persian labels and number/date formatting
  ui/       Jetpack Compose screens (Persian, RTL)
app/schemas/   exported Room schemas (v1, v2)
workbook/      accounting prototype workbook + generator + verifier
docs/          requirements, domain model, plan, architecture, tests, roadmap
```

## Documentation

1. [Requirements, assumptions, open decisions](docs/01-requirements-and-assumptions.md)
2. [Domain model, schema, invariants](docs/02-domain-model.md)
3. [Implementation plan](docs/03-implementation-plan.md)
4. [Architecture and extension points](docs/04-architecture-and-extensibility.md)
5. [Test results](docs/05-test-results.md)
6. [Known limitations and roadmap](docs/06-limitations-and-roadmap.md)

All sample data in this repository is synthetic.
