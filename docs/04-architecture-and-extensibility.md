# 04 — Architecture, Technology Choices and Extension Points

## Stack (verified building with these exact versions)

| Component | Version | Why |
|---|---|---|
| Gradle / AGP | 8.11.1 / 8.7.3 | Stable pair; matches the owner's other projects |
| Kotlin / KSP | 2.0.21 / 2.0.21-1.0.28 | K2 compiler, Compose compiler plugin |
| Compose BOM / Material 3 | 2024.12.01 | Stable Compose + M3 |
| Navigation Compose | 2.8.5 | Simple string routes |
| Room | 2.6.1 (KSP, Kotlin codegen) | Exported schemas, tested migrations |
| kotlinx-serialization | 1.7.3 | Policies, snapshots and backups as JSON |
| Robolectric | 4.14.1 | Room/repository tests on the JVM |
| minSdk / target | 26 / 35 | `java.time` without desugaring |

Deliberately **not** used: a DI framework (one manual `AppContainer` is enough
for one module), multiple Gradle modules (the domain boundary is enforced by
package and verified by a grep for `android*` imports), Apache POI (too heavy
for Android; a 200-line OOXML writer suffices for export), any network library
(the only network use is the updater, a plain `HttpURLConnection` GET; see below).

## In-app updates

`update/Updater.kt` reads the landing page's `release.json` (written by
`scripts/release.sh`) at most once a day on start in release builds, or when the
user taps «بررسی نسخه جدید». A newer `versionCode` is offered on Home and in
Settings; the APK is downloaded from the version-pinned release URL into the
cache, checked against the published SHA-256 and the archive's own package name
and version, then committed to a `PackageInstaller` session. Android always shows
its confirmation screen and rejects an APK signed with a different key.
`UpdateReceiver` maps the installer's status back to the UI. Permissions:
`INTERNET` and `REQUEST_INSTALL_PACKAGES`. Debug builds never check unless built
with `-PupdateUrl=…`, which also allows cleartext to `localhost` for testing.

## Layers and rules

```
ui ──► data (repositories) ──► domain (pure Kotlin)
 │          │
 └──► io ◄──┘   (exporters are pure functions of ReportData)
```

1. **Domain** never imports Android; every financial rule is here and is unit
   tested on the JVM.
2. **Repositories** are the only writers. Every multi-step write runs in one
   Room transaction; journal entries go through `JournalWriter`, which rejects
   unbalanced entries and records an audit event.
3. **UI** observes Room `Flow`s; derived figures (balances, previews) are
   recomputed from a database-change ticker, so screens never show stale
   totals. UI text lives in `ui/S.kt` and `i18n/Labels.kt` (Persian).
4. **Exports and backups** are produced from a read-only snapshot
   (`ReportData` / a single transaction) and written through the Storage Access
   Framework to a location the user picks.

## Extension points (documented, intentionally not implemented)

| Future feature | Where it plugs in | What already makes it possible |
|---|---|---|
| Google Sheets import/export | A new exporter next to `WorkbookExporter`, reading `ReportData` | Exporters are pure functions; no provider coupling in the domain |
| Optional cloud sync / multi-device | A sync service exchanging `BuildingBackup` deltas | All rows have stable UUIDs; the journal is append-only (entries can be merged by id); `BackupRepository.preview` already classifies new / identical / diverged records |
| Shared access and roles | A backend owning `Repos` operations; the app becomes a client | Business rules live in repositories/domain, not in screens |
| Notifications / reminders | A WorkManager job reading `FinanceRepository.balances` and period due dates | Due dates are stored on periods |
| Receipt attachments | `ExpenseEntity.documentRef` today holds a text reference; a future schema version adds a content-URI column with a migration | Migration path tested (v1→v2) |
| More languages | Turn `S`/`Fa` into interfaces with one implementation per language | Exhaustive `when`s make missing translations compile errors |
| New allocation bases | Add a `Basis` subtype + a branch in `AllocationEngine.quantities` | Policies are versioned JSON; unknown types fail loudly |

Not built now (by design): a backend, a custom sync protocol, account
registration, analytics.
