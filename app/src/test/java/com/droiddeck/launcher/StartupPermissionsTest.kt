package com.droiddeck.launcher

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPermissionsTest {
    @Test fun aReturnVisitWithStorageGrantedAsksNothing() {
        assertEquals(emptyList<String>(), StartupPermissions.wanted(storageGranted = true, micEnabled = false, micAsked = false, micGranted = false))
        assertEquals(emptyList<String>(), StartupPermissions.wanted(storageGranted = true, micEnabled = true, micAsked = true, micGranted = false))
    }

    @Test fun storageIsAskedOnlyWhileNotGranted() {
        assertEquals(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            StartupPermissions.wanted(storageGranted = false, micEnabled = false, micAsked = false, micGranted = false))
    }

    @Test fun theMicrophoneIsAskedOnceAndOnlyWhenEnabled() {
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO),
            StartupPermissions.wanted(storageGranted = true, micEnabled = true, micAsked = false, micGranted = false))
        assertEquals(emptyList<String>(), StartupPermissions.wanted(storageGranted = true, micEnabled = true, micAsked = false, micGranted = true))
    }
}
