package com.github.opscalehub.dong.i18n

import com.github.opscalehub.dong.data.db.BuildingEntity

/** How a building's amounts are shown and typed. Storage is always exact minor units. */
data class MoneyFormat(val minorDigits: Int = 0, val divisor: Long = 1, val unitName: String = "ریال") {
    fun format(minor: Long): String = Fmt.moneyIn(minor, minorDigits, divisor)
    fun withUnit(minor: Long): String = "${format(minor)} $unitName"
    fun parse(text: String): Long? = Fmt.parseMoneyIn(text, minorDigits, divisor)

    companion object {
        fun of(b: BuildingEntity) = MoneyFormat(b.minorDigits, b.displayDivisor, b.displayCurrencyName.ifBlank { b.currencyName })
    }
}
