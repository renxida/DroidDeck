package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLaunchFailureTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun find(vararg lines: String) = SessionLaunchFailure.find(lines.asSequence())

    @Test fun installedSteamThatExitsWithSigillHasACpuFailureHint() {
        assertEquals(SessionLaunchFailure.STEAM_ILLEGAL_INSTRUCTION, find(
            "== STEP 06:35:36.987 Steam client ready",
            "== STEP 06:35:41.058 starting the Steam client",
            "/usr/local/bin/droiddeck-session: line 309: 1192 Illegal instruction steam",
            "== steam exited rc=132",
            "(EE) failed to read Wayland events: Broken pipe",
        ))
    }

    @Test fun gamescopeWithoutAPhysicalDeviceHasAGraphicsFailureHint() {
        assertEquals(SessionLaunchFailure.VULKAN_DEVICE_MISSING, find(
            "[gamescope] [\u001b[0;31mError\u001b[0m] \u001b[0;37mvulkan:\u001b[0m failed to find physical device",
            "SDL_Vulkan_CreateSurface failed: VK_KHR_wayland_surface extension is not enabled in the Vulkan instance.Failed to create backend.",
        ))
    }

    @Test fun unrelatedIllegalInstructionsAndExitCodesAreNotBlamedOnSteam() {
        assertNull(find("game: Illegal instruction", "== steam exited rc=132"))
        assertNull(find("== STEP 12:00:00 starting the Steam client", "== steam exited rc=139"))
        assertNull(find("== STEP 12:00:00 starting the Steam client", "== steam exited rc=1320"))
        assertNull(find("== STEP 12:00:00 starting the Steam client", "== steam exited rc=0"))
    }

    @Test fun onlyTheLatestClientAttemptCounts() {
        assertNull(find(
            "== STEP 12:00:00 starting the Steam client", "== steam exited rc=132",
            "== STEP 12:01:00 starting the Steam client", "== steam exited rc=0",
        ))
    }

    @Test fun aLaterSuccessfulDeviceSelectionClearsTheGraphicsFailure() {
        assertNull(find(
            "[gamescope] [Error] vulkan: failed to find physical device",
            "[gamescope] [Info] vulkan: selecting physical device 'Turnip Adreno (TM) 610': queue family 0",
        ))
    }

    @Test fun aNewSessionDoesNotReuseAnOldFailure() {
        assertNull(find(
            "== STEP 12:00:00 starting the Steam client", "== steam exited rc=132",
            "== droiddeck-session Fri Oct 23 12:01:00 mode=steam",
        ))
    }
    @Test fun aDeviceMissingARequiredExtensionIsNoUsableDevice() {
        assertEquals(SessionLaunchFailure.VULKAN_DEVICE_MISSING, find(
            "[gamescope] [\u001b[0;34mInfo\u001b[0m]  \u001b[0;37mvulkan:\u001b[0m selecting physical device 'Virtio-GPU Venus (SwiftShader Device (LLVM 10.0.0))': queue family 0",
            "[gamescope] [\u001b[0;34mInfo\u001b[0m]  \u001b[0;37mvulkan:\u001b[0m physical device does not support DRM format modifiers",
            "[gamescope] [\u001b[0;31mError\u001b[0m] \u001b[0;37mvulkan:\u001b[0m Missing required extension: VK_KHR_external_memory_fd",
        ))
    }

    @Test fun vulkanErrorsAfterGamescopeStartedTheSessionAreNotAFailedStart() {
        assertNull(find(
            "== droiddeck-session Sat Nov 14 20:24:09 UTC 2026 mode=run /root/game.exe",
            "[gamescope] [Info]  vulkan: selecting physical device 'llvmpipe (LLVM 21.1.8, 128 bits)': queue family 0",
            SessionLaunchFailure.COMPOSITOR_UP,
            "== program cores: 4,5,6,7",
            "[gamescope] [Error] vulkan: Missing required extension: VK_EXT_something_a_game_wanted",
            "[gamescope] [Error] vulkan: failed to find physical device",
        ))
        assertEquals(SessionLaunchFailure.VULKAN_DEVICE_MISSING, find(
            SessionLaunchFailure.COMPOSITOR_UP,
            "== droiddeck-session Sat Nov 14 20:30:00 UTC 2026 mode=steam",
            "[gamescope] [Error] vulkan: failed to find physical device",
        ))
    }

    @Test fun protonWithoutTheSteamClientIsReported() {
        assertEquals(SessionLaunchFailure.STEAM_CLIENT_MISSING, find(
            "== droiddeck-session Sat Nov 14 20:24:09 UTC 2026 mode=run /usr/local/bin/droiddeck-proton-run -- /root/standin/game.exe",
            SessionLaunchFailure.COMPOSITOR_UP,
            "droiddeck-proton-run: the Steam client is not installed yet (no steamclient.so); open Steam once and let it finish starting, then start this again",
        ))
        assertNull(find(
            "droiddeck-proton-run: the Steam client is not installed yet (no steamclient.so); open Steam once",
            "== droiddeck-session Sat Nov 14 20:30:00 UTC 2026 mode=run /usr/local/bin/droiddeck-proton-run -- /root/game.exe",
            SessionLaunchFailure.COMPOSITOR_UP,
        ))
    }

    @Test fun onlyFailuresThatStopTheStartAreFailedStarts() {
        assertEquals(false, SessionLaunchFailure.STEAM_ILLEGAL_INSTRUCTION.failedStart)
        assertEquals(true, SessionLaunchFailure.VULKAN_DEVICE_MISSING.failedStart)
        assertEquals(true, SessionLaunchFailure.STEAM_CLIENT_MISSING.failedStart)
    }

    @Test fun theLogOnDiskIsReadFromItsEnd() {
        val log = tmp.newFile("session.log")
        log.writeText("x".repeat(600 * 1024) + "\n[gamescope] [Error] vulkan: failed to find physical device\n")
        assertEquals(SessionLaunchFailure.VULKAN_DEVICE_MISSING, SessionLaunchFailure.find(log))
        log.writeText("[gamescope] [Error] vulkan: failed to find physical device\n" + "y".repeat(600 * 1024) + "\n")
        assertNull(SessionLaunchFailure.find(log))
        assertNull(SessionLaunchFailure.find(java.io.File(tmp.root, "missing.log")))
    }
}
