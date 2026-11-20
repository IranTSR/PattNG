package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DnsDelayTesterTest {

    @Test
    fun aBuiltQueryAsksForOneARecord() {
        val query = DnsDelayTester.buildQuery("example.com", 0x1234)

        assertEquals(0x1234, DnsDelayTester.queryId(query))
        // Flags: recursion desired, standard query.
        assertEquals(0x01, query[2].toInt())
        assertEquals(0x00, query[3].toInt())
        // QDCOUNT = 1, ANCOUNT = 0.
        assertEquals(0x01, query[5].toInt())
        assertEquals(0x00, query[7].toInt())
        // QNAME: example(7) com(3) root(0), then QTYPE A and QCLASS IN.
        val qname = byteArrayOf(7) + "example".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(3) + "com".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        assertEquals(qname.toList(), query.slice(12 until 12 + qname.size))
        val tail = query.slice(12 + qname.size until query.size).map { it.toInt() }
        assertEquals(listOf(0, 1, 0, 1), tail)
    }

    @Test
    fun aResponseWithAnAnswerCounts() {
        // Header only: id 0x1234, flags 0x8180 (response, no error), QDCOUNT 1, ANCOUNT 1.
        val response = byteArrayOf(
            0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
        )
        assertTrue(DnsDelayTester.isAnswer(response, 0x1234))
        // Another query's id, an error, or no answer at all does not count.
        assertFalse(DnsDelayTester.isAnswer(response, 0x1235))
        val error = response.copyOf().also { it[3] = 0x83.toByte() }
        assertFalse(DnsDelayTester.isAnswer(error, 0x1234))
        val noAnswer = response.copyOf().also { it[7] = 0x00 }
        assertFalse(DnsDelayTester.isAnswer(noAnswer, 0x1234))
        assertFalse(DnsDelayTester.isAnswer(byteArrayOf(0x12, 0x34), 0x1234))
    }
}
