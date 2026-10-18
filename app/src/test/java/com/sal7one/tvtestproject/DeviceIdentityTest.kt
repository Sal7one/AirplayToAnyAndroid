package com.sal7one.tvtestproject

import org.junit.Assert.*
import org.junit.Test

class DeviceIdentityTest {
    @Test fun namesDistinguishPhoneAndTvAndRemainStable() {
        val first = "02:11:22:a1:b2:c3"
        val second = "02:11:22:d4:e5:f6"
        assertEquals("FossMirrorTV - TV A1B2C3", DeviceIdentity.displayName(first, true))
        assertEquals("FossMirrorTV - Phone D4E5F6", DeviceIdentity.displayName(second, false))
        assertEquals(DeviceIdentity.displayName(first, true), DeviceIdentity.displayName(first.uppercase(), true))
        assertNotEquals(DeviceIdentity.displayName(first, true), DeviceIdentity.displayName(second, true))
        assertNotEquals(DeviceIdentity.displayName(first, true), DeviceIdentity.displayName(first, false))
    }

    @Test fun bothDnsServiceNamesFitOneLabelAndUseTheSameSuffix() {
        val id = "02:11:22:a1:b2:c3"
        for (tv in listOf(false, true)) {
            val name = DeviceIdentity.displayName(id, tv)
            val raop = DeviceIdentity.raopServiceInstanceName(id, name)
            assertTrue(name.toByteArray().size <= 63)
            assertTrue(raop.toByteArray().size <= 63)
            assertTrue(raop.endsWith("@$name"))
        }
    }

    @Test fun malformedIdentityCannotBecomeADiscoveryName() {
        for (id in listOf("", "bad", "02:11:22:a1:b2", "02:11:22:a1:b2:zz")) {
            assertThrows(IllegalArgumentException::class.java) { DeviceIdentity.displayName(id, true) }
        }
    }
}
