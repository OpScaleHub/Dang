# دنگ (Dong) — offline building management & accounting

**دنگ** یعنی «سهم هر نفر از یک هزینه مشترک». این برنامه اندرویدی هزینه‌های
ساختمان را بر اساس قواعدی که مدیر ساختمان تعیین می‌کند (مساوی، تعداد ساکنان،
متراژ، کنتور آب، پارکینگ، وزنی یا مبلغ مشخص) بین واحدها تقسیم می‌کند، دفتر
مالی دوطرفه نگه می‌دارد و بدون اینترنت، حساب کاربری یا سرور کار می‌کند.

Dong is an offline-first Android app (Persian, right-to-left) for residential
building management and expense accounting. It runs entirely on the phone: no
backend, no account, no network permission, no analytics.

## What it does

**Day to day** the app is organised around the manager's month: the home
screen «این ماه» shows a five-step checklist (read meters → enter bills →
review & close → send bills → collect) with live progress, key figures and
a collection ring. Bills go to residents as an image through the phone's
share sheet (WhatsApp, Telegram, Eitaa…), with a ready-made reminder text.
Amounts are shown in **تومان** while stored exactly in ریال (per-building
setting). Reports include collection rate, debt aging, a monthly cash-flow
waterfall, expenses by category vs. last month, a 12-month billed/collected
trend, reserve growth, water use per unit vs. the building average and an
unexplained-water (leak) trend — all drawn in-app, offline.

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

1. Open the app → **راه‌اندازی ساختمان** (setup wizard: details → units,
   generated from floors × units → optional opening balances), or load the
   9-unit demo building (synthetic data, clearly labelled).
2. **خانه** — follow the month checklist: enter all meter readings on one
   screen, add the month's bills (presets + a live «چه کسی چقدر
   می‌پردازد» preview), then **بستن ماه و صدور شارژ**.
3. **واحدها** — see who owes what (with debt aging); on a unit, record a
   payment, **ارسال قبض** (share the bill image) or send a reminder.
4. **گزارش** — charts for the month and the trend; export the XLSX workbook
   or CSV. **بیشتر** — meters and tariffs, parking, categories, recurring
   charges, all transactions, settings and **backup** (do it regularly).

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
./gradlew testDebugUnitTest                      # 116 JVM + Robolectric tests
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
  ui/       Jetpack Compose screens (Persian, RTL); ui/charts = in-app charts
  share/    bill image rendering + share sheet (FileProvider, no permissions)
app/schemas/   exported Room schemas (v1–v3)
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
7. [UX & reporting overhaul plan](docs/07-ux-plan.md)

All sample data in this repository is synthetic.

Font: [Vazirmatn](https://github.com/rastikerdar/vazirmatn), SIL Open Font
License 1.1 (see `licenses/Vazirmatn-OFL.txt`).

## Landing page and releases

- Landing page: <https://opscalehub.github.io/Dong/> (static files in `site/`,
  deployed by `.github/workflows/pages.yml` on every push to `main` that touches `site/`).
- Releases are cut locally, no CI build:

  ```bash
  scripts/release.sh            # patch bump: 0.2.0 -> 0.2.1
  scripts/release.sh minor      # or major, or an explicit 1.0.0
  scripts/release.sh --dry-run  # build and show notes, change nothing
  ```

  The script bumps `version.properties`, runs the unit tests, builds a signed
  release APK, writes `site/release.json` (version, size, SHA-256, Jalali date),
  commits and tags `vX.Y.Z`, pushes, and publishes a GitHub Release with
  `dong.apk` attached. The landing page's download button always points at
  `releases/latest/download/dong.apk`.
- The signing key is created on the first run in `~/.config/dong/`
  (override with `DONG_SIGNING=/path/to/signing.properties`). **Back it up**:
  without it, later versions cannot update installed copies.
