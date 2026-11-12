package com.droiddeck.launcher

import android.Manifest

internal object StartupPermissions {
    fun wanted(storageGranted: Boolean, micEnabled: Boolean, micAsked: Boolean, micGranted: Boolean): List<String> = buildList {
        if (!storageGranted) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (micEnabled && !micAsked && !micGranted) add(Manifest.permission.RECORD_AUDIO)
    }
}
