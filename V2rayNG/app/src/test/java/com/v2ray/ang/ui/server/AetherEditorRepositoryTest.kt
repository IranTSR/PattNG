package com.v2ray.ang.ui.server

import com.v2ray.ang.enums.AetherProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AetherEditorRepositoryTest {

    @Test
    fun theProcessesAloneTellTheSessionWhereTheyCanBeListed() {
        // The core of a latency test listens on the Aether port as well; it is no session.
        assertNull(AetherEditorRepository.sessionOf(protocol = null, processesListed = true) { true })
        assertEquals(
            AetherSession(AetherProtocol.MASQUE),
            AetherEditorRepository.sessionOf(AetherProtocol.MASQUE, processesListed = true) { false }
        )
    }

    @Test
    fun withoutTheProcessesAListenerOnTheAetherPortStandsInForTheSession() {
        assertEquals(AetherSession(protocol = null), AetherEditorRepository.sessionOf(protocol = null, processesListed = false) { true })
        assertNull(AetherEditorRepository.sessionOf(protocol = null, processesListed = false) { false })
        assertEquals(
            AetherSession(AetherProtocol.WIREGUARD),
            AetherEditorRepository.sessionOf(AetherProtocol.WIREGUARD, processesListed = false) { true }
        )
    }
}
