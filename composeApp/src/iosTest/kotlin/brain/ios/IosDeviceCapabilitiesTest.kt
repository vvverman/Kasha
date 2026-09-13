package brain.ios

import brain.studio.DevicePermissionState
import kotlin.test.Test
import kotlin.test.assertEquals

class IosDeviceCapabilitiesTest {
    @Test
    fun speechAuthorizationMapsWithoutInventingAccess() {
        assertEquals(DevicePermissionState.NOT_DETERMINED, IosDeviceCapabilities.mapSpeechStatus(0L))
        assertEquals(DevicePermissionState.DENIED, IosDeviceCapabilities.mapSpeechStatus(1L))
        assertEquals(DevicePermissionState.DENIED, IosDeviceCapabilities.mapSpeechStatus(2L))
        assertEquals(DevicePermissionState.GRANTED, IosDeviceCapabilities.mapSpeechStatus(3L))
        assertEquals(DevicePermissionState.UNAVAILABLE, IosDeviceCapabilities.mapSpeechStatus(99L))
    }

    @Test
    fun notificationAuthorizationTreatsProvisionalAndEphemeralAsGranted() {
        assertEquals(DevicePermissionState.NOT_DETERMINED, IosDeviceCapabilities.mapNotificationStatus(0L))
        assertEquals(DevicePermissionState.DENIED, IosDeviceCapabilities.mapNotificationStatus(1L))
        assertEquals(DevicePermissionState.GRANTED, IosDeviceCapabilities.mapNotificationStatus(2L))
        assertEquals(DevicePermissionState.GRANTED, IosDeviceCapabilities.mapNotificationStatus(3L))
        assertEquals(DevicePermissionState.GRANTED, IosDeviceCapabilities.mapNotificationStatus(4L))
        assertEquals(DevicePermissionState.UNAVAILABLE, IosDeviceCapabilities.mapNotificationStatus(99L))
    }
}
