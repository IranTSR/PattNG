package com.v2ray.ang.handler

import com.v2ray.ang.handler.SmartFragmentManager.FragmentCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartFragmentManagerTest {

    private val a = FragmentCandidate("tlshello", "50-100", "10-20", "10")
    private val b = FragmentCandidate("1-3", "100-200", "10-20", "20")
    private val c = FragmentCandidate("1-1", "100-200", "10-20", "10")

    @Test
    fun allFailuresReturnNull() {
        assertNull(SmartFragmentManager.pickBest(listOf(a to -1L, b to -1L, c to -1L)))
    }

    @Test
    fun emptyResultsReturnNull() {
        assertNull(SmartFragmentManager.pickBest(emptyList()))
    }

    @Test
    fun picksLowestNonNegativeDelay() {
        val winner = SmartFragmentManager.pickBest(listOf(a to 300L, b to 120L, c to 250L))
        assertEquals(b, winner)
    }

    @Test
    fun ignoresNegativeDelays() {
        val winner = SmartFragmentManager.pickBest(listOf(a to -1L, b to 200L, c to -1L))
        assertEquals(b, winner)
    }

    @Test
    fun zeroDelayWins() {
        val winner = SmartFragmentManager.pickBest(listOf(a to 50L, b to 0L))
        assertEquals(b, winner)
    }

    @Test
    fun candidateLabelFormatsFields() {
        assertEquals("tlshello / 50-100 / 10-20 / 10", a.label())
    }
}
