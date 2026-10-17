package com.github.opscalehub.dong.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import com.github.opscalehub.dong.R
import com.github.opscalehub.dong.i18n.MoneyFormat
import com.github.opscalehub.dong.ui.S
import java.io.File

/** Content of one unit's bill; built by the caller from the ledger. */
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
)

/**
 * Renders a bill as a PNG (Persian, right-to-left, Vazirmatn) and shares it
 * through the system share sheet. Files go to the app cache only and are
 * exposed to the receiving app through a private FileProvider URI.
 */
object BillShare {

    fun render(context: Context, bill: Bill, money: MoneyFormat): Bitmap {
        val regular = ResourcesCompat.getFont(context, R.font.vazirmatn_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, R.font.vazirmatn_bold) ?: Typeface.DEFAULT_BOLD
        val w = 1080
        val pad = 64f
        val inner = (w - 2 * pad).toInt()
        val teal = Color.rgb(0x00, 0x69, 0x5C)
        val ink = Color.rgb(0x1B, 0x24, 0x24)
        val muted = Color.rgb(0x5B, 0x6B, 0x6B)
        fun paint(size: Float, tf: Typeface, color: Int = ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = tf; this.color = color }
        fun layout(text: String, p: TextPaint, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, width: Int = inner) =
            StaticLayout.Builder.obtain(text, 0, text.length, p, width).setAlignment(align)
                .setTextDirection(TextDirectionHeuristics.RTL).setIncludePad(false).build()

        // Measure first, then draw onto a bitmap of the right height.
        data class Item(val label: StaticLayout, val value: StaticLayout?, val gapAfter: Float, val rule: Boolean = false)
        val items = mutableListOf<Item>()
        items += Item(layout(S.billTitle + (bill.period?.let { " — $it" } ?: ""), paint(56f, bold, teal)), null, 12f)
        items += Item(layout("${bill.building}، ${S.unit} ${bill.unit}", paint(38f, regular, muted)), null, 40f, rule = true)
        if (bill.lines.isNotEmpty()) {
            items += Item(layout(S.billThisMonth, paint(36f, bold)), null, 16f)
            bill.lines.forEach { (k, v) -> items += Item(layout(k, paint(36f, regular)), layout(money.format(v), paint(36f, regular), Layout.Alignment.ALIGN_OPPOSITE), 10f) }
            items += Item(layout(S.total, paint(36f, bold)), layout(money.format(bill.lines.sumOf { it.second }), paint(36f, bold), Layout.Alignment.ALIGN_OPPOSITE), 32f, rule = true)
        }
        items += Item(layout(S.billPrevious, paint(36f, regular)), layout(money.format(bill.previous), paint(36f, regular), Layout.Alignment.ALIGN_OPPOSITE), 10f)
        items += Item(layout(S.billPaid, paint(36f, regular)), layout(money.format(bill.paid), paint(36f, regular), Layout.Alignment.ALIGN_OPPOSITE), 32f)
        val dueLabel = when {
            bill.due > 0 -> S.billTotalDue
            bill.due < 0 -> S.billCredit
            else -> S.billSettled
        }
        val duePaint = paint(64f, bold, if (bill.due > 0) Color.rgb(0xB3, 0x26, 0x1E) else teal)
        items += Item(layout(dueLabel, paint(40f, bold)), if (bill.due != 0L) layout(money.withUnit(kotlin.math.abs(bill.due)), duePaint, Layout.Alignment.ALIGN_OPPOSITE) else null, 40f)
        items += Item(layout("${bill.date}، ${S.billFooter}", paint(28f, regular, muted)), null, 0f)

        val height = (pad * 2 + items.sumOf { (maxOf(it.label.height, it.value?.height ?: 0) + it.gapAfter).toDouble() }).toInt()
        val bmp = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val band = Paint().apply { color = teal }
        c.drawRect(RectF(0f, 0f, w.toFloat(), 16f), band)
        var y = pad
        val rule = Paint().apply { color = Color.rgb(0xDF, 0xE5, 0xE5); strokeWidth = 3f }
        for (it in items) {
            val h = maxOf(it.label.height, it.value?.height ?: 0)
            c.save(); c.translate(pad, y); it.label.draw(c); c.restore()
            it.value?.let { v -> c.save(); c.translate(pad, y + (h - v.height)); v.draw(c); c.restore() }
            y += h + it.gapAfter
            if (it.rule) c.drawLine(pad, y - it.gapAfter / 2, w - pad, y - it.gapAfter / 2, rule)
        }
        return bmp
    }

    fun shareBill(context: Context, bill: Bill, money: MoneyFormat, reminder: String) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        // Overwrite one file per unit; nothing accumulates in the cache.
        // ASCII file name: some messengers mishandle non-Latin names.
        val file = File(dir, "bill-${com.github.opscalehub.dong.i18n.Fmt.latin(bill.unit).filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.ifBlank { "unit" }}.png")
        file.outputStream().use { render(context, bill, money).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, reminder)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, S.sharedVia).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        context.startActivity(Intent.createChooser(send, S.sharedVia).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
