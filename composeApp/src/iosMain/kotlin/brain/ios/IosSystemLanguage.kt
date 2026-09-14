package brain.ios

import platform.Foundation.*

/** Только факт системной локали; выбор поддерживаемого языка выполняет общий Languages.resolve. */
internal fun iosSystemLanguage(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)?.takeIf(String::isNotBlank) ?: "en"
