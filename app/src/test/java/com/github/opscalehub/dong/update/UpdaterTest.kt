package com.github.opscalehub.dong.update

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdaterTest {
    private val sha = "a".repeat(64)
    private fun json(code: Int, extra: String = "") =
        """{"version":"0.$code.0","versionCode":$code,"minSdk":26,"sizeBytes":7000000,"sha256":"$sha",
           "apk":"https://example.invalid/latest/dong.apk","apkPinned":"https://example.invalid/v$code/dong.apk","unknownField":1$extra}"""

    @Test fun parsesReleaseJsonAndIgnoresUnknownFields() {
        val info = UpdatePolicy.parse(json(3))
        assertEquals(3, info.versionCode)
        assertEquals("https://example.invalid/v3/dong.apk", with(UpdatePolicy) { info.downloadUrl() })
    }

    @Test fun offersOnlyNewerInstallableVerifiableReleases() {
        val info = UpdatePolicy.parse(json(3))
        assertTrue(UpdatePolicy.offer(info, currentCode = 2, sdk = 26, skippedCode = 0, manual = false))
        assertFalse("same version", UpdatePolicy.offer(info, 3, 26, 0, false))
        assertFalse("older", UpdatePolicy.offer(info, 4, 26, 0, false))
        assertFalse("needs newer Android", UpdatePolicy.offer(info.copy(minSdk = 30), 2, 29, 0, false))
        assertFalse("no checksum", UpdatePolicy.offer(info.copy(sha256 = ""), 2, 26, 0, false))
        assertFalse("no download url", UpdatePolicy.offer(info.copy(apk = "", apkPinned = ""), 2, 26, 0, false))
        // An old release.json without versionCode never triggers an update.
        assertFalse(UpdatePolicy.offer(UpdatePolicy.parse("""{"version":"0.2.0"}"""), 2, 26, 0, false))
    }

    @Test fun skippedVersionIsHiddenFromAutoCheckButNotFromManualCheck() {
        val info = UpdatePolicy.parse(json(3))
        assertFalse(UpdatePolicy.offer(info, 2, 26, skippedCode = 3, manual = false))
        assertTrue(UpdatePolicy.offer(info, 2, 26, skippedCode = 3, manual = true))
        assertTrue("a later version shows again", UpdatePolicy.offer(UpdatePolicy.parse(json(4)), 2, 26, skippedCode = 3, manual = false))
    }

    @Test fun autoCheckRunsAtMostOncePerDay() {
        val day = UpdatePolicy.CHECK_INTERVAL_MS
        assertTrue(UpdatePolicy.dueForAutoCheck(lastCheck = 0, now = 10 * day))
        assertFalse(UpdatePolicy.dueForAutoCheck(lastCheck = 10 * day, now = 10 * day + day - 1))
        assertTrue(UpdatePolicy.dueForAutoCheck(lastCheck = 10 * day, now = 11 * day))
        assertTrue("clock moved backwards", UpdatePolicy.dueForAutoCheck(lastCheck = 11 * day, now = 10 * day))
    }

    private fun updater(fetch: (String) -> String, auto: Boolean = true) =
        Updater(ApplicationProvider.getApplicationContext(), currentCode = 2, urls = listOf("https://example.invalid/release.json"), autoCheckAllowed = auto, fetch = fetch)

    private fun settled(u: Updater) = runBlocking { withTimeout(5_000) { u.state.first { it !is UpdateState.Idle && it !is UpdateState.Checking } } }

    @Test fun checkFindsUpdateThenSkipHidesIt() {
        val u = updater({ json(3) })
        u.check(manual = false)
        val s = settled(u)
        assertTrue(s is UpdateState.Available && s.info.versionCode == 3)
        u.skip((s as UpdateState.Available).info)
        assertEquals(UpdateState.Idle, u.state.value)
    }

    @Test fun fallsBackToTheNextAddressWhenTheFirstFails() {
        val asked = mutableListOf<String>()
        val u = Updater(ApplicationProvider.getApplicationContext(), currentCode = 2,
            urls = listOf("https://new.invalid/release.json", "https://old.invalid/release.json"), autoCheckAllowed = true,
            fetch = { asked += it; if (it.startsWith("https://new")) error("404") else json(3) })
        u.check(manual = true)
        assertTrue(settled(u) is UpdateState.Available)
        assertEquals(listOf("https://new.invalid/release.json", "https://old.invalid/release.json"), asked)
    }

    @Test fun manualCheckReportsUpToDateAndNetworkErrors() {
        val ok = updater({ json(2) })
        ok.check(manual = true)
        assertEquals(UpdateState.UpToDate, settled(ok))
        val down = updater({ error("offline") })
        down.check(manual = true)
        assertEquals(UpdateState.Failed(UpdateState.Reason.NETWORK, null), settled(down))
    }

    @Test fun autoCheckIsSilentOnErrorsAndRespectsTheSwitch() {
        var calls = 0
        val u = updater({ calls++; error("offline") })
        u.autoCheck = false
        u.onAppStart(now = 1_000_000_000_000)
        assertEquals(0, calls)
        u.autoCheck = true
        u.onAppStart(now = 1_000_000_000_000)
        runBlocking { withTimeout(5_000) { while (calls == 0) kotlinx.coroutines.delay(10) } }
        Thread.sleep(100)
        assertEquals(UpdateState.Idle, u.state.value)
        val never = updater({ calls++; json(3) }, auto = false)
        never.onAppStart(now = 1_000_000_000_000)
        assertEquals(UpdateState.Idle, never.state.value)
    }
}
