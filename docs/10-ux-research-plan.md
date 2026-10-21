# UX research plan — making دنگ easy for non-technical managers

Status: **plan, nothing implemented yet** (2026-10-21). Trigger: the owner's father (60+, about to
manage the building with the app) and wife (highly educated, non-technical) both found the app
complicated: where the formulas and split methods live, too many categories, too many choices.

## 1. The role to take, and the trap to avoid

Work first as a **UX researcher** (understand how these people think about the job), then as an
**interaction and content designer** (shape screens and words), with the owner as **product
owner** deciding trade-offs. Not as the developer.

The trap is the *curse of knowledge*: whoever built the app knows what «سیاست تقسیم»، «صندوق»
and «ماه بسته‌شده» mean, so the app reads as clear to them. The 0.3.2 review (docs/09) was done
from that inside view. It fixed wording, but it could not find out whether the **model** of the
app (expenses → split policies → closing a month → ledger) matches how a building manager
thinks. Only watching real users can show that.

## 2. What the evidence says

| Principle | What it means for دنگ | Source |
|---|---|---|
| **Match the real world / users' mental model**: use the user's words and concepts, not the system's | Managers think "this month each unit pays X", "the cleaner costs Y", not "allocation policy with basis and remainder". The app's model may be right internally and still wrong on screen. | Nielsen's 10 heuristics; NN/g on mental models: mistakes come from a gap between the user's model and the design |
| **Recognition rather than recall** | The user should not have to remember which screen holds the split method or what «مبنا» means; options and their effect should be visible where they act. | Nielsen's heuristics |
| **Progressive disclosure + good defaults** | Show only the primary options; defer advanced ones to a secondary screen. Make the common case need no decisions at all. | NN/g: progressive disclosure makes apps "easier to learn and less error-prone" |
| **Information overload is the top barrier for older users** | Fewer things per screen, one task per screen, fewer categories. | W3C WAI-AGE literature review (150+ articles); W3C *Older Users and Web Accessibility* |
| **Older adults: larger targets, plain words, forgiving errors, slower pace** | Seniors were 43% slower in NN/g's studies; small controls, dropdowns and jargon cause failures; errors must say what happened and how to fix it. Material/Android: touch targets ≥ 48 dp with 8 dp spacing. | NN/g *UX Design for Seniors* (87 guidelines; research with ages 65–89); NN/g *Define techy terms*; Google Android accessibility (48 dp) |
| **Plain-language error messages** | Human words, precise, constructive, never blame the user. | NN/g error-message guidelines; heuristic 9 |
| **Design with users, not for them** | Participatory design improves usability and satisfaction for older adults. | Systematic review of 132 studies of apps for 60+ (PMC12350549) |

Market convention (what users will compare against): Iranian building apps advertise
"شارژ بر اساس متراژ، نفرات، پارکینگ" as a short list of formulas (e.g. آپارتمانا، شارژمان،
شارژینو on Cafe Bazaar). Users expect to pick a simple formula, not to build one.

## 3. Method, in five steps

Small, repeated rounds beat one big study: NN/g's model shows **5 participants find most
usability problems** in a qualitative test (≈31% found per user, diminishing after ~5).

### Step 1. Understand the job (before touching the app), about 45 minutes with the father
*Contextual inquiry.* Sit with him and ask how the building is (or would be) run without any app:
- What bills arrive each month, and how does he decide who pays what? Ask him to show real bills.
- Which words does he use: «شارژ»، «سهم»، «قبض»، «هزینه مشترک»، «صندوق»?
- What is hard or annoying today, and what does he worry about (disputes, mistakes, forgetting)?
- What do residents ask him?

Output: his vocabulary, his real list of expense types, and how he splits each one. These
replace our guesses.

### Step 2. Card sort for categories, about 15 minutes, same session
Write each expense type from his real bills, plus the app's current categories, on cards.
Ask him to group them and name the groups (*open sort*). Repeat with the wife.
Output: the category list and names they actually use. This addresses "a lot of categories
may not even be needed".

### Step 3. Usability test of the current app (think-aloud), about 30 minutes each
Five participants: father, wife, and three others (ideally one more 60+ person and one
resident who is not the manager). Use the test kit below. The facilitator does not help and
does not explain; they only note where people hesitate, what they say, and where they fail.
Record the screen and voice, with consent. After the tasks, fill in the SUS questionnaire.

In parallel I run a **cognitive walkthrough** of the same tasks with the two personas,
asking at each step: will they try the right thing, will they see the control, will they
understand the label, will they see that it worked?

### Step 4. Synthesize and decide (together)
Merge the findings into one list. Rate each problem by severity (0 = not a problem … 4 = blocks
the task) and frequency. Group the problems into themes. The product owner picks what to change.
Expected themes, to be **confirmed or rejected by the data, not assumed**:
- a "simple mode" by default (a few split methods with everyday names; formulas only on request);
- fewer, user-named categories from the card sort;
- setup that asks plain questions («نظافت را چطور حساب می‌کنید؟ مساوی / به تعداد نفر / مبلغ ثابت»)
  instead of exposing the engine;
- one task per screen, bigger targets, a larger-text option.

### Step 5. Prototype, re-test, measure
Change the smallest thing that fixes the biggest problems. Repeat Step 3 with the same tasks
and compare task success, time, errors and SUS.

## 4. How we will know it is better

| Measure | Now | Target |
|---|---|---|
| Father completes a month alone (tasks 1–6) without help | unknown (Step 3 will measure) | yes |
| Task success across participants | baseline from Step 3 | ≥ 90% |
| SUS score (68 = average of ~500 studies; above 80 ≈ top 10%) | baseline | ≥ 80 |
| Words participants did not understand | list from Step 3 | none in the main tasks |

## 5. What is needed from the owner
- Time with the father and the wife (one about 75-minute session each, plus a short re-test later), and three more people if possible.
- Consent to record the phone screen and voice (or to take notes only).
- One month of real bills from the building, with names removed.
- The decision at Step 4 on which themes to act on.

---

## Appendix A: Test kit (Persian, ready to use)

**Before starting** (read aloud):
«ما برنامه را آزمایش می‌کنیم، نه شما را. هیچ جواب غلطی وجود ندارد. لطفاً هرچه به ذهنتان
می‌رسد بلند بگویید: کجا را نگاه می‌کنید، چه انتظاری دارید، چه چیزی گیجتان می‌کند. من کمکی
نمی‌کنم چون می‌خواهم ببینم برنامه بدون کمک چطور است.»

Use the demo building («ساختمان نمونه + داده‌های نمایشی»). The tasks describe goals, never
button names.

| # | Task (read to the participant) | Success = |
|---|---|---|
| 1 | «قبض برق مشاعات این ماه آمده: ۸۰۰ هزار تومان. آن را در برنامه وارد کنید.» | Expense saved in the current month |
| 2 | «می‌خواهید هزینه نظافت به تعداد نفرات هر واحد تقسیم شود، نه مساوی. این را تنظیم کنید.» | The expense or its category uses the per-person split |
| 3 | «قبل از ثبت، ببینید واحد ۲-۱ از این هزینه چقدر باید بدهد.» | Reads the amount from the preview |
| 4 | «این ماه تمام شده. کاری کنید که شارژ هر واحد مشخص شود.» | Month closed (readings and blockers handled) |
| 5 | «قبض واحد ۱-۱ را برایش در واتس‌اپ (یا هر پیام‌رسانی) بفرستید.» | Share sheet opened with the bill |
| 6 | «همسایه واحد ۱-۱، ۲۰۰ هزار تومان کارت‌به‌کارت کرده. ثبتش کنید.» | Payment recorded on 1-1 |
| 7 | «کدام واحدها هنوز بدهکارند و چقدر؟» | Names at least the largest debtor and amount |
| 8 | «فکر می‌کنید مبلغ قبض آب ماه قبل را اشتباه وارد کرده‌اید. چه می‌کنید؟» | Understands the closed month cannot be edited and finds a correction path |

For each task, note: success / partial / fail; time; where they hesitated; their exact words.

**After the tasks: SUS questionnaire** (1 = کاملاً مخالفم … 5 = کاملاً موافقم).
For a published, validated wording, use Dianat et al. 2014 (Persian SUS, α = 0.79).
Working translation:
1. فکر می‌کنم دوست دارم از این برنامه زیاد استفاده کنم.
2. برنامه را بی‌دلیل پیچیده دیدم.
3. فکر می‌کنم استفاده از برنامه آسان بود.
4. فکر می‌کنم برای استفاده از این برنامه به کمک یک فرد فنی نیاز دارم.
5. بخش‌های مختلف برنامه به‌خوبی با هم جور بودند.
6. فکر می‌کنم در برنامه ناهماهنگی زیادی وجود داشت.
7. فکر می‌کنم بیشتر مردم خیلی زود کار با این برنامه را یاد می‌گیرند.
8. کار با برنامه را خیلی دست‌وپاگیر دیدم.
9. هنگام استفاده از برنامه احساس اطمینان داشتم.
10. قبل از اینکه بتوانم با این برنامه کار کنم، باید چیزهای زیادی یاد می‌گرفتم.

Score: for odd items (score − 1), for even items (5 − score); sum × 2.5 = 0–100.

**Closing questions:** «سخت‌ترین قسمت کجا بود؟» «کدام کلمه را نفهمیدید؟» «اگر یک چیز را
عوض کنیم، چه باشد؟»

## Appendix B: Sources
- Nielsen, *10 Usability Heuristics* — https://www.nngroup.com/articles/ten-usability-heuristics/
- NN/g, *Mental Models* — https://www.nngroup.com/articles/mental-models/
- NN/g, *Progressive Disclosure* — https://www.nngroup.com/videos/progressive-disclosure/
- NN/g, *Usability for Older Adults* and *UX Design for Seniors* (3rd ed.) — https://www.nngroup.com/articles/usability-for-senior-citizens/ , https://www.nngroup.com/reports/senior-citizens-on-the-web/
- NN/g, *Define Techy Terms for Older Users* — https://www.nngroup.com/articles/define-techy-words-old-users/
- NN/g, *Usability Testing With Older Adults* — https://www.nngroup.com/articles/usability-testing-older-adults/
- NN/g, *Why You Only Need to Test with 5 Users* — https://www.nngroup.com/articles/why-you-only-need-to-test-with-5-users/
- NN/g, *Error-message guidelines* — https://www.nngroup.com/videos/error-message-communication-guidelines/
- W3C WAI, *Older Users and Web Accessibility* and WAI-AGE review — https://www.w3.org/WAI/older-users/ , https://www.w3.org/WAI/WAI-AGE/conclude.html
- Cognitive walkthrough — https://en.wikipedia.org/wiki/Cognitive_walkthrough
- Systematic review of age-friendly mobile app design — https://www.ncbi.nlm.nih.gov/pmc/articles/PMC12350549/
- Design guidelines of mobile apps for older adults (review) — https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10557006/
- Google, Android touch target size (48 dp) — https://support.google.com/accessibility/android/answer/7101858
- SUS interpretation (Sauro) — https://measuringu.com/interpret-sus-score/
- Dianat et al. 2014, Persian SUS — https://pmc.ncbi.nlm.nih.gov/articles/PMC4122034
- Iranian building apps, e.g. آپارتمانا — https://cafebazaar.ir/app/com.mahaksoft.apartment
