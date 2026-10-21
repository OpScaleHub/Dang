# Usability review — a non-technical manager's view (0.3.2)

Walked through every screen with the demo building on the emulator (dark mode, 100% and
130% font size), reading each label as someone with no accounting background would.

## Found and fixed

| # | Problem | Change |
|---|---|---|
| 1 | An expense of a closed month opened as a normal, editable-looking form (fields, split-method chips) with no save button, so edits went nowhere; doing related things failed with messages naming tools the user had never seen («تعدیل») | Closed or cancelled expenses open as a **read-only summary**: what it was, «چرا قابل تغییر نیست؟» with concrete next steps, what each unit actually paid in that month, and the one thing still possible, paying the supplier |
| 2 | The amount field said تومان while the line under it said «ریال» (the storage unit) | Removed; the field shows only the display unit |
| 3 | New expense: red «الزامی» under «شرح» before anything was typed; ten fields at once; a greyed-out save with no reason | Essentials first (category, amount, description, date, month, «همین حالا پرداخت شد»); supplier, fund, invoice number and notes behind «جزئیات بیشتر»; no premature error; under the save button «برای ذخیره، این‌ها را وارد کنید: …» |
| 4 | Expense list: one long list across months; «پرداخت‌شده» did not say paid by whom (it means to the supplier, but readers think of residents) | Grouped by month, newest first, closed months marked «بسته‌شده» with one line on what that means; status reads «به فروشنده پرداخت شده / هنوز به فروشنده پرداخت نشده» (unpaid highlighted) |
| 5 | Month review: ten identical red lines «خطا: قرائت پایان دوره…», no way to act on them; «بستن ماه» greyed out without explanation; «حذف این ماه» as prominent as closing | Repeated problems grouped into one line with the meter names and a «ثبت قرائت همه کنتورها» button; «باید رفع شود / توجه» instead of «خطا / هشدار»; a line explaining why the month can't be closed yet; delete is a quiet red text button |
| 6 | Three words for one thing: «دوره» / «ماه», «قطعی‌شده» / «بسته‌شده», «پیش‌نویس» / «باز» | One vocabulary everywhere: **ماه، بسته‌شده، باز** (strings, error messages, change log) |
| 7 | Accounting jargon: «سند»، «تعدیل»، «برگشت سند»، «از محل صندوق»، «مرجع سند»، «نفر-روز»، «خروجی صورتحساب (HTML)» | «تراکنش»، «اصلاح حساب واحد»، «برگشت تراکنش»، «پرداخت از صندوق»، «شماره فاکتور یا رسید (اختیاری)»، «به نسبت روزهای سکونت»، «صورتحساب کامل برای چاپ» |
| 8 | «بیشتر» menu subtitles were long technical sentences, cut off mid-word | One short plain line per item |
| 9 | Building settings opened with currency code (IRR), decimal places, rounding and occupancy basis | Those are under «تنظیمات پیشرفته»; name, address, Toman/Rial and calendar stay visible |
| 10 | Locked-month errors («این دوره قطعی شده…», «این تاریخ در یک دوره قطعی‌شده است…») | Rewritten to say what to do next, naming the actual buttons |

Checked and fine as is: Home's «این ماه» checklist, the unit page, the send-bills and
collect screens, reports, and every screen at 130% font size (text wraps, nothing clipped).

## Suggestions not done (for a later round)

- ~~**«همه تراکنش‌ها»** starts with five action buttons~~ — done in 0.4: one main action, the rest under «کارهای دیگر».
- **Meters screen** shows reconciliation numbers and every meter's last reading at once; a
  simpler default with details on tap would suit occasional users.
- **Short "what is this page?" help** on Expenses, Months and Meters for first-time users.
- **Undo after a mistaken entry**: today a wrong payment is fixed with «برگشت تراکنش»; a
  snackbar «برگرداندن» right after saving would catch most slips.
