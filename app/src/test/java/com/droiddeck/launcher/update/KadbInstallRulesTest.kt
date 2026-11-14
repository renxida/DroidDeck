package com.droiddeck.launcher.update

import com.droiddeck.launcher.update.KadbInstallRules.Marker
import com.droiddeck.launcher.update.KadbInstallRules.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KadbInstallRulesTest {
    /** Switched on, paired, charging with the screen off, cool, nothing running. */
    private val ready = State(enabled = true, paired = true, wirelessDebugging = true, sessionBusy = false,
        charging = true, interactive = false, thermal = 0, batteryC = 30f)

    @Test fun anIdleChargingPairedPhoneInstalls() {
        assertNull(KadbInstallRules.skip(ready))
        assertNull(KadbInstallRules.skip(ready.copy(reachable = true)))
    }

    @Test fun offByDefaultMeansThePrompt() {
        assertEquals("switched off", KadbInstallRules.skip(ready.copy(enabled = false)))
    }

    @Test fun theSwitchComesBeforeAnythingElse() {
        val everythingWrong = State(enabled = false, paired = false, wirelessDebugging = false, sessionBusy = true,
            charging = false, interactive = true, thermal = 6, batteryC = 50f, reachable = false)
        assertEquals("switched off", KadbInstallRules.skip(everythingWrong))
    }

    @Test fun needsASavedPairing() {
        assertEquals("no Wireless debugging pairing", KadbInstallRules.skip(ready.copy(paired = false)))
    }

    @Test fun neverDuringASession() {
        assertEquals("a session is running", KadbInstallRules.skip(ready.copy(sessionBusy = true)))
    }

    @Test fun onlyOnTheChargerWithTheScreenOff() {
        assertEquals("not charging", KadbInstallRules.skip(ready.copy(charging = false)))
        assertEquals("screen is on", KadbInstallRules.skip(ready.copy(interactive = true)))
    }

    @Test fun aWarmPhoneWaits() {
        assertNull(KadbInstallRules.skip(ready.copy(thermal = 1)))
        assertTrue(KadbInstallRules.skip(ready.copy(thermal = 2))!!.startsWith("phone is warm"))
        assertTrue(KadbInstallRules.skip(ready.copy(thermal = 4))!!.startsWith("phone is warm"))
        assertEquals("battery at 38 C", KadbInstallRules.skip(ready.copy(batteryC = 38f)))
        assertNull(KadbInstallRules.skip(ready.copy(batteryC = Float.NaN)))
    }

    @Test fun wirelessDebuggingMustBeOnAndAnswer() {
        assertEquals("Wireless debugging is off", KadbInstallRules.skip(ready.copy(wirelessDebugging = false)))
        assertNull(KadbInstallRules.skip(ready.copy(wirelessDebugging = null)))
        assertEquals("Wireless debugging not reachable", KadbInstallRules.skip(ready.copy(reachable = false)))
    }

    @Test fun theInstallCommandDeletesTheApkWhateverHappens() {
        val command = KadbInstallRules.installCommand()
        assertTrue(command.contains("pm install -r -t /data/local/tmp/kestrel-update.apk"))
        assertTrue(command.indexOf("rm -f /data/local/tmp/kestrel-update.apk") > command.indexOf("pm install"))
        assertTrue(command.startsWith("out=\$(pm install"))
    }

    @Test fun onlyAnExactSuccessLineCounts() {
        assertTrue(KadbInstallRules.installSucceeded("Performing Streamed Install\nSuccess\n"))
        assertTrue(KadbInstallRules.installSucceeded("Success"))
        assertFalse(KadbInstallRules.installSucceeded("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: not Success]"))
        assertFalse(KadbInstallRules.installSucceeded(""))
        assertEquals("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]",
            KadbInstallRules.failureLine("Performing Streamed Install\nFailure [INSTALL_FAILED_VERSION_DOWNGRADE]\n"))
        assertEquals("pm printed nothing", KadbInstallRules.failureLine("\n"))
    }

    @Test fun theMarkerRoundTrips() {
        val marker = Marker(19, "1111111aaaa", 1_700_000_000_000L)
        assertEquals(marker, KadbInstallRules.parseMarker(KadbInstallRules.formatMarker(marker)))
        assertEquals(Marker(19, "", 5L), KadbInstallRules.parseMarker(KadbInstallRules.formatMarker(Marker(19, "", 5L))))
        assertNull(KadbInstallRules.parseMarker(null))
        assertNull(KadbInstallRules.parseMarker("garbage"))
        assertNull(KadbInstallRules.parseMarker("x\nabc\n1\n"))
    }

    @Test fun aChangedBuildMeansTheInstallWentThrough() {
        val marker = Marker(19, "1111111aaaa", 1L)
        assertTrue(KadbInstallRules.installedSince(marker, SelfUpdateRules.Build(20, "1111111aaaa", 0L)))
        assertTrue(KadbInstallRules.installedSince(marker, SelfUpdateRules.Build(19, "2222222bbbb", 0L)))
        assertFalse(KadbInstallRules.installedSince(marker, SelfUpdateRules.Build(19, "1111111AAAA", 0L)))
        assertFalse(KadbInstallRules.installedSince(marker, SelfUpdateRules.Build(19, "1111111", 0L)))
        assertFalse(KadbInstallRules.installedSince(marker, SelfUpdateRules.Build(19, "", 0L)))
    }
}
