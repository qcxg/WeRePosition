package com.shiraka.wereposition.utils

import com.shiraka.wereposition.data.model.SpecifiedWifiConnection
import org.junit.Assert.*
import org.junit.Test

class MacAddressInputTest {
    @Test fun acceptsCommonPasteFormatsAndStoresCanonicalBytes() {
        for (input in listOf(" 02:60:ac:4d:59:01\n", "02-60-ac-4d-59-01", "0260.ac4d.5901", "0260ac4d5901")) {
            val wifi = SpecifiedWifiConnection(ssid = "MY-KL-Test", bssid = input)
            assertTrue(input, wifi.isValid)
            assertEquals("02:60:AC:4D:59:01", wifi.normalized().bssid)
        }
    }

    @Test fun neverStripsArbitraryCharactersOrTruncatesAnAddress() {
        for (input in listOf("x02:60:AC:4D:59:01", "02:60:AC:4D:59:01:AA", "02:60:AC:4D:59", "02-60:AC:4D:59:01", "0260ac4d590Z")) {
            assertNull(input, MacAddressInput.normalize(input))
        }
    }

    @Test fun hiddenAndroidAddressIsRejectedInEveryNotation() {
        for (input in listOf("02:00:00:00:00:00", "020000000000", "0200.0000.0000")) {
            assertFalse(SpecifiedWifiConnection("test", input).hasValidBssid)
        }
    }

    @Test fun aValidBssidDoesNotMakeInvalidOtherFieldsValid() {
        val valid = SpecifiedWifiConnection("MY-KL-Test", "02:60:AC:4D:59:01")
        assertTrue(valid.isValid)
        assertFalse(valid.copy(ssid = " ").isValid)
        assertFalse(valid.copy(macAddress = "wrong").isValid)
        assertFalse(valid.copy(ipAddress = "192.168.1.256").isValid)
        assertFalse(valid.copy(dns1 = "+1.2.3.4").isValid)
        assertTrue(valid.copy(ipAddress = " 192.168.1.2 ").isValid)
    }
}
