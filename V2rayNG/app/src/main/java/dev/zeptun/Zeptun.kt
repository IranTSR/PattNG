package dev.zeptun

/**
 * JNI bridge to libzeptun-jni.so (which links libzeptun.so).
 *
 * The native side registers these methods for exactly this class name
 * ("dev/zeptun/Zeptun") in JNI_OnLoad; renaming the class or the methods, or
 * dropping any of the four registered natives, breaks the binding
 * (RegisterNatives fails and the load throws UnsatisfiedLinkError). The
 * [service] argument of [nativeStart] must expose `protect(int): Boolean`
 * (android.net.VpnService does); the engine calls it for its upstream sockets
 * so they don't loop back into the tunnel.
 */
object Zeptun {
    const val OK = 0
    const val ERR_ALREADY_RUNNING = -7

    /**
     * @param service the VpnService owning the TUN interface.
     * @param fd the TUN file descriptor from VpnService.Builder.establish().
     * @param config TOML configuration text; see ZeptunConfig.
     * @return 0 ([OK]) on success, otherwise a ZEPTUN_ERR_* code.
     */
    @JvmStatic
    external fun nativeStart(service: Any, fd: Int, config: String): Int

    @JvmStatic
    external fun nativeStop()

    /** Engine version string, e.g. from zeptun_version_string(). */
    @JvmStatic
    external fun nativeVersion(): String

    /**
     * Reads one engine statistics counter by index; -1 when the engine is not
     * running or the index is out of range.
     */
    @JvmStatic
    external fun nativeCounter(index: Int): Long

    init {
        System.loadLibrary("zeptun-jni")
    }
}
