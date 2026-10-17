# 03 — Implementation Plan and Acceptance Criteria

Status legend: ☐ planned · ☑ done and verified (see `docs/05-test-results.md`).

| # | Milestone | Acceptance criteria |
|---|---|---|
| ☑ M0 | Requirements, domain model, plan (this folder) | Docs reviewed against the brief; assumptions and open decisions listed |
| ☑ M1 | Accounting workbook prototype (`workbook/`) | Opens in Excel/LibreOffice; input cells styled differently from formula cells; data validation on inputs; sample scenario totals equal the Kotlin engine's expected values; zero formula errors |
| ☑ M2 | Gradle project skeleton | `./gradlew assembleDebug` succeeds with Compose + Room + KSP |
| ☑ M3 | Domain engine: money, allocator, policies, water, tariffs, period calculator, ledger, Jalali calendar | All allocation/water/financial unit tests pass |
| ☑ M4 | Persistence: entities, DAOs, repositories, migrations, transactional posting | Robolectric tests: CRUD, multi-building isolation, rollback on error, restart retention, migration v1→v2 |
| ☑ M5 | Import/export: XLSX writer, CSV, HTML statement, backup/restore codec | Round-trip test: export → restore → identical balances; XLSX parses as valid OOXML; duplicate import adds nothing |
| ☑ M6 | Compose UI (Persian, RTL) | All primary destinations reachable; sample building created; reading entry, expense entry, period preview/finalize, payment, reports, backup work on emulator |
| ☑ M7 | Release docs: README, known limitations, roadmap, test results, debug APK | APK path stated; README matches actual behaviour |

Milestones are executed in order; tests run after each one and failures are
fixed before continuing.
