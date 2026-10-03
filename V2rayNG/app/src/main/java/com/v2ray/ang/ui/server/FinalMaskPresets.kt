package com.v2ray.ang.ui.server

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * The finalMasks a finalMask field offers to pick from, each given as the JSON a pick puts in the field. A pick only
 * fills the field in: what the field holds afterwards, edited or not, is the finalMask that is saved.
 */
internal class FinalMaskPresets(values: List<String>) {

    private val parsed: List<JsonElement?> = values.map(::parse)

    /**
     * The index of the finalMask [text] is, written in whatever spacing or key order, or -1 when it is none of them,
     * blank, or not JSON.
     */
    fun indexOf(text: String): Int = parse(text)?.let { parsed.indexOf(it) } ?: -1

    /**
     * Whether a pick asks before it takes the place of [text]: only when the field holds JSON of the user's own, which
     * is anything but blank or one of the finalMasks, since that would be lost.
     */
    fun asksBeforeReplacing(text: String): Boolean = text.isNotBlank() && indexOf(text) < 0

    /** [text] read as JSON, without the error JsonUtil logs: what is being typed is often not JSON yet. */
    private fun parse(text: String): JsonElement? = try {
        JsonParser.parseString(text)
    } catch (_: RuntimeException) {
        null
    }
}
