package com.v2ray.ang.ui.server

import com.v2ray.ang.core.AetherExit
import com.v2ray.ang.ui.server.FieldPresets.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The ready-made values the finalMask and cipherSuites fields offer: which one a field holds, whether a pick asks before
 * it takes the field's place, and the lists themselves as the app's resources give them, read from the module's folder
 * the unit tests run in.
 */
class FieldPresetsTest {

    private val fragment = """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello"}}]}"""
    private val noise = """{"udp": [{"type": "noise", "settings": {"noise": [{"rand": "10-20", "delay": "10"}]}}]}"""
    private val masks = FieldPresets(listOf(fragment, noise), Match.JSON)
    private val ecdsa = "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"
    private val rsa = "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
    private val chacha = "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
    private val suites = FieldPresets(listOf("$ecdsa:$rsa", chacha), Match.NAMES)

    @Test
    fun aFinalMaskFieldHoldsOneOfTheListInWhateverSpacingOrKeyOrder() {
        assertEquals(0, masks.indexOf(fragment))
        assertEquals(1, masks.indexOf(noise))
        assertEquals(1, masks.indexOf(noise.replace(" ", "")))
        assertEquals(0, masks.indexOf("{\n  \"tcp\": [\n    {\"settings\": {\"packets\": \"tlshello\"}, \"type\": \"fragment\"}\n  ]\n}"))
    }

    @Test
    fun aFinalMaskFieldThatIsBlankEditedOrNotJsonHoldsNoneOfThem() {
        assertEquals(-1, masks.indexOf(""))
        assertEquals(-1, masks.indexOf(" \n "))
        assertEquals(-1, masks.indexOf(fragment.replace("tlshello", "1-3")))
        assertEquals(-1, masks.indexOf("""{"tcp": [{"type": "fragment""""))
        assertEquals(-1, masks.indexOf("[]"))
        // The masks run in the order they are written, so the same two in the other order are another finalMask.
        val both = FieldPresets(listOf("""{"tcp": [{"type": "a"}, {"type": "b"}]}"""), Match.JSON)
        assertEquals(0, both.indexOf("""{"tcp": [{"type": "a"}, {"type": "b"}]}"""))
        assertEquals(-1, both.indexOf("""{"tcp": [{"type": "b"}, {"type": "a"}]}"""))
    }

    @Test
    fun aCipherSuitesFieldHoldsOneOfTheListWhenItHasItsNamesInItsOrder() {
        assertEquals(0, suites.indexOf("$ecdsa:$rsa"))
        assertEquals(1, suites.indexOf(chacha))
        // Xray splits the names on ':' and trims each, and skips an empty one.
        assertEquals(0, suites.indexOf(" $ecdsa :\n$rsa\n"))
        assertEquals(0, suites.indexOf("$ecdsa::$rsa:"))
        assertEquals(1, suites.indexOf("$chacha\n"))
    }

    @Test
    fun aCipherSuitesFieldWithOtherNamesOrInAnotherOrderHoldsNoneOfThem() {
        assertEquals(-1, suites.indexOf("$rsa:$ecdsa"))
        assertEquals(-1, suites.indexOf("$ecdsa:$rsa:$chacha"))
        assertEquals(-1, suites.indexOf(ecdsa))
        assertEquals(-1, suites.indexOf("${ecdsa}X:$rsa"))
        assertEquals(-1, suites.indexOf(""))
        assertEquals(-1, suites.indexOf(" : \n"))
    }

    @Test
    fun aPickAsksFirstOnlyWhenItWouldTakeThePlaceOfAValueOfTheUsersOwn() {
        assertFalse(masks.asksBeforeReplacing(""))
        assertFalse(masks.asksBeforeReplacing("  "))
        assertFalse(masks.asksBeforeReplacing(noise))
        assertFalse(masks.asksBeforeReplacing(noise.replace(" ", "")))
        assertTrue(masks.asksBeforeReplacing(noise.replace("10-20", "30-40")))
        assertTrue(masks.asksBeforeReplacing("""{"tcp": ["""))
        assertFalse(suites.asksBeforeReplacing(""))
        assertFalse(suites.asksBeforeReplacing(chacha))
        assertFalse(suites.asksBeforeReplacing("$ecdsa : $rsa"))
        assertTrue(suites.asksBeforeReplacing("$rsa:$ecdsa"))
        assertTrue(suites.asksBeforeReplacing(" : "))
    }

    @Test
    fun theFinalMaskFieldsOfferTheGivenFinalMasksEachLaidOutAsWrittenUnderItsName() {
        val names = array("final_mask_preset_names")
        val values = array("final_mask_preset_values").map(::readAsAapt)
        assertEquals(listOf("tlshello-0-len (0-104-1-0-0-114-1-1-11)", "tlshello (6-98-1-0-0-114-1-1-11)", "udp-noise (rnd-24-1200-1230)"), names)
        assertEquals(listOf(TLSHELLO_0_LEN, TLSHELLO, UDP_NOISE), values)
        // Each is one an exit-node takes, as an outbound does, and the list tells each apart from the others.
        val listed = FieldPresets(values, Match.JSON)
        values.forEachIndexed { index, value ->
            assertTrue(value, AetherExit.takesFinalMask(value))
            assertEquals(index, listed.indexOf(value))
        }
    }

    @Test
    fun theCipherSuitesFieldOffersTheGivenListUnderItsName() {
        val names = array("cipher_suites_preset_names")
        val values = array("cipher_suites_preset_values").map(::readPlain)
        assertEquals(listOf("semi-python-cipherSuites"), names)
        assertEquals(listOf(SEMI_PYTHON), values)
        val listed = FieldPresets(values, Match.NAMES)
        values.forEachIndexed { index, value -> assertEquals(index, listed.indexOf(value)) }
    }

    private fun array(name: String): List<String> {
        val arrays = File("src/main/res/values/arrays.xml")
        assertTrue("${arrays.absolutePath} is where the unit tests run from", arrays.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(arrays)
        val nodes = document.getElementsByTagName("string-array")
        val array = (0 until nodes.length).map { nodes.item(it) }.single { it.attributes.getNamedItem("name").nodeValue == name }
        val items = array.childNodes
        return (0 until items.length).map { items.item(it) }.filter { it.nodeName == "item" }.map { it.textContent.trim() }
    }

    /**
     * [raw] as aapt reads a string resource put in quotes so that its spaces stay: the quotes go, and \n, \" and \\ are
     * undone. A quote left unescaped would end the string early, so it fails here.
     */
    private fun readAsAapt(raw: String): String {
        assertTrue(raw, raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"'))
        val text = StringBuilder()
        var escaped = false
        for (c in raw.substring(1, raw.length - 1)) {
            when {
                escaped -> {
                    text.append(if (c == 'n') '\n' else c)
                    escaped = false
                }
                c == '\\' -> escaped = true
                else -> {
                    assertFalse("an unescaped quote in $raw", c == '"')
                    text.append(c)
                }
            }
        }
        assertFalse(raw, escaped)
        return text.toString()
    }

    /**
     * [raw] as aapt reads a string resource written without quotes: unchanged, as long as it has no white space, quote,
     * apostrophe or backslash, and starts with neither @ nor ?.
     */
    private fun readPlain(raw: String): String {
        assertTrue(raw, raw.isNotEmpty() && raw.none { it.isWhitespace() || it in "\"'\\" } && raw.first() !in "@?")
        return raw
    }

    private companion object {
        const val SEMI_PYTHON = "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384:TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256:TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256:TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA:TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"

        val TLSHELLO_0_LEN = """
            {
              "tcp": [
                {"type": "fragment", "settings": {"packets": "tlshello", "lengths": ["0", "104", "1"], "delays": ["0"], "maxSplit": "0"}},
                {"type": "fragment", "settings": {"packets": "1-1", "lengths": ["114", "1"], "delays": ["1"], "maxSplit": "11"}}
              ]
            }
        """.trimIndent()

        val TLSHELLO = """
            {
              "tcp": [
                {"type": "fragment", "settings": {"packets": "tlshello", "lengths": ["6", "98", "1"], "delays": ["0"], "maxSplit": "0"}},
                {"type": "fragment", "settings": {"packets": "1-1", "lengths": ["114", "1"], "delays": ["1"], "maxSplit": "11"}}
              ]
            }
        """.trimIndent()

        val UDP_NOISE = """
            {
              "udp": [
                {
                  "type": "noise",
                  "settings": {
                    "reset": "28",
                    "noise": [
                      {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"},
                      {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"},
                      {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"},
                      {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"},
                      {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"},
                      {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"},
                      {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"},
                      {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"}, {"rand": "1200-1230", "delay": "10"}
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
