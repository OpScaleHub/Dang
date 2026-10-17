package com.github.opscalehub.dong.share

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.opscalehub.dong.i18n.MoneyFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BillShareTest {
    @Test
    fun `bill image renders at share width`() {
        val bill = Bill("ساختمان نمونه", "۱-۱", "مهر ۱۴۰۵", listOf("آب" to 1_959_000L, "نظافت" to 692_000L), 0, 3_000_000, 1_902_000, "۱۴۰۵/۰۸/۰۵")
        val bmp = BillShare.render(ApplicationProvider.getApplicationContext(), bill, MoneyFormat(0, 10, "تومان"))
        assertEquals(1080, bmp.width)
        assertTrue(bmp.height in 500..3000)
    }
}
