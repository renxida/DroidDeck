package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinuxRuntimeGpuStandInTest {
    @Test fun theMaliNodeComesFirst() {
        assertEquals(LinuxRuntime.MALI_DEVICE, LinuxRuntime.bridgeStandIn { true })
    }

    @Test fun otherwiseTheFirstRenderNodeThatExists() {
        val present = setOf("/dev/dri/renderD129", "/dev/dri/renderD131")
        assertEquals("/dev/dri/renderD129", LinuxRuntime.bridgeStandIn { it in present })
    }

    @Test fun noneWhenTheDeviceHasNoNode() {
        assertNull(LinuxRuntime.bridgeStandIn { false })
        assertNull(LinuxRuntime.bridgeStandIn { it == "/dev/dri/renderD200" })
    }
}
