package com.v2ray.ang.ui.server

import android.app.Application
import android.util.Log
import com.v2ray.ang.R
import com.v2ray.ang.core.AetherIdentity
import com.v2ray.ang.core.AetherIdentityStatus
import com.v2ray.ang.core.AetherScanResult
import com.v2ray.ang.dto.AetherEndpoint
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class ServerAetherViewModelTest {

    private val profile = ProfileItem.create(EConfigType.AETHER).apply { aetherProtocol = AetherProtocol.MASQUE.type }
    private val found = AetherScanResult(AetherEndpoint("162.159.197.3", 443))
    private val oldKey = AetherIdentity("a1b2c3d4e5f6", "172.16.0.2", "2606:4700:110:8a36::1")
    private val newKey = AetherIdentity("f6e5d4c3b2a1", "172.16.0.2", "2606:4700:110:8a36::2")

    private class FakeSource : AetherEditorSource {
        var available = true
        var session: AetherSession? = null
        var scanner: suspend (ProfileItem, (String) -> Unit) -> AetherScanResult? = { _, _ -> null }
        var renewer: suspend (ProfileItem, (String) -> Unit) -> AetherIdentityStatus? = { _, _ -> null }
        var clearer: suspend () -> Boolean = { true }
        var regions: List<String> = emptyList()
        val identities = mutableMapOf<AetherProtocol, AetherIdentityStatus>()

        override suspend fun isCoreAvailable() = available
        override suspend fun isPsiphonAvailable(): Boolean = false
        override suspend fun isTorTransportsAvailable(): Boolean = false
        override suspend fun activeSession() = session
        override suspend fun scan(profile: ProfileItem, onOutput: (String) -> Unit) = scanner(profile, onOutput)
        override suspend fun identityStatus(protocol: AetherProtocol) =
            identities[protocol] ?: AetherIdentityStatus(protocol, null)

        override suspend fun renewIdentity(profile: ProfileItem, onOutput: (String) -> Unit) = renewer(profile, onOutput)
        override suspend fun clearPsiphonData() = clearer()
        override suspend fun psiphonRegions() = regions
    }

    private val source = FakeSource()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ServerAetherViewModel(mock<Application>(), source)

    private fun ServerAetherViewModel.texts() = log.value.map { it.text }

    private fun resource(id: Int, vararg args: String) = AetherLogText.Resource(id, args.toList())

    @Test
    fun startsIdleWithAnEmptyLogAndReportsWhetherTheCoreIsAvailable() {
        source.available = false
        val viewModel = viewModel()

        assertEquals(AetherScanState.Idle, viewModel.scanState.value)
        assertFalse(viewModel.isCoreAvailable.value)
        assertFalse(viewModel.isRenewingIdentity.value)
        assertNull(viewModel.session.value)
        assertTrue(viewModel.log.value.isEmpty())
    }

    @Test
    fun aScanStreamsTheCoreOutputAndEndsWithTheEndpointItFound() {
        val pending = CompletableDeferred<AetherScanResult?>()
        var scanned: ProfileItem? = null
        source.scanner = { profile, onOutput ->
            scanned = profile
            onOutput("[2026-09-11T10:00:00.000Z INFO  aether] [+] candidate ok 162.159.197.3:443 rtt=84ms")
            onOutput("   ")
            pending.await()
        }
        val viewModel = viewModel()

        viewModel.scan(profile)
        assertEquals(AetherScanState.Scanning, viewModel.scanState.value)
        assertSame(profile, scanned)

        pending.complete(found)

        assertEquals(AetherScanState.Found(found), viewModel.scanState.value)
        assertEquals(
            listOf(
                resource(R.string.aether_log_scan_started),
                AetherLogText.Raw("[+] candidate ok 162.159.197.3:443 rtt=84ms"),
                resource(R.string.aether_log_scan_found, "162.159.197.3:443"),
                resource(R.string.aether_log_masque_key_missing),
            ),
            viewModel.texts()
        )

        viewModel.onScanHandled()
        assertEquals(AetherScanState.Idle, viewModel.scanState.value)
    }

    @Test
    fun aScanThatFindsNothingSaysSo() {
        val viewModel = viewModel()

        viewModel.scan(profile)

        assertEquals(AetherScanState.NotFound, viewModel.scanState.value)
        val outcome = viewModel.log.value.first { it.text == resource(R.string.aether_scan_failed) }
        assertEquals(Log.WARN, outcome.priority)
    }

    @Test
    fun bothGoolHopsAreLoggedTogether() {
        val hops = AetherScanResult(AetherEndpoint("162.159.192.1", 2408), AetherEndpoint("188.114.96.1", 894))

        assertEquals(
            resource(R.string.aether_log_scan_found_hops, "162.159.192.1:2408", "188.114.96.1:894"),
            ServerAetherViewModel.scanOutcome(hops)
        )
    }

    @Test
    fun coreOutputKeepsItsLevelButLosesItsHeader() {
        source.scanner = { _, onOutput ->
            onOutput("[2026-09-11T10:00:00.000Z WARN  aether] [-] scan deadline reached")
            onOutput("Error: NoCleanEndpoint")
            null
        }
        val viewModel = viewModel()

        viewModel.scan(profile)

        val output = viewModel.log.value.filter { it.text is AetherLogText.Raw }
        assertEquals(listOf(Log.WARN, Log.ERROR), output.map { it.priority })
        assertEquals(
            listOf(AetherLogText.Raw("[-] scan deadline reached"), AetherLogText.Raw("Error: NoCleanEndpoint")),
            output.map { it.text }
        )
    }

    @Test
    fun aSecondActionWaitsForTheFirstToFinish() {
        val pending = CompletableDeferred<AetherScanResult?>()
        var scans = 0
        var renewals = 0
        source.scanner = { _, _ -> scans++; pending.await() }
        source.renewer = { _, _ -> renewals++; null }
        val viewModel = viewModel()

        viewModel.scan(profile)
        viewModel.scan(profile)
        viewModel.renewIdentity(profile)
        assertEquals(1, scans)
        assertEquals(0, renewals)

        pending.complete(null)
        viewModel.onScanHandled()
        viewModel.scan(profile)
        assertEquals(2, scans)
    }

    @Test
    fun cancellingAScanStopsItAndReturnsToIdle() {
        var stopped = false
        source.scanner = { _, _ ->
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val viewModel = viewModel()

        viewModel.scan(profile)
        viewModel.cancelScan()

        assertTrue(stopped)
        assertEquals(AetherScanState.Idle, viewModel.scanState.value)
        assertEquals(resource(R.string.aether_log_scan_cancelled), viewModel.texts().last())
    }

    @Test
    fun cancellingWhenNothingRunsKeepsTheResult() {
        source.scanner = { _, _ -> found }
        val viewModel = viewModel()

        viewModel.scan(profile)
        viewModel.cancelScan()

        assertEquals(AetherScanState.Found(found), viewModel.scanState.value)
    }

    @Test
    fun handlingARunningScanDoesNotHideIt() {
        source.scanner = { _, _ -> awaitCancellation() }
        val viewModel = viewModel()

        viewModel.scan(profile)
        viewModel.onScanHandled()

        assertEquals(AetherScanState.Scanning, viewModel.scanState.value)
    }

    @Test
    fun theKeyStatusIsLoggedOnceUntilItChanges() {
        source.identities[AetherProtocol.MASQUE] = AetherIdentityStatus(AetherProtocol.MASQUE, oldKey)
        val viewModel = viewModel()

        viewModel.showIdentity(AetherProtocol.MASQUE)
        viewModel.showIdentity(AetherProtocol.MASQUE)
        viewModel.showIdentity(AetherProtocol.WIREGUARD)

        assertEquals(
            listOf(
                resource(R.string.aether_log_masque_key_ready, "a1b2c3d4…", "172.16.0.2", "2606:4700:110:8a36::1"),
                resource(R.string.aether_log_wireguard_key_missing),
            ),
            viewModel.texts()
        )
    }

    @Test
    fun aTwoHopProtocolReportsBothHopKeys() {
        for (protocol in listOf(AetherProtocol.GOOL, AetherProtocol.MIM)) {
            val status = AetherIdentityStatus(protocol, oldKey, null)

            assertEquals(
                listOf(
                    resource(R.string.aether_log_outer_key_ready, "a1b2c3d4…", "172.16.0.2", "2606:4700:110:8a36::1"),
                    resource(R.string.aether_log_inner_key_missing),
                ),
                ServerAetherViewModel.identityLines(status)
            )
        }
    }

    @Test
    fun renewingTheKeyShowsTheNewKey() {
        val pending = CompletableDeferred<AetherIdentityStatus?>()
        source.renewer = { _, onOutput ->
            onOutput("[2026-09-11T10:00:00.000Z INFO  aether] [+] provisioned and saved new masque identity")
            pending.await()
        }
        val viewModel = viewModel()

        viewModel.renewIdentity(profile)
        assertTrue(viewModel.isRenewingIdentity.value)

        pending.complete(AetherIdentityStatus(AetherProtocol.MASQUE, newKey))

        assertFalse(viewModel.isRenewingIdentity.value)
        assertEquals(
            listOf(
                resource(R.string.aether_log_key_renewing),
                AetherLogText.Raw("[+] provisioned and saved new masque identity"),
                resource(R.string.aether_log_key_renewed),
                resource(R.string.aether_log_masque_key_ready, "f6e5d4c3…", "172.16.0.2", "2606:4700:110:8a36::2"),
            ),
            viewModel.texts()
        )
    }

    @Test
    fun cancellingTheKeyRenewalStopsItAndShowsTheKeysInUse() {
        var stopped = false
        source.identities[AetherProtocol.MASQUE] = AetherIdentityStatus(AetherProtocol.MASQUE, oldKey)
        source.renewer = { _, _ ->
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val viewModel = viewModel()

        viewModel.renewIdentity(profile)
        assertTrue(viewModel.isRenewingIdentity.value)
        viewModel.cancelRenewal()

        assertTrue(stopped)
        assertFalse(viewModel.isRenewingIdentity.value)
        val cancelled = viewModel.log.value.first { it.text == resource(R.string.aether_log_key_renew_cancelled) }
        assertEquals(Log.WARN, cancelled.priority)
        assertEquals(
            listOf(
                resource(R.string.aether_log_key_renewing),
                resource(R.string.aether_log_key_renew_cancelled),
                resource(R.string.aether_log_masque_key_ready, "a1b2c3d4…", "172.16.0.2", "2606:4700:110:8a36::1"),
            ),
            viewModel.texts()
        )

        // Nothing is busy any more: the next action starts.
        var scans = 0
        source.scanner = { _, _ -> scans++; null }
        viewModel.scan(profile)
        assertEquals(1, scans)
    }

    @Test
    fun aCancelledScanStaysBusyUntilItHasStopped() {
        val stopped = CompletableDeferred<Unit>()
        var scans = 0
        source.scanner = { _, _ ->
            scans++
            try {
                awaitCancellation()
            } finally {
                // The core is still being ended, as a real one takes a moment to.
                withContext(NonCancellable) { stopped.await() }
            }
        }
        val viewModel = viewModel()

        viewModel.scan(profile)
        viewModel.cancelScan()

        assertEquals(AetherScanState.Scanning, viewModel.scanState.value)
        viewModel.scan(profile)
        viewModel.renewIdentity(profile)
        viewModel.cancelScan()
        assertEquals(1, scans)
        assertFalse(viewModel.isRenewingIdentity.value)
        assertEquals(1, viewModel.texts().count { it == resource(R.string.aether_log_scan_cancelled) })

        stopped.complete(Unit)

        assertEquals(AetherScanState.Idle, viewModel.scanState.value)
        viewModel.scan(profile)
        assertEquals(2, scans)
    }

    @Test
    fun aCancelledKeyRenewalStaysBusyUntilItHasStopped() {
        val stopped = CompletableDeferred<Unit>()
        var renewals = 0
        var scans = 0
        source.renewer = { _, _ ->
            renewals++
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { stopped.await() }
            }
        }
        source.scanner = { _, _ -> scans++; null }
        val viewModel = viewModel()

        viewModel.renewIdentity(profile)
        viewModel.cancelRenewal()

        // Its core is still being ended: nothing else starts, and a second cancel adds nothing.
        assertTrue(viewModel.isRenewingIdentity.value)
        viewModel.renewIdentity(profile)
        viewModel.scan(profile)
        viewModel.cancelRenewal()
        assertEquals(1, renewals)
        assertEquals(0, scans)
        assertEquals(1, viewModel.texts().count { it == resource(R.string.aether_log_key_renew_cancelled) })

        stopped.complete(Unit)

        assertFalse(viewModel.isRenewingIdentity.value)
        viewModel.scan(profile)
        assertEquals(1, scans)
    }

    @Test
    fun aRenewalCancelledOnceItsNewKeysWereInPlaceShowsThem() {
        source.identities[AetherProtocol.MASQUE] = AetherIdentityStatus(AetherProtocol.MASQUE, oldKey)
        val viewModel = viewModel()
        viewModel.showIdentity(AetherProtocol.MASQUE)
        source.renewer = { _, _ ->
            source.identities[AetherProtocol.MASQUE] = AetherIdentityStatus(AetherProtocol.MASQUE, newKey)
            awaitCancellation()
        }

        viewModel.renewIdentity(profile)
        viewModel.cancelRenewal()

        assertEquals(
            listOf(
                resource(R.string.aether_log_masque_key_ready, "a1b2c3d4…", "172.16.0.2", "2606:4700:110:8a36::1"),
                resource(R.string.aether_log_key_renewing),
                resource(R.string.aether_log_key_renew_cancelled),
                resource(R.string.aether_log_masque_key_ready, "f6e5d4c3…", "172.16.0.2", "2606:4700:110:8a36::2"),
            ),
            viewModel.texts()
        )
    }

    @Test
    fun cancellingWhenNoRenewalRunsDoesNothing() {
        val viewModel = viewModel()
        viewModel.cancelRenewal()
        assertTrue(viewModel.log.value.isEmpty())

        source.renewer = { _, _ -> AetherIdentityStatus(AetherProtocol.MASQUE, newKey) }
        viewModel.renewIdentity(profile)
        val finished = viewModel.texts()
        viewModel.cancelRenewal()

        assertEquals(finished, viewModel.texts())
        assertEquals(resource(R.string.aether_log_masque_key_ready, "f6e5d4c3…", "172.16.0.2", "2606:4700:110:8a36::2"), finished.last())
    }

    @Test
    fun aLiveSessionIsReportedWhenTheScreenOpens() {
        source.session = AetherSession(AetherProtocol.MASQUE)

        assertEquals(AetherSession(AetherProtocol.MASQUE), viewModel().session.value)
    }

    @Test
    fun theExitCountriesOnOfferComeFromTheServerList() {
        assertTrue(viewModel().psiphonRegions.value.isEmpty())
        source.regions = listOf("DE", "US")
        assertEquals(listOf("DE", "US"), viewModel().psiphonRegions.value)
    }

    @Test
    fun psiphonDataIsNotClearedUnderALiveSessionAndTheLogTellsTheOutcome() {
        var clears = 0
        source.clearer = { clears++; true }
        val viewModel = viewModel()

        source.session = AetherSession(AetherProtocol.MASQUE)
        viewModel.clearPsiphonData()
        assertEquals(0, clears)
        val blocked = viewModel.log.value.single()
        assertEquals(resource(R.string.aether_psiphon_clear_blocked), blocked.text)
        assertEquals(Log.WARN, blocked.priority)

        source.session = null
        viewModel.clearPsiphonData()
        assertEquals(1, clears)
        val cleared = viewModel.log.value.last()
        assertEquals(resource(R.string.aether_log_psiphon_cleared), cleared.text)
        assertEquals(Log.INFO, cleared.priority)

        source.clearer = { clears++; false }
        viewModel.clearPsiphonData()
        assertEquals(2, clears)
        val failed = viewModel.log.value.last()
        assertEquals(resource(R.string.aether_log_psiphon_clear_failed), failed.text)
        assertEquals(Log.ERROR, failed.priority)
    }

    @Test
    fun theKeyIsNotRenewedUnderALiveSession() {
        var renewals = 0
        source.renewer = { _, _ -> renewals++; null }
        val viewModel = viewModel()
        assertNull(viewModel.session.value)

        // Renewal replaces every key file, so a session of another protocol blocks it as well.
        source.session = AetherSession(AetherProtocol.WIREGUARD)
        viewModel.renewIdentity(profile)

        assertEquals(0, renewals)
        assertEquals(AetherSession(AetherProtocol.WIREGUARD), viewModel.session.value)
        assertFalse(viewModel.isRenewingIdentity.value)
        val blocked = viewModel.log.value.single()
        assertEquals(resource(R.string.aether_renew_blocked), blocked.text)
        assertEquals(Log.WARN, blocked.priority)

        source.session = null
        viewModel.refreshSession()
        assertNull(viewModel.session.value)
        viewModel.renewIdentity(profile)
        assertEquals(1, renewals)
    }

    @Test
    fun aScanIsNotStartedOnTheKeyOfALiveSession() {
        var scans = 0
        source.scanner = { _, _ -> scans++; found }
        val viewModel = viewModel()

        source.session = AetherSession(AetherProtocol.MASQUE)
        viewModel.scan(profile)

        assertEquals(0, scans)
        assertEquals(AetherScanState.Idle, viewModel.scanState.value)
        assertEquals(AetherSession(AetherProtocol.MASQUE), viewModel.session.value)
        val blocked = viewModel.log.value.single()
        assertEquals(resource(R.string.aether_scan_blocked), blocked.text)
        assertEquals(Log.WARN, blocked.priority)

        // Only the listener was visible, so the session's key is unknown and the scan stays blocked.
        source.session = AetherSession(protocol = null)
        viewModel.scan(profile)
        assertEquals(0, scans)

        // A WireGuard session uses another key than this MASQUE profile, so the scan goes ahead.
        source.session = AetherSession(AetherProtocol.WIREGUARD)
        viewModel.scan(profile)
        assertEquals(1, scans)
        assertEquals(AetherScanState.Found(found), viewModel.scanState.value)
    }

    @Test
    fun aFailedRenewalSaysTheOldKeyWasKept() {
        val viewModel = viewModel()

        viewModel.renewIdentity(profile)

        assertFalse(viewModel.isRenewingIdentity.value)
        val failure = viewModel.log.value.last()
        assertEquals(resource(R.string.aether_log_key_renew_failed), failure.text)
        assertEquals(Log.ERROR, failure.priority)
    }

    @Test
    fun theLogKeepsOnlyTheLatestEntries() {
        source.scanner = { _, onOutput ->
            repeat(ServerAetherViewModel.LOG_CAPACITY + 50) { onOutput("line $it") }
            null
        }
        val viewModel = viewModel()

        viewModel.scan(profile)

        val entries = viewModel.log.value
        assertEquals(ServerAetherViewModel.LOG_CAPACITY, entries.size)
        assertEquals(entries.map { it.id }.sorted(), entries.map { it.id })
        assertTrue(entries.none { it.text == AetherLogText.Raw("line 0") })
    }
}
