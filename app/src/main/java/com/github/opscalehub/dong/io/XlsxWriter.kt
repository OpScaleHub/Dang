package com.github.opscalehub.dong.io

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal, dependency-free Office Open XML (.xlsx) writer: inline strings,
 * numbers, formulas (recalculated by the spreadsheet on open), right-to-left
 * sheets, a few styles, frozen header row and column widths. Produces no
 * macros, external links or shared formulas.
 */
class XlsxWriter {
    enum class Style(val id: Int) { NORMAL(0), HEADER(1), MONEY(2), BOLD(3), TITLE(4), MONEY_BOLD(5), DECIMAL3(6) }

    sealed interface Cell {
        data class Text(val value: String, val style: Style = Style.NORMAL) : Cell
        data class Num(val value: Long, val style: Style = Style.MONEY) : Cell
        data class Dec(val value: Double, val style: Style = Style.DECIMAL3) : Cell
        data class Formula(val formula: String, val style: Style = Style.MONEY_BOLD) : Cell
        data object Empty : Cell
    }

    class Sheet(val name: String, val rtl: Boolean = true) {
        val rows = mutableListOf<List<Cell>>()
        val widths = mutableMapOf<Int, Double>()
        var freezeRows = 0

        fun row(vararg cells: Cell) = apply { rows += cells.toList() }
        fun row(cells: List<Cell>) = apply { rows += cells }
        fun header(vararg names: String) = apply {
            rows += names.map { Cell.Text(it, Style.HEADER) }
            freezeRows = rows.size
        }
        fun width(col: Int, w: Double) = apply { widths[col] = w }
        val rowCount: Int get() = rows.size
    }

    private val sheets = mutableListOf<Sheet>()

    fun sheet(name: String, rtl: Boolean = true): Sheet {
        require(name.length in 1..31 && name.none { it in "[]:*?/\\" }) { "invalid sheet name: $name" }
        require(sheets.none { it.name == name }) { "duplicate sheet: $name" }
        return Sheet(name, rtl).also { sheets += it }
    }

    fun write(out: OutputStream) {
        require(sheets.isNotEmpty())
        ZipOutputStream(out).use { zip ->
            fun put(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes())
            put("_rels/.rels", RELS)
            put("xl/workbook.xml", workbook())
            put("xl/_rels/workbook.xml.rels", workbookRels())
            put("xl/styles.xml", STYLES)
            sheets.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
    }

    private fun contentTypes() = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        sheets.indices.forEach {
            append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun workbook() = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("<sheets>")
        sheets.forEachIndexed { i, s -> append("""<sheet name="${esc(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
        append("</sheets>")
        append("""<calcPr calcId="191029" fullCalcOnLoad="1"/>""")
        append("</workbook>")
    }

    private fun workbookRels() = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        sheets.indices.forEach {
            append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""")
        }
        append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun sheetXml(s: Sheet) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("<sheetViews><sheetView workbookViewId=\"0\"")
        if (s.rtl) append(" rightToLeft=\"1\"")
        append(">")
        if (s.freezeRows > 0) {
            append("""<pane ySplit="${s.freezeRows}" topLeftCell="A${s.freezeRows + 1}" activePane="bottomLeft" state="frozen"/>""")
        }
        append("</sheetView></sheetViews>")
        val maxCols = s.rows.maxOfOrNull { it.size } ?: 0
        if (maxCols > 0) {
            append("<cols>")
            for (c in 0 until maxCols) {
                val w = s.widths[c] ?: 16.0
                append("""<col min="${c + 1}" max="${c + 1}" width="$w" customWidth="1"/>""")
            }
            append("</cols>")
        }
        append("<sheetData>")
        s.rows.forEachIndexed { ri, row ->
            val r = ri + 1
            append("<row r=\"$r\">")
            row.forEachIndexed { ci, cell ->
                val ref = colName(ci) + r
                when (cell) {
                    is Cell.Text -> append("""<c r="$ref" t="inlineStr" s="${cell.style.id}"><is><t xml:space="preserve">${esc(cell.value)}</t></is></c>""")
                    is Cell.Num -> append("""<c r="$ref" s="${cell.style.id}"><v>${cell.value}</v></c>""")
                    is Cell.Dec -> append("""<c r="$ref" s="${cell.style.id}"><v>${cell.value}</v></c>""")
                    is Cell.Formula -> append("""<c r="$ref" s="${cell.style.id}"><f>${esc(cell.formula)}</f></c>""")
                    Cell.Empty -> Unit
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    companion object {
        fun colName(index: Int): String {
            var i = index
            val sb = StringBuilder()
            do {
                sb.insert(0, 'A' + i % 26)
                i = i / 26 - 1
            } while (i >= 0)
            return sb.toString()
        }

        /** XML-escapes and strips characters that are illegal in XML 1.0. */
        fun esc(s: String): String = buildString(s.length) {
            for (ch in s) when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> append(ch)
                ch < ' ' || ch == '￾' || ch == '￿' -> Unit
                else -> append(ch)
            }
        }

        private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

        private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0;[Red]-#,##0;&quot;-&quot;"/><numFmt numFmtId="165" formatCode="#,##0.000"/></numFmts><fonts count="3"><font><sz val="10"/><name val="Tahoma"/></font><font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Tahoma"/></font><font><b/><sz val="12"/><name val="Tahoma"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF37474F"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="7"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="164" fontId="2" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    }
}
