package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

data class AetherIdentity(
    val deviceId: String,
    val ipv4: String,
    val ipv6: String,
)

data class AetherIdentityStatus(
    val protocol: AetherProtocol,
    val primary: AetherIdentity?,
    val secondary: AetherIdentity? = null,
)

object AetherIdentityManager {

    const val BASE_FILE = "aether.toml"
    const val MASQUE_FILE = "aether-masque.toml"
    const val MASQUE_INNER_FILE = "aether-masque-secondary.toml"
    const val WIREGUARD_FILE = "aether-wg.toml"
    const val WIREGUARD_INNER_FILE = "aether-wg-secondary.toml"

    /** The keys of every kind, which a renewal replaces all together. */
    internal val KEY_FILES = listOf(WIREGUARD_FILE, WIREGUARD_INNER_FILE, MASQUE_FILE, MASQUE_INNER_FILE)

    private const val WORK_DIR = "aether"

    /**
     * Where a renewal gathers its new keys. The keys in use stay in [WORK_DIR], untouched, until
     * every new key is here; only then does each new key take the place of the old one.
     */
    private const val RENEWAL_DIR = "aether-renewal"

    /**
     * Written into [RENEWAL_DIR] once every new key is there. From then on the new keys are the
     * keys: whatever stops the moving of them, [settle] finishes it.
     */
    internal const val READY_MARK = "ready"

    /**
     * Where the renewal before [RENEWAL_DIR] moved the keys in use while it registered new ones.
     * An app killed during such a renewal left it behind, holding keys nothing uses any more; it
     * goes once a renewal has replaced every key.
     */
    private const val PREVIOUS_DIR = "aether-previous"

    /** Four registrations and two MASQUE key enrollments, each of which the core may retry. */
    private const val RENEW_TIMEOUT_MS = 4 * 60_000L

    /** A renewal through Tor or Psiphon around the tunnel waits for the carrier to come up first, which takes what a scan may take. */
    private const val RENEW_THROUGH_CARRIER_TIMEOUT_MS = 10 * 60_000L

    private const val SOURCE = "aether-key"

    private val identityField = Regex("""^(device_id|ipv4|ipv6)\s*=\s*"([^"]*)"$""")
    private val multilineDelimiter = Regex("\"\"\"|'''")

    /** The core's last word in a registration, once every key it was asked for is saved. */
    private val keysRegistered = Regex("""identities ready: \S""")

    /** One renewal at a time: they share [RENEWAL_DIR]. */
    private val renewal = Mutex()

    fun workDir(context: Context): File = File(context.filesDir, WORK_DIR)

    private fun renewalDir(context: Context): File = File(context.filesDir, RENEWAL_DIR)

    suspend fun status(context: Context, protocol: AetherProtocol): AetherIdentityStatus =
        withContext(Dispatchers.IO) {
            settle(context)
            status(workDir(context), protocol)
        }

    /**
     * Registers a new key of every kind, the WireGuard and the MASQUE key and the inner hop key of
     * each, and puts them in place of the keys in use once all of them are there. Until then the
     * keys in use stay where they are, untouched, so a failure, a cancellation or the app being
     * killed leaves them in use. Returns the new keys of [profile]'s protocol, or null when the
     * keys in use stay.
     */
    suspend fun renew(
        context: Context,
        profile: ProfileItem,
        onOutput: (String) -> Unit,
    ): AetherIdentityStatus? = renewal.withLock {
        val workDir = workDir(context)
        val renewalDir = renewalDir(context)
        if (!renewAll(workDir, renewalDir) { registerAll(context, profile, renewalDir, onOutput) }) return@withLock null
        withContext(Dispatchers.IO) {
            if (!settle(workDir, renewalDir)) {
                LogUtil.w(AppConfig.TAG, "AetherIdentity: the new keys are ready but not all in place yet; the next core start moves the rest")
            }
            File(context.filesDir, PREVIOUS_DIR).deleteRecursively()
            status(workDir, AetherProtocol.fromString(profile.aetherProtocol))
        }
    }

    /** [settle] on the app's own folders; whatever reads the keys calls it first. */
    fun settle(context: Context): Boolean = settle(workDir(context), renewalDir(context))

    /**
     * Runs the core once to register every key into [renewalDir]: it registers them, through the
     * carrier around the tunnel if there is one, and ends without scanning or opening a tunnel; see
     * [AetherCoreManager.keyRenewalArguments]. The run is over at its word that the keys are saved.
     */
    private suspend fun registerAll(
        context: Context,
        profile: ProfileItem,
        renewalDir: File,
        onOutput: (String) -> Unit,
    ): Boolean {
        val port = withContext(Dispatchers.IO) { AetherCoreManager.scanPort(profile) }
        return AetherCoreManager.runUntil(
            context = context,
            arguments = AetherCoreManager.keyRenewalArguments(profile, port),
            timeoutMs = if (AetherCoreManager.reachesWarpThroughCarrier(profile)) RENEW_THROUGH_CARRIER_TIMEOUT_MS else RENEW_TIMEOUT_MS,
            source = SOURCE,
            onOutput = onOutput,
            keysDir = renewalDir,
        ) { line -> line.takeIf(::isRegistered) } != null
    }

    internal fun status(workDir: File, protocol: AetherProtocol): AetherIdentityStatus = when (protocol) {
        AetherProtocol.MASQUE -> AetherIdentityStatus(protocol, read(File(workDir, MASQUE_FILE)))
        AetherProtocol.WIREGUARD -> AetherIdentityStatus(protocol, read(File(workDir, WIREGUARD_FILE)))
        AetherProtocol.GOOL -> AetherIdentityStatus(
            protocol,
            read(File(workDir, WIREGUARD_FILE)),
            read(File(workDir, WIREGUARD_INNER_FILE)),
        )

        AetherProtocol.MIM -> AetherIdentityStatus(
            protocol,
            read(File(workDir, MASQUE_FILE)),
            read(File(workDir, MASQUE_INNER_FILE)),
        )
    }

    internal fun parse(text: String): AetherIdentity? {
        val fields = mutableMapOf<String, String>()
        var insideMultiline = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (!insideMultiline) {
                identityField.matchEntire(line)?.destructured?.let { (key, value) -> fields.putIfAbsent(key, value) }
            }
            if (multilineDelimiter.findAll(line).count() % 2 == 1) {
                insideMultiline = !insideMultiline
            }
        }
        val deviceId = fields["device_id"]?.takeIf { it.isNotBlank() } ?: return null
        return AetherIdentity(deviceId, fields["ipv4"].orEmpty(), fields["ipv6"].orEmpty())
    }

    /** The tunnels over MASQUE share the MASQUE key and the others the WireGuard key; a two-hop tunnel adds a second key of its kind. */
    fun sharesIdentity(first: AetherProtocol, second: AetherProtocol): Boolean =
        first.overMasque == second.overMasque

    internal fun isRegistered(line: String): Boolean = keysRegistered.containsMatchIn(line)

    /**
     * Gathers new keys in [renewalDir] through [register] and moves them over the keys in [workDir]
     * once every one of [KEY_FILES] is there and reads as a key. Nothing in [workDir] is touched
     * before that moment, so whatever stops a renewal earlier leaves the keys in use as they were;
     * a stop after it is finished by [settle]. True when the new keys took the place of the old ones.
     */
    internal suspend fun renewAll(
        workDir: File,
        renewalDir: File,
        register: suspend () -> Boolean,
    ): Boolean {
        val cleared = withContext(Dispatchers.IO) {
            // A renewal whose keys were all ready is finished first; what one stopped earlier left is no key anyone uses.
            settle(workDir, renewalDir) && renewalDir.deleteRecursively() && renewalDir.mkdirs()
        }
        if (!cleared) return false
        // A cancellation can be delivered as the result of a blocking step comes back, after the step
        // itself has run; whether the new keys were marked ready is therefore recorded inside that
        // step, and the finally block goes by the record rather than by a value the step returned.
        val ready = AtomicBoolean(false)
        try {
            if (register()) {
                withContext(Dispatchers.IO) { ready.set(isComplete(renewalDir) && markReady(renewalDir)) }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (ready.get()) settle(workDir, renewalDir) else renewalDir.deleteRecursively()
            }
        }
        return ready.get()
    }

    /**
     * Moves the new keys of a renewal whose keys were all ready over the keys in [workDir], one
     * after another, and then removes [renewalDir]. Without the ready mark there is nothing to move:
     * the new keys are incomplete, or a renewal is still gathering them, and the keys in use stay.
     * Another process may be finishing the same renewal at the same time; a key it moved first is
     * simply gone here. True when no renewal with ready keys waits any longer.
     */
    internal fun settle(workDir: File, renewalDir: File): Boolean {
        if (!File(renewalDir, READY_MARK).isFile) return true
        workDir.mkdirs()
        for (name in KEY_FILES) {
            val renewed = File(renewalDir, name)
            if (renewed.isFile && !moveOver(renewed, File(workDir, name))) return false
        }
        // The mark goes with the folder, after every key: until then it tells that keys are left to move.
        return renewalDir.deleteRecursively()
    }

    /**
     * Moves [source] over [target]; true once [source] is gone, moved by this call or by another
     * process finishing the same renewal. On Android a rename replaces the key in use in one step,
     * and a rename that fails leaves it in place. A Windows host, where the app's JVM tests may
     * run, will not rename onto an existing file, so there, and only there, the key in use is
     * removed first; the new key is complete and marked ready by then. Remove the fallback once
     * minSdk reaches 26, where Files.move with REPLACE_EXISTING and ATOMIC_MOVE replaces on both.
     */
    private fun moveOver(source: File, target: File): Boolean {
        if (source.renameTo(target) || !source.isFile) return true
        if (!onWindows) return false
        target.delete()
        return source.renameTo(target) || !source.isFile
    }

    private val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Whether every one of [KEY_FILES] is in [dir] and reads as a key. */
    internal fun isComplete(dir: File): Boolean = KEY_FILES.all { read(File(dir, it)) != null }

    private fun markReady(dir: File): Boolean = try {
        File(dir, READY_MARK).createNewFile()
    } catch (_: IOException) {
        false
    }

    private fun read(file: File): AetherIdentity? = try {
        if (file.isFile) parse(file.readText()) else null
    } catch (_: IOException) {
        null
    }
}
