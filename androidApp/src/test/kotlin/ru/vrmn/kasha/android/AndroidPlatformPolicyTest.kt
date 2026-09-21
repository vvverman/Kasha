package ru.vrmn.kasha.android

import org.junit.Test
import org.junit.Assert.*
import javax.crypto.KeyGenerator
import javax.crypto.AEADBadTagException

class AndroidPlatformPolicyTest {
    @Test fun focusNeverAutoResumes() {
        listOf(-1, -2, -3).forEach { assertTrue(AndroidAudioPolicy.pauseOnFocusChange(it)) }
        listOf(0, 1, 2, 3, 4).forEach { assertFalse(AndroidAudioPolicy.pauseOnFocusChange(it)) }
    }
    @Test fun playbackRateUsesProductRange() {
        assertEquals(1.5f, AndroidAudioPolicy.rate(1.5))
        assertEquals(1f, AndroidAudioPolicy.rate(-9.0))
        assertEquals(2f, AndroidAudioPolicy.rate(9.0))
        assertThrows(IllegalArgumentException::class.java) { AndroidAudioPolicy.rate(Double.NaN) }
    }
    @Test fun alarmUsesCoreInstants() {
        assertEquals(200L, AndroidReminderPolicy.nextWakeAt(sequenceOf(500L, 200L), 100L))
        assertEquals(100L, AndroidReminderPolicy.nextWakeAt(sequenceOf(1L), 100L))
        assertNull(AndroidReminderPolicy.nextWakeAt(sequenceOf(0L, -1L, Long.MAX_VALUE), 100L))
    }
    @Test fun encryptedSecretIsIdentityBound() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val input = "test-only-secret".toByteArray()
        val encrypted = AndroidSecretEnvelope.encrypt(key, "provider-a", input)
        assertArrayEquals(input, AndroidSecretEnvelope.decrypt(key, "provider-a", encrypted))
        assertThrows(AEADBadTagException::class.java) { AndroidSecretEnvelope.decrypt(key, "provider-b", encrypted) }
        assertFalse(encrypted.contentEquals(AndroidSecretEnvelope.encrypt(key, "provider-a", input)))
    }
}
