package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LauncherManagerTest {

    private fun profile(type: EConfigType, address: String?) =
        ProfileItem.create(type).apply { server = address }

    @Test
    fun anAetherProfileStartsWithoutAnEndpoint() {
        assertTrue(LauncherManager.hasUsableServer(profile(EConfigType.AETHER, null)))
        assertTrue(LauncherManager.hasUsableServer(profile(EConfigType.AETHER, "")))
        assertTrue(LauncherManager.hasUsableServer(profile(EConfigType.AETHER, "162.159.198.1")))
    }

    @Test
    fun groupedProfilesStartWithoutAnAddressOfTheirOwn() {
        assertTrue(LauncherManager.hasUsableServer(profile(EConfigType.POLICYGROUP, null)))
        assertTrue(LauncherManager.hasUsableServer(profile(EConfigType.PROXYCHAIN, null)))
        assertTrue(LauncherManager.hasUsableServer(profile(EConfigType.CUSTOM, null)))
    }

    @Test
    fun aRegularProfileNeedsAnAddress() {
        assertFalse(LauncherManager.hasUsableServer(profile(EConfigType.VLESS, null)))
        assertFalse(LauncherManager.hasUsableServer(profile(EConfigType.VLESS, "")))
        assertTrue(LauncherManager.hasUsableServer(profile(EConfigType.VLESS, "162.159.198.1")))
        assertTrue(LauncherManager.hasUsableServer(profile(EConfigType.WIREGUARD, "2606:4700:d0::a29f:c001")))
    }

    @Test
    fun aDnsProfileStartsWithAHostname() {
        assertTrue(LauncherManager.hasUsableServer(profile(EConfigType.DNS, "dns.adguard-dns.com")))
        assertTrue(LauncherManager.hasUsableServer(profile(EConfigType.DNS, "1.1.1.1")))
    }

    @Test
    fun onlyDnsProfilesNeedVpnMode() {
        // DNS owns no proxy, only the TUN captures the device's DNS, which only the VPN service provides.
        assertTrue(LauncherManager.requiresVpnMode(EConfigType.DNS))
        assertFalse(LauncherManager.requiresVpnMode(EConfigType.VLESS))
        assertFalse(LauncherManager.requiresVpnMode(EConfigType.AETHER))
    }
}
