package brain.ios

import brain.studio.Languages
import platform.Foundation.*
import kotlin.test.*

class IosSystemLanguageTest {
    @Test fun systemLanguageComesFromFoundationPreferences() {
        val expected = (NSLocale.preferredLanguages.firstOrNull() as? String)?.takeIf(String::isNotBlank) ?: "en"
        assertEquals(expected, iosSystemLanguage())
    }

    @Test fun explicitChoiceOverridesSystemLanguageThroughSharedResolver() {
        for (code in Languages.codes) assertEquals(code, Languages.resolve(code, iosSystemLanguage()))
        assertEquals("en", Languages.resolve("system", "unsupported-ZZ"))
        assertEquals("de", Languages.resolve("system", "de-DE"))
        assertEquals("kk", Languages.resolve("system", "kk_KZ"))
    }
}
