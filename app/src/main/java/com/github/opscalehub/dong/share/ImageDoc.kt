package com.github.opscalehub.dong.share

import android.content.Context
import android.content.Intent
import android.content.IntentSender
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
import com.github.opscalehub.dong.ui.S
import java.io.File

/** One line of a shareable image, top to bottom. */
sealed interface Block {
    data class Title(val text: String) : Block
    data class Subtitle(val text: String) : Block
    data class Section(val text: String) : Block
    data class Row(val label: String, val value: String, val bold: Boolean = false) : Block
    /** A row with a thin bar under it; [fraction] is 0..1 of the full width. */
    data class Bar(val label: String, val value: String, val fraction: Float, val color: Int = ImageDoc.TEAL) : Block
    data class Big(val label: String, val value: String?, val color: Int) : Block
    data object Rule : Block
    data class Footer(val text: String) : Block
}

/**
 * Renders [Block]s as a white, right-to-left PNG in Vazirmatn (the bill and the month summary),
 * and shares PNGs through the system share sheet. Files go to the app cache only and reach
 * the receiving app through a private FileProvider URI.
 */
object ImageDoc {
    val TEAL = Color.rgb(0x00, 0x69, 0x5C)
    val RED = Color.rgb(0xB3, 0x26, 0x1E)
    private val INK = Color.rgb(0x1B, 0x24, 0x24)
    private val MUTED = Color.rgb(0x5B, 0x6B, 0x6B)
    private val TRACK = Color.rgb(0xE6, 0xEC, 0xEB)
    private val RULE = Color.rgb(0xDF, 0xE5, 0xE5)
    private const val W = 1080
    private const val PAD = 64f

    fun render(context: Context, blocks: List<Block>): Bitmap {
        val regular = ResourcesCompat.getFont(context, R.font.vazirmatn_regular) ?: Typeface.DEFAULT
        val bold = ResourcesCompat.getFont(context, R.font.vazirmatn_bold) ?: Typeface.DEFAULT_BOLD
        val inner = (W - 2 * PAD).toInt()
        fun paint(size: Float, tf: Typeface, color: Int = INK) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = tf; this.color = color }
        fun layout(text: String, p: TextPaint, opposite: Boolean = false) =
            StaticLayout.Builder.obtain(text, 0, text.length, p, inner)
                .setAlignment(if (opposite) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.RTL).setIncludePad(false).build()

        // Measure first, then draw onto a bitmap of the right height.
        class Item(val label: StaticLayout?, val value: StaticLayout?, val gapAfter: Float, val rule: Boolean = false, val bar: Pair<Float, Int>? = null)
        val items = blocks.map { b ->
            when (b) {
                is Block.Title -> Item(layout(b.text, paint(56f, bold, TEAL)), null, 12f)
                is Block.Subtitle -> Item(layout(b.text, paint(38f, regular, MUTED)), null, 40f, rule = true)
                is Block.Section -> Item(layout(b.text, paint(36f, bold)), null, 16f)
                is Block.Row -> {
                    val tf = if (b.bold) bold else regular
                    Item(layout(b.label, paint(36f, tf)), layout(b.value, paint(36f, tf), opposite = true), 10f)
                }
                is Block.Bar -> Item(layout(b.label, paint(34f, regular)), layout(b.value, paint(34f, regular), opposite = true), 18f,
                    bar = b.fraction.coerceIn(0f, 1f) to b.color)
                is Block.Big -> Item(layout(b.label, paint(40f, bold)), b.value?.let { layout(it, paint(64f, bold, b.color), opposite = true) }, 40f)
                Block.Rule -> Item(null, null, 32f, rule = true)
                is Block.Footer -> Item(layout(b.text, paint(28f, regular, MUTED)), null, 0f)
            }
        }
        fun rowHeight(it: Item): Float = maxOf(it.label?.height ?: 0, it.value?.height ?: 0) + if (it.bar != null) BAR_H + 6f else 0f
        val height = (PAD * 2 + items.sumOf { (rowHeight(it) + it.gapAfter).toDouble() }).toInt()
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.drawRect(RectF(0f, 0f, W.toFloat(), 16f), Paint().apply { color = TEAL })
        val rule = Paint().apply { color = RULE; strokeWidth = 3f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        var y = PAD
        for (it in items) {
            val h = maxOf(it.label?.height ?: 0, it.value?.height ?: 0).toFloat()
            it.label?.let { l -> c.save(); c.translate(PAD, y); l.draw(c); c.restore() }
            it.value?.let { v -> c.save(); c.translate(PAD, y + (h - v.height)); v.draw(c); c.restore() }
            it.bar?.let { (f, color) ->
                // Right-to-left: the bar grows from the right edge.
                val top = y + h + 6
                fill.color = TRACK; c.drawRoundRect(RectF(PAD, top, W - PAD, top + BAR_H), 6f, 6f, fill)
                fill.color = color; c.drawRoundRect(RectF(W - PAD - (W - 2 * PAD) * f, top, W - PAD, top + BAR_H), 6f, 6f, fill)
            }
            y += rowHeight(it) + it.gapAfter
            if (it.rule) c.drawLine(PAD, y - it.gapAfter / 2, W - PAD, y - it.gapAfter / 2, rule)
        }
        return bmp
    }

    private const val BAR_H = 14f

    /**
     * Writes [bitmap] to the cache as [name] (ASCII: some messengers mishandle other names) and
     * opens the share sheet. [onChosen] is told which app the user picked, if any.
     */
    fun share(context: Context, bitmap: Bitmap, name: String, text: String?, onChosen: IntentSender? = null) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, S.sharedVia, onChosen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Lowercase ASCII letters and digits of [s] (Persian digits converted), or [fallback]. */
    fun asciiName(s: String, fallback: String) =
        com.github.opscalehub.dong.i18n.Fmt.latin(s).filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.ifBlank { fallback }
}
