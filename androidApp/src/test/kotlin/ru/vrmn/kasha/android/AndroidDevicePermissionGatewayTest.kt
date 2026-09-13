package ru.vrmn.kasha.android

import brain.studio.DevicePermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDevicePermissionGatewayTest {
    @Test
    fun microphonePermissionClassificationUsesOnlyObservablePublicState() {
        assertEquals(
            DevicePermissionStatus.UNAVAILABLE,
            classifyMicrophonePermission(
                hasMicrophone = false,
                granted = false,
                requestedBefore = false,
                blockedByObservedResult = false,
                requestInFlight = false,
            ),
        )
        assertEquals(
            DevicePermissionStatus.GRANTED,
            classifyMicrophonePermission(
                hasMicrophone = true,
                granted = true,
                requestedBefore = true,
                blockedByObservedResult = true,
                requestInFlight = false,
            ),
        )
        assertEquals(
            DevicePermissionStatus.NOT_DETERMINED,
            classifyMicrophonePermission(
                hasMicrophone = true,
                granted = false,
                requestedBefore = false,
                blockedByObservedResult = false,
                requestInFlight = false,
            ),
        )
        assertEquals(
            DevicePermissionStatus.NOT_DETERMINED,
            classifyMicrophonePermission(
                hasMicrophone = true,
                granted = false,
                requestedBefore = true,
                blockedByObservedResult = false,
                requestInFlight = true,
            ),
        )
        assertEquals(
            DevicePermissionStatus.DENIED,
            classifyMicrophonePermission(
                hasMicrophone = true,
                granted = false,
                requestedBefore = true,
                blockedByObservedResult = false,
                requestInFlight = false,
            ),
        )
        assertEquals(
            DevicePermissionStatus.RESTRICTED,
            classifyMicrophonePermission(
                hasMicrophone = true,
                granted = false,
                requestedBefore = true,
                blockedByObservedResult = true,
                requestInFlight = false,
            ),
        )
    }
}
