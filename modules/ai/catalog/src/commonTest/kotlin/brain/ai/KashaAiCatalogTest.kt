package brain.ai

import brain.studio.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KashaAiCatalogTest {
    @Test
    fun defaultsResolveToLocalRoleCompatibleEngines() {
        val selection = KashaAiCatalog.validateSelection(AiSelection())
        AiRole.entries.forEach { role ->
            val engine = KashaAiCatalog.engine(selection.engineId(role))
            assertNotNull(engine)
            assertTrue(engine.supports(role))
            assertEquals(AiLocality.LOCAL, engine.locality)
        }
    }

    @Test
    fun wrongRoleIsRejectedByCatalog() {
        assertFails {
            KashaAiCatalog.validateSelection(AiSelection(text = AiSelection.DEFAULT_STT))
        }
    }

    @Test
    fun providersCoverSupportedExternalApis() {
        val ids = KashaAiCatalog.cloudProviders.map { it.id }.toSet()
        assertTrue("openai" in ids)
        assertTrue("anthropic" in ids)
        assertTrue("gemini" in ids)
        assertTrue("openrouter" in ids)
        assertTrue("openai-compatible" in ids)
        assertTrue("custom" in ids)
    }

    @Test
    fun cloudEngineIdsRoundTripRoleAndProvider() {
        val id = KashaAiCatalog.cloudEngineId("openai", AiRole.TEXT)
        assertEquals("openai", KashaAiCatalog.cloudProviderId(id))
        assertEquals(AiRole.TEXT, KashaAiCatalog.cloudRole(id))
        assertTrue(KashaAiCatalog.supportsSelection(id, AiRole.TEXT))
    }
}
