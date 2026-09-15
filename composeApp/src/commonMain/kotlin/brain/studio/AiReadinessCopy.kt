package brain.studio

/** Общие подписи готовности для всех платформ и восьми языков Kasha. */
internal object AiReadinessCopy {
    private val keys = listOf(
        "ready", "cloudReady", "capabilityChecking", "platformUnavailable", "languageUnsupported",
        "modelNotInstalled", "modelDownloading", "modelInstallFailed", "runtimeUnavailable",
        "permissionRequired", "permissionDenied", "secureStoreUnavailable", "cloudConnectionDisabled",
        "cloudConsentRequired", "cloudModelRequired", "apiKeyMissing", "capabilityCheckFailed",
        "INSTALL_MODEL", "CHOOSE_ENGINE", "REQUEST_PERMISSION", "OPEN_SETTINGS", "EDIT_CONNECTION", "RETRY", "connectionSaved",
    )
    private val values = mapOf(
        "ru" to listOf(
            "Готово к запуску", "Готово к запросу", "Проверяем доступность…", "Не поддерживается в этой сборке", "Недоступно для выбранного языка",
            "Модель не установлена", "Модель загружается", "Не удалось установить модель", "Движок не запускается",
            "Нужно разрешение", "Доступ запрещён в настройках устройства", "Защищённое хранилище недоступно", "Подключение отключено",
            "Подтвердите передачу данных", "Не выбрана модель для этой функции", "API-ключ отсутствует", "Не удалось проверить состояние",
            "Скачать", "Другой способ", "Разрешить", "Разрешения устройства", "Настроить подключение", "Проверить снова", "Подключение сохранено, не проверено",
        ),
        "en" to listOf(
            "Ready to run", "Ready to send a request", "Checking availability…", "Not supported in this build", "Unavailable for this language",
            "Model not installed", "Downloading model", "Model installation failed", "Engine cannot start",
            "Permission required", "Access denied in device settings", "Secure storage unavailable", "Connection disabled",
            "Confirm data sharing", "No model selected for this function", "API key missing", "Could not check availability",
            "Download", "Choose another", "Allow", "Device permissions", "Configure connection", "Check again", "Connection saved, not tested",
        ),
        "es" to listOf(
            "Listo para ejecutar", "Listo para enviar una solicitud", "Comprobando disponibilidad…", "No compatible con esta versión", "No disponible para este idioma",
            "Modelo no instalado", "Descargando modelo", "Error al instalar el modelo", "El motor no puede iniciarse",
            "Se requiere permiso", "Acceso denegado en los ajustes del dispositivo", "Almacenamiento seguro no disponible", "Conexión desactivada",
            "Confirma el envío de datos", "No hay modelo para esta función", "Falta la clave API", "No se pudo comprobar la disponibilidad",
            "Descargar", "Elegir otro", "Permitir", "Permisos del dispositivo", "Configurar conexión", "Comprobar de nuevo", "Conexión guardada, sin comprobar",
        ),
        "fr" to listOf(
            "Prêt à démarrer", "Prêt à envoyer une requête", "Vérification de la disponibilité…", "Non pris en charge dans cette version", "Indisponible pour cette langue",
            "Modèle non installé", "Téléchargement du modèle", "Échec de l’installation du modèle", "Le moteur ne peut pas démarrer",
            "Autorisation requise", "Accès refusé dans les réglages de l’appareil", "Stockage sécurisé indisponible", "Connexion désactivée",
            "Confirmez l’envoi des données", "Aucun modèle choisi pour cette fonction", "Clé API absente", "Impossible de vérifier la disponibilité",
            "Télécharger", "Choisir un autre", "Autoriser", "Autorisations de l’appareil", "Configurer la connexion", "Vérifier à nouveau", "Connexion enregistrée, non testée",
        ),
        "de" to listOf(
            "Startbereit", "Bereit für eine Anfrage", "Verfügbarkeit wird geprüft…", "In dieser Version nicht unterstützt", "Für diese Sprache nicht verfügbar",
            "Modell nicht installiert", "Modell wird heruntergeladen", "Modellinstallation fehlgeschlagen", "Die Engine kann nicht starten",
            "Berechtigung erforderlich", "Zugriff in den Geräteeinstellungen verweigert", "Sicherer Speicher nicht verfügbar", "Verbindung deaktiviert",
            "Datenübertragung bestätigen", "Kein Modell für diese Funktion gewählt", "API-Schlüssel fehlt", "Verfügbarkeit konnte nicht geprüft werden",
            "Herunterladen", "Andere wählen", "Erlauben", "Geräteberechtigungen", "Verbindung einrichten", "Erneut prüfen", "Verbindung gespeichert, nicht geprüft",
        ),
        "uk" to listOf(
            "Готово до запуску", "Готово до запиту", "Перевіряємо доступність…", "Не підтримується в цій збірці", "Недоступно для вибраної мови",
            "Модель не встановлена", "Модель завантажується", "Не вдалося встановити модель", "Рушій не запускається",
            "Потрібен дозвіл", "Доступ заборонено в налаштуваннях пристрою", "Захищене сховище недоступне", "Підключення вимкнено",
            "Підтвердьте передавання даних", "Не вибрано модель для цієї функції", "API-ключ відсутній", "Не вдалося перевірити стан",
            "Завантажити", "Інший спосіб", "Дозволити", "Дозволи пристрою", "Налаштувати підключення", "Перевірити знову", "Підключення збережено, не перевірено",
        ),
        "be" to listOf(
            "Гатова да запуску", "Гатова да запыту", "Правяраем даступнасць…", "Не падтрымліваецца ў гэтай зборцы", "Недаступна для выбранай мовы",
            "Мадэль не ўсталявана", "Мадэль спампоўваецца", "Не ўдалося ўсталяваць мадэль", "Рухавік не запускаецца",
            "Патрэбны дазвол", "Доступ забаронены ў наладах прылады", "Абароненае сховішча недаступнае", "Падключэнне адключана",
            "Пацвердзіце перадачу даных", "Не выбрана мадэль для гэтай функцыі", "API-ключ адсутнічае", "Не ўдалося праверыць стан",
            "Спампаваць", "Іншы спосаб", "Дазволіць", "Дазволы прылады", "Наладзіць падключэнне", "Праверыць зноў", "Падключэнне захавана, не праверана",
        ),
        "kk" to listOf(
            "Іске қосуға дайын", "Сұрау жіберуге дайын", "Қолжетімділік тексерілуде…", "Бұл нұсқада қолдау жоқ", "Таңдалған тіл үшін қолжетімсіз",
            "Модель орнатылмаған", "Модель жүктелуде", "Модельді орнату мүмкін болмады", "Қозғалтқыш іске қосылмайды",
            "Рұқсат қажет", "Құрылғы баптауларында кіруге тыйым салынған", "Қорғалған сақтау орны қолжетімсіз", "Қосылым өшірілген",
            "Деректерді жіберуді растаңыз", "Бұл функция үшін модель таңдалмаған", "API кілті жоқ", "Күйді тексеру мүмкін болмады",
            "Жүктеу", "Басқасын таңдау", "Рұқсат беру", "Құрылғы рұқсаттары", "Қосылымды баптау", "Қайта тексеру", "Қосылым сақталған, тексерілмеген",
        ),
    ).mapValues { (_, texts) ->
        require(texts.size == keys.size)
        keys.zip(texts).toMap()
    }

    fun text(language: String, key: String): String {
        val copy = values[language.substringBefore('-').substringBefore('_')] ?: values.getValue("en")
        return copy[key] ?: copy.getValue("capabilityCheckFailed")
    }
    fun status(language: String, capability: AiRoleCapability, locality: AiLocality?): String =
        text(language, if (capability.executable) { if (locality == AiLocality.CLOUD) "cloudReady" else "ready" }
            else capability.reason ?: "capabilityCheckFailed")
    fun complete(language: String): Boolean = values[language]?.keys == keys.toSet()
}
