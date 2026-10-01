package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherCoreTest {

    private fun profile(listen: String? = null, block: ProfileItem.() -> Unit = {}) =
        ProfileItem.create(EConfigType.AETHER).apply {
            aetherProtocol = AetherProtocol.WIREGUARD.type
            aetherListenPort = listen
            block()
        }

    private val pinned = profile(listen = "20808") { server = "188.114.96.77"; serverPort = "443" }

    private fun valueAfter(arguments: List<String>, flag: String): String? =
        arguments.indexOf(flag).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }

    @Test
    fun theCoreOfAProfileIsItsSettingsOnItsPortWithoutALogLevel() {
        val core = AetherCore.of(pinned)
        // Obfuscation left automatic is the core's own choice, so the arguments say nothing about it.
        assertEquals(
            listOf(
                "--bind", "127.0.0.1:20808", "--protocol", "wg", "--scan", "balanced", "--ip", "v4",
                "--peer", "188.114.96.77:443", "--quick-reconnect",
            ),
            core.arguments
        )
        assertEquals(20808, core.port)
        assertEquals(AetherProtocol.WIREGUARD, core.protocol)
        assertEquals(AetherCoreManager.socksPort, AetherCore.of(profile()).port)
    }

    @Test
    fun theCommandOfAProfileReadsBackAsTheSameCore() {
        val core = AetherCore.of(pinned)
        assertEquals(
            "aether --bind 127.0.0.1:20808 --protocol wg --scan balanced --ip v4 --peer 188.114.96.77:443 --quick-reconnect",
            core.command
        )
        assertEquals(core, AetherCore.ofCommand(core.command))

        val gool = AetherCore.of(profile { aetherProtocol = AetherProtocol.GOOL.type; aetherWiwOuter = "162.159.192.1:2408" })
        assertEquals(gool, AetherCore.ofCommand(gool.command))
        assertEquals(AetherProtocol.GOOL, AetherCore.ofCommand(gool.command)!!.protocol)

        val mim = AetherCore.of(profile { aetherProtocol = AetherProtocol.MIM.type; aetherWiwInner = "188.114.96.1:443" })
        assertEquals(mim, AetherCore.ofCommand(mim.command))
        assertEquals(AetherProtocol.MIM, AetherCore.ofCommand(mim.command)!!.protocol)
    }

    @Test
    fun aCommandIsReadAsWritten() {
        // Whatever the words mean is for the core to say; the app reads the listener and the protocol.
        val core = AetherCore.ofCommand("aether --gool --scan balanced --bind 127.0.0.1:20808 --dns 1.1.1.1")!!
        assertEquals(listOf("--gool", "--scan", "balanced", "--bind", "127.0.0.1:20808", "--dns", "1.1.1.1"), core.arguments)
        assertEquals(20808, core.port)
        assertEquals(AetherProtocol.GOOL, core.protocol)
    }

    @Test
    fun theProgramNameInFrontIsDroppedWhateverItIs() {
        val arguments = listOf("--wg", "--bind", "127.0.0.1:10819")
        assertEquals(arguments, AetherCore.ofCommand("aether --wg --bind 127.0.0.1:10819")!!.arguments)
        assertEquals(arguments, AetherCore.ofCommand("/data/app/lib/libaether.so --wg --bind 127.0.0.1:10819")!!.arguments)
        assertEquals(arguments, AetherCore.ofCommand("--wg --bind 127.0.0.1:10819")!!.arguments)
        assertEquals(arguments, AetherCore.ofCommand("  aether   --wg\t--bind 127.0.0.1:10819\n")!!.arguments)
    }

    @Test
    fun aCommandWithoutABindListensOnTheDefaultPortOfTheApp() {
        // The core's own default is another port, which no outbound of the app dials.
        val core = AetherCore.ofCommand("aether --wg --turbo")!!
        assertEquals(listOf("--wg", "--turbo", "--bind", "127.0.0.1:10819"), core.arguments)
        assertEquals(AetherCoreManager.socksPort, core.port)
    }

    @Test
    fun aCommandThatNamesNothingTheAppCanRunIsNoCore() {
        assertNull(AetherCore.ofCommand(""))
        assertNull(AetherCore.ofCommand("   "))
        assertNull(AetherCore.ofCommand("aether"))
        // A listener whose port cannot be read would otherwise be replaced without a word.
        assertNull(AetherCore.ofCommand("aether --wg --bind 10819"))
        assertNull(AetherCore.ofCommand("aether --wg --bind"))
    }

    @Test
    fun quotesKeepAWordTogether() {
        val bridge = "obfs4 1.2.3.4:443 FINGERPRINT cert=abc iat-mode=0"
        val core = AetherCore.ofCommand("aether --tor-only --tor-bridge \"$bridge\" --bind 127.0.0.1:10819")!!
        assertEquals(listOf("--tor-only", "--tor-bridge", bridge, "--bind", "127.0.0.1:10819"), core.arguments)
        assertEquals("aether --tor-only --tor-bridge \"$bridge\" --bind 127.0.0.1:10819", core.command)

        assertEquals(listOf("--x", "a b"), AetherCore.words("--x 'a b'"))
        assertEquals(listOf("--x", ""), AetherCore.words("--x \"\""))
        assertEquals(listOf("--x", "a b"), AetherCore.words("--x \"a b"))
        assertEquals(listOf("--x", "ab"), AetherCore.words("--x a\"\"b"))
    }

    @Test
    fun aCoreIsMovedToAnotherPortForATest() {
        val core = AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808 --scan turbo")!!

        val moved = core.on(41234)

        assertEquals(listOf("--wg", "--scan", "turbo", "--bind", "127.0.0.1:41234"), moved.arguments)
        assertEquals(41234, moved.port)
        assertNotEquals(core, moved)
        // Still the same tunnel: a process started this way runs the core, whatever it logs.
        assertTrue(core.runsAs(moved.arguments))
        assertTrue(core.runsAs(moved.arguments + listOf("--log-level", "debug")))
        assertFalse(core.runsAs(AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808 --scan thorough")!!.arguments))
        assertFalse(core.runsAs(emptyList()))
    }

    @Test
    fun aProfileWithACommandOfItsOwnRunsThatCommand() {
        val core = AetherCore.of(pinned.copy(aetherCommand = "aether --wg --dns 1.1.1.1 --bind 127.0.0.1:20808"))
        assertEquals(listOf("--wg", "--dns", "1.1.1.1", "--bind", "127.0.0.1:20808"), core.arguments)
        assertEquals(20808, core.port)
        // A command the app cannot read is left aside for the settings; the editor refuses to store one.
        assertEquals(AetherCore.of(pinned), AetherCore.of(pinned.copy(aetherCommand = "aether")))
        assertEquals(AetherCore.of(pinned), AetherCore.of(pinned.copy(aetherCommand = "   ")))
    }

    @Test
    fun withPsiphonInsideTheTunnelTheAppDialsPsiphon() {
        val chain = AetherCore.of(pinned.copy(aetherPsiphon = "chain"))
        assertEquals(20808, chain.port)
        assertEquals(listOf(20809, 20808), chain.ports)

        val moved = chain.on(41234)
        assertEquals(41234, moved.port)
        assertEquals("127.0.0.1:41235", valueAfter(moved.arguments, "--bind"))
        assertEquals("127.0.0.1:41234", valueAfter(moved.arguments, "--psiphon-bind"))
        assertTrue(chain.runsAs(moved.arguments))

        // A hand-written command with Psiphon inside gets the app's port for Psiphon when it names none, and keeps its own otherwise.
        assertEquals(listOf("--psiphon", "--wg", "--psiphon-bind", "127.0.0.1:10819"), AetherCore.ofCommand("aether --psiphon --wg")!!.arguments)
        assertEquals(1821, AetherCore.ofCommand("aether --psiphon --bind 127.0.0.1:10819 --psiphon-bind 127.0.0.1:1821")!!.port)
        assertNull(AetherCore.ofCommand("aether --psiphon --psiphon-bind 1821"))
    }

    @Test
    fun withPsiphonAroundTheTunnelTheAppDialsTheTunnel() {
        val reverse = AetherCore.of(pinned.copy(aetherProtocol = "masque", aetherPsiphon = "reverse"))
        assertEquals(20808, reverse.port)
        assertEquals(listOf(20808), reverse.ports)
        assertEquals(41234, reverse.on(41234).port)
        assertEquals("127.0.0.1:0", valueAfter(reverse.on(41234).arguments, "--psiphon-bind"))

        val only = AetherCore.of(pinned.copy(aetherPsiphon = "only"))
        assertEquals(20808, only.port)
        assertEquals(listOf(20808), only.ports)
    }

    @Test
    fun withTorInsideTheTunnelTheAppDialsTor() {
        val chain = AetherCore.of(pinned.copy(aetherTor = "chain"))
        assertEquals(20808, chain.port)
        assertEquals(listOf(20809, 20808), chain.ports)

        val moved = chain.on(41234)
        assertEquals(41234, moved.port)
        assertEquals("127.0.0.1:41234", valueAfter(moved.arguments, "--tor-bind"))
        assertEquals("127.0.0.1:41235", valueAfter(moved.arguments, "--bind"))
        assertTrue(chain.runsAs(moved.arguments))

        // A hand-written command with Tor inside gets the app's port for Tor when it names none, and keeps its own otherwise.
        assertEquals(listOf("--tor", "--wg", "--tor-bind", "127.0.0.1:10819"), AetherCore.ofCommand("aether --tor --wg")!!.arguments)
        assertEquals(1820, AetherCore.ofCommand("aether --tor --bind 127.0.0.1:10819 --tor-bind 127.0.0.1:1820")!!.port)
    }

    @Test
    fun withTorAroundTheTunnelTorsOwnListenerFollowsTheTunnel() {
        val reverse = AetherCore.of(pinned.copy(aetherProtocol = "masque", aetherTor = "reverse"))
        assertEquals(20808, reverse.port)
        assertEquals(listOf(20808, 20809), reverse.ports)
        val moved = reverse.on(41234)
        assertEquals("127.0.0.1:41234", valueAfter(moved.arguments, "--bind"))
        assertEquals("127.0.0.1:41235", valueAfter(moved.arguments, "--tor-bind"))

        // Nested carriers move together, in the order the profile hands the ports out; Psiphon's ephemeral port stays.
        val nested = AetherCore.of(pinned.copy(aetherProtocol = "masque", aetherPsiphon = "chain", aetherTor = "reverse"))
        assertEquals(listOf(20809, 20810, 20808), nested.ports)
        val movedNested = nested.on(41234)
        assertEquals(41234, movedNested.port)
        assertEquals("127.0.0.1:41234", valueAfter(movedNested.arguments, "--psiphon-bind"))
        assertEquals("127.0.0.1:41235", valueAfter(movedNested.arguments, "--bind"))
        assertEquals("127.0.0.1:41236", valueAfter(movedNested.arguments, "--tor-bind"))
        assertTrue(nested.runsAs(movedNested.arguments))
        val onThatPort = AetherCore.of(pinned.copy(aetherProtocol = "masque", aetherPsiphon = "chain", aetherTor = "reverse", aetherListenPort = "41234"))
        assertTrue(nested.runsAs(onThatPort.arguments))
        assertEquals(movedNested.ports.sorted(), onThatPort.ports.sorted())

        val torInside = AetherCore.of(pinned.copy(aetherProtocol = "masque", aetherPsiphon = "reverse", aetherTor = "chain")).on(41234)
        assertEquals("127.0.0.1:41234", valueAfter(torInside.arguments, "--tor-bind"))
        assertEquals("127.0.0.1:41235", valueAfter(torInside.arguments, "--bind"))
        assertEquals("127.0.0.1:0", valueAfter(torInside.arguments, "--psiphon-bind"))
    }

    @Test
    fun aCoreNamesItsTunnelFromTheOutsideIn() {
        assertEquals(listOf("WIREGUARD"), AetherCore.of(pinned).path)
        assertEquals(listOf("WIREGUARD", "PSIPHON"), AetherCore.of(pinned.copy(aetherPsiphon = "chain")).path)
        assertEquals(listOf("PSIPHON"), AetherCore.of(pinned.copy(aetherPsiphon = "only")).path)
        assertEquals(listOf("TOR", "MASQUE"), AetherCore.of(pinned.copy(aetherProtocol = "masque", aetherTor = "reverse")).path)
        assertEquals(listOf("GOOL", "TOR"), AetherCore.ofCommand("aether --gool --tor")!!.path)
    }

    @Test
    fun theSameArgumentsAreTheSameCore() {
        assertEquals(AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808"), AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808"))
        assertNotEquals(AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808"), AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20809"))
        assertNotEquals(AetherCore.ofCommand("aether --wg --bind 127.0.0.1:20808"), AetherCore.ofCommand("aether --bind 127.0.0.1:20808 --wg"))
    }

    @Test
    fun aCoreDialsOutThroughXrayAndStaysTheSameTunnel() {
        val core = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --protocol wg --scan balanced")!!
        val routed = core.through(10821)
        assertFalse(core.hasUpstream)
        assertTrue(routed.hasUpstream)
        assertEquals("socks5://127.0.0.1:10821", valueAfter(routed.arguments, "--upstream"))
        // The session's core is the profile's tunnel still: a process running one runs the other.
        assertTrue(core.runsAs(routed.arguments))
        assertTrue(routed.runsAs(core.arguments))
        assertEquals(core.ports, routed.ports)

        // A command that names an upstream of its own keeps it.
        val own = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --upstream socks5://127.0.0.1:1080")!!
        assertTrue(own.hasUpstream)
        assertEquals(own, own.through(10821))
    }

    @Test
    fun theInboundTheCoreDialsOutThroughIsTwoAboveItsPortUnlessThatIsTaken() {
        val plain = AetherCore.ofCommand("aether --bind 127.0.0.1:10819")!!
        assertEquals(10821, plain.exitPort { false })
        assertEquals(10823, plain.exitPort { it == 10821 || it == 10822 })

        // Psiphon inside the tunnel and Tor around it listen on the port, the one above and the one above that.
        val three = AetherCore.ofCommand(
            "aether --bind 127.0.0.1:10820 --psiphon --psiphon-bind 127.0.0.1:10819 --tor-reverse --tor-bind 127.0.0.1:10821"
        )!!
        assertEquals(10819, three.port)
        assertEquals(10822, three.exitPort { false })

        // At the top of the port range it looks below the port instead.
        assertEquals(1024, AetherCore.ofCommand("aether --bind 127.0.0.1:65535")!!.exitPort { false })
    }
}
