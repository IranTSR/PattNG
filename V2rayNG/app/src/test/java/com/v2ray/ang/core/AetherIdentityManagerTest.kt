package com.v2ray.ang.core

import com.v2ray.ang.enums.AetherProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class AetherIdentityManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val quotes = "\"\"\""

    private fun keyFile(deviceId: String, ipv4: String = "172.16.0.2", ipv6: String = "2606:4700:110:8a36::1") =
        listOf(
            "device_id = \"$deviceId\"",
            "access_token = \"secret-token\"",
            "cert_pem = $quotes",
            "-----BEGIN CERTIFICATE-----",
            "device_id = \"hidden-in-pem\"",
            "-----END CERTIFICATE-----",
            quotes,
            "cert_issued_at = 1757580000",
            "ipv4 = \"$ipv4\"",
            "ipv6 = \"$ipv6\"",
            "wg_private_key = \"c2VjcmV0\"",
        ).joinToString("\n")

    private fun workDir(vararg files: Pair<String, String>): File =
        folder.newFolder("aether").apply {
            files.forEach { (name, text) -> File(this, name).writeText(text) }
        }

    @Test
    fun onlyTheIdentityFieldsAreReadFromAKeyFile() {
        val identity = AetherIdentityManager.parse(keyFile("a1b2c3d4-e5f6"))
        assertEquals(AetherIdentity("a1b2c3d4-e5f6", "172.16.0.2", "2606:4700:110:8a36::1"), identity)
    }

    @Test
    fun aFileWithoutADeviceIsNotAKey() {
        assertNull(AetherIdentityManager.parse("ipv4 = \"172.16.0.2\""))
        assertNull(AetherIdentityManager.parse("device_id = \"\""))
        assertNull(AetherIdentityManager.parse(""))
    }

    @Test
    fun eachProtocolReadsItsOwnKeyFiles() {
        val dir = workDir(
            AetherIdentityManager.MASQUE_FILE to keyFile("masque"),
            AetherIdentityManager.MASQUE_INNER_FILE to keyFile("masque-inner"),
            AetherIdentityManager.WIREGUARD_FILE to keyFile("outer"),
            AetherIdentityManager.WIREGUARD_INNER_FILE to keyFile("inner"),
        )

        assertEquals("masque", AetherIdentityManager.status(dir, AetherProtocol.MASQUE).primary?.deviceId)
        assertNull(AetherIdentityManager.status(dir, AetherProtocol.MASQUE).secondary)
        assertEquals("outer", AetherIdentityManager.status(dir, AetherProtocol.WIREGUARD).primary?.deviceId)

        val gool = AetherIdentityManager.status(dir, AetherProtocol.GOOL)
        assertEquals("outer", gool.primary?.deviceId)
        assertEquals("inner", gool.secondary?.deviceId)

        val mim = AetherIdentityManager.status(dir, AetherProtocol.MIM)
        assertEquals("masque", mim.primary?.deviceId)
        assertEquals("masque-inner", mim.secondary?.deviceId)
    }

    @Test
    fun aMissingKeyFileMeansNoKey() {
        val dir = workDir(AetherIdentityManager.WIREGUARD_FILE to keyFile("outer"))

        assertNull(AetherIdentityManager.status(dir, AetherProtocol.MASQUE).primary)
        assertNull(AetherIdentityManager.status(dir, AetherProtocol.GOOL).secondary)
        assertNull(AetherIdentityManager.status(dir, AetherProtocol.MIM).primary)
        assertNull(AetherIdentityManager.status(dir, AetherProtocol.MIM).secondary)
        assertNull(AetherIdentityManager.status(File(folder.root, "absent"), AetherProtocol.WIREGUARD).primary)
    }

    @Test
    fun theTunnelsOverMasqueShareAKeyAndTheOthersAnother() {
        assertTrue(AetherIdentityManager.sharesIdentity(AetherProtocol.MASQUE, AetherProtocol.MASQUE))
        assertTrue(AetherIdentityManager.sharesIdentity(AetherProtocol.MASQUE, AetherProtocol.MIM))
        assertTrue(AetherIdentityManager.sharesIdentity(AetherProtocol.MIM, AetherProtocol.MIM))
        assertTrue(AetherIdentityManager.sharesIdentity(AetherProtocol.WIREGUARD, AetherProtocol.GOOL))
        assertTrue(AetherIdentityManager.sharesIdentity(AetherProtocol.GOOL, AetherProtocol.GOOL))
        assertFalse(AetherIdentityManager.sharesIdentity(AetherProtocol.MASQUE, AetherProtocol.WIREGUARD))
        assertFalse(AetherIdentityManager.sharesIdentity(AetherProtocol.GOOL, AetherProtocol.MASQUE))
        assertFalse(AetherIdentityManager.sharesIdentity(AetherProtocol.MIM, AetherProtocol.GOOL))
        assertFalse(AetherIdentityManager.sharesIdentity(AetherProtocol.WIREGUARD, AetherProtocol.MIM))
    }

    @Test
    fun theCoreSaysWhenEveryKeyIsRegistered() {
        val done = "[2026-10-01T10:00:00.000Z INFO  aether] [+] identities ready: wireguard, wireguard inner, masque, masque inner"
        assertTrue(AetherIdentityManager.isRegistered(done))

        // Each key is announced as it is saved; only the last line ends the run.
        val one = "[2026-10-01T10:00:00.000Z INFO  aether] [+] masque identity ready: device=a1b2 ipv4=172.16.0.2 ipv6=2606::1"
        assertFalse(AetherIdentityManager.isRegistered(one))
        assertFalse(AetherIdentityManager.isRegistered("[+] no masque identity found; provisioning dedicated masque account"))
    }

    /** The device of each key in [dir], in the order of [AetherIdentityManager.KEY_FILES]; null for a key that is not there. */
    private fun devices(dir: File): List<String?> =
        AetherIdentityManager.KEY_FILES.map { name ->
            File(dir, name).takeIf { it.isFile }?.let { AetherIdentityManager.parse(it.readText()) }?.deviceId
        }

    private fun every(deviceId: String?): List<String?> = List(AetherIdentityManager.KEY_FILES.size) { deviceId }

    private fun keysInUse(): File = workDir(*AetherIdentityManager.KEY_FILES.map { it to keyFile("old") }.toTypedArray())

    private fun register(renewalDir: File, files: List<String> = AetherIdentityManager.KEY_FILES, deviceId: String = "new") =
        files.forEach { File(renewalDir, it).writeText(keyFile(deviceId)) }

    @Test
    fun aRenewalReplacesEveryKeyOnceAllTheNewOnesAreThere() = runBlocking {
        val dir = keysInUse()
        val renewal = File(folder.root, "aether-renewal")

        val renewed = AetherIdentityManager.renewAll(dir, renewal) {
            register(renewal)
            true
        }

        assertTrue(renewed)
        assertEquals(every("new"), devices(dir))
        assertFalse(renewal.exists())
    }

    @Test
    fun theKeysInUseAreNotTouchedWhileTheNewOnesAreRegistered() = runBlocking {
        val dir = keysInUse()
        val renewal = File(folder.root, "aether-renewal")

        AetherIdentityManager.renewAll(dir, renewal) {
            register(renewal, AetherIdentityManager.KEY_FILES.dropLast(1))
            // Three new keys are there and the last one is still being registered.
            assertEquals(every("old"), devices(dir))
            register(renewal, AetherIdentityManager.KEY_FILES.takeLast(1))
            assertEquals(every("old"), devices(dir))
            true
        }

        assertEquals(every("new"), devices(dir))
    }

    @Test
    fun aMissingNewKeyKeepsEveryOldKey() = runBlocking {
        val dir = keysInUse()
        val renewal = File(folder.root, "aether-renewal")

        val renewed = AetherIdentityManager.renewAll(dir, renewal) {
            // The runs said they were done, but the inner WireGuard key is not there.
            register(renewal, AetherIdentityManager.KEY_FILES - AetherIdentityManager.WIREGUARD_INNER_FILE)
            true
        }

        assertFalse(renewed)
        assertEquals(every("old"), devices(dir))
        assertFalse(renewal.exists())
    }

    @Test
    fun aNewKeyThatDoesNotReadAsAKeyKeepsEveryOldKey() = runBlocking {
        val dir = keysInUse()
        val renewal = File(folder.root, "aether-renewal")

        val renewed = AetherIdentityManager.renewAll(dir, renewal) {
            register(renewal)
            File(renewal, AetherIdentityManager.MASQUE_INNER_FILE).writeText("device_id = \"\"")
            true
        }

        assertFalse(renewed)
        assertEquals(every("old"), devices(dir))
        assertFalse(renewal.exists())
    }

    @Test
    fun aFailedRegistrationKeepsEveryOldKey() = runBlocking {
        val dir = keysInUse()
        val renewal = File(folder.root, "aether-renewal")

        val renewed = AetherIdentityManager.renewAll(dir, renewal) {
            // The MASQUE keys came, then the WireGuard run failed.
            register(renewal, listOf(AetherIdentityManager.MASQUE_FILE, AetherIdentityManager.MASQUE_INNER_FILE))
            false
        }

        assertFalse(renewed)
        assertEquals(every("old"), devices(dir))
        assertFalse(renewal.exists())
    }

    @Test
    fun aCancelledRenewalKeepsEveryOldKey() = runBlocking {
        val dir = keysInUse()
        val renewal = File(folder.root, "aether-renewal")
        val registering = CompletableDeferred<Unit>()

        val job = launch {
            AetherIdentityManager.renewAll(dir, renewal) {
                register(renewal)
                registering.complete(Unit)
                awaitCancellation()
            }
        }
        registering.await()
        job.cancelAndJoin()

        assertEquals(every("old"), devices(dir))
        assertFalse(renewal.exists())
    }

    @Test
    fun aCancellationLandingRightAfterTheNewKeysWereMarkedReadyStillPutsThemInPlace() = runBlocking {
        val dir = keysInUse()
        val renewal = File(folder.root, "aether-renewal")
        val registered = CompletableDeferred<Unit>()

        val job = launch {
            AetherIdentityManager.renewAll(dir, renewal) {
                register(renewal)
                registered.complete(Unit)
                true
            }
        }
        registered.await()
        // The mark is written on the IO dispatcher. Waiting for it without suspending keeps this
        // single-threaded event loop busy, so the step's result can only be delivered after the
        // cancellation below: the moment a renewal must neither drop its new keys nor leave half of them.
        val mark = File(renewal, AetherIdentityManager.READY_MARK)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!mark.exists() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(mark.exists())
        job.cancel()
        job.join()

        assertEquals(every("new"), devices(dir))
        assertFalse(renewal.exists())
    }

    @Test
    fun aRenewalStoppedWhileItMovedTheKeysIsFinishedLater() {
        val dir = keysInUse()
        val renewal = folder.newFolder("aether-renewal")
        register(renewal)
        File(renewal, AetherIdentityManager.READY_MARK).createNewFile()
        // The app was killed after the first two new keys were moved.
        AetherIdentityManager.KEY_FILES.take(2).forEach { File(renewal, it).renameTo(File(dir, it)) }

        assertTrue(AetherIdentityManager.settle(dir, renewal))

        assertEquals(every("new"), devices(dir))
        assertFalse(renewal.exists())
    }

    @Test
    fun newKeysNotMarkedReadyAreNeverMovedIntoPlace() {
        val dir = keysInUse()
        val renewal = folder.newFolder("aether-renewal")
        // A renewal is still at work here, or one was stopped before it had checked its keys.
        register(renewal)

        assertTrue(AetherIdentityManager.settle(dir, renewal))

        assertEquals(every("old"), devices(dir))
        assertEquals(every("new"), devices(renewal))
    }

    @Test
    fun whatAnInterruptedRenewalLeftIsNotTakenForNewKeys() = runBlocking {
        val dir = keysInUse()
        val renewal = folder.newFolder("aether-renewal")
        register(renewal, AetherIdentityManager.KEY_FILES.take(3), deviceId = "stale")

        val renewed = AetherIdentityManager.renewAll(dir, renewal) {
            // The stale keys are gone before the core runs, which registers all four anew.
            assertEquals(every(null), devices(renewal))
            register(renewal)
            true
        }

        assertTrue(renewed)
        assertEquals(every("new"), devices(dir))
    }

    @Test
    fun aFirstRenewalPutsEveryKeyInPlace() = runBlocking {
        val dir = File(folder.root, "aether")
        val renewal = File(folder.root, "aether-renewal")

        val renewed = AetherIdentityManager.renewAll(dir, renewal) {
            register(renewal)
            true
        }

        assertTrue(renewed)
        assertEquals(every("new"), devices(dir))
    }

    @Test
    fun aFailedFirstRenewalLeavesNoKeyBehind() = runBlocking {
        val dir = File(folder.root, "aether")
        val renewal = File(folder.root, "aether-renewal")

        val renewed = AetherIdentityManager.renewAll(dir, renewal) {
            register(renewal, listOf(AetherIdentityManager.MASQUE_FILE))
            false
        }

        assertFalse(renewed)
        assertEquals(every(null), devices(dir))
        assertFalse(renewal.exists())
    }

    @Test
    fun aRenewalLeavesEverythingButTheKeysAlone() = runBlocking {
        val dir = keysInUse()
        File(dir, "aether-wg-lastconn.toml").writeText("peer = \"162.159.192.1:2408\"")
        File(dir, "aether-tor").mkdirs()
        File(dir, "aether-tor/state").writeText("guards")
        val renewal = File(folder.root, "aether-renewal")

        AetherIdentityManager.renewAll(dir, renewal) {
            register(renewal)
            true
        }

        assertEquals(every("new"), devices(dir))
        assertEquals("peer = \"162.159.192.1:2408\"", File(dir, "aether-wg-lastconn.toml").readText())
        assertEquals("guards", File(dir, "aether-tor/state").readText())
    }
}
