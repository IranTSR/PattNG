package com.v2ray.ang.handler

import android.util.Log
import com.tencent.mmkv.MMKV
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockMakers
import org.mockito.Mockito
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * A profile stored by the release that still had SSTP must not break the server list once the
 * SSTP enum entry is gone: Gson decodes the unknown "SSTP" name as a null configType, which
 * would crash callers on the first access. The decode guard drops only that profile.
 */
class StoredProfileDecodeTest {
    private val profileValues = mutableMapOf<String, String>()

    @BeforeEach
    fun prepareStorage() {
        Mockito.reset(profiles)
        whenever(profiles.decodeString(any())).thenAnswer { profileValues[it.getArgument<String>(0)] }
    }

    @Test
    fun aStoredSstpProfileIsSkipped() {
        profileValues["old-sstp"] = """{"configVersion":4,"configType":"SSTP","subscriptionId":"","remarks":"Old SSTP","server":"vpn.example.com","serverPort":"443","username":"u","password":"p"}"""

        mockStatic(Log::class.java).use {
            assertNull(MmkvManager.decodeServerConfig("old-sstp"))
        }
    }

    @Test
    fun aStoredSstpProfileByValueIsSkipped() {
        profileValues["old-sstp-int"] = """{"configVersion":4,"configType":501,"subscriptionId":"","remarks":"Old SSTP","server":"vpn.example.com","serverPort":"443"}"""

        mockStatic(Log::class.java).use {
            assertNull(MmkvManager.decodeServerConfig("old-sstp-int"))
        }
    }

    @Test
    fun aStoredProfileWithoutATypeIsSkipped() {
        profileValues["no-type"] = """{"configVersion":4,"subscriptionId":"","remarks":"Typeless","server":"example.com","serverPort":"443"}"""

        mockStatic(Log::class.java).use {
            assertNull(MmkvManager.decodeServerConfig("no-type"))
        }
    }

    @Test
    fun aStoredVlessProfileStillDecodes() {
        profileValues["vless"] = """{"configVersion":4,"configType":"VLESS","subscriptionId":"","remarks":"Kept","server":"example.com","serverPort":"443"}"""

        val decoded = MmkvManager.decodeServerConfig("vless")

        assertEquals(EConfigType.VLESS, decoded?.configType)
        assertEquals("example.com", decoded?.server)
    }

    companion object {
        private fun handle(): MMKV = Mockito.mock(MMKV::class.java, Mockito.withSettings().mockMaker(MockMakers.SUBCLASS))

        private val profiles: MMKV = handle()

        @BeforeAll
        @JvmStatic
        fun initializeHandles() {
            mockStatic(MMKV::class.java).use {
                it.`when`<MMKV> { MMKV.mmkvWithID("PROFILE_FULL_CONFIG", MMKV.MULTI_PROCESS_MODE) }.thenReturn(profiles)
                MmkvManager.decodeServerConfig("test-initialize")
            }
        }
    }
}
