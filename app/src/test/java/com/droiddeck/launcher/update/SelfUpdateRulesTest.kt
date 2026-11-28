package com.droiddeck.launcher.update

import com.droiddeck.launcher.update.SelfUpdateRules.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdateRulesTest {
    private val installed = Build(19, "1111111aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 1_000_000L)

    @Test fun aHigherVersionCodeIsNewerWhateverTheCommit() {
        assertNull(SelfUpdateRules.notNewerReason(Build(20, "", 0L), installed))
    }

    @Test fun aLowerVersionCodeIsNeverNewer() {
        assertNotNull(SelfUpdateRules.notNewerReason(Build(18, "2222222bbbb", 9_000_000L), installed))
    }

    @Test fun atTheSameVersionCodeALaterDifferentCommitIsNewer() {
        assertNull(SelfUpdateRules.notNewerReason(Build(19, "2222222bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 2_000_000L), installed))
    }

    @Test fun atTheSameVersionCodeTheSameCommitOrAnOlderOneIsNot() {
        assertTrue(SelfUpdateRules.notNewerReason(Build(19, "1111111AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", 2_000_000L), installed)!!.startsWith("same commit"))
        assertTrue(SelfUpdateRules.notNewerReason(Build(19, "1111111", 2_000_000L), installed)!!.startsWith("same commit"))
        assertNotNull(SelfUpdateRules.notNewerReason(Build(19, "2222222", 500_000L), installed))
        assertNotNull(SelfUpdateRules.notNewerReason(Build(19, "2222222", 1_000_000L), installed))
    }

    @Test fun atTheSameVersionCodeAMissingCommitOrTimeRefuses() {
        assertEquals("the APK names no commit", SelfUpdateRules.notNewerReason(Build(19, "", 2_000_000L), installed))
        assertEquals("the APK names no commit time", SelfUpdateRules.notNewerReason(Build(19, "2222222", 0L), installed))
        assertEquals("the installed build has no commit time",
            SelfUpdateRules.notNewerReason(Build(19, "2222222", 2_000_000L), installed.copy(commitTimeMs = 0L)))
    }

    @Test fun manifestValuesParseOnlyWithTheirShape() {
        assertEquals("abcdef0123456", SelfUpdateRules.parseCommit("sha:abcdef0123456"))
        assertEquals("", SelfUpdateRules.parseCommit("sha:"))
        assertEquals("", SelfUpdateRules.parseCommit("sha:not-a-commit"))
        assertEquals("", SelfUpdateRules.parseCommit(null))
        assertEquals(1_763_000_000_000L, SelfUpdateRules.parseCommitTimeMs("t:1763000000"))
        assertEquals(0L, SelfUpdateRules.parseCommitTimeMs("t:0"))
        assertEquals(0L, SelfUpdateRules.parseCommitTimeMs("t:x"))
        assertEquals(0L, SelfUpdateRules.parseCommitTimeMs(null))
    }

    @Test fun certificatesMustMatchExactlyAsSets() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        assertTrue(SelfUpdateRules.sameCertificates(listOf(a), listOf(byteArrayOf(1, 2, 3))))
        assertTrue(SelfUpdateRules.sameCertificates(listOf(a, b), listOf(b.copyOf(), a.copyOf())))
        assertFalse(SelfUpdateRules.sameCertificates(listOf(a), listOf(b)))
        assertFalse(SelfUpdateRules.sameCertificates(listOf(a), listOf(byteArrayOf(1, 2, 3, 0))))
        assertFalse(SelfUpdateRules.sameCertificates(listOf(a, b), listOf(a)))
        assertFalse(SelfUpdateRules.sameCertificates(listOf(a, a), listOf(a, b)))
        assertFalse(SelfUpdateRules.sameCertificates(emptyList(), emptyList()))
        assertFalse(SelfUpdateRules.sameCertificates(listOf(a), emptyList()))
    }

    @Test fun checksWaitForNoSessionAndThirtyMinutes() {
        val every = SelfUpdateRules.CHECK_EVERY_MS
        assertTrue(SelfUpdateRules.checkDue(nowMs = 10_000L, lastCheckMs = 0L, sessionActive = false))
        assertFalse(SelfUpdateRules.checkDue(nowMs = 10_000L, lastCheckMs = 0L, sessionActive = true))
        assertFalse(SelfUpdateRules.checkDue(nowMs = 5_000L + every - 1, lastCheckMs = 5_000L, sessionActive = false))
        assertTrue(SelfUpdateRules.checkDue(nowMs = 5_000L + every, lastCheckMs = 5_000L, sessionActive = false))
        assertTrue("a clock set back does not stop checks", SelfUpdateRules.checkDue(nowMs = 1_000L, lastCheckMs = 5_000L, sessionActive = false))
        assertTrue("the remote command ignores the throttle", SelfUpdateRules.checkDue(nowMs = 5_001L, lastCheckMs = 5_000L, sessionActive = true, force = true))
    }

    @Test fun anInstallNeverStartsDuringASession() {
        assertFalse(SelfUpdateRules.mayStartInstall(sessionActive = true, frontResumed = true, staged = true, declined = false))
        assertFalse(SelfUpdateRules.mayStartInstall(sessionActive = true, frontResumed = true, staged = true, declined = false, byPerson = true))
        assertFalse(SelfUpdateRules.mayPrompt(sessionActive = true, frontResumed = true))
    }

    @Test fun anInstallStartsOnlyOnTheResumedFrontScreenWithAStagedBuild() {
        assertTrue(SelfUpdateRules.mayStartInstall(sessionActive = false, frontResumed = true, staged = true, declined = false))
        assertFalse(SelfUpdateRules.mayStartInstall(sessionActive = false, frontResumed = false, staged = true, declined = false))
        assertFalse(SelfUpdateRules.mayStartInstall(sessionActive = false, frontResumed = true, staged = false, declined = false))
        assertTrue(SelfUpdateRules.mayPrompt(sessionActive = false, frontResumed = true))
        assertFalse(SelfUpdateRules.mayPrompt(sessionActive = false, frontResumed = false))
    }

    @Test fun aCancelledBuildIsOfferedAgainOnlyByTheInstallButton() {
        assertFalse(SelfUpdateRules.mayStartInstall(sessionActive = false, frontResumed = true, staged = true, declined = true))
        assertTrue(SelfUpdateRules.mayStartInstall(sessionActive = false, frontResumed = true, staged = true, declined = true, byPerson = true))
    }

    @Test fun anInstallReChecksTheReleaseWhenTheLastCheckIsStale() {
        val now = 10_000_000L
        assertTrue(SelfUpdateRules.recheckBeforeInstall(now, 0L))
        assertTrue(SelfUpdateRules.recheckBeforeInstall(now, now - SelfUpdateRules.FRESH_FOR_INSTALL_MS))
        assertFalse(SelfUpdateRules.recheckBeforeInstall(now, now - 30_000L))
        assertTrue(SelfUpdateRules.recheckBeforeInstall(now, now + 60_000L))
    }

    @Test fun noCheckStartsWhileAnInstallIsUnderWay() {
        assertFalse(SelfUpdateRules.mayStartCheck(installing = true, alreadyChecking = false))
        assertFalse(SelfUpdateRules.mayStartCheck(installing = false, alreadyChecking = true))
        assertTrue(SelfUpdateRules.mayStartCheck(installing = false, alreadyChecking = false))
    }
}
