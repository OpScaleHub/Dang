package com.github.opscalehub.dong.i18n

import com.github.opscalehub.dong.data.OpError
import com.github.opscalehub.dong.domain.allocation.Basis
import com.github.opscalehub.dong.domain.allocation.BasisKind
import com.github.opscalehub.dong.domain.allocation.CommonWater
import com.github.opscalehub.dong.domain.allocation.ComponentPart
import com.github.opscalehub.dong.domain.allocation.QuantityKind
import com.github.opscalehub.dong.domain.allocation.SimpleBasis
import com.github.opscalehub.dong.domain.allocation.SpaceFilter
import com.github.opscalehub.dong.domain.ledger.AccountKind
import com.github.opscalehub.dong.domain.ledger.EntryType
import com.github.opscalehub.dong.domain.model.CalendarSystem
import com.github.opscalehub.dong.domain.model.ChargeMode
import com.github.opscalehub.dong.domain.model.MeterRole
import com.github.opscalehub.dong.domain.model.OccupancyBasis
import com.github.opscalehub.dong.domain.model.ParkingRelation
import com.github.opscalehub.dong.domain.model.ReadingKind
import com.github.opscalehub.dong.domain.period.Issue
import com.github.opscalehub.dong.domain.period.IssueCode
import com.github.opscalehub.dong.domain.period.Severity

/**
 * Persian labels for every domain enum and issue code. Kept in one place (and
 * exhaustive `when`s) so the compiler flags any untranslated value. Adding a
 * language later means providing another implementation of these functions.
 */
object Fa {
    fun entryType(t: EntryType) = when (t) {
        EntryType.OPENING -> "مانده افتتاحیه"
        EntryType.EXPENSE -> "ثبت هزینه"
        EntryType.SUPPLIER_PAYMENT -> "پرداخت به تامین‌کننده"
        EntryType.CHARGE -> "شارژ دوره"
        EntryType.RESIDENT_PAYMENT -> "دریافت از واحد"
        EntryType.REFUND -> "استرداد وجه"
        EntryType.ADJUSTMENT -> "تعدیل"
        EntryType.FUND_TRANSFER -> "انتقال بین صندوق‌ها"
        EntryType.CASH_TRANSFER -> "انتقال وجه نقد"
        EntryType.PERIOD_CORRECTION -> "اصلاحیه دوره"
        EntryType.REVERSAL -> "برگشت سند"
    }

    fun account(a: AccountKind) = when (a) {
        AccountKind.CASH -> "نقد و بانک"
        AccountKind.RECEIVABLE -> "مطالبات از واحدها"
        AccountKind.PAYABLE -> "بدهی به تامین‌کنندگان"
        AccountKind.FUND_EQUITY -> "سرمایه صندوق"
        AccountKind.INCOME -> "شارژ (درآمد)"
        AccountKind.EXPENSE -> "هزینه"
    }

    fun basisKind(b: BasisKind) = when (b) {
        BasisKind.EQUAL -> "مساوی بین واحدها"
        BasisKind.OCCUPANTS -> "بر اساس تعداد ساکنان"
        BasisKind.AREA -> "بر اساس متراژ"
        BasisKind.CONSUMPTION -> "بر اساس مصرف کنتور"
        BasisKind.WEIGHTED -> "وزنی"
        BasisKind.EXPLICIT -> "مبلغ مشخص برای هر واحد"
        BasisKind.PARKING -> "بر اساس جای پارک"
        BasisKind.WATER_TARIFF -> "تعرفه آب"
    }

    fun basis(b: Basis): String = when (b) {
        Basis.Equal -> basisKind(BasisKind.EQUAL)
        Basis.Occupants -> basisKind(BasisKind.OCCUPANTS)
        Basis.Area -> basisKind(BasisKind.AREA)
        is Basis.Consumption -> basisKind(BasisKind.CONSUMPTION) + " — " + commonWater(b.common)
        is Basis.Weighted -> basisKind(BasisKind.WEIGHTED)
        is Basis.Explicit -> basisKind(BasisKind.EXPLICIT)
        is Basis.Parking -> basisKind(BasisKind.PARKING) + " (" + spaceFilter(b.filter) + ")"
    }

    fun commonWater(c: CommonWater) = when (c) {
        CommonWater.Proportional -> "اختلاف کنتور اصلی به نسبت مصرف"
        is CommonWater.Separate -> "سهم مشترک " + simpleBasis(c.basis)
        CommonWater.FundBorne -> "سهم مشترک از محل صندوق"
    }

    fun simpleBasis(b: SimpleBasis) = when (b) {
        SimpleBasis.EQUAL -> "مساوی"
        SimpleBasis.OCCUPANTS -> "بر اساس ساکنان"
        SimpleBasis.AREA -> "بر اساس متراژ"
    }

    fun spaceFilter(f: SpaceFilter) = when (f) {
        SpaceFilter.ALL_ASSIGNED -> "همه جاهای تخصیص‌یافته"
        SpaceFilter.IN_USE -> "فقط جاهای در استفاده"
        SpaceFilter.SELECTED -> "جاهای انتخاب‌شده"
    }

    fun part(p: ComponentPart) = when (p) {
        ComponentPart.WHOLE -> ""
        ComponentPart.METERED -> "سهم مصرف کنتورها"
        ComponentPart.COMMON -> "سهم مشترک (تخصیص‌نیافته)"
    }

    fun quantity(kind: QuantityKind, q: Long): String = when (kind) {
        QuantityKind.UNIT -> "۱ واحد"
        QuantityKind.OCCUPANTS -> "${Fmt.number(q)} نفر"
        QuantityKind.OCCUPANT_DAYS -> "${Fmt.number(q)} نفر-روز"
        QuantityKind.AREA_CENTI_M2 -> "${Fmt.digits(if (q % 100 == 0L) Fmt.grouped(q / 100) else "%.2f".format(q / 100.0))} متر مربع"
        QuantityKind.LITERS -> "${Fmt.cubicMeters(q)} مترمکعب"
        QuantityKind.WEIGHT -> "وزن ${Fmt.number(q)}"
        QuantityKind.EXPLICIT_AMOUNT -> "مبلغ مشخص"
        QuantityKind.PARKING_SPACES -> "${Fmt.number(q)} جای پارک"
    }

    fun occupancyBasis(b: OccupancyBasis) = when (b) {
        OccupancyBasis.DAY_WEIGHTED -> "نفر-روز (تغییرات میان دوره لحاظ می‌شود)"
        OccupancyBasis.PERIOD_START -> "تعداد ساکنان در روز اول دوره"
        OccupancyBasis.PERIOD_END -> "تعداد ساکنان در روز آخر دوره"
    }

    fun calendar(c: CalendarSystem) = when (c) {
        CalendarSystem.JALALI -> "هجری شمسی"
        CalendarSystem.GREGORIAN -> "میلادی"
    }

    fun relation(r: ParkingRelation) = when (r) {
        ParkingRelation.OWNED -> "مالکیت"
        ParkingRelation.ASSIGNED -> "تخصیص"
        ParkingRelation.RENTED -> "اجاره"
    }

    fun meterRole(r: MeterRole) = when (r) {
        MeterRole.MAIN -> "کنتور اصلی"
        MeterRole.UNIT -> "کنتور واحد"
        MeterRole.COMMON -> "کنتور مشاعات"
    }

    fun readingKind(k: ReadingKind) = when (k) {
        ReadingKind.REGULAR -> "عادی"
        ReadingKind.INITIAL -> "قرائت اولیه (نصب/صفر شدن)"
        ReadingKind.FINAL -> "قرائت نهایی (برداشتن)"
        ReadingKind.ROLLOVER -> "گردش کنتور (عبور از حداکثر)"
    }

    fun chargeMode(m: ChargeMode) = when (m) {
        ChargeMode.TOTAL -> "مبلغ کل، تسهیم بین واحدها"
        ChargeMode.RATE -> "نرخ ثابت به ازای هر واحد مبنا"
        ChargeMode.WATER_TARIFF -> "تعرفه آب بر اساس مصرف"
    }

    fun severity(s: Severity) = when (s) {
        Severity.ERROR -> "خطا"
        Severity.WARNING -> "هشدار"
        Severity.INFO -> "توضیح"
    }

    /** Resolves ids to labels in issue messages. */
    interface Names {
        fun unit(id: String?): String
        fun meter(id: String?): String
        fun source(id: String?): String
    }

    fun issue(i: Issue, n: Names): String {
        val a = i.args
        val src = i.sourceId?.let { "«${n.source(it)}»: " } ?: ""
        return src + when (i.code) {
            IssueCode.NO_ELIGIBLE_UNITS -> "هیچ واحد مشمولی برای تسهیم وجود ندارد."
            IssueCode.ZERO_BASIS -> "مجموع مبنای تسهیم صفر است (مثلاً هیچ ساکن، مصرف یا وزنی ثبت نشده)."
            IssueCode.MISSING_AREA -> "متراژ واحد ${n.unit(i.unitId)} ثبت نشده است."
            IssueCode.MISSING_OCCUPANCY -> "تعداد ساکنان واحد ${n.unit(i.unitId)} ثبت نشده است."
            IssueCode.MISSING_CONSUMPTION -> "مصرف آب واحد ${n.unit(i.unitId)} در دسترس نیست (کنتور یا قرائت ناقص)."
            IssueCode.EXPLICIT_SUM_MISMATCH -> "جمع مبالغ مشخص‌شده (${Fmt.money(a["actual"]?.toLong() ?: 0)}) با مبلغ (${Fmt.money(a["expected"]?.toLong() ?: 0)}) برابر نیست."
            IssueCode.PORTIONS_EXCEED_AMOUNT -> "مجموع بخش‌های ثابت/درصدی از مبلغ کل بیشتر است."
            IssueCode.PORTIONS_INCOMPLETE -> "بخش‌های تسهیم کل مبلغ را پوشش نمی‌دهند (یک بخش «باقیمانده» لازم است)."
            IssueCode.INVALID_PERCENT -> "درصد نامعتبر است."
            IssueCode.NEGATIVE_AMOUNT -> "مبلغ یا وزن منفی مجاز نیست."
            IssueCode.UNKNOWN_UNIT_IN_POLICY -> "سیاست تسهیم به واحدی اشاره دارد که وجود ندارد یا غیرفعال است (${n.unit(i.unitId)})."
            IssueCode.MAIN_METER_REQUIRED -> "برای جدا کردن سهم مشترک آب، قرائت کامل کنتور اصلی لازم است."
            IssueCode.UNIT_METERS_EXCEED_MAIN -> "مجموع مصرف کنتورهای واحدها از کنتور اصلی بیشتر است (${Fmt.cubicMeters(a["liters"]?.toLong() ?: 0)} مترمکعب)."
            IssueCode.RATE_BASIS_UNSUPPORTED -> "برای شارژ نرخ ثابت فقط مبنای مساوی، ساکنان، متراژ یا پارکینگ مجاز است."
            IssueCode.PARKING_SPACES_EXCLUDED -> "${Fmt.digits(a["count"] ?: "0")} جای پارک مشمول این هزینه نشد (بدون تخصیص، بلااستفاده یا خارج از فیلتر)."
            IssueCode.UNIT_ZERO_WEIGHT -> "سهم واحد ${n.unit(i.unitId)} صفر است (مبنا صفر)."
            IssueCode.FUND_BORNE_PORTION -> "مبلغ ${Fmt.money(a["amount"]?.toLong() ?: 0)} از محل صندوق پرداخت می‌شود و به واحدها شارژ نمی‌شود."
            IssueCode.MISSING_READING -> "برای ${n.meter(i.meterId)} هیچ قرائتی ثبت نشده است."
            IssueCode.MISSING_OPENING_READING -> "قرائت ابتدای دوره برای ${n.meter(i.meterId)} وجود ندارد (قرائت قبل از دوره یا «قرائت اولیه» لازم است)."
            IssueCode.MISSING_CLOSING_READING -> "قرائت پایان دوره برای ${n.meter(i.meterId)} ثبت نشده است."
            IssueCode.NEGATIVE_CONSUMPTION -> "قرائت ${n.meter(i.meterId)} از قرائت قبلی کمتر است (${Fmt.cubicMeters(a["current"]?.toLong() ?: 0)} < ${Fmt.cubicMeters(a["previous"]?.toLong() ?: 0)}). اگر کنتور تعویض یا صفر شده یا گردش کرده، نوع قرائت را مشخص کنید."
            IssueCode.ROLLOVER_WITHOUT_LIMIT -> "برای ${n.meter(i.meterId)} گردش کنتور ثبت شده ولی حداکثر شمارنده تعریف نشده است."
            IssueCode.READING_AFTER_FINAL -> "پس از قرائت نهایی ${n.meter(i.meterId)} قرائت دیگری ثبت شده است."
            IssueCode.ROLLOVER_NOT_WRAPPED -> "قرائت «گردش کنتور» ${n.meter(i.meterId)} در واقع کمتر از قبلی نیست."
            IssueCode.UNALLOCATED_WATER -> "${Fmt.cubicMeters(a["liters"]?.toLong() ?: 0)} مترمکعب مصرف کنتور اصلی توسط کنتورهای واحدها توضیح داده نمی‌شود."
            IssueCode.NO_MAIN_METER -> "کنتور اصلی تعریف نشده؛ تطبیق مصرف ممکن نیست."
            IssueCode.UNIT_WITHOUT_METER -> "واحد ${n.unit(i.unitId)} کنتور ندارد."
            IssueCode.DOUBLE_WATER_CHARGE -> "این قبض آب به واحدها تسهیم می‌شود در حالی که شارژ تعرفه آب هم فعال است؛ این باعث دوبار دریافت هزینه آب می‌شود. قبض را «از محل صندوق» ثبت کنید."
            IssueCode.NO_TARIFF -> "هیچ تعرفه آبی برای این دوره تعریف نشده است."
            IssueCode.INVALID_TARIFF -> "تعرفه آب نامعتبر است."
            IssueCode.EMPTY_PERIOD -> "این دوره هیچ هزینه یا قلم شارژی ندارد."
            IssueCode.INVALID_PERIOD_DATES -> "تاریخ پایان دوره قبل از تاریخ شروع است."
        }
    }

    /** Audit action codes are stable machine identifiers; this maps them for display. */
    fun auditAction(code: String): String = when {
        code.startsWith("POST_") -> runCatching { "ثبت سند: " + entryType(EntryType.valueOf(code.removePrefix("POST_"))) }.getOrDefault(code)
        code.startsWith("RESTORE_") -> "بازیابی از پشتیبان"
        else -> mapOf(
            "CREATE_BUILDING" to "ایجاد ساختمان", "UPDATE_SETTINGS" to "تغییر تنظیمات", "CREATE_UNIT" to "ایجاد واحد", "UPDATE_UNIT" to "ویرایش واحد",
            "SET_OCCUPANCY" to "تغییر تعداد ساکنان", "DELETE_OCCUPANCY" to "حذف سابقه سکونت", "SAVE_PARKING_SPACE" to "ثبت جای پارک",
            "ASSIGN_PARKING" to "تخصیص پارکینگ", "SAVE_METER" to "ثبت کنتور", "ADD_READING" to "ثبت قرائت", "CORRECT_READING" to "اصلاح قرائت",
            "DELETE_READING" to "حذف قرائت", "REPLACE_METER" to "تعویض کنتور", "SAVE_TARIFF" to "ثبت تعرفه", "SAVE_CATEGORY" to "ثبت دسته هزینه",
            "UPDATE_EXPENSE" to "ویرایش هزینه", "VOID_EXPENSE" to "ابطال هزینه", "SAVE_CHARGE_ITEM" to "ثبت قلم شارژ", "CREATE_PERIOD" to "ایجاد دوره",
            "DELETE_PERIOD" to "حذف دوره", "FINALIZE_PERIOD" to "قطعی کردن دوره", "CORRECT_PERIOD" to "اصلاحیه دوره", "ADD_FUND" to "ایجاد صندوق",
            "ADD_CASH_ACCOUNT" to "ایجاد حساب نقد", "DEMO_DATA" to "بارگذاری داده نمایشی",
        )[code] ?: code
    }

    fun error(e: OpError) = when (e) {
        OpError.NOT_FOUND -> "مورد پیدا نشد."
        OpError.INVALID_AMOUNT -> "مبلغ نامعتبر است."
        OpError.INVALID_INPUT -> "اطلاعات واردشده نامعتبر یا ناقص است."
        OpError.REASON_REQUIRED -> "ذکر دلیل الزامی است."
        OpError.PERIOD_FINALIZED -> "این دوره قطعی شده و قابل تغییر نیست. برای اصلاح از «اصلاحیه» یا «تعدیل» استفاده کنید."
        OpError.PERIOD_OVERLAP -> "این دوره با دوره دیگری همپوشانی دارد."
        OpError.PERIOD_HAS_BLOCKING_ISSUES -> "دوره خطای حل‌نشده دارد و قطعی نمی‌شود."
        OpError.PERIOD_NOT_FINALIZED -> "این دوره هنوز قطعی نشده است."
        OpError.NOTHING_TO_CORRECT -> "محاسبه مجدد با نتیجه فعلی تفاوتی ندارد."
        OpError.LOCKED_BY_FINALIZED_PERIOD -> "این تاریخ در یک دوره قطعی‌شده است. از «اصلاح قرائت» استفاده کنید."
        OpError.ALREADY_REVERSED -> "این سند قبلاً برگشت خورده است."
        OpError.CANNOT_REVERSE -> "این نوع سند مستقیماً قابل برگشت نیست."
        OpError.HAS_PAYMENTS -> "ابتدا پرداخت‌های مرتبط را برگشت بزنید."
        OpError.OPENING_EXISTS -> "مانده افتتاحیه قبلاً ثبت شده است."
        OpError.NOT_BALANCED -> "سند تراز نیست."
    }
}
