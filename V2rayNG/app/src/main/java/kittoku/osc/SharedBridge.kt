package kittoku.osc

// Vendored from kittoku/Open-SSTP-Client (MIT, (c) 2019 KOBAYASHI Ittoku),
// adapted for PattNG: parameters come from the selected profile, user-tunable
// behavior is fixed to safe defaults (MS-CHAPv2, IPv4 only, system TLS trust).

import android.net.VpnService
import kittoku.osc.terminal.IPTerminal
import kittoku.osc.terminal.SSLTerminal
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Socket
import java.util.UUID

internal const val AUTH_PROTOCOl_PAP = "PAP"
internal const val AUTH_PROTOCOL_MSCHAPv2 = "MSCHAPv2"
internal const val AUTH_PROTOCOL_EAP_MSCHAPv2 = "EAP-MSCHAPv2"

internal enum class Where {
    CERT,
    CERT_PATH,
    SSL,
    PROXY,
    SSTP_DATA,
    SSTP_CONTROL,
    SSTP_REQUEST,
    SSTP_HASH,
    PPP,
    PAP,
    CHAP,
    MSCHAPV2,
    EAP,
    LCP,
    LCP_MRU,
    LCP_AUTH,
    IPCP,
    IPCP_IP,
    IPV6CP,
    IPV6CP_IDENTIFIER,
    IP,
    IPv4,
    IPv6,
    ROUTE,
    INCOMING,
    OUTGOING,
}

internal data class ControlMessage(
    val from: Where,
    val result: Result,
    val supplement: String? = null
)

internal enum class Result {
    PROCEEDED,

    // common errors
    ERR_TIMEOUT,
    ERR_COUNT_EXHAUSTED,
    ERR_UNKNOWN_TYPE, // the data cannot be parsed
    ERR_UNEXPECTED_MESSAGE, // the data can be parsed, but it's received in the wrong time
    ERR_PARSING_FAILED,
    ERR_VERIFICATION_FAILED,

    // for SSTP
    ERR_NEGATIVE_ACKNOWLEDGED,
    ERR_ABORT_REQUESTED,
    ERR_DISCONNECT_REQUESTED,

    // for PPP
    ERR_TERMINATE_REQUESTED,
    ERR_PROTOCOL_REJECTED,
    ERR_CODE_REJECTED,
    ERR_AUTHENTICATION_FAILED,
    ERR_ADDRESS_REJECTED,
    ERR_OPTION_REJECTED,

    // for IP
    ERR_INVALID_ADDRESS,

    // for INCOMING
    ERR_INVALID_PACKET_SIZE,
}

/** Lifecycle callbacks the PattNG engine implements. */
internal interface SstpEvents {
    fun onError(header: String, detail: String?)
    fun onConnected()
    fun onDisconnected()
}

/**
 * Carries the SSTP session: profile parameters, coroutine plumbing, and the
 * negotiated PPP state. Property names match the original so the vendored
 * protocol units keep working unchanged.
 */
internal class SharedBridge(
    val vpnService: VpnService?,
    val scope: CoroutineScope,
    val host: String,
    val port: Int,
    username: String,
    password: String,
    val events: SstpEvents,
) {
    // Only used when a TUN interface is established; test mode never touches it.
    val builder: VpnService.Builder get() = vpnService!!.Builder()
    lateinit var handler: CoroutineExceptionHandler

    val controlMailbox = Channel<ControlMessage>(Channel.BUFFERED)

    var sslTerminal: SSLTerminal? = null
    var ipTerminal: IPTerminal? = null

    val HOME_USERNAME = username
    val HOME_PASSWORD = password
    val PPP_MRU = 1500
    val PPP_MTU = 1500
    val PPP_AUTH_PROTOCOLS = setOf(AUTH_PROTOCOL_MSCHAPv2)
    val PPP_IPv4_ENABLED = true
    val PPP_IPv6_ENABLED = false

    var hlak: ByteArray? = null
    val nonce = ByteArray(32)
    val guid = UUID.randomUUID().toString()
    var hashProtocol: Byte = 0

    private val mutex = Mutex()
    private var frameID = -1

    var currentMRU = PPP_MRU
    var currentAuth = ""
    val currentIPv4 = ByteArray(4)
    val currentIPv6 = ByteArray(8)
    val currentProposedDNS = ByteArray(4)

    fun isEnabled(authProtocol: String): Boolean {
        return authProtocol in PPP_AUTH_PROTOCOLS
    }

    /**
     * When true, the session negotiates the tunnel but never establishes a TUN
     * interface: used for connectivity tests that must not disturb the device.
     */
    var testMode: Boolean = false

    fun attachSSLTerminal() {
        sslTerminal = SSLTerminal(this)
    }

    fun attachIPTerminal() {
        ipTerminal = IPTerminal(this)
    }

    suspend fun allocateNewFrameID(): Byte {
        mutex.withLock {
            frameID += 1
            return frameID.toByte()
        }
    }

    /**
     * Marks the SSTP control socket as bypassing the VPN tunnel (prevents routing loops).
     * No-op when no VPN service is attached (test mode has no TUN to loop with).
     */
    fun protect(socket: Socket): Boolean = vpnService?.protect(socket) ?: true
}
