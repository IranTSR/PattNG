package kittoku.osc.terminal

import android.os.ParcelFileDescriptor
import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kittoku.osc.extension.toHexByteArray
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer


internal class IPTerminal(private val bridge: SharedBridge) {
    private var fd: ParcelFileDescriptor? = null

    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    internal suspend fun initialize() {
        if (bridge.PPP_IPv4_ENABLED) {
            if (bridge.currentIPv4.contentEquals(ByteArray(4))) {
                bridge.controlMailbox.send(ControlMessage(Where.IPv4, Result.ERR_INVALID_ADDRESS))
                return
            }

            InetAddress.getByAddress(bridge.currentIPv4).also {
                bridge.builder.addAddress(it, 32)
            }

            if (!bridge.currentProposedDNS.contentEquals(ByteArray(4))) {
                InetAddress.getByAddress(bridge.currentProposedDNS).also {
                    bridge.builder.addDnsServer(it)
                }
            }

            bridge.builder.addRoute("0.0.0.0", 0)
        }

        if (bridge.PPP_IPv6_ENABLED) {
            if (bridge.currentIPv6.contentEquals(ByteArray(8))) {
                bridge.controlMailbox.send(ControlMessage(Where.IPv6, Result.ERR_INVALID_ADDRESS))
                return
            }

            ByteArray(16).also { // for link local addresses
                "FE80".toHexByteArray().copyInto(it)
                ByteArray(6).copyInto(it, destinationOffset = 2)
                bridge.currentIPv6.copyInto(it, destinationOffset = 8)
                bridge.builder.addAddress(InetAddress.getByAddress(it), 64)
            }

            setIPv6BasedRouting()
        }

        bridge.builder.setMtu(bridge.PPP_MTU)
        bridge.builder.setBlocking(true)

        fd = bridge.builder.establish()!!.also {
            inputStream = FileInputStream(it.fileDescriptor)
            outputStream = FileOutputStream(it.fileDescriptor)
        }

        bridge.controlMailbox.send(ControlMessage(Where.IP, Result.PROCEEDED))
    }

    private fun setIPv6BasedRouting() {
        bridge.builder.addRoute("::", 0)
    }

    internal fun writePacket(start: Int, size: Int, buffer: ByteBuffer) {
        // nothing will be written until initialized
        // the position won't be changed
        outputStream?.write(buffer.array(), start, size)
    }

    internal fun readPacket(buffer: ByteBuffer) {
        buffer.clear()
        buffer.position(inputStream?.read(buffer.array(), 0, bridge.PPP_MTU) ?: buffer.position())
        buffer.flip()
    }

    internal fun close() {
        fd?.close()
    }

    internal fun parcelFd(): ParcelFileDescriptor? = fd
}
