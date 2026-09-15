package brain.android.external

import brain.studio.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidExternalAiClientTest {
    private fun configured(): CloudAiConnection {
        val draft = CloudAiConnection(providerId = "custom", endpoint = "https://example.invalid/v1",
            modelIds = AiRole.entries.associateWith { "model-${it.name}" }, enabled = true,
            privacyConsentVersion = AiPrivacy.CONSENT_VERSION)
        return draft.copy(consentSnapshot = AiPrivacy.snapshot(draft))
    }
    private class Connection : HttpURLConnection(URL("https://example.invalid")) {
        val sent = ByteArrayOutputStream()
        var body = "{\"choices\":[{\"message\":{\"content\":\"result\"}}]}".toByteArray()
        var code = 200
        @Volatile var disconnected = false
        var waitForDisconnect = false
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; released.countDown() }
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getResponseCode(): Int {
            entered.countDown()
            if (waitForDisconnect) check(released.await(5, TimeUnit.SECONDS)) { "socketWasNotCancelled" }
            return code
        }
    }
    @Test fun consentIsCheckedBeforeOpeningConnection() = runBlocking {
        var opened = false
        val client = AndroidExternalAiClient { opened = true; Connection() }
        try {
            client.generate(configured().copy(consentSnapshot = null), AiRole.TEXT, "key", "private text")
            fail("Consent was required")
        } catch (_: IllegalArgumentException) { }
        assertFalse(opened)
    }
    @Test fun requestUsesExactSelectedRoleModelAndNeverPersistsKey() = runBlocking {
        val connection = Connection()
        var url = ""
        val client = AndroidExternalAiClient { url = it.toString(); connection }
        assertEquals("result", client.generate(configured(), AiRole.ROUTING, "PRIVATE-KEY", "projects batch"))
        assertEquals("https://example.invalid/v1/chat/completions", url)
        assertEquals("Bearer PRIVATE-KEY", connection.getRequestProperty("Authorization"))
        val payload = connection.sent.toString("UTF-8")
        assertTrue(payload.contains("model-ROUTING")); assertTrue(payload.contains("projects batch"))
        assertFalse(payload.contains("PRIVATE-KEY")); assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches); assertTrue(connection.disconnected)
    }
    @Test fun redirectsAreRejectedWithoutSecondRequest() = runBlocking {
        val connection = Connection().apply { code = 302 }
        var opened = 0
        val client = AndroidExternalAiClient { opened++; connection }
        try { client.generate(configured(), AiRole.TEXT, "key", "private"); fail("Redirect accepted") }
        catch (failure: IllegalStateException) { assertEquals("cloudHttp302", failure.message) }
        assertEquals(1, opened); assertFalse(connection.instanceFollowRedirects); assertTrue(connection.disconnected)
    }
    @Test fun audioBodyPreservesOriginalAndDoesNotSendPersonalFilename() = runBlocking {
        val file = File.createTempFile("private-personal-recording-", ".wav")
        val bytes = byteArrayOf(82, 73, 70, 70, 1, 2, 3, 4)
        file.writeBytes(bytes)
        try {
            val connection = Connection().apply { body = "{\"text\":\"speech\"}".toByteArray() }
            assertEquals("speech", AndroidExternalAiClient { connection }.transcribe(configured(), "key", file.path, "ru"))
            assertArrayEquals(bytes, file.readBytes())
            val payload = connection.sent.toString("ISO-8859-1")
            assertTrue(payload.contains("audio/wav")); assertTrue(payload.contains("filename=\"audio.wav\""))
            assertTrue(payload.contains("model-SPEECH_TO_TEXT")); assertFalse(payload.contains(file.name))
            assertTrue(connection.disconnected)
        } finally { file.delete() }
    }
    @Test fun oversizedResponseIsRejectedAndClosed() = runBlocking {
        val connection = Connection().apply { body = ByteArray(brain.ai.external.ExternalAiProtocol.MAX_RESPONSE_BYTES + 1) }
        try { AndroidExternalAiClient { connection }.generate(configured(), AiRole.TEXT, "key", "input"); fail("Oversized response accepted") }
        catch (failure: IllegalStateException) { assertEquals("cloudResponseTooLarge", failure.message) }
        assertTrue(connection.disconnected)
    }
    @Test fun cancellationClosesBlockedTransport() = runBlocking {
        val connection = Connection().apply { waitForDisconnect = true }
        val job = launch(Dispatchers.Default) { AndroidExternalAiClient { connection }.generate(configured(), AiRole.TEXT, "key", "input") }
        check(connection.entered.await(5, TimeUnit.SECONDS))
        job.cancel()
        withTimeout(2000) { job.join() }
        assertTrue(job.isCancelled); assertTrue(connection.disconnected)
    }
}
