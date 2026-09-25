package com.v2ray.ang.snispoof

import android.content.Context
import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.root.RootProcessRunner
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import java.io.File
import java.net.Socket
import java.security.MessageDigest

/**
 * Owns the SNI-Spoofing-Go root sidecar: a fake TLS ClientHello (SNI) injection proxy
 * bundled with the app and run as root beside the core.
 *
 * When enabled for the running profile, the sidecar listens on loopback and the generated
 * core config dials 127.0.0.1:<port> instead of the real server; the sidecar forwards to
 * the real server while injecting a decoy ClientHello first (wrong-seq desync, so the
 * server never sees it but on-path DPI does).
 *
 * Lifecycle: [startForRun] runs before the core config is built (the config rewrite in
 * [CoreOutboundBuilder] reads [loopbackPortFor]); [stop] kills the daemon on every stop
 * path. The daemon cleans its iptables/nfqueue rules on SIGTERM; SIGKILL is only a
 * last resort.
 */
object SniSpoofManager {

    /** User-facing failure; the message is shown as-is. */
    class SniSpoofException(message: String) : Exception(message)

    data class Session(val profile: ProfileItem, val port: Int, val pid: Int)

    @Volatile
    private var session: Session? = null

    /** TCP protocols the sidecar can front. It is a TCP proxy: UDP protocols (WireGuard, Hysteria2) are out. */
    fun isSupportedType(type: EConfigType): Boolean = type in setOf(
        EConfigType.VMESS,
        EConfigType.VLESS,
        EConfigType.TROJAN,
        EConfigType.SHADOWSOCKS,
        EConfigType.SOCKS,
        EConfigType.HTTP,
    )

    fun isEnabledFor(profile: ProfileItem): Boolean =
        profile.sniSpoofEnabled == true && isSupportedType(profile.configType)

    /**
     * Starts the sidecar for a run, or reuses the live session when the same profile is
     * already served (e.g. a core reload after a network handover).
     *
     * This performs bounded blocking work (one root probe, one daemon spawn, a short
     * listen poll) and must be called before the core config is built.
     *
     * @return the loopback port the core must dial.
     * @throws SniSpoofException with a user-facing message when the sidecar cannot start.
     */
    @Throws(SniSpoofException::class)
    fun startForRun(context: Context, profile: ProfileItem): Int {
        currentSession().let { current ->
            if (current != null && current.profile == profile && isPidAlive(current.pid)) {
                LogUtil.i(AppConfig.TAG, "SniSpoofManager: reusing live session on port ${current.port}")
                return current.port
            }
        }
        stop()

        if (!isEnabledFor(profile)) throw SniSpoofException("SNI spoofing is not enabled for this profile")
        if (!isArm64()) throw SniSpoofException("SNI spoofing needs an arm64 device")
        val server = profile.server.orEmpty()
        val serverPort = profile.serverPort.orEmpty()
        if (server.isBlank() || serverPort.isBlank()) {
            throw SniSpoofException("SNI spoofing: the server address is empty")
        }
        if (Utils.isPureIpAddress(server) && server.contains(":")) {
            throw SniSpoofException("SNI spoofing is IPv4-only")
        }
        if (!RootManager.isRootAvailable()) {
            throw SniSpoofException("SNI spoofing needs root access")
        }
        val bin = ensureBinary(context) ?: throw SniSpoofException("SNI spoofing: the sidecar binary is missing")
        val port = pickPort()
        val pid = launchDaemon(context, bin, profile, port)
            ?: throw SniSpoofException("SNI spoofing: the sidecar failed to start")
        if (!awaitListening(port, 5_000)) {
            killPid(pid)
            throw SniSpoofException("SNI spoofing: the sidecar did not start listening")
        }
        session = Session(profile.copy(), port, pid)
        LogUtil.i(AppConfig.TAG, "SniSpoofManager: sidecar running, pid=$pid port=$port")
        return port
    }

    /**
     * The loopback port the generated core config must dial for [profile], or null when no
     * live session serves exactly this profile. Called from the outbound builder.
     */
    fun loopbackPortFor(profile: ProfileItem): Int? {
        val s = currentSession() ?: return null
        return if (s.profile == profile) s.port else null
    }

    /**
     * Rewrites a built outbound to dial the sidecar instead of the real server.
     * No-op unless a live session serves [profileItem].
     */
    fun applyLoopbackRewrite(outbound: V2rayConfig.OutboundBean, profileItem: ProfileItem) {
        val port = loopbackPortFor(profileItem) ?: return
        val settings = outbound.settings ?: return
        settings.address = AppConfig.LOOPBACK
        settings.port = port
        LogUtil.i(
            AppConfig.TAG,
            "SniSpoofManager: ${outbound.protocol} outbound now dials ${AppConfig.LOOPBACK}:$port"
        )
    }

    /**
     * Stops the sidecar. Safe to call repeatedly and with no session.
     *
     * Fast and bounded (SIGTERM + a short wait): this runs on the service's main-thread
     * stop path. Anything that survives is reaped from the PID file on the next start.
     */
    fun stop() {
        val s = currentSession()
        session = null
        if (s != null) {
            killPid(s.pid, waitMs = 1_000)
            LogUtil.i(AppConfig.TAG, "SniSpoofManager: sidecar stopped")
        }
    }

    internal fun currentSession(): Session? = session

    // ---------------------------------------------------------- binary

    private fun isArm64(): Boolean =
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }

    /**
     * Extracts the bundled arm64 binary to the app's private dir (once, verified by SHA-256)
     * and makes it executable.
     */
    private fun ensureBinary(context: Context): File? {
        return try {
            val dir = File(context.filesDir, AppConfig.SNI_SPOOF_RUNTIME_DIR).apply { mkdirs() }
            val bin = File(dir, AppConfig.SNI_SPOOF_BIN_NAME)
            if (!bin.isFile || sha256Hex(bin) != AppConfig.SNI_SPOOF_ASSET_SHA256) {
                context.assets.open(AppConfig.SNI_SPOOF_ASSET_ARM64).use { input ->
                    bin.outputStream().use { output -> input.copyTo(output) }
                }
                LogUtil.i(AppConfig.TAG, "SniSpoofManager: sidecar binary extracted")
            }
            bin.setExecutable(true, true)
            if (bin.canExecute()) bin else null
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            LogUtil.e(AppConfig.TAG, "SniSpoofManager: binary setup failed", e)
            null
        }
    }

    private fun sha256Hex(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(8192)
                var n = input.read(buf)
                while (n > 0) {
                    digest.update(buf, 0, n)
                    n = input.read(buf)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "SniSpoofManager: sha256 failed: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------- daemon

    private fun pickPort(): Int =
        if (isPortFree(AppConfig.SNI_SPOOF_DEFAULT_PORT)) AppConfig.SNI_SPOOF_DEFAULT_PORT
        else Utils.findRandomFreePort()

    private fun isPortFree(port: Int): Boolean {
        return try {
            Socket(AppConfig.LOOPBACK, port).close()
            false
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Builds the exact argv for the sidecar. Pure: unit-tested.
     */
    internal fun buildArgs(profile: ProfileItem, port: Int): List<String> {
        val args = mutableListOf(
            "-listen", "${AppConfig.LOOPBACK}:$port",
            "-connect", "${profile.server.orEmpty()}:${profile.serverPort.orEmpty()}",
        )
        profile.sniSpoofFakeSni?.takeIf { it.isNotBlank() }?.let {
            args += listOf("-fake-sni", it.trim())
        }
        args += listOf("-utls", normalizeUtls(profile.sniSpoofUtls))
        args += listOf("-injector", normalizeInjector(profile.sniSpoofInjector))
        return args
    }

    internal fun normalizeUtls(value: String?): String {
        val v = value?.trim().orEmpty()
        return if (v.isEmpty()) AppConfig.SNI_SPOOF_DEFAULT_UTLS else v
    }

    internal fun normalizeInjector(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            "passive" -> "passive"
            else -> AppConfig.SNI_SPOOF_DEFAULT_INJECTOR
        }
    }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Spawns the daemon detached via su (nohup + &), records its PID, and returns it.
     */
    private fun launchDaemon(context: Context, bin: File, profile: ProfileItem, port: Int): Int? {
        return try {
            val dir = File(context.filesDir, AppConfig.SNI_SPOOF_RUNTIME_DIR)
            val pidFile = File(dir, AppConfig.SNI_SPOOF_PID_FILE)
            // A previous process death can orphan the daemon (it is detached via nohup); reap it
            // before spawning so its port and netfilter rules never leak into this run.
            reapStalePidFile(pidFile, bin.name)
            if (pidFile.exists()) pidFile.delete()

            val argv = buildArgs(profile, port).joinToString(" ") { shQuote(it) }
            val script = buildString {
                appendLine("BIN=${shQuote(bin.absolutePath)}")
                appendLine("PIDFILE=${shQuote(pidFile.absolutePath)}")
                appendLine("nohup \"\$BIN\" $argv >/dev/null 2>&1 &")
                appendLine("echo \$! > \"\$PIDFILE\"")
            }
            val scriptFile = File(dir, "start_sni_spoof.sh").apply {
                writeText(script)
                setExecutable(true, true)
            }
            val result = RootProcessRunner.run(
                listOf("su", "-c", "sh ${shQuote(scriptFile.absolutePath)}"),
                15_000
            )
            if (result.code != 0) {
                LogUtil.e(AppConfig.TAG, "SniSpoofManager: daemon spawn failed: ${result.output.trim()}")
                return null
            }
            val pid = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            if (pid == null || pid <= 0) {
                LogUtil.e(AppConfig.TAG, "SniSpoofManager: no PID recorded")
                return null
            }
            pid
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            LogUtil.e(AppConfig.TAG, "SniSpoofManager: daemon spawn threw", e)
            null
        }
    }

    private fun awaitListening(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            try {
                Socket(AppConfig.LOOPBACK, port).close()
                return true
            } catch (_: Exception) {
                // not up yet
            }
            try {
                Thread.sleep(200)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun isPidAlive(pid: Int): Boolean {
        val r = RootProcessRunner.run(listOf("su", "-c", "kill -0 $pid"), 5_000)
        return r.code == 0
    }

    /**
     * SIGTERM first: the sidecar removes its iptables/nfqueue rules on graceful shutdown.
     * SIGKILL only when it refuses to die within [waitMs] (its netfilter rules may leak then,
     * and the next start reaps the survivor from the PID file).
     */
    private fun killPid(pid: Int, waitMs: Long = 3_000) {
        try {
            RootProcessRunner.run(listOf("su", "-c", "kill $pid"), 5_000)
            val deadline = System.nanoTime() + waitMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (!isPidAlive(pid)) return
                try {
                    Thread.sleep(200)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (isPidAlive(pid)) {
                LogUtil.w(AppConfig.TAG, "SniSpoofManager: pid $pid ignored SIGTERM, SIGKILLing")
                RootProcessRunner.run(listOf("su", "-c", "kill -9 $pid"), 5_000)
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            LogUtil.e(AppConfig.TAG, "SniSpoofManager: kill failed", e)
        }
    }

    /**
     * Kills a daemon orphaned by a previous process death. The PID is only trusted when
     * /proc/<pid>/cmdline still names our binary, so a recycled PID can never hit an
     * unrelated process.
     */
    private fun reapStalePidFile(pidFile: File, binName: String) {
        val pid = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: return
        if (pid <= 0 || !isPidAlive(pid)) return
        val cmdline = RootProcessRunner.run(
            listOf("su", "-c", "cat /proc/$pid/cmdline"), 5_000
        ).output
        if (!cmdline.contains(binName)) {
            LogUtil.w(AppConfig.TAG, "SniSpoofManager: pid file holds foreign pid $pid, ignoring")
            return
        }
        LogUtil.w(AppConfig.TAG, "SniSpoofManager: reaping orphaned sidecar pid $pid")
        killPid(pid)
    }
}
