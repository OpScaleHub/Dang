# 06 — Known Limitations, Unresolved Decisions and Roadmap

## Known limitations of release 0.1.0

1. **Single device per building.** Backups move data between phones, but two
   phones editing the same building cannot be merged. Restore refuses to merge
   a building whose records diverged ("add missing" is only offered when the
   backup is a superset); the only options are skip or replace.
2. **File sharing is not synchronization.** Exporting the workbook or a
   backup to Google Drive does not keep phones in sync.
3. **No import of spreadsheets or bank statements.** Only Dang backup files
   can be imported (by design: smaller attack surface, no macros).
4. **Payments are balance-forward.** A payment reduces the unit's balance; it
   is not matched to individual charges.
5. **Attachments are text references only** (`documentRef`), not photos.
6. **Dates** use a built-in Jalali month picker (typing still works); there
   is no year jump control yet.
7. **Persian-only UI** (product decision). The domain is locale-free; adding a
   language is mechanical (see docs/04).
8. **Water tariff tiers apply per unit and per period** as entered; tiered
   pricing on the building total is not modelled.
9. **Late fees, interest and penalties are not implemented** (legal question).
10. **Corrections after finalization** recalculate with corrected readings,
    occupancy, areas and parking. Changes to expense amounts of a finalized
    period are made with a new (possibly negative) expense or a unit
    adjustment, not by editing the original.
11. **Distribution is direct APK only** (GitHub Releases + in-app updater, signed
    with one release key kept outside the repo; see README). No store listing.
12. **Accessibility** was checked for labels (all actions and charts have
    accessible names/summaries) but not with a full TalkBack walkthrough.
13. **Existing buildings keep their display unit after the v3 upgrade**
    (Rial); switch to Toman under Settings. New buildings default to Toman.

## Unresolved business decisions (the administrator decides; the app offers options)

See docs/01 §4: who bears unexplained water, elevator basis, parking liability,
vacant-unit treatment, owner vs. tenant liability, rounding increment, reserve
target, late fees.

## Prioritized roadmap

| Priority | Item | Notes |
|---|---|---|
| P1 | Share bills to all debtors in one go | Partly done in 0.3.1: «ارسال قبض واحدها» lists every unit with sent status; each bill still opens its own share sheet |
| P1 | F-Droid / Play listing | Signed releases and in-app updates exist since 0.2.0/0.3.0 |
| P2 | Receipt photos (content URI + migration v5) | Backup must then include files (zip) |
| P2 | Payment allocation to specific charges (instead of oldest-first) | Aging and collection reports already use oldest-first settlement |
| P2 | Reminders for due dates and missing readings | WorkManager, local only |
| P2 | **Shared read-only view for residents via Google Drive** (owner's idea, 2026-10-18; deliberately not started) | The manager publishes the building's workbook (or a summary) to their own Google Drive/Sheets and shares a view-only link with residents, who see balances and monthly reports without installing anything. Open questions for that round: Google sign-in and Drive scope (`drive.file` only), automatic vs. manual publish after closing a month, what residents may see (per-unit debts are sensitive — same opt-in as the month summary image), and keeping the app usable fully offline. Builds on the existing XLSX export and the month summary image. |
| P3 | Optional sync / shared access with roles | Requires a backend; append-only journal and UUIDs already fit |
| — | Additional languages | Not planned: the product targets Iranian buildings (see docs/08) |
