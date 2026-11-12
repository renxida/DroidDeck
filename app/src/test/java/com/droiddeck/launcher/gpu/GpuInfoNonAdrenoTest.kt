package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.gpu.GpuInfo.Family
import com.droiddeck.launcher.gpu.GpuInfo.Support
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuInfoNonAdrenoTest {
    private fun gpu(name: String, family: Family, model: Int = 0) =
        GpuInfo(name = name, model = model, family = family, soc = "", oneUi8Gen2 = false)

    @Test fun powerVrAndXclipseAreExperimentalWithTheirOwnText() {
        val xclipse = gpu("Samsung Xclipse 940", Family.POWERVR)
        assertEquals(Support.UNTESTED, xclipse.support)
        assertEquals("Experimental: RADV or the system Xclipse driver", xclipse.supportText)
        assertEquals("Samsung Xclipse", Family.POWERVR.label(xclipse.name))
        val powerVr = gpu("PowerVR B-Series BXM-8-256", Family.POWERVR)
        assertEquals("System PowerVR driver: compatibility varies by device", powerVr.supportText)
        assertEquals("PowerVR", Family.POWERVR.label(powerVr.name))
    }

    @Test fun maliFamiliesAreExperimental() {
        assertEquals("Experimental: Mali through the phone's own driver (Venus)", gpu("Mali-T880", Family.MALI_OTHER).supportText)
        for (family in listOf(Family.MALI_JM, Family.MALI_G610_G710, Family.MALI_G615, Family.MALI_G720, Family.MALI_LATEST)) {
            val mali = gpu("Mali", family)
            assertEquals(family.name, Support.UNTESTED, mali.support)
            assertEquals(family.name, "Experimental: open Mali driver (test builds)", mali.supportText)
            assertTrue(family.name, family.mali)
        }
        assertFalse(Family.POWERVR.mali)
        assertFalse(Family.NOT_ADRENO.mali)
    }

    @Test fun anUnsupportedGpuPointsAtTheBridgeOnlyWhenItIsOn() {
        val other = gpu("Immortalis", Family.NOT_ADRENO)
        assertEquals(Support.UNSUPPORTED, other.support)
        assertEquals("Runs through the Mali bridge, on the phone's own Vulkan driver", other.supportText(true))
        assertEquals("Not supported with the Mali bridge off: Turnip needs an Adreno (Snapdragon) GPU", other.supportText(false))
        val adreno = gpu("Adreno 750", Family.A7XX, 750)
        assertEquals("Supported", adreno.supportText(true))
        assertEquals("Supported", adreno.supportText(false))
    }

    @Test fun powerVrIsCheckedBeforeAMaliNameAndAnAdrenoBeforeBoth() {
        assertEquals(Family.POWERVR, GpuInfo.familyOf(false, 0, powerVr = true, mali = "Mali-G720"))
        assertEquals(Family.A7XX, GpuInfo.familyOf(true, 740, powerVr = true, mali = "Mali-G720"))
        assertEquals(Family.MALI_G720, GpuInfo.familyOf(false, 0, powerVr = false, mali = "Mali-G720"))
    }

    @Test fun maliNumbersAndKbaseCoverage() {
        assertEquals(720, GpuInfo.maliNumber("Mali-G720-Immortalis MC12"))
        assertEquals(57, GpuInfo.maliNumber("mali-g57 mc2"))
        assertNull(GpuInfo.maliNumber("Mali-T880"))
        assertNull(GpuInfo.maliNumber("Mali-G1-Ultra"))
        for (n in listOf(57, 68, 77, 78, 610, 710, 615, 715, 720, 725, 925)) assertTrue("G$n", GpuInfo.maliKbaseCovered("Mali-G$n"))
        for (n in listOf(31, 51, 52, 76)) assertFalse("G$n", GpuInfo.maliKbaseCovered("Mali-G$n"))
        assertFalse(GpuInfo.maliKbaseCovered("Mali-T880"))
    }
}
