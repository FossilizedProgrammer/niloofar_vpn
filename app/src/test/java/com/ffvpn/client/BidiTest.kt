package com.ffvpn.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTest {
    private val lri = '\u2066'
    private val pdi = '\u2069'

    @Test
    fun `a latin word in persian text becomes one isolate`() {
        assertEquals(
            "اتصال ${lri}VPN$pdi برقرار است",
            Bidi.isolate("اتصال VPN برقرار است")
        )
    }

    @Test
    fun `hosts, versions and counts stay in one piece`() {
        assertEquals(
            "${lri}SOCKS5$pdi در ${lri}127.0.0.1:1080$pdi و نسخه ${lri}1.2.0$pdi",
            Bidi.isolate("SOCKS5 در 127.0.0.1:1080 و نسخه 1.2.0")
        )
    }

    @Test
    fun `persian digits are left alone`() {
        val text = "۱۲ بریج ذخیره شد"
        assertFalse(Bidi.isolate(text).contains(lri))
    }

    @Test
    fun `isolating twice changes nothing`() {
        val once = Bidi.isolate("ذخیره 12 بریج")
        assertEquals(once, Bidi.isolate(once))
        assertTrue(once.contains(lri))
    }

    @Test
    fun `an empty string stays empty`() {
        assertEquals("", Bidi.isolate(""))
    }
}
