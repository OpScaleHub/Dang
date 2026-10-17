package com.github.opscalehub.dong

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.opscalehub.dong.data.SampleData
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
 * Drives the real Compose UI through a complete month: building → units →
 * period → expense → finalize → payment → reversal → activity recreation.
 * Assertions read both the screen and the ledger. Wipes the app's data first,
 * so run it on an emulator or a device without real data.
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

    private fun type(label: String, value: String) {
        field(label).performTextClearance()
        field(label).performTextInput(value)
    }

    /** The last clickable node with this exact text (dialog buttons share text with titles/screens). */
    private fun clickLast(text: String) {
        val nodes = rule.onAllNodes(hasText(text) and hasClickAction())
        runCatching { rule.waitUntil(5_000) { nodes.fetchSemanticsNodes().isNotEmpty() } }
        val count = nodes.fetchSemanticsNodes().size
        if (count == 0) {
            // Some Material components (e.g. the extended FAB) keep their label in the unmerged tree;
            // a touch on the label lands on the component.
            val labels = rule.onAllNodes(hasText(text), useUnmergedTree = true)
            check(labels.fetchSemanticsNodes().isNotEmpty()) { "no node: $text" }
            labels[labels.fetchSemanticsNodes().size - 1].performClick()
            rule.waitForIdle()
            return
        }
        nodes[count - 1].performClick()
        rule.waitForIdle()
    }

    private fun assertNoUnexpectedError() {
        assertTrue("unexpected-error snackbar shown", rule.onAllNodesWithText("خطای غیرمنتظره", substring = true).fetchSemanticsNodes().isEmpty())
    }

    private fun navigate(item: String) {
        assertNoUnexpectedError()
        // From a sub-screen, go back (like a user) until the drawer button is available.
        repeat(3) {
            if (rule.onAllNodes(hasContentDescription("منو")).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodes(hasContentDescription("بازگشت")).fetchSemanticsNodes().isNotEmpty()) {
                rule.onNode(hasContentDescription("بازگشت")).performClick()
                rule.waitForIdle()
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("منو")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription("منو")).performClick()
        rule.waitForIdle()
        clickLast(item)
    }

    private fun scrollTo(text: String) {
        rule.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text))
    }

    @Test
    fun fullMonthThroughTheUi() {
        // 1. onboarding → building
        waitFor("به «دنگ» خوش آمدید")
        clickLast("ساختمان جدید")
        type("نام ساختمان", "ساختمان آزمون")
        clickLast("ذخیره")
        waitFor("دوره جاری")
        val building = runBlocking { container.db.buildingDao().buildings().single() }

        // 2. two units with occupants
        navigate("واحدها و ساکنان")
        for ((label, occ) in listOf("101" to "2", "102" to "3")) {
            clickLast("واحد جدید")
            type("شماره/نام واحد", label)
            type("متراژ (متر مربع)", "80")
            type("تعداد ساکنان", occ)
            clickLast("ذخیره")
            waitFor("واحد $label")
        }
        rule.waitUntil(5_000) { runBlocking { container.db.buildingDao().units(building.id).size == 2 } }

        // 3. period for the current month
        navigate("دوره‌های صورتحساب")
        clickLast("دوره جدید")
        clickLast("ذخیره")
        val month = SampleData.month(container.repos.clock.today())
        waitFor(month.name)

        // 4. expense: cleaning (category default: per occupant, 2 + 3 people), assigned to the draft period
        navigate("هزینه‌ها")
        clickLast("هزینه جدید")
        type("شرح", "هزینه آزمون")
        rule.onNode(hasText("دسته") and hasClickAction(), useUnmergedTree = false).performClick()
        rule.waitForIdle()
        clickLast("نظافت")
        type("مبلغ", "1000000")
        scrollTo("ذخیره")
        clickLast("ذخیره")
        rule.waitUntil(5_000) { runBlocking { container.db.expenseDao().expenses(building.id).isNotEmpty() } }

        // 5. preview and finalize
        navigate("دوره‌های صورتحساب")
        clickLast(month.name)
        waitFor("جمع شارژ")
        clickLast("قطعی کردن دوره")
        clickLast("قطعی کردن دوره") // confirm in dialog
        rule.waitUntil(10_000) { runBlocking { container.db.periodDao().periods(building.id).single().status == PeriodStatus.FINALIZED } }
        val units = runBlocking { container.db.buildingDao().units(building.id).associateBy { it.label } }
        runBlocking {
            val bal = container.repos.finance.balances(building.id)
            assertEquals(400_000L, bal.units.getValue(units.getValue("101").id))
            assertEquals(600_000L, bal.units.getValue(units.getValue("102").id))
            assertTrue(bal.reconciles)
        }

        // 6. payment from unit 101
        navigate("دریافت‌ها و مانده‌ها")
        clickLast("ثبت دریافت از واحد")
        rule.onNode(hasText("واحد") and hasClickAction()).performClick()
        rule.waitForIdle()
        clickLast("101")
        type("مبلغ", "400000")
        clickLast("ذخیره")
        rule.waitUntil(5_000) { runBlocking { container.repos.finance.balances(building.id).units.getValue(units.getValue("101").id) == 0L } }
        waitFor("تسویه")

        // 7. reverse the payment with a reason → debt is back, original entry kept
        clickLast("برگشت سند")
        type("دلیل", "آزمون برگشت")
        clickLast("تأیید")
        rule.waitUntil(5_000) { runBlocking { container.repos.finance.balances(building.id).units.getValue(units.getValue("101").id) == 400_000L } }
        runBlocking {
            val entries = container.db.financeDao().entries(building.id)
            assertTrue(entries.any { it.type == EntryType.RESIDENT_PAYMENT })
            assertTrue(entries.any { it.type == EntryType.REVERSAL })
        }

        // 8. activity recreation keeps everything
        rule.activityRule.scenario.recreate()
        waitFor("ساختمان آزمون")
        runBlocking {
            val bal = container.repos.finance.balances(building.id)
            assertEquals(1_000_000L, bal.outstandingDebts)
            assertEquals(0L, bal.cashTotal)
            assertTrue(bal.reconciles)
        }
    }
}
