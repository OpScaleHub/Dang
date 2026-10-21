# 13 — Project history, decisions and lessons (to resume later)

This is the handover record: what was built, in what order, **why** each choice was made, and
what we learned. It is written so that someone (or a new AI session) who was not there can pick
the project up without the original conversations. Details live in the other docs; this file
links to them.

## 1. What دنگ / Dang is, and for whom

- An offline-first Android app for running a residential building: expenses, monthly charges,
  water meters, parking, payments, debts, monthly bills and reports. Persian only, right-to-left,
  for Iranian buildings (Jalali calendar, Toman display, Rial storage).
- **The user who decides whether it works**: the owner's father, 60+, who will manage his
  building and has never used accounting software. Other real testers: the owner's wife (highly
  educated, non-technical) and his brother. Every UX choice after 0.3 is measured against them.
- Repository: <https://github.com/OpScaleHub/Dang>. Landing page and update feed:
  <https://opscalehub.github.io/Dang/>. Everything in the repo is synthetic data; it is public.

## 2. Timeline

| Date | Version | What and why |
|---|---|---|
| 2026-10-17 | 0.1 (no release) | Requirements, domain model and a prototype accounting workbook first (docs/01–03), then the engine (allocation, water, periods, double-entry ledger) with tests, Room persistence, XLSX/CSV/HTML export, backup/restore with validation, and the Compose UI. Tested on the emulator and on a physical phone (docs/05). |
| 2026-10-17/18 | — | Task-first UI and in-app reports and bill sharing (docs/07), then fixes found on the phone. |
| 2026-10-18 | 0.2.0 | Landing page on GitHub Pages and a local release script (`scripts/release.sh`). |
| 2026-10-18 | 0.3.0 | In-app updater: reads `release.json` from Pages, downloads the pinned APK and installs it. |
| 2026-10-18 | 0.3.1 | Sent-bill tracking, debtors list, month summary image. Released **before** the repo rename so old installs could still find it (docs/08). |
| 2026-10-18 | — | Rename to دنگ / Dang (repo `Dong` → `Dang`); technical identifiers kept (see §4). |
| 2026-10-18 | 0.3.2 | Plain-language wording review for non-technical managers (docs/09). |
| 2026-10-21 | — | Real users gave up after about half an hour: too many categories, too many choices, "where are the formulas?". Research plan and Persian test kit written (docs/10). |
| 2026-10-21 | 0.4.0 | "Simple by default" redesign (docs/11): the wizard asks *how the building charges*, 6 expense types, simple/advanced mode, text size, built-in feedback («نظر») and a SUS survey. Tester message: docs/12. |
| 2026-10-21 | 0.4.1 | Owner's feedback: combined monthly charge (per m² + per person + base), per-unit multipliers («۱ برابر … ۴ برابر», «بر اساس طبقه»). |
| 2026-10-21 | 0.4.2 | Generate, don't add one by one: water meters and parking created from the wizard, a «قبض‌های مشترک» step, meters no longer block months nobody splits by meter, simpler menu and screens. |
| 2026-10-21 | 0.4.3 | Reorder units (▲▼ in the wizard and the Units tab); the water-meter split offered only on the water type. |
| 2026-10-21 | 0.4.4 | The in-app demo is named «نمونه» (easy to find and delete) and holds a year of data: 11 closed months plus the current one, seasonal water, a price rise, one-off repairs, a reserve-funded emergency, and different payment habits (on time, partial, late, ahead, a bounced cheque). |

## 3. Key decisions and the reasons behind them

### Accounting model
- **Double-entry, append-only journal; nothing is edited or deleted.** Mistakes are fixed with
  reversals and adjustments that require a reason. A building's money is a trust matter between
  neighbours, so every number must be explainable and auditable (docs/02).
- **Money is stored as integer Rials, displayed as Toman** (÷10, configurable). No floating
  point anywhere in money; rounding increments are explicit (docs/01).
- **Months are periods that get finalized.** A finalized month is locked; corrections go through
  a recalculation with a revision (docs/02). Consequence that bit us in code: nothing may be
  written into a finalized month's dates (readings, payments, assignments), so generators must
  write data in time order.
- **Payments are balance-forward** (not matched to single charges); ageing uses oldest-first
  settlement. Simpler for managers; listed as a limitation in docs/06.

### Product and distribution
- **Offline-first, one phone per building.** No server, no account, no data leaves the phone
  except files the user shares. Backups move a building between phones. Sync is a P3 roadmap
  item; a read-only Google Drive view for residents is a deliberately deferred idea (docs/06).
- **Persian only**: the product targets Iranian buildings; the domain layer is locale-free so a
  language could be added, but it is not planned.
- **Direct APK + in-app updater** instead of a store: fastest path to a few testers. One signing
  key, kept outside the repo; losing it means every install has to be uninstalled to update.

### UX (from 0.4)
- **Ask how the building charges, then hide the engine.** Iranian buildings either take a fixed
  monthly amount (bills paid from the pot) or split the actual bills. The wizard asks this in
  plain words and sets up everything else. The engine (formulas, bases, funds) is still there,
  in advanced mode.
- **Simple mode never changes data**, it only hides controls. Old buildings keep everything.
- **Name choices by what they do** («مساوی بین همه واحدها»، «به تعداد نفرات»، «از صندوق
  ساختمان») rather than by engine terms («دسته»، «پایه تسهیم»).
- **Generate from the building's structure, keep later edits possible** (owner's principle,
  0.4.2). The wizard already knows floors and units, so meters and parking are created for all;
  each unit's «ویرایش» changes residents and parking from today, keeping history.
- **Meters are water-only.** In practice only water has per-unit internal meters; electricity is
  always each unit's own, and a shared gas meter has no unit sub-meters (it is split by area,
  people or equally).
- **▲▼ buttons instead of drag-and-drop** for ordering: drag needs a long press and steady hands,
  which older users find hard.
- **Feedback is built in** («نظر» on every screen: screenshot + three guided questions via any
  messenger; SUS after 5 days) because testers will not write reports on their own.
- **The demo is a real-looking year, not two months** (0.4.4): people judge an app by its
  reports and debts screens, which are empty without history. The name is short so that it is
  obvious which building to delete afterwards. The brief's two-month reference scenario is kept
  unchanged under `addDemoData(history = 0)` because the workbook tests check its exact numbers.

## 4. Things that must never change
- applicationId/package `com.github.opscalehub.dong`, `DongDatabase`, `dong.db`, backup format id
  `"dong-backup"`, FileProvider authority, prefs names, `Theme.Dong`. Renaming any of them breaks
  updates or old backups (docs/08).
- The release key `~/.config/dong/dong-release.jks` (alias `dong`, CN=Dong) and its passwords.
  Back it up off the machine.
- The update feed URLs (`UPDATE_URLS` in `app/build.gradle.kts`): `…/Dang/release.json` first,
  `…/Dong/release.json` second.

## 5. Lessons learned

### About users and UX
- **A builder's own review fixes words, not the model.** The 0.3.2 wording review was useful but
  real users still gave up: the gap was conceptual (engine concepts on screen). Watch real users,
  or at least role-play the specific person, before polishing text (docs/09, docs/10).
- **The owner's quick feedback rounds were the most productive input**: each of 0.4.1–0.4.4 came
  from one concrete observation ("a third unit on floor 1 stays at the bottom", "why is the meter
  option on every expense?"). Ship small, ask, repeat.
- **Defaults matter more than options.** Every choice we removed from the default path (rounding,
  currency, funds, tariffs, serial numbers) made the app easier without losing anything.

### Technical
- **GitHub redirects a renamed repo but not its Pages site.** Release once before renaming, and
  let the updater try both URLs (docs/08).
- **A meter's first reading becomes its baseline** and sets its install date. Writing a later
  reading first (as happened while building the year demo) makes the earlier one look missing,
  which surfaces as `MAIN_METER_REQUIRED`. Always write readings oldest first.
- **Finalized months lock their dates**, so seed data must be generated month by month: readings
  for all months first, then for each month create → expenses → finalize → payments dated in
  the next month.
- **Meter checks must only block what uses meters.** Before 0.4.2, adding a main meter by hand
  blocked every month even when no expense was split by reading.
- **Deterministic seed data**: the year demo uses `Random(1405)` so it is the same on every phone
  and in tests; a test loads it on awkward dates (Nowruz, 30 Esfand of a leap year, month ends).

### Working on devices
- **Never run the instrumented test on a real phone**: `EndToEndTest` deletes every building. Use
  `ANDROID_SERIAL=emulator-5554` (AVD `qg2`).
- **Never drive the owner's phone while they are using it.** Before any tap, check
  `adb shell dumpsys window | grep mCurrentFocus` shows `com.github.opscalehub.dong`.
- Wi-Fi adb changes port after the phone locks; rediscover with `adb mdns services`.
- In UI automation, dismiss the keyboard (`input keyevent 4`) before tapping «بعدی».

## 6. How to resume

- **Build and test**: `./gradlew testDebugUnitTest` (143 tests at 0.4.4; count them from
  `app/build/test-results/testDebugUnitTest/*.xml`), then
  `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest` (1 end-to-end test).
- **Release**: commit, `git push` (the script requires main in sync with origin), then
  `scripts/release.sh patch --skip-tests` (or `minor`). It builds the signed APK, writes
  `site/release.json`, tags, creates the GitHub Release with `dang.apk` and `dang-X.Y.Z.apk`,
  and Pages redeploys. Installed apps see the update within an hour (or on next start).
- **Where things are**: requirements docs/01, domain docs/02, architecture docs/04, test results
  docs/05, limitations and roadmap docs/06, UX history docs/07, docs/09–11, tester kit docs/10
  (appendix) and docs/12.
- **Next steps** (in order):
  1. Hand 0.4.x to the testers (docs/12), let them use «نظر» and the survey for a while.
  2. Synthesize their feedback as in docs/10 step 4 before building anything new.
  3. Open suggestions: short "what is this page?" help on Expenses, Months and Meters; an
     «برگرداندن» (undo) snackbar after saving a payment or expense (docs/09).
  4. Roadmap items in docs/06 (store listing, receipt photos, reminders, Drive read-only view).
