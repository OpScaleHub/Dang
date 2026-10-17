package com.github.opscalehub.dong.domain.period

import kotlinx.serialization.Serializable

@Serializable
enum class Severity { ERROR, WARNING, INFO }

/** Structured, locale-free validation findings. The UI renders them in Persian. */
@Serializable
enum class IssueCode(val severity: Severity) {
    // allocation
    NO_ELIGIBLE_UNITS(Severity.ERROR),
    ZERO_BASIS(Severity.ERROR),
    MISSING_AREA(Severity.ERROR),
    MISSING_OCCUPANCY(Severity.ERROR),
    MISSING_CONSUMPTION(Severity.ERROR),
    EXPLICIT_SUM_MISMATCH(Severity.ERROR),
    PORTIONS_EXCEED_AMOUNT(Severity.ERROR),
    PORTIONS_INCOMPLETE(Severity.ERROR),
    INVALID_PERCENT(Severity.ERROR),
    NEGATIVE_AMOUNT(Severity.ERROR),
    UNKNOWN_UNIT_IN_POLICY(Severity.ERROR),
    MAIN_METER_REQUIRED(Severity.ERROR),
    UNIT_METERS_EXCEED_MAIN(Severity.WARNING),
    RATE_BASIS_UNSUPPORTED(Severity.ERROR),
    PARKING_SPACES_EXCLUDED(Severity.INFO),
    UNIT_ZERO_WEIGHT(Severity.INFO),
    FUND_BORNE_PORTION(Severity.INFO),
    // water
    MISSING_READING(Severity.ERROR),
    MISSING_OPENING_READING(Severity.ERROR),
    MISSING_CLOSING_READING(Severity.ERROR),
    NEGATIVE_CONSUMPTION(Severity.ERROR),
    ROLLOVER_WITHOUT_LIMIT(Severity.ERROR),
    READING_AFTER_FINAL(Severity.ERROR),
    ROLLOVER_NOT_WRAPPED(Severity.WARNING),
    UNALLOCATED_WATER(Severity.INFO),
    NO_MAIN_METER(Severity.WARNING),
    UNIT_WITHOUT_METER(Severity.WARNING),
    DOUBLE_WATER_CHARGE(Severity.ERROR),
    NO_TARIFF(Severity.ERROR),
    INVALID_TARIFF(Severity.ERROR),
    // period
    EMPTY_PERIOD(Severity.WARNING),
    INVALID_PERIOD_DATES(Severity.ERROR),
    ;
}

@Serializable
data class Issue(
    val code: IssueCode,
    /** Expense / charge item / meter id the issue belongs to, if any. */
    val sourceId: String? = null,
    val unitId: String? = null,
    val meterId: String? = null,
    val args: Map<String, String> = emptyMap(),
) {
    val severity: Severity get() = code.severity
    val blocking: Boolean get() = severity == Severity.ERROR
}
