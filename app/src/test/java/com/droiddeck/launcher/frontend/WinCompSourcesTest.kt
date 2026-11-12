package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.WinComponents
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WinCompSourcesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    private fun game(folder: File?, appId: Int = 0x0123_4567, library: String = "steam", name: String = "Game") =
        Library.SteamGame(appId, name, null, library, gameFiles = folder)

    private fun auto(findings: List<WinCompSources.Finding>) = findings.filter { it.auto }.map { it.component to it.origin }

    @Test fun steamworksSharedDepotsMapToComponents() {
        assertEquals(listOf("vcredist2015_dll" to "VC++ 2015", "d3dx9" to "DirectX (June 2010)"), WinCompSources.steamDepots(listOf("228986", "228990", "999")))
        // Hotline Miami's OpenAL depot: the DLL-copy entry, which installs without running anything.
        assertEquals(listOf("oalinst_dll" to "OpenAL"), WinCompSources.steamDepots(listOf("229020")))
    }


    @Test fun steamTakesItsSharedDepotsAndTurnsThemOn() {
        val steamapps = tmp.newFolder("steamapps")
        val folder = File(steamapps, "common/Game").apply { mkdirs() }
        File(steamapps, "appmanifest_570.acf").writeText(
            "\"AppState\"\n{\n\t\"appid\"\t\t\"570\"\n\t\"SharedDepots\"\n\t{\n\t\t\"228986\"\t\t\"228980\"\n\t\t\"228990\"\t\t\"228980\"\n\t}\n}\n",
        )
        val findings = WinCompSources.forGame(app, game(folder, 570, library = "steam", name = "Dota"))
        assertEquals(listOf("vcredist2015_dll" to WinCompSources.Origin.STEAM, "d3dx9" to WinCompSources.Origin.STEAM), auto(findings))
        assertEquals(listOf("VC++ 2015", "Dota", "570"), findings.first().args)
    }

    private fun appinfo(file: File, appId: Int) = writeAppinfo(file, appId)

    companion object {
    fun writeAppinfo(file: File, appId: Int) {
        val strings = listOf("appinfo", "common", "name", "depots", "228986", "depotfromapp", "228990", "1001", "maxsize")
        fun key(s: String) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(strings.indexOf(s)).array()
        val kv = ByteArrayOutputStream().apply {
            fun map(k: String) { write(0x00); write(key(k)) }
            fun str(k: String, v: String) { write(0x01); write(key(k)); write(v.toByteArray()); write(0) }
            fun end() = write(0x08)
            map("appinfo"); map("common"); str("name", "Some Game"); end()
            map("depots"); map("228986"); str("depotfromapp", "228980"); end(); map("228990"); str("depotfromapp", "228980"); end()
            map("1001"); str("maxsize", "1"); end(); end(); end(); end()
        }.toByteArray()
        val body = ByteArray(60) + kv
        val table = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(strings.size).array())
            strings.forEach { write(it.toByteArray()); write(0) }
        }.toByteArray()
        val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0x07564429).putInt(1)
        val entries = ByteBuffer.allocate(8 + body.size + 4).order(ByteOrder.LITTLE_ENDIAN).putInt(appId).putInt(body.size).put(body).putInt(0).array()
        head.putLong(16L + entries.size)
        file.parentFile!!.mkdirs()
        file.writeBytes(head.array() + entries + table)
    }
    }

    @Test fun steamsAppInfoIsReadForAnAppWithoutAManifest() {
        val file = File(tmp.newFolder(), "appinfo.vdf")
        appinfo(file, 1145360)
        val info = SteamAppInfo.read(file, 1145360)!!
        assertEquals("Some Game", info.name)
        assertEquals(listOf("228986", "228990"), info.sharedDepots)
        assertEquals(null, SteamAppInfo.read(file, 1))
    }

    @Test fun aGameWithoutSteamDataHasTheFolderScanOnly() {
        val folder = tmp.newFolder("Unknown")
        File(folder, "_CommonRedist/vcredist/2013").mkdirs()
        File(folder, "_CommonRedist/vcredist/2013/vcredist_x64.exe").writeText("x")
        val findings = WinCompSources.forGame(app, game(folder))
        assertTrue(auto(findings).isEmpty())
        assertEquals(listOf("vcredist2013" to WinCompSources.Origin.FOLDER), findings.map { it.component to it.origin })
    }


    private fun ready(name: String) = WinComponents.Component(name, "", "", "ready", emptyList(),
        listOf(WinComponents.Step("override_dll", JSONObject().put("dll", "x").put("type", "native"))))

    @Test fun onlyAutoFindingsThatInstallHereTurnOn() {
        val catalog = listOf(ready("vcredist2015_dll"), ready("vcredist2015"), ready("d3dx9"),
            WinComponents.Component("physx", "", "", "broken", emptyList(), emptyList())).associateBy { it.name }
        val findings = listOf(
            WinCompSources.Finding("vcredist2015", "VC++ 2015", WinCompSources.Origin.STEAM, true),
            WinCompSources.Finding("physx", "PhysX", WinCompSources.Origin.STEAM, true),
            WinCompSources.Finding("d3dx9", "d3dx9_43.dll", WinCompSources.Origin.FOLDER, false),
        )
        val picks = AutoComponents.autoPicks(findings, catalog)
        assertEquals(listOf("vcredist2015_dll"), picks.keys.toList())
        assertEquals("STEAM", picks.getValue("vcredist2015_dll").kind)
    }

    @Test fun theUsersSwitchWinsOverAnAutoPickEitherWay() {
        val key = "12345"
        WinComponents.setAuto(app, key, mapOf("d3dx9" to WinComponents.AutoReason("GOG", listOf("DirectX")), "xact" to WinComponents.AutoReason("GOG", listOf("XACT"))))
        assertEquals(listOf("d3dx9", "xact"), WinComponents.picks(app, key))
        WinComponents.setUser(app, key, "d3dx9", false)
        WinComponents.setUser(app, key, "oalinst_dll", true)
        assertEquals(listOf("xact", "oalinst_dll"), WinComponents.picks(app, key))
        WinComponents.setAuto(app, key, mapOf("d3dx9" to WinComponents.AutoReason("GOG", listOf("DirectX"))))
        assertEquals(listOf("oalinst_dll"), WinComponents.picks(app, key))
        assertEquals(mapOf("d3dx9" to false, "oalinst_dll" to true), WinComponents.selection(app, key).user)
        val text = File(app.filesDir, "wincomponents.json").readText()
        assertEquals(1, JSONObject(text).getInt("version"))
        assertEquals("oalinst_dll", JSONObject(text).getJSONObject("games").getJSONArray(key).getString(0))
    }

    @Test fun picksFromBeforeOverridesAreTheUsersOwn() {
        File(app.filesDir, "wincomponents.json").writeText("""{"version":1,"games":{"777":["d3dx9","xact"]}}""")
        assertEquals(mapOf("d3dx9" to true, "xact" to true), WinComponents.selection(app, "777").user)
        assertEquals(listOf("d3dx9", "xact"), WinComponents.picks(app, "777"))
    }

    private val catalog3 = listOf(ready("d3dx9"), ready("vcredist2013_dll"), ready("vcredist2019_dll"))
    private val installedNow = mutableSetOf<String>()
    private val downloaded = ArrayList<String>()
    private var session = false
    private var gameUp = false

    private fun payback(appId: Int = 1262580): Library.SteamGame {
        val steamapps = tmp.newFolder("steamapps")
        val folder = File(steamapps, "common/Payback").apply { mkdirs() }
        File(steamapps, "appmanifest_$appId.acf").writeText(
            "\"AppState\"\n{\n\t\"SharedDepots\"\n\t{\n\t\t\"228985\"\t\t\"228980\"\n\t\t\"228988\"\t\t\"228980\"\n\t\t\"228990\"\t\t\"228980\"\n\t}\n}\n",
        )
        return game(folder, appId, library = "steam", name = "Need for Speed Payback")
    }

    private fun <T> withFakeWorker(block: () -> T): T {
        val steamPics = SteamAppInfo.pics
        SteamAppInfo.pics = { null }
        AutoComponents.repairFailures.value = emptyMap()
        AutoComponents.catalogOf = { _, _ -> catalog3 }
        AutoComponents.installedOf = { installedNow.toSet() }
        AutoComponents.installer = { _, c, _, _ -> downloaded.add(c.name); installedNow.add(c.name); null }
        AutoComponents.sessionRunning = { session }
        AutoComponents.unfavourable = { false }
        AutoComponents.online = { true }
        AutoComponents.gameRunning = { gameUp }
        AutoComponents.runNow = true
        try {
            return block()
        } finally {
            AutoComponents.runNow = false
            SteamAppInfo.pics = steamPics
            AutoComponents.repairFailures.value = emptyMap()
            AutoComponents.catalogOf = { c, network -> if (network) WinComponents.fetch(c) else WinComponents.cached(c) }
            AutoComponents.installedOf = { WinComponents.installedIds(it).toSet() }
            AutoComponents.installer = { c, comp, all, progress -> WinComponents.install(c, comp, all, progress) }
            AutoComponents.sessionRunning = { com.droiddeck.launcher.session.SessionState.running }
            AutoComponents.online = { c -> AutoComponents.isOnline(c) }
            AutoComponents.gameRunning = { false }
        }
    }

    @Test fun anExplicitCheckRefreshesTheCatalogAndAnOfflineCheckUsesTheCache() = withFakeWorker {
        installedNow.addAll(catalog3.map { it.name })
        val requests = ArrayList<Boolean>()
        AutoComponents.catalogOf = { _, network -> requests.add(network); catalog3 }
        val game = payback()
        AutoComponents.queueGame(app, game, asked = true)
        assertTrue(requests.first())
        requests.clear()
        AutoComponents.online = { false }
        AutoComponents.queueGame(app, game, asked = true)
        assertFalse(requests.first())
    }

    @Test fun aSweepRefreshesTheCatalogOnlyOnAFavourableConnection() = withFakeWorker {
        installedNow.addAll(catalog3.map { it.name })
        val requests = ArrayList<Boolean>()
        AutoComponents.catalogOf = { _, network -> requests.add(network); catalog3 }
        val games = listOf(payback())
        AutoComponents.sweep(app, games)
        assertTrue(requests.first())
        requests.clear()
        AutoComponents.unfavourable = { true }
        AutoComponents.sweep(app, games)
        assertFalse(requests.contains(true))
        requests.clear()
        AutoComponents.unfavourable = { false }
        AutoComponents.online = { false }
        AutoComponents.sweep(app, games)
        assertFalse(requests.contains(true))
    }

    @Test fun aSteamListedComponentNotYetDownloadedTurnsOnWhenItLands() = withFakeWorker {
        installedNow.add("d3dx9")
        val game = payback()
        WinComponents.setUser(app, "1262580", "vcredist2019_dll", false)
        AutoComponents.queueGame(app, game, asked = true)
        assertEquals(listOf("vcredist2013_dll"), downloaded)
        val sel = WinComponents.selection(app, "1262580")
        assertEquals(setOf("d3dx9", "vcredist2013_dll", "vcredist2019_dll"), sel.auto.keys)
        assertEquals(setOf("d3dx9", "vcredist2013_dll"), WinComponents.picks(app, "1262580").toSet())
        assertEquals("STEAM", sel.auto.getValue("vcredist2013_dll").kind)
        assertEquals(3, AutoComponents.cachedFindings(app, "1262580")!!.count { it.auto })
    }

    @Test fun downloadCallbacksReuseTheCatalogInsteadOfRefreshingAgain() = withFakeWorker {
        val requests = ArrayList<Boolean>()
        AutoComponents.catalogOf = { _, network -> requests.add(network); catalog3 }
        AutoComponents.queueGame(app, payback(), asked = true)
        assertEquals(3, downloaded.size)
        assertEquals(1 + downloaded.size, requests.count { it })
    }

    @Test fun normalLaunchRecordsPendingComponentsBeforeQueueingTheirRepairs() = withFakeWorker {
        AutoComponents.installer = { _, c, _, _ ->
            assertTrue(c.name in AutoComponents.Pending.ids(app, "1262580"))
            assertEquals(c.revision, AutoComponents.Pending.revision(app, c.name))
            downloaded.add(c.name)
            installedNow.add(c.name)
            null
        }

        AutoComponents.queueGame(app, payback(), asked = true)

        assertTrue(AutoComponents.Pending.ids(app, "1262580").isEmpty())
    }

    @Test fun launchDiscoveryPersistsMissingComponentsWhileAWorkerWaitsForTheSession() = withFakeWorker {
        session = true
        gameUp = true
        val appId = 9876543

        assertTrue(AutoComponents.prepareLaunch(app, payback(appId)))

        assertEquals(setOf("d3dx9", "vcredist2013_dll", "vcredist2019_dll"), AutoComponents.Pending.ids(app, appId.toString()).toSet())
        session = false
        gameUp = false
        AutoComponents.drain(app)
    }

    @Test fun launchDiscoveryUsesFreshCatalogAndIncludesManualDependencies() = withFakeWorker {
        session = true
        gameUp = true
        val appId = 9876544
        val manual = WinComponents.Component("manual_component", "", "", "ready", listOf("d3dx9"), emptyList(), revision = 3)
        val catalog = catalog3 + manual
        val requests = ArrayList<Boolean>()
        AutoComponents.catalogOf = { _, network -> requests.add(network); catalog }
        WinComponents.setUser(app, appId.toString(), manual.name, true)

        assertTrue(AutoComponents.prepareLaunch(app, game(null, appId, library = "steam")))

        assertTrue(requests.contains(true))
        assertEquals(setOf("manual_component", "d3dx9"), AutoComponents.Pending.ids(app, appId.toString()).toSet())
        session = false
        gameUp = false
        AutoComponents.drain(app)
    }

    @Test fun pendingRepairsSurviveTemporaryCatalogFetchFailure() = withFakeWorker {
        val id = "vcredist2010_dll"
        AutoComponents.catalogOf = { _, _ -> null }

        AutoComponents.queueRepair(app, id, "4242", revision = 2)

        assertEquals(listOf(id), AutoComponents.Pending.ids(app, "4242"))
        assertEquals(2, AutoComponents.Pending.revision(app, id))
    }

    @Test fun launchCanProceedWithoutCatalogOnlyWhenNoRepairIsPending() = withFakeWorker {
        AutoComponents.catalogOf = { _, _ -> null }
        val noPending = game(null, 9876545, library = "steam")
        assertTrue(AutoComponents.prepareLaunch(app, noPending))

        AutoComponents.Pending.add(app, "9876546", listOf("vcredist2010_dll"), mapOf("vcredist2010_dll" to 2))
        val pending = game(null, 9876546, library = "steam")
        assertFalse(AutoComponents.prepareLaunch(app, pending))
        assertEquals(listOf("vcredist2010_dll"), AutoComponents.Pending.ids(app, "9876546"))
    }

    @Test fun offlineLaunchPersistsMissingSelectedComponentsWithoutACatalog() = withFakeWorker {
        AutoComponents.catalogOf = { _, _ -> null }
        AutoComponents.online = { false }
        val appId = 9876547
        val id = "vcredist2010_dll"
        WinComponents.setUser(app, appId.toString(), id, true)

        assertFalse(AutoComponents.prepareLaunch(app, game(null, appId, library = "steam")))
        assertEquals(listOf(id), AutoComponents.Pending.ids(app, appId.toString()))
        AutoComponents.catalogOf = { _, _ -> catalog3 + ready(id) }
        AutoComponents.online = { true }
        AutoComponents.drain(app)
    }

    @Test fun pendingRepairsKeepTheirRequiredRevisionUntilTheyFinish() = withFakeWorker {
        val repair = WinComponents.Component("vcredist2010_dll", "", "", "ready", emptyList(), emptyList(), revision = 2)
        AutoComponents.catalogOf = { _, _ -> catalog3 + repair }
        WinComponents.setUser(app, "4242", repair.name, true)
        session = true
        gameUp = true
        AutoComponents.restoreMissing(app)
        val pending = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app), "root/.config/droiddeck/wincomponents-pending.json")
        fun revision() = JSONObject(pending.readText()).getJSONObject("revisions").getInt(repair.name)
        assertEquals(2, revision())
        AutoComponents.Pending.add(app, "other", listOf(repair.name), mapOf(repair.name to 1))
        AutoComponents.Pending.set(app, "4242", listOf(repair.name))
        assertEquals(2, revision())
        gameUp = false
        AutoComponents.drain(app)
        assertFalse(JSONObject(pending.readText()).getJSONObject("revisions").has(repair.name))
    }

    @Test fun manualRepairKeepsPendingWhenCatalogIsOlderThanRequestedRevision() = withFakeWorker {
        val repair = WinComponents.Component("vcredist2010_dll", "", "", "ready", emptyList(), emptyList(), revision = 1)
        AutoComponents.catalogOf = { _, _ -> catalog3 + repair }
        installedNow.add(repair.name)
        AutoComponents.Pending.add(app, "4242", listOf(repair.name), mapOf(repair.name to 2))
        AutoComponents.queueRepair(app, repair.name, "4242", revision = 1)
        AutoComponents.drain(app)
        assertEquals("The component catalog is older than the requested revision", AutoComponents.repairFailures.value[repair.name])
        assertEquals(listOf(repair.name), AutoComponents.Pending.ids(app, "4242"))
        val pendingFile = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app), "root/.config/droiddeck/wincomponents-pending.json")
        assertEquals(2, JSONObject(pendingFile.readText()).getJSONObject("revisions").getInt(repair.name))
    }

    @Test fun restoreRequeuesPersistedRevisionEvenWhenOlderComponentIsInstalled() = withFakeWorker {
        val repair = WinComponents.Component("vcredist2010_dll", "", "", "ready", emptyList(), emptyList(), revision = 1)
        AutoComponents.catalogOf = { _, _ -> catalog3 + repair }
        installedNow.add(repair.name)
        AutoComponents.Pending.add(app, "4242", listOf(repair.name), mapOf(repair.name to 2))
        assertTrue(WinComponents.selections(app).isEmpty())
        assertTrue(AutoComponents.restoreMissing(app).isEmpty())
        assertEquals("The component catalog is older than the requested revision", AutoComponents.repairFailures.value[repair.name])
        assertEquals(listOf(repair.name), AutoComponents.Pending.ids(app, "4242"))
        assertEquals(2, AutoComponents.Pending.revision(app, repair.name))
        WinComponents.setUser(app, "4242", repair.name, false)
        AutoComponents.queueInSession(app, game(null, 4242, library = "steam"))
        assertTrue(AutoComponents.Pending.ids(app, "4242").isEmpty())
    }

    @Test fun missingCatalogComponentCannotDropANewerPendingRepair() = withFakeWorker {
        val id = "vcredist2010_dll"
        AutoComponents.catalogOf = { _, _ -> catalog3 }
        session = true
        gameUp = true
        AutoComponents.queueRepair(app, id, "4242", revision = 1)
        AutoComponents.Pending.add(app, "other", listOf(id), mapOf(id to 2))
        gameUp = false
        AutoComponents.drain(app)
        assertEquals(listOf(id), AutoComponents.Pending.ids(app, "4242"))
        assertEquals(listOf(id), AutoComponents.Pending.ids(app, "other"))
        assertEquals(2, AutoComponents.Pending.revision(app, id))
    }

    @Test fun restoredLegacyPendingRepairUsesThePersistedRevision() = withFakeWorker {
        val id = "vcredist2010_dll"
        val repair = WinComponents.Component(id, "", "", "ready", emptyList(), emptyList(), revision = 1)
        AutoComponents.catalogOf = { _, _ -> catalog3 + repair }
        var attempts = 0
        AutoComponents.installer = { _, _, _, _ -> attempts++; null }
        val pendingFile = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app), "root/.config/droiddeck/wincomponents-pending.json")
        pendingFile.parentFile?.mkdirs()
        pendingFile.writeText(JSONObject().put("games", JSONObject().put("4242", org.json.JSONArray(listOf(id)))).toString())
        session = true
        gameUp = true
        AutoComponents.queueInSession(app, game(null, 4242, library = "steam"))
        AutoComponents.Pending.add(app, "other", listOf(id), mapOf(id to 2))
        gameUp = false
        AutoComponents.drain(app)
        assertEquals("The component catalog is older than the requested revision", AutoComponents.repairFailures.value[id])
        assertEquals(0, attempts)
        assertEquals(2, AutoComponents.Pending.revision(app, id))
    }

    @Test fun catalogLoadFailureCannotDropANewerPendingRepair() = withFakeWorker {
        val id = "vcredist2010_dll"
        AutoComponents.catalogOf = { _, _ -> null }
        session = true
        gameUp = true
        AutoComponents.queueRepair(app, id, "4242", revision = 1)
        AutoComponents.Pending.add(app, "other", listOf(id), mapOf(id to 2))
        gameUp = false
        AutoComponents.drain(app)
        assertEquals("Could not load the component catalog", AutoComponents.repairFailures.value[id])
        assertEquals(listOf(id), AutoComponents.Pending.ids(app, "4242"))
        assertEquals(listOf(id), AutoComponents.Pending.ids(app, "other"))
        assertEquals(2, AutoComponents.Pending.revision(app, id))
    }

    @Test fun manualRepairsRecordTheirRevisionAndExposeFailuresForRetry() = withFakeWorker {
        val repair = WinComponents.Component("vcredist2010_dll", "", "", "ready", emptyList(), emptyList(), revision = 2)
        AutoComponents.catalogOf = { _, _ -> catalog3 + repair }
        var attempts = 0
        AutoComponents.installer = { _, c, _, _ ->
            attempts++
            if (attempts == 1) throw IllegalStateException("network unavailable") else { installedNow.add(c.name); null }
        }
        session = true
        gameUp = true
        AutoComponents.queueRepair(app, repair.name, "4242", repair.revision)
        val pending = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app), "root/.config/droiddeck/wincomponents-pending.json")
        assertEquals(2, JSONObject(pending.readText()).getJSONObject("revisions").getInt(repair.name))
        gameUp = false
        AutoComponents.drain(app)
        assertEquals("network unavailable", AutoComponents.repairFailures.value[repair.name])
        assertEquals(1, attempts)
        assertFalse(JSONObject(pending.readText()).getJSONObject("games").has("4242"))
        session = false
        AutoComponents.queueRepair(app, repair.name, "4242", repair.revision)
        assertEquals(2, attempts)
        assertEquals(null, AutoComponents.repairFailures.value[repair.name])
        assertTrue(repair.name in installedNow)
        assertFalse(JSONObject(pending.readText()).getJSONObject("revisions").has(repair.name))
    }

    @Test fun anEarlierRepairCannotDropANewerGamesPendingRevision() = withFakeWorker {
        session = false
        gameUp = false
        val first = WinComponents.Component("vcredist2010_dll", "", "", "ready", emptyList(), emptyList(), revision = 2)
        val newer = WinComponents.Component("vcredist2010_dll", "", "", "ready", emptyList(), emptyList(), revision = 3)
        AutoComponents.catalogOf = { _, _ -> catalog3 + first }
        var attempts = 0
        AutoComponents.installer = { _, c, _, _ ->
            attempts++
            if (attempts == 1) {
                AutoComponents.catalogOf = { _, _ -> catalog3 + newer }
                session = true
                gameUp = true
                AutoComponents.queueRepair(app, c.name, "other", newer.revision)
            } else installedNow.add(c.name)
            null
        }
        AutoComponents.queueRepair(app, first.name, "first", first.revision)
        val pending = File(com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app), "root/.config/droiddeck/wincomponents-pending.json")
        assertEquals(3, JSONObject(pending.readText()).getJSONObject("revisions").getInt(first.name))
        assertEquals(listOf(first.name), AutoComponents.Pending.ids(app, "other"))
        gameUp = false
        session = false
        AutoComponents.drain(app)
        assertEquals(2, attempts)
        assertFalse(JSONObject(pending.readText()).getJSONObject("revisions").has(first.name))
    }

    @Test fun theQueueWaitsWhileASessionRunsAndResumesAfter() = withFakeWorker {
        val game = payback()
        session = true
        AutoComponents.queueGame(app, game, asked = true)
        assertTrue(downloaded.isEmpty())
        assertEquals(listOf("g:1262580"), AutoComponents.queued())
        session = false
        AutoComponents.drain(app)
        assertEquals(listOf("vcredist2013_dll", "vcredist2019_dll", "d3dx9"), downloaded)
        assertTrue(AutoComponents.queued().isEmpty())
    }

    @Test fun aJobNobodyAskedForWaitsOnAnUnfavourableDevice() = withFakeWorker {
        val game = payback()
        AutoComponents.unfavourable = { true }
        AutoComponents.queueGame(app, game, asked = false)
        assertTrue(downloaded.isEmpty())
        AutoComponents.queueGame(app, game, asked = true)
        assertEquals(3, downloaded.size)
    }

    @Test fun aGameAndAComponentAreQueuedOnceEach() = withFakeWorker {
        val first = payback()
        val other = game(first.gameFiles, 1262580, library = "steam", name = "Same app again")
        session = true
        AutoComponents.queueGame(app, first, asked = false)
        AutoComponents.queueGame(app, other, asked = false)
        assertEquals(listOf("g:1262580"), AutoComponents.queued())
        session = false
        AutoComponents.drain(app)
        assertEquals(downloaded.distinct(), downloaded)
    }

    @Test fun queuedPicsLookupsMergeAppIdsFromLaterSweeps() = withFakeWorker {
        val lookedUp = ArrayList<List<Int>>()
        SteamAppInfo.pics = { ids -> lookedUp.add(ids); ids.associateWith { null } }
        session = true
        val first = game(null, 810001, library = "steam")
        val second = game(null, 810002, library = "steam")

        AutoComponents.sweep(app, listOf(first))
        AutoComponents.sweep(app, listOf(second))
        session = false
        AutoComponents.drain(app)

        assertEquals(listOf(listOf(810001, 810002)), lookedUp)
    }

    @Test fun aDownloadWaitsForANetwork() = withFakeWorker {
        val game = payback()
        AutoComponents.online = { false }
        AutoComponents.queueGame(app, game, asked = true)
        assertTrue(downloaded.isEmpty())
        assertTrue(AutoComponents.queued().all { it.startsWith("c:") })
        AutoComponents.online = { true }
        AutoComponents.drain(app)
        assertEquals(3, downloaded.size)
    }


    @Test fun anAppTheClientLearnsLaterIsReadOnceItsCacheIsWritten() {
        val file = SteamAppInfo.appinfoFile(app)
        appinfo(file, 1)
        file.setLastModified(1_000_000_000_000L)
        assertEquals(null, SteamAppInfo.info(app, 1145360))
        assertEquals(null, SteamAppInfo.info(app, 1145360))
        appinfo(file, 1145360)
        file.setLastModified(1_000_000_100_000L)
        assertEquals(listOf("228986", "228990"), SteamAppInfo.info(app, 1145360)!!.sharedDepots)
    }

    @Test fun unitysManagedFolderIsNotADotNetRequirement() {
        val folder = tmp.newFolder("DOOMBLADE")
        File(folder, "DOOMBLADE_Data/Managed").mkdirs()
        File(folder, "DOOMBLADE_Data/Managed/com.rlabrecque.steamworks.net.dll").writeText("x")
        File(folder, "DOOMBLADE_Data/Managed/System.Net.Http.dll").writeText("x")
        assertTrue(DependencyDetector.detect(folder).isEmpty())
    }

    private fun manifest(dir: File, appId: Int, state: Int, time: Long) =
        File(dir, "appmanifest_$appId.acf").apply {
            writeText("\"AppState\"\n{\n\t\"appid\"\t\t\"$appId\"\n\t\"name\"\t\t\"Audiosurf\"\n\t\"installdir\"\t\t\"Audiosurf\"\n\t\"StateFlags\"\t\t\"$state\"\n}\n")
            setLastModified(time)
        }

    @Test fun aManifestTurningInstalledIsNoticedOnce() {
        val steamapps = tmp.newFolder("steamapps")
        manifest(steamapps, 12900, 1026, 1_000_000L)
        manifest(steamapps, 570, 4, 1_000_000L)
        val watch = SessionInstalls.Watch { listOf(steamapps) }
        assertTrue(watch.poll().isEmpty())
        assertTrue(watch.poll().isEmpty())
        manifest(steamapps, 12900, 4, 2_000_000L)
        manifest(steamapps, 440, 4, 2_000_000L)
        val done = watch.poll()
        assertEquals(setOf(12900, 440), done.map { it.appId }.toSet())
        assertEquals(File(steamapps, "common/Audiosurf"), SessionInstalls.gameOf(done.first { it.appId == 12900 }).gameFiles)
        assertTrue(watch.poll().isEmpty())
    }

    @Test fun anInSessionJobWaitsWhileAGameRunsThenReadiesItsComponents() = withFakeWorker {
        val installed = payback()
        session = true
        gameUp = true
        AutoComponents.queueInSession(app, installed)
        assertTrue(downloaded.isEmpty())
        val other = game(tmp.newFolder("Other"))
        AutoComponents.queueGame(app, other, asked = false)
        gameUp = false
        AutoComponents.drain(app)
        assertEquals(3, downloaded.size)
        assertEquals(setOf("d3dx9", "vcredist2013_dll", "vcredist2019_dll"), WinComponents.picks(app, "1262580").toSet())
        assertEquals(0, AutoComponents.Pending.read(app).length())
        assertEquals(listOf("g:${AutoComponents.appKey(other)}"), AutoComponents.queued())
        session = false
        AutoComponents.drain(app)
        assertTrue(AutoComponents.queued().isEmpty())
    }

    @Test fun aLostPartOfAnInstalledBundleIsReinstalledAndTheGameWaitsForIt() = withFakeWorker {
        val bundle = WinComponents.Component("directmusic", "", "", "ready", listOf("dmband"), emptyList())
        AutoComponents.catalogOf = { _, _ -> catalog3 + ready("dmband") + bundle }
        installedNow.addAll(listOf("directmusic", "d3dx9"))
        WinComponents.setUser(app, "4242", "directmusic", true)
        WinComponents.setUser(app, "4242", "d3dx9", true)
        session = true
        gameUp = true
        assertEquals(listOf("dmband"), AutoComponents.restoreMissing(app))
        assertTrue(downloaded.isEmpty())
        assertEquals("[\"dmband\"]", AutoComponents.Pending.read(app).optJSONArray("4242").toString())
        gameUp = false
        AutoComponents.drain(app)
        assertEquals(listOf("dmband"), downloaded)
        assertEquals(0, AutoComponents.Pending.read(app).length())
        assertTrue(AutoComponents.restoreMissing(app).isEmpty())
    }

    @Test fun aRestoreRunsADownloadQueuedBeforeTheSessionWithoutWaitingForItToEnd() = withFakeWorker {
        WinComponents.setUser(app, "4243", "vcredist2019_dll", true)
        session = true
        AutoComponents.queueGame(app, game(tmp.newFolder("Wait")), asked = true)
        AutoComponents.restoreMissing(app)
        AutoComponents.restoreMissing(app)
        assertEquals(listOf("vcredist2019_dll"), downloaded)
        session = false
        AutoComponents.drain(app)
    }

    @Test fun aGameJobThatRunsFirstKeepsARestoredComponentPending() = withFakeWorker {
        AutoComponents.catalogOf = { _, _ -> catalog3 + ready("dmband") }
        val seen = ArrayList<List<String>>()
        AutoComponents.installer = { _, c, _, _ ->
            if (c.name == "dmband") seen.add(AutoComponents.Pending.ids(app, "1262580"))
            downloaded.add(c.name); installedNow.add(c.name); null
        }
        val game = payback()
        WinComponents.setUser(app, "1262580", "dmband", true)
        session = true
        gameUp = true
        AutoComponents.queueInSession(app, game)
        AutoComponents.restoreMissing(app)
        gameUp = false
        AutoComponents.drain(app)
        assertEquals(1, seen.size)
        assertTrue("dmband" in seen.single())
        assertEquals(0, AutoComponents.Pending.read(app).length())
        session = false
        AutoComponents.drain(app)
    }
}
