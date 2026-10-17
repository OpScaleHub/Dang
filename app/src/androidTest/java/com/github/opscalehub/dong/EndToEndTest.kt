package com.github.opscalehub.dong

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.opscalehub.dong.data.db.PeriodStatus
import com.github.opscalehub.dong.domain.ledger.EntryType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real Compose UI through a complete month with the task-first
 * interface: welcome → setup wizard → expense (preset) → close the month from
 * the home checklist → payment from the unit page (typed in Toman) → reversal →
 * reports → activity recreation. Assertions read the screen and the ledger
 * (exact Rial). Wipes the app's data first: run it on an emulator only.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val container get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DongApp).container

    @Before
    fun wipe() = runBlocking {
        container.db.buildingDao().buildings().forEach { container.repos.buildings.delete(it.id) }
    }

    private fun waitFor(text: String, timeout: Long = 10_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun field(label: String): SemanticsNodeInteraction = rule.onNode(hasSetTextAction() and hasText(label, substring = true))

    /** On failure, log every visible text (one per line; whole-tree dumps get truncated by logcat). */
    private fun dumpScreen(tag: String) {
        rule.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().forEach { root ->
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
                val t = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString()
                val d = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString()
                if (t != null || d != null) android.util.Log.e("E2E", "$tag ${"  ".repeat(depth)}text=$t desc=$d")
                n.children.forEach { walk(it, depth + 1) }
            }
            walk(root, 0)
        }
    }

    private fun type(label: String, value: String) {
        val ok = runCatching { rule.waitUntil(5_000) { rule.onAllNodes(hasSetTextAction() and hasText(label, substring = true)).fetchSemanticsNodes().isNotEmpty() } }
        if (ok.isFailure) dumpScreen("type($label)")
        field(label).performTextClearance()
        field(label).performTextInput(value)
    }

    /** The last clickable node with this text or content description (dialog buttons share text with titles). */
    private fun clickLast(text: String) {
        val nodes = rule.onAllNodes((hasText(text) or hasContentDescription(text)) and hasClickAction())
        runCatching { rule.waitUntil(5_000) { nodes.fetchSemanticsNodes().isNotEmpty() } }
        val count = nodes.fetchSemanticsNodes().size
        if (count == 0) {
            // Some components keep their label in the unmerged tree; a touch on the label lands on them.
            val labels = rule.onAllNodes(hasText(text), useUnmergedTree = true)
            check(labels.fetchSemanticsNodes().isNotEmpty()) { "no node: $text" }
            labels[labels.fetchSemanticsNodes().size - 1].performClick()
        } else {
            nodes[count - 1].performClick()
        }
        rule.waitForIdle()
    }

    /**
     * Click that should open a new screen. The test harness occasionally loses a click injected
     * while a navigation transition settles (real touches were verified on device at 0.1 s), so retry.
     */
    private fun clickExpect(text: String, expected: String) {
        repeat(3) {
            clickLast(text)
            val shown = runCatching { rule.waitUntil(3_000) { rule.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty() } }
            if (shown.isSuccess) return
        }
        dumpScreen("clickExpect($text)")
        error("$text did not open $expected")
    }

    private fun scrollTo(text: String) {
        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text))
    }

    private fun assertNoUnexpectedError() =
        assertTrue("unexpected-error snackbar shown", rule.onAllNodesWithText("خطای غیرمنتظره", substring = true).fetchSemanticsNodes().isEmpty())

    /** Back to a top-level screen (like a user), then the bottom-bar tab. */
    private fun tab(label: String) {
        assertNoUnexpectedError()
        repeat(3) {
            if (rule.onAllNodes(hasContentDescription("بازگشت")).fetchSemanticsNodes().isNotEmpty()) {
                rule.onNode(hasContentDescription("بازگشت")).performClick()
                rule.waitForIdle()
            }
        }
        clickLast(label)
    }

    @Test
    fun fullMonthThroughTheUi() {
        // 1. welcome → setup wizard: one floor × two units (generator defaults: 100 m², 2 people)
        waitFor("راه‌اندازی ساختمان")
        clickLast("راه‌اندازی ساختمان")
        type("نام ساختمان", "ساختمان آزمون")
        clickLast("بعدی")
        type("تعداد طبقات", "1")
        type("واحد در هر طبقه", "2")
        clickLast("ساختن فهرست واحدها")
        clickLast("بعدی")
        clickLast("پایان")
        waitFor("این ماه")
        val building = runBlocking { container.db.buildingDao().buildings().single() }
        assertEquals(10L, building.displayDivisor)
        val units = runBlocking { container.db.buildingDao().units(building.id).associateBy { it.label } }
        assertEquals(setOf("۱-۱", "۱-۲"), units.keys)
        assertEquals(1, runBlocking { container.db.periodDao().periods(building.id).size })

        // 2. expense: cleaning (per occupant), 100,000 Toman = 1,000,000 Rial
        tab("هزینه‌ها")
        clickExpect("هزینه جدید", "شرح")
        type("شرح", "نظافت آزمون")
        rule.onNode(hasText("دسته") and hasClickAction()).performClick()
        rule.waitForIdle()
        clickLast("نظافت")
        type("مبلغ", "100000")
        // The live preview is below the fold; scroll to it and check it lists both units.
        scrollTo("چه کسی چقدر می‌پردازد")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("۵۰٬۰۰۰", substring = true).fetchSemanticsNodes().size >= 2 }
        scrollTo("ذخیره")
        clickLast("ذخیره")
        rule.waitUntil(5_000) { runBlocking { container.db.expenseDao().expenses(building.id).isNotEmpty() } }
        assertEquals(1_000_000L, runBlocking { container.db.expenseDao().expenses(building.id).single().amountMinor })

        // 3. close the month from the home checklist
        tab("خانه")
        clickExpect("بررسی و بستن ماه", "جمع شارژ")
        clickLast("بستن ماه و صدور شارژ")
        clickLast("بستن ماه و صدور شارژ") // confirm in dialog
        rule.waitUntil(10_000) { runBlocking { container.db.periodDao().periods(building.id).single().status == PeriodStatus.FINALIZED } }
        runBlocking {
            val bal = container.repos.finance.balances(building.id)
            assertEquals(500_000L, bal.units.getValue(units.getValue("۱-۱").id))
            assertEquals(500_000L, bal.units.getValue(units.getValue("۱-۲").id))
            assertTrue(bal.reconciles)
        }

        // 4. payment from the unit page: 50,000 Toman
        tab("واحدها")
        clickExpect("واحد ۱-۱", "ارسال قبض")
        clickExpect("ثبت دریافت", "ثبت دریافت از واحد")
        type("مبلغ", "50000")
        clickLast("ذخیره")
        rule.waitUntil(5_000) { runBlocking { container.repos.finance.balances(building.id).units.getValue(units.getValue("۱-۱").id) == 0L } }

        // 5. reverse it from «بیشتر» → all transactions
        tab("بیشتر")
        clickExpect("همه تراکنش‌ها", "برگشت تراکنش")
        clickExpect("برگشت تراکنش", "دلیل (الزامی")
        type("دلیل", "آزمون برگشت")
        clickLast("تأیید")
        rule.waitUntil(5_000) { runBlocking { container.repos.finance.balances(building.id).units.getValue(units.getValue("۱-۱").id) == 500_000L } }
        runBlocking {
            val entries = container.db.financeDao().entries(building.id)
            assertTrue(entries.any { it.type == EntryType.RESIDENT_PAYMENT })
            assertTrue(entries.any { it.type == EntryType.REVERSAL })
        }

        // 6. reports render for the closed month; recreation keeps everything
        tab("گزارش")
        waitFor("وصول شارژ")
        rule.activityRule.scenario.recreate()
        waitFor("این ماه")
        assertNoUnexpectedError()
        runBlocking {
            val bal = container.repos.finance.balances(building.id)
            assertEquals(1_000_000L, bal.outstandingDebts)
            assertTrue(bal.reconciles)
        }
    }
}
