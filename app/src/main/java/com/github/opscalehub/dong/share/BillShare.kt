package com.github.opscalehub.dong.share

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import com.github.opscalehub.dong.DongApp
import com.github.opscalehub.dong.i18n.MoneyFormat
import com.github.opscalehub.dong.ui.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Content of one unit's bill; built by [com.github.opscalehub.dong.data.BillRepository]. */
data class Bill(
    val building: String,
    val unit: String,
    val period: String?,
    /** This period's charges by category name. */
    val lines: List<Pair<String, Long>>,
    val previous: Long,
    val paid: Long,
    /** Current balance: > 0 owed, < 0 credit. */
    val due: Long,
    val date: String,
    /** Charges of months after [period] (a late-sent bill), already in [due]. */
    val later: Long = 0,
    /** Credits, adjustments and refunds since the month began, signed as they move [due]. */
    val adjust: Long = 0,
)

/** A unit's bill as an image, and sharing it so the app learns that it went out. */
object BillShare {

    fun blocks(bill: Bill, money: MoneyFormat): List<Block> = buildList {
        add(Block.Title(S.billTitle + (bill.period?.let { " — $it" } ?: "")))
        add(Block.Subtitle("${bill.building}، ${S.unit} ${bill.unit}"))
        if (bill.lines.isNotEmpty()) {
            add(Block.Section(S.billThisMonth))
            bill.lines.forEach { (k, v) -> add(Block.Row(k, money.format(v))) }
            add(Block.Row(S.total, money.format(bill.lines.sumOf { it.second }), bold = true))
            add(Block.Rule)
        }
        // previous + this month + later + adjust − paid = due, so the image always adds up.
        add(Block.Row(S.billPrevious, money.format(bill.previous)))
        if (bill.later != 0L) add(Block.Row(S.billLater, money.format(bill.later)))
        if (bill.adjust != 0L) add(Block.Row(S.billAdjust, com.github.opscalehub.dong.i18n.Fmt.signed(bill.adjust > 0, money.format(kotlin.math.abs(bill.adjust)))))
        add(Block.Row(S.billPaid, money.format(bill.paid)))
        add(Block.Big(
            when {
                bill.due > 0 -> S.billTotalDue
                bill.due < 0 -> S.billCredit
                else -> S.billSettled
            },
            if (bill.due != 0L) money.withUnit(kotlin.math.abs(bill.due)) else null,
            if (bill.due > 0) ImageDoc.RED else ImageDoc.TEAL,
        ))
        add(Block.Footer("${bill.date}، ${S.billFooter}"))
    }

    fun render(context: Context, bill: Bill, money: MoneyFormat): Bitmap = ImageDoc.render(context, blocks(bill, money))

    /**
     * Shares the bill. When it is for a closed [periodId], the app the user picks in the share
     * sheet is recorded as the bill having been sent (see [ChosenReceiver]); dismissing the sheet
     * records nothing.
     */
    fun shareBill(context: Context, bill: Bill, money: MoneyFormat, reminder: String, periodId: String? = null, unitId: String? = null) {
        val callback = if (periodId != null && unitId != null) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(context, ChosenReceiver::class.java).putExtra(EXTRA_PERIOD, periodId).putExtra(EXTRA_UNIT, unitId)
            PendingIntent.getBroadcast(context, (periodId + unitId).hashCode(), intent, flags).intentSender
        } else null
        ImageDoc.share(context, render(context, bill, money), "bill-${ImageDoc.asciiName(bill.unit, "unit")}", reminder, callback)
    }

    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, S.sharedVia).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal const val EXTRA_PERIOD = "periodId"
    internal const val EXTRA_UNIT = "unitId"

    /** Told by the share sheet which app received a bill; records the bill as sent. */
    class ChosenReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val period = intent.getStringExtra(EXTRA_PERIOD) ?: return
            val unit = intent.getStringExtra(EXTRA_UNIT) ?: return
            val chosen = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, android.content.ComponentName::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
            val pending = goAsync()
            val repos = (context.applicationContext as DongApp).container.repos
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    repos.bills.markSent(period, unit, chosen?.packageName ?: "share")
                } catch (e: Exception) {
                    android.util.Log.i("Dang", "bill share not recorded: ${e.javaClass.simpleName}")
                } finally {
                    pending.finish()
                }
            }
        }
    }
}

/** The month at a glance for the building's group or channel: totals, expenses, optionally units. */
object SummaryShare {
    fun blocks(s: com.github.opscalehub.dong.data.MonthSummary, money: MoneyFormat, showUnits: Boolean, showDebts: Boolean): List<Block> = buildList {
        add(Block.Title("${S.summaryTitle} — ${s.period}"))
        add(Block.Subtitle(s.building))
        if (s.billed > 0) {
            val pct = (s.collected * 100 / s.billed).toInt()
            add(Block.Row(S.billed, money.format(s.billed)))
            add(Block.Bar("${S.collected} (${com.github.opscalehub.dong.i18n.Fmt.digits(pct.toString())}٪)", money.format(s.collected), s.collected.toFloat() / s.billed))
            add(Block.Row(S.remaining, money.format(s.billed - s.collected), bold = true))
            add(Block.Rule)
        }
        if (s.expenses.isNotEmpty()) {
            add(Block.Section(S.summaryExpenses))
            val max = s.expenses.maxOf { it.second }.toFloat()
            val top = s.expenses.take(7)
            top.forEach { (k, v) -> add(Block.Bar(k, money.format(v), v / max)) }
            val rest = s.expenses.drop(7).sumOf { it.second }
            if (rest > 0) add(Block.Bar(S.otherCategories, money.format(rest), rest / max))
            add(Block.Row(S.expensesTotal, money.format(s.expenses.sumOf { it.second }), bold = true))
            add(Block.Rule)
        }
        if (showUnits && s.units.isNotEmpty()) {
            add(Block.Section(if (showDebts) S.summaryUnitsWithDebt else S.summaryUnits))
            s.units.forEach { (label, charge, balance) ->
                val v = money.format(charge) + if (showDebts && balance > 0) "   (${S.owes} ${money.format(balance)})" else ""
                add(Block.Row("${S.unit} $label", v))
            }
            add(Block.Rule)
        }
        add(Block.Row(S.availableCash, money.format(s.cash)))
        if (s.reserve != 0L) add(Block.Row(S.reserveFund, money.format(s.reserve)))
        add(Block.Footer("${money.unitName}، ${s.date}، ${S.billFooter}"))
    }

    fun share(context: Context, s: com.github.opscalehub.dong.data.MonthSummary, money: MoneyFormat, showUnits: Boolean, showDebts: Boolean) =
        ImageDoc.share(context, ImageDoc.render(context, blocks(s, money, showUnits, showDebts)), "summary", null)
}
