# 07 — UX & Reporting Overhaul Plan

Status: **implemented** (all three phases; see docs/05 for verification).

Goal: turn Dang from "a ledger with screens" into a tool a non-technical
building manager enjoys using every month, with reporting worth opening.
The financial engine does not change; every phase keeps all existing tests
green and adds its own.

## Design principles

1. **Task-first, not model-first.** The home screen is "this month": what is
   done, what is left, one tap to the next step.
2. **Plain Persian.** «بدهی / طلب / تسویه» instead of «بدهکار / بستانکار»;
   «تراکنش» instead of «سند»; «روش تقسیم» instead of «مبنای تسهیم». Accounting
   detail stays available under «جزئیات».
3. **Show, don't list.** Key numbers are large; progress and composition are
   charts; anomalies are highlighted, not buried.
4. **Fast entry.** Batch screens, sensible defaults, a real Jalali date picker,
   live digit grouping, amounts in تومان.
5. **Shareable.** A unit's bill goes to WhatsApp/Telegram/Eitaa as an image in
   one tap.
6. **Offline and private as before.** No chart library, no network: charts are
   drawn with Compose Canvas, RTL-correct.

## Phase 1 — Tool, not ledger

| # | Change | Acceptance |
|---|---|---|
| 1.1 | **Display currency**: store Rial exactly; show تومان (÷10) by default; per-building setting. Schema v3 (`displayCurrencyName`, `displayDivisor`) with tested migration. Money input is typed in the display unit. | Migration test 2→3; formatting/parsing tests (e.g. 15 ریال ↔ «۱٫۵ تومان»); exports keep exact Rial |
| 1.2 | **Visual identity**: Vazirmatn font (OFL, bundled), larger key figures, status colours, unit chips, empty states | Screenshots light/dark on phone |
| 1.3 | **Plain-language copy** across `S.kt` / `Labels.kt` | No «بدهکار/بستانکار/سند/تسهیم» on primary screens |
| 1.4 | **Bottom navigation**: خانه · واحدها · هزینه‌ها · گزارش · بیشتر; drawer removed; building switcher in the top bar | E2E test navigates via tabs |
| 1.5 | **Home = «این ماه»**: month card with progress; checklist (readings x/n → bills → review & close → send bills → collect); KPI tiles; "start next month" | Correct counts on demo data |
| 1.6 | **Batch meter readings**: all meters in one list with previous reading, live consumption, anomaly flags (negative, >2× previous); saved atomically | Repository test for batch save + rollback |
| 1.7 | **Faster input**: Jalali date picker; live thousands grouping in amount fields; «۰۰۰» quick key | Picker tested on device |

## Phase 2 — Reporting worth opening

| # | Chart / feature | Data (new, tested in `ReportsMath`) |
|---|---|---|
| 2.1 | Chart primitives (Compose Canvas, RTL): ring, horizontal bars with delta, grouped columns, waterfall, stacked bar, sparkline | — |
| 2.2 | **Collection ring**: collected vs billed this month | payments dated in the period vs charges of the period |
| 2.3 | **Debt aging** per unit (current, 1–2 months, older) | FIFO: payments/credits settle oldest charges first |
| 2.4 | **Cash-flow waterfall** for the month | opening → +collections → −supplier payments → −refunds → closing |
| 2.5 | **Expenses by category** vs previous month | expense lines per period |
| 2.6 | **12-month trend**: billed vs collected; reserve balance | per-period series |
| 2.7 | **Water**: consumption per unit vs building average; unexplained-water % trend (leak signal) | water reports per period |
| 2.8 | **Unit statement**: «پول شما کجا رفت» stacked bar by category; balance sparkline | unit charges by category |
| 2.9 | **Share a bill**: per-unit bill image (Persian, RTL) + reminder text via the share sheet (FileProvider, no permissions) | image renders on device |

## Phase 3 — Remove the remaining friction

| # | Change | Acceptance |
|---|---|---|
| 3.1 | **Allocation presets + live preview** in the expense form («پیش‌فرض دسته»، «مساوی»، «نفرات»، «متراژ»، «مصرف آب»، «مثل دفعه قبل»); full editor behind «پیشرفته» | Preview equals the finalized result (test) |
| 3.2 | **Setup wizard** for a new building: details → units quick table (or "generate floors × units") → opening balances (optional) | E2E creates a building through the wizard |

## Verification per phase

`./gradlew testDebugUnitTest` (all green) · instrumented E2E on the emulator
only · screenshots on the phone (light and dark) · commit per phase · push at
the end · docs/05 updated with results.
