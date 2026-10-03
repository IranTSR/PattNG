package com.v2ray.ang.ui.server

import com.google.gson.JsonParser

/**
 * The ready-made values a field offers to pick from, each given as the text a pick puts in the field, and how the
 * field's text is matched to them. A pick only fills the field in: what the field holds afterwards, edited or not, is
 * what is saved. Matching parses JSON, so the fields match off the main thread.
 */
internal class FieldPresets(private val values: List<String>, private val match: Match) {

    enum class Match {
        /** JSON, such as a finalMask: a field holds one in whatever spacing or key order it is written. */
        JSON,

        /**
         * Names joined by ':', such as cipherSuites, which Xray splits on ':' and trims each of: a field holds one when it
         * has the same names in the same order, whatever the white space around each, and an empty name counts for
         * none, as Xray skips it.
         */
        NAMES,
    }

    /** Read on the first match, on the thread that matches. */
    private val keys: List<Any?> by lazy { values.map(::keyOf) }

    /** The index of the value [text] is, or -1 when it is none of them, blank, or for JSON not JSON at all. */
    fun indexOf(text: String): Int = keyOf(text)?.let { keys.indexOf(it) } ?: -1

    /** What [text] is compared by. JSON is read without the error JsonUtil logs: what is being typed is often not JSON yet. */
    private fun keyOf(text: String): Any? = when (match) {
        Match.JSON -> try {
            JsonParser.parseString(text)
        } catch (_: RuntimeException) {
            null
        }
        Match.NAMES -> text.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    companion object {
        /**
         * Whether a pick asks before it takes the place of [text], which holds the value at [held], -1 for none of
         * them, or null while that is not known yet: only when the field holds a value of the user's own, or may hold
         * one, which is anything but blank or one of the list, since that would be lost.
         */
        fun asksBeforeReplacing(text: String, held: Int?): Boolean = text.isNotBlank() && (held == null || held < 0)
    }
}
