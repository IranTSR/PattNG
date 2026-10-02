package com.v2ray.ang.core

import com.v2ray.ang.enums.AetherFingerprint
import com.v2ray.ang.enums.AetherKeyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AetherKeysTest {

    private fun valueAfter(arguments: List<String>, flag: String): String? =
        arguments.lastIndexOf(flag).takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }

    /** The number of each TLS 1.2 suite by the name BoringSSL gives it, for the ones the fingerprints name. */
    private val suiteIds = mapOf(
        "ECDHE-ECDSA-AES128-GCM-SHA256" to 0xc02b,
        "ECDHE-RSA-AES128-GCM-SHA256" to 0xc02f,
        "ECDHE-ECDSA-AES256-GCM-SHA384" to 0xc02c,
        "ECDHE-RSA-AES256-GCM-SHA384" to 0xc030,
        "ECDHE-ECDSA-CHACHA20-POLY1305" to 0xcca9,
        "ECDHE-RSA-CHACHA20-POLY1305" to 0xcca8,
        "ECDHE-RSA-AES128-SHA" to 0xc013,
        "ECDHE-RSA-AES256-SHA" to 0xc014,
        "ECDHE-RSA-AES128-SHA256" to 0xc027,
        "AES128-GCM-SHA256" to 0x009c,
        "AES256-GCM-SHA384" to 0x009d,
        "AES128-SHA" to 0x002f,
        "AES256-SHA" to 0x0035,
    )

    /** The TLS 1.2 suites of a ClientHello's cipher_suites in hex, GREASE and the TLS 1.3 suites left out. */
    private fun tls12Of(captured: String): List<Int> =
        captured.chunked(4).map { it.toInt(16) }.filter { it and 0x0f0f != 0x0a0a && it !in 0x1301..0x1305 }

    private fun suitesOf(fingerprint: AetherFingerprint): List<Int> = fingerprint.ciphers.split(':').map(suiteIds::getValue)

    @Test
    fun eachFingerprintOffersTheTls12SuitesOfItsCapturedClientHello() {
        // The ClientHellos captured with Wireshark.
        assertEquals(tls12Of("eaea130113021303c02bc02fc02cc030cca9cca8c013c014009c009d002f0035"), suitesOf(AetherFingerprint.CHROME))
        assertEquals(tls12Of("130113031302c02bc02fcca9cca8c02cc030c013c014009c009d002f0035"), suitesOf(AetherFingerprint.FIREFOX))
        // Python's, without the suites BoringSSL does not have: no DHE, no CBC with SHA-384, no ECDSA CBC with SHA-256.
        val missing = listOf(0xc024, 0xc028, 0xc023, 0x009f, 0x009e, 0x006b, 0x0067)
        assertEquals(
            tls12Of("130213031301c02cc030c02bc02fcca9cca8c024c028c023c027009f009e006b0067") - missing.toSet(),
            suitesOf(AetherFingerprint.PYTHON)
        )
    }

    @Test
    fun onlyChromeSendsGrease() {
        assertTrue(AetherFingerprint.CHROME.grease)
        assertFalse(AetherFingerprint.FIREFOX.grease)
        assertFalse(AetherFingerprint.PYTHON.grease)

        for (fingerprint in AetherFingerprint.entries) {
            val arguments = AetherKeys.arguments(AetherKeysSettings(fingerprint = fingerprint))
            assertEquals(fingerprint.ciphers, valueAfter(arguments, "--tls-ciphers"))
            assertEquals(!fingerprint.grease, "--disable-grease" in arguments)
        }
    }

    @Test
    fun theDefaultsGetEveryKeyFromTheApisOwnAddressWithChromesSuites() {
        assertEquals(
            listOf(
                "--register", "all",
                "--enroll-address", "api.cloudflareclient.com",
                "--tls-ciphers", AetherFingerprint.CHROME.ciphers,
            ),
            AetherKeys.arguments(AetherKeysSettings())
        )
        assertEquals("aether --register all --enroll-address api.cloudflareclient.com --tls-ciphers ${AetherFingerprint.CHROME.ciphers}", AetherKeys.builtCommand(AetherKeysSettings()))
        // A plain exit-node.
        assertEquals(AetherExit(), AetherKeysSettings().exit)
    }

    @Test
    fun eachKindRegistersItsKeysAndReadsBack() {
        for (kind in AetherKeyKind.entries) {
            val arguments = AetherKeys.arguments(AetherKeysSettings(kind = kind))
            assertEquals(listOf("--register", kind.type), arguments.take(2))
            assertEquals(kind, AetherKeys.kindOf(arguments))
        }
    }

    @Test
    fun theKindIsReadAsTheCoreReadsIt() {
        assertEquals(AetherKeyKind.WIREGUARD, AetherKeys.kindOf(listOf("--register", "WireGuard")))
        assertEquals(AetherKeyKind.WIREGUARD, AetherKeys.kindOf(listOf("--register", "warp")))
        assertEquals(AetherKeyKind.GOOL, AetherKeys.kindOf(listOf("--register", "warp-in-warp")))
        assertEquals(AetherKeyKind.GOOL, AetherKeys.kindOf(listOf("--register", " wiw ")))
        assertEquals(AetherKeyKind.MIM, AetherKeys.kindOf(listOf("--register", "masque-in-masque")))
        // The last one counts.
        assertEquals(AetherKeyKind.MASQUE, AetherKeys.kindOf(listOf("--register", "all", "--register", "masque")))
        assertNull(AetherKeys.kindOf(listOf("--register", "everything")))
        assertNull(AetherKeys.kindOf(listOf("--register")))
        assertNull(AetherKeys.kindOf(listOf("--protocol", "wg")))
    }

    @Test
    fun echAsksForTheKeyWhereTheSettingsSayAndKeepsTheDefaultsForBlanks() {
        val ech = AetherKeys.arguments(AetherKeysSettings(ech = true, echDns = "https://1.1.1.1/dns-query", echDomain = " example.com "))
        assertEquals("auto", valueAfter(ech, "--ech"))
        assertEquals("https://1.1.1.1/dns-query", valueAfter(ech, "--ech-dns"))
        assertEquals("example.com", valueAfter(ech, "--ech-domain"))

        val defaults = AetherKeys.arguments(AetherKeysSettings(ech = true, echDns = " ", echDomain = ""))
        assertEquals("udp://1.1.1.1", valueAfter(defaults, "--ech-dns"))
        assertEquals("cloudflare-ech.com", valueAfter(defaults, "--ech-domain"))

        // Off, nothing about ECH goes along, whatever the fields hold.
        assertFalse(AetherKeys.arguments(AetherKeysSettings(echDns = "tcp://8.8.8.8")).any { it.startsWith("--ech") })
    }

    @Test
    fun aBlankRequestAddressLeavesTheCoresOwn() {
        assertFalse("--enroll-address" in AetherKeys.arguments(AetherKeysSettings(enrollAddress = "  ")))
        assertEquals("188.114.97.6:443", valueAfter(AetherKeys.arguments(AetherKeysSettings(enrollAddress = " 188.114.97.6:443 ")), "--enroll-address"))
    }

    @Test
    fun theRequestAddressIsWhatTheCoreTakes() {
        for (address in listOf(
            "api.cloudflareclient.com", "api.cloudflareclient.com:443", "api.cloudflareclient.com.", "188.114.97.6",
            "188.114.97.6:2053", "2606:4700::1", "[2606:4700::1]", "[2606:4700::1]:8443", "[188.114.97.6]:443",
        )) {
            assertTrue(address, AetherKeys.isEnrollAddress(address))
        }
        for (address in listOf(
            "", "-x", "--upstream", "a b", "188.114.97.6:0", "188.114.97.6:65536", "188.114.97.6:", ":443",
            "188.114.97.6:4a3", "2606:4700::1:", "[2606:4700::1", "[2606:4700::1]:", "https://api.cloudflareclient.com",
            "api.-cloudflareclient.com",
            // An IPv6 address takes brackets before a port.
            "1:2:3:4:5:6:7:8:443",
        )) {
            assertFalse(address, AetherKeys.isEnrollAddress(address))
        }
    }

    @Test
    fun eachProblemIsFoundAndNothingElse() {
        val fine = AetherKeysSettings()
        assertNull(AetherKeys.problem(fine))
        assertEquals(AetherKeys.Problem.INVALID_ENROLL_ADDRESS, AetherKeys.problem(fine.copy(enrollAddress = "-x")))
        assertNull(AetherKeys.problem(fine.copy(enrollAddress = "")))
        // The ECH fields count while ECH is on.
        assertNull(AetherKeys.problem(fine.copy(echDns = "dns.google")))
        assertEquals(AetherKeys.Problem.INVALID_ECH_DNS, AetherKeys.problem(fine.copy(ech = true, echDns = "dns.google")))
        assertEquals(AetherKeys.Problem.INVALID_ECH_DOMAIN, AetherKeys.problem(fine.copy(ech = true, echDomain = "-x")))
        assertNull(AetherKeys.problem(fine.copy(ech = true, echDns = "", echDomain = "")))
        assertEquals(AetherKeys.Problem.INVALID_FINAL_MASK, AetherKeys.problem(fine.copy(finalMask = "{not json")))
        assertNull(AetherKeys.problem(fine.copy(finalMask = """{"tcp": []}""", dialMode = "anything")))
    }

    @Test
    fun aCommandWrittenByHandRunsAsWritten() {
        val settings = AetherKeysSettings(enrollAddress = "-x", command = "aether --register wg --tor-reverse")
        assertTrue(AetherKeys.isCustom(settings))
        assertEquals(listOf("--register", "wg", "--tor-reverse"), AetherKeys.runArguments(settings))
        // The settings it replaces do not count; what it registers does, and so does the exit-node it dials out through.
        assertNull(AetherKeys.problem(settings))
        assertEquals(AetherKeys.Problem.INVALID_COMMAND, AetherKeys.problem(settings.copy(command = "aether --protocol wg")))
        assertEquals(AetherKeys.Problem.INVALID_COMMAND, AetherKeys.problem(settings.copy(command = "--register every")))
        assertEquals(AetherKeys.Problem.INVALID_FINAL_MASK, AetherKeys.problem(settings.copy(finalMask = "[")))

        // The command the settings give, or none, is no command of its own.
        val built = AetherKeysSettings(kind = AetherKeyKind.MIM)
        assertFalse(AetherKeys.isCustom(built.copy(command = " " + AetherKeys.builtCommand(built) + " ")))
        assertFalse(AetherKeys.isCustom(built.copy(command = "")))
        assertEquals(AetherKeys.arguments(built), AetherKeys.runArguments(built.copy(command = AetherKeys.builtCommand(built))))
    }

    @Test
    fun theExitNodeTakesTheFinalMaskAndTheDialMode() {
        val settings = AetherKeysSettings(finalMask = """{"tcp": []}""", dialMode = "ForceIP")
        assertEquals(AetherExit("""{"tcp": []}""", "ForceIP"), settings.exit)
        assertEquals(AetherExit(), settings.copy(finalMask = " ", dialMode = "").exit)
    }
}
