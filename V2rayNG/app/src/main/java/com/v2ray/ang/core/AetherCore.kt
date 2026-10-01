package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherProtocol

/**
 * The Aether core a configuration runs on, as the arguments its process is started with, the
 * listeners among them. The core of a profile is built from the profile's settings, or is the
 * command line the profile carries in their place; the core of a custom configuration is the
 * command line it carries as aetherCommand. A command line is read as written and run as written,
 * so that what the profile or the configuration says is what runs. Two cores with the same
 * arguments are one core, which is how one process comes to serve several outbounds.
 */
data class AetherCore(val arguments: List<String>) {

    /**
     * The loopback port the app dials: Psiphon's or Tor's listener when one of them runs inside the
     * tunnel and is what the app reaches, the core's own listener otherwise.
     */
    val port: Int get() = AetherCoreManager.listenerPortOf(arguments) ?: AetherCoreManager.socksPort

    /** Every loopback port the core is told to listen on; an inbound of the configuration cannot share one. */
    val ports: List<Int>
        get() = LISTENERS
            .mapNotNull { AetherCoreManager.portAfter(arguments, it) }
            .filter { it != 0 }
            .distinct()

    /** The protocol, which tells whose identity files the core uses. */
    val protocol: AetherProtocol get() = AetherCoreManager.protocolOf(arguments)

    /** The tunnel as the names of its parts from the outside in, carriers included; see [AetherCoreManager.pathOf]. */
    val path: List<String> get() = AetherCoreManager.pathOf(arguments)

    /** The command line a profile or a custom configuration carries for this core; [ofCommand] reads it back. */
    val command: String get() = (listOf(COMMAND_NAME) + arguments).joinToString(" ", transform = ::quoted)

    /**
     * This core dialled on [port] instead, as a latency test opens it on a port of its own. The other
     * listeners it names move to the ports after it, in the order of [LISTENERS], where
     * [AetherCoreManager.buildArguments] puts them; one on an ephemeral port stays there.
     */
    fun on(port: Int): AetherCore {
        val dialed = AetherCoreManager.listenerFlagOf(arguments)
        var moved = AetherCoreManager.withListener(arguments, dialed, port)
        var next = port + 1
        for (listener in LISTENERS) {
            if (listener == dialed) continue
            val current = AetherCoreManager.portAfter(arguments, listener) ?: continue
            if (current == 0) continue
            moved = AetherCoreManager.withListener(moved, listener, next++)
        }
        return AetherCore(moved)
    }

    /** True when a process started with [processArguments] runs this core, on whatever ports and at whatever log level. */
    fun runsAs(processArguments: List<String>): Boolean =
        AetherCoreManager.tunnelArguments(processArguments) == AetherCoreManager.tunnelArguments(arguments)

    /** True when the core is told which proxy to dial out through, as a command written by hand may be. */
    val hasUpstream: Boolean get() = AetherCoreManager.UPSTREAM in arguments

    /**
     * This core dialling out through the SOCKS inbound on [port] of the loopback address, which Xray
     * serves so that what the core sends leaves through Xray. A core told an upstream of its own keeps it.
     */
    fun through(port: Int): AetherCore =
        if (hasUpstream) this else AetherCore(arguments + listOf(AetherCoreManager.UPSTREAM, "socks5://${AppConfig.LOOPBACK}:$port"))

    /**
     * The port for the inbound the core dials out through: two above [port], the one between being
     * where the core itself, Tor or Psiphon may listen, or else the next port that is none of [ports]
     * and not [isTaken] by an inbound of the configuration.
     */
    fun exitPort(isTaken: (Int) -> Boolean): Int =
        (((port + 2)..LAST_PORT).asSequence() + (FIRST_UNPRIVILEGED_PORT until port).asSequence())
            .first { it !in ports && !isTaken(it) }

    companion object {

        /** The name a command line starts with; the app runs its own copy of the core whatever the name says. */
        const val COMMAND_NAME = "aether"

        /** The listeners a core may be told to bind, in the order [on] hands ports out: the core's own, Tor's, Psiphon's. */
        private val LISTENERS = listOf("--bind", AetherCoreManager.TOR_BIND, AetherCoreManager.PSIPHON_BIND)

        private const val LAST_PORT = 65535
        private const val FIRST_UNPRIVILEGED_PORT = 1024

        /**
         * The core of [profile]: the command line it carries, or its settings as arguments on its
         * listen port. The log level is the session's to add. A command the app cannot read is
         * left aside for the settings; the profile editor refuses to store one.
         */
        fun of(profile: ProfileItem): AetherCore =
            profile.aetherCommand?.takeIf { it.isNotBlank() }?.let(::ofCommand)
                ?: AetherCore(
                    AetherCoreManager.withoutOption(
                        AetherCoreManager.buildArguments(profile, AetherCoreManager.listenPort(profile)),
                        "--log-level",
                    )
                )

        /**
         * The core [command] describes, or null when it names nothing the app can run: no argument
         * at all, or a listener whose port cannot be read. Words are split on whitespace, quotes keep a
         * word together, and a program name in front is dropped. A command that names no listener for
         * the app to dial gets one on [AetherCoreManager.socksPort], the port the Aether outbounds of
         * the app dial unless told otherwise; the core's own defaults are other ports, which nothing
         * in the app dials.
         */
        fun ofCommand(command: String): AetherCore? {
            val words = words(command)
            val arguments = if (words.firstOrNull()?.startsWith("-") == false) words.drop(1) else words
            if (arguments.isEmpty()) return null
            val listener = AetherCoreManager.listenerFlagOf(arguments)
            if (listener !in arguments) return AetherCore(AetherCoreManager.withListener(arguments, listener, AetherCoreManager.socksPort))
            return AetherCore(arguments).takeIf { AetherCoreManager.portAfter(arguments, listener) != null }
        }

        /** The words of a command line: split on whitespace, with single or double quotes keeping a word together. */
        internal fun words(command: String): List<String> {
            val words = mutableListOf<String>()
            val word = StringBuilder()
            var quote: Char? = null
            var open = false
            for (c in command) {
                when {
                    quote != null -> if (c == quote) quote = null else word.append(c)
                    c == '"' || c == '\'' -> {
                        quote = c
                        open = true
                    }

                    c.isWhitespace() -> if (open) {
                        words.add(word.toString())
                        word.setLength(0)
                        open = false
                    }

                    else -> {
                        word.append(c)
                        open = true
                    }
                }
            }
            if (open) words.add(word.toString())
            return words
        }

        /** [word] as a command line carries it: quoted when whitespace would split it. */
        private fun quoted(word: String): String = if (word.isEmpty() || word.any(Char::isWhitespace)) "\"$word\"" else word
    }
}
