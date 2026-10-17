# 06 — Known Limitations, Unresolved Decisions and Roadmap

## Known limitations of release 0.1.0

1. **Single device per building.** Backups move data between phones, but two
   phones editing the same building cannot be merged. Restore refuses to merge
   a building whose records diverged ("add missing" is only offered when the
   backup is a superset); the only options are skip or replace.
2. **File sharing is not synchronization.** Exporting the workbook or a
   backup to Google Drive does not keep phones in sync.
3. **No import of spreadsheets or bank statements.** Only Dong backup files
   can be imported (by design: smaller attack surface, no macros).
4. **Payments are balance-forward.** A payment reduces the unit's balance; it
   is not matched to individual charges.
5. **Attachments are text references only** (`documentRef`), not photos.
6. **Date entry is typed** (`۱۴۰۵/۰۷/۲۵`, Persian or Latin digits, with a
   "today" button); there is no Jalali date-picker widget yet.
7. **Persian-only UI** (product decision). The domain is locale-free; adding a
   language is mechanical (see docs/04).
8. **Water tariff tiers apply per unit and per period** as entered; tiered
   pricing on the building total is not modelled.
9. **Late fees, interest and penalties are not implemented** (legal question).
10. **Corrections after finalization** recalculate with corrected readings,
    occupancy, areas and parking. Changes to expense amounts of a finalized
    period are made with a new (possibly negative) expense or a unit
    adjustment, not by editing the original.
11. **Release signing** is not configured; `assembleRelease` produces an
    unsigned APK. Debug APKs are installable for evaluation.
12. **Accessibility** was checked for labels (all actions have accessible
    names) but not with a full TalkBack walkthrough.

## Unresolved business decisions (the administrator decides; the app offers options)

See docs/01 §4: who bears unexplained water, elevator basis, parking liability,
vacant-unit treatment, owner vs. tenant liability, rounding increment, reserve
target, late fees.

## Prioritized roadmap

| Priority | Item | Notes |
|---|---|---|
| P1 | Jalali date picker | Faster, error-free date entry |
| P1 | Per-unit statement sharing (HTML/PDF per unit via share sheet) | Today: one HTML for all units |
| P1 | Signed release build + F-Droid / Play listing | Needs a keystore decision |
| P2 | Receipt photos (content URI + migration v3) | Backup must then include files (zip) |
| P2 | Payment allocation to specific charges / aging report | For buildings that need it |
| P2 | Reminders for due dates and missing readings | WorkManager, local only |
| P3 | Google Sheets export | Optional, user-initiated |
| P3 | Optional sync / shared access with roles | Requires a backend; append-only journal and UUIDs already fit |
| P3 | Additional languages (English first) | |
