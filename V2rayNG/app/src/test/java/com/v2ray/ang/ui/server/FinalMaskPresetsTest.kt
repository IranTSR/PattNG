package com.v2ray.ang.ui.server

import com.v2ray.ang.core.AetherExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The finalMasks the finalMask fields offer: which one a field holds, in whatever spacing, whether a pick asks before it
 * takes the field's place, and the lists themselves as the app's resources give them, read from the module's folder the
 * unit tests run in.
 */
class FinalMaskPresetsTest {

    private val fragment = """{"tcp": [{"type": "fragment", "settings": {"packets": "tlshello"}}]}"""
    private val noise = """{"udp": [{"type": "noise", "settings": {"noise": [{"rand": "10-20", "delay": "10"}]}}]}"""
    private val presets = FinalMaskPresets(listOf(fragment, noise))

    @Test
    fun aFieldHoldsAFinalMaskOfTheListInWhateverSpacingOrKeyOrder() {
        assertEquals(0, presets.indexOf(fragment))
        assertEquals(1, presets.indexOf(noise))
        assertEquals(1, presets.indexOf(noise.replace(" ", "")))
        assertEquals(0, presets.indexOf("{\n  \"tcp\": [\n    {\"settings\": {\"packets\": \"tlshello\"}, \"type\": \"fragment\"}\n  ]\n}"))
    }

    @Test
    fun aFieldThatIsBlankEditedOrNotJsonHoldsNoneOfThem() {
        assertEquals(-1, presets.indexOf(""))
        assertEquals(-1, presets.indexOf(" \n "))
        assertEquals(-1, presets.indexOf(fragment.replace("tlshello", "1-3")))
        assertEquals(-1, presets.indexOf("""{"tcp": [{"type": "fragment""""))
        assertEquals(-1, presets.indexOf("[]"))
        // The masks run in the order they are written, so the same two in the other order are another finalMask.
        val both = FinalMaskPresets(listOf("""{"tcp": [{"type": "a"}, {"type": "b"}]}"""))
        assertEquals(0, both.indexOf("""{"tcp": [{"type": "a"}, {"type": "b"}]}"""))
        assertEquals(-1, both.indexOf("""{"tcp": [{"type": "b"}, {"type": "a"}]}"""))
    }

    @Test
    fun aPickAsksFirstOnlyWhenItWouldTakeThePlaceOfJsonOfTheUsersOwn() {
        assertFalse(presets.asksBeforeReplacing(""))
        assertFalse(presets.asksBeforeReplacing("  "))
        assertFalse(presets.asksBeforeReplacing(noise))
        assertFalse(presets.asksBeforeReplacing(noise.replace(" ", "")))
        assertTrue(presets.asksBeforeReplacing(noise.replace("10-20", "30-40")))
        assertTrue(presets.asksBeforeReplacing("""{"tcp": ["""))
    }

    @Test
    fun theFieldsOfferTheGivenFinalMasksEachLaidOutAsWrittenUnderItsName() {
        val names = array("final_mask_preset_names")
        val values = array("final_mask_preset_values").map(::readAsAapt)
        assertEquals(listOf("tlshello-0-len (0-104-1-0-0-114-1-1-11)", "tlshello (6-98-1-0-0-114-1-1-11)", "udp-noise (rnd-24-1200-1230)"), names)
        assertEquals(listOf(TLSHELLO_0_LEN, TLSHELLO, UDP_NOISE), values)
        // Each is one an exit-node takes, as an outbound does, and the list tells each apart from the others.
        val listed = FinalMaskPresets(values)
        values.forEachIndexed { index, value ->
            assertTrue(value, AetherExit.takesFinalMask(value))
            assertEquals(index, listed.indexOf(value))
        }
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

    private companion object {
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
