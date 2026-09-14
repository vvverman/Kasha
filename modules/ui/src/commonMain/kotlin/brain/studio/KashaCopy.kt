package brain.studio

/** Новые продуктовые строки. Порядок языков совпадает с Languages.codes. */
object KashaCopy {
    private val rows = """
tasks|Задачи|Tasks|Tareas|Tâches|Aufgaben|Завдання|Задачы|Тапсырмалар
task|Задача|Task|Tarea|Tâche|Aufgabe|Завдання|Задача|Тапсырма
note|Заметка|Note|Nota|Note|Notiz|Нотатка|Нататка|Жазба
noteText|Текст заметки|Note text|Texto de la nota|Texte de la note|Notiztext|Текст нотатки|Тэкст нататкі|Жазба мәтіні
sendToNotes|В заметки|To notes|A notas|Vers les notes|Zu Notizen|У нотатки|У нататкі|Жазбаларға
sendToTasks|В задачи|To tasks|A tareas|Vers les tâches|Zu Aufgaben|У завдання|У задачы|Тапсырмаларға
noTasks|Пока нет задач|No tasks yet|Aún no hay tareas|Aucune tâche|Noch keine Aufgaben|Поки немає завдань|Пакуль няма задач|Әзірге тапсырма жоқ
sortAlphabetical|А-Я|A-Z|A-Z|A-Z|A-Z|А-Я|А-Я|А-Я
sortCreated|Создано|Created|Creado|Créé|Erstellt|Створено|Створана|Жасалған
sortUpdated|Изменено|Updated|Actualizado|Modifié|Geändert|Змінено|Зменена|Өзгертілген
sortManual|Вручную|Manual|Manual|Manuel|Manuell|Вручну|Уручную|Қолмен
archive|Архив|Archive|Archivo|Archives|Archiv|Архів|Архіў|Мұрағат
activeTasks|Активные|Active|Activas|Actives|Aktiv|Активні|Актыўныя|Белсенді
completedTasks|Выполненные|Completed|Completadas|Terminées|Erledigt|Виконані|Выкананыя|Орындалған
completeTask|Выполнить|Complete|Completar|Terminer|Erledigen|Виконати|Выканаць|Орындау
changeTime|Изменить время|Change time|Cambiar hora|Changer l’heure|Zeit ändern|Змінити час|Змяніць час|Уақытты өзгерту
deleteTask|Удалить|Delete|Eliminar|Supprimer|Löschen|Видалити|Выдаліць|Жою
reminder|Напоминание|Reminder|Recordatorio|Rappel|Erinnerung|Нагадування|Напамін|Еске салу
dueDate|Дата|Date|Fecha|Date|Datum|Дата|Дата|Күні
dueTime|Время|Time|Hora|Heure|Uhrzeit|Час|Час|Уақыты
repeatReminder|Повторять напоминание|Repeat reminder|Repetir recordatorio|Répéter le rappel|Erinnerung wiederholen|Повторювати нагадування|Паўтараць напамін|Еске салуды қайталау
every10m|Раз в 10 минут|Every 10 minutes|Cada 10 minutos|Toutes les 10 minutes|Alle 10 Minuten|Кожні 10 хвилин|Кожныя 10 хвілін|Әр 10 минут
every30m|Раз в полчаса|Every 30 minutes|Cada 30 minutos|Toutes les 30 minutes|Alle 30 Minuten|Кожні 30 хвилин|Кожныя 30 хвілін|Әр 30 минут
hourly|Раз в час|Every hour|Cada hora|Toutes les heures|Stündlich|Щогодини|Штогадзіну|Әр сағат
daily|Каждый день|Every day|Cada día|Tous les jours|Täglich|Щодня|Штодня|Күн сайын
weekly|Каждую неделю|Every week|Cada semana|Chaque semaine|Wöchentlich|Щотижня|Штотыдзень|Апта сайын
weekends|По выходным|Weekends|Fines de semana|Le week-end|Am Wochenende|На вихідних|Па выходных|Демалыста
weekdays|По будням|Weekdays|Días laborables|En semaine|Werktags|У будні|Па буднях|Жұмыс күндері
saveReminder|Сохранить задачу|Save task|Guardar tarea|Enregistrer la tâche|Aufgabe speichern|Зберегти завдання|Захаваць задачу|Тапсырманы сақтау
invalidSchedule|Укажите будущие дату и время|Choose a future date and time|Elige una fecha y hora futuras|Choisissez une date et une heure futures|Wähle Datum und Uhrzeit in der Zukunft|Вкажіть майбутні дату й час|Укажыце будучыя дату і час|Болашақ күн мен уақытты көрсетіңіз
dueLabel|Срок|Due|Vence|Échéance|Fällig|Термін|Тэрмін|Мерзімі
completedLabel|Выполнено|Completed|Completada|Terminée|Erledigt|Виконано|Выканана|Орындалды
localOnly|Все данные и ИИ работают только на этом устройстве.|All data and AI stay on this device.|Todos los datos y la IA permanecen en este dispositivo.|Toutes les données et l’IA restent sur cet appareil.|Alle Daten und KI bleiben auf diesem Gerät.|Усі дані та ШІ залишаються на цьому пристрої.|Усе даныя і ШІ застаюцца на гэтай прыладзе.|Барлық дерек пен ЖИ осы құрылғыда қалады.
ai|ИИ|AI|IA|IA|KI|ШІ|ШІ|ЖИ
aiSpeech|Транскрибация|Transcription|Transcripción|Transcription|Transkription|Транскрипція|Транскрыпцыя|Транскрипция
aiText|Обработка текста|Text processing|Procesamiento de texto|Traitement du texte|Textverarbeitung|Обробка тексту|Апрацоўка тэксту|Мәтінді өңдеу
aiRouting|Распределение по проектам|Project routing|Distribución por proyectos|Routage des projets|Projektzuordnung|Розподіл за проєктами|Размеркаванне па праектах|Жобаларға бөлу
aiLocal|Локально|Local|Local|Local|Lokal|Локально|Лакальна|Жергілікті
aiCloud|Внешний сервис|External service|Servicio externo|Service externe|Externer Dienst|Зовнішній сервіс|Знешні сэрвіс|Сыртқы сервис
aiModels|Локальные модели|Local models|Modelos locales|Modèles locales|Lokale Modelle|Локальні моделі|Лакальныя мадэлі|Жергілікті модельдер
aiCloudProviders|Сторонние ИИ|External AI|IA externa|IA externe|Externe KI|Сторонні ШІ|Старонні ШІ|Сыртқы ЖИ
aiInstalled|Установлено|Installed|Instalado|Installé|Installiert|Встановлено|Усталявана|Орнатылған
aiDownload|Скачать|Download|Descargar|Télécharger|Herunterladen|Завантажити|Спампаваць|Жүктеу
aiRemove|Удалить модель|Remove model|Eliminar modelo|Supprimer le modèle|Modell entfernen|Видалити модель|Выдаліць мадэль|Модельді жою
aiSelected|Выбрано|Selected|Seleccionado|Sélectionné|Ausgewählt|Вибрано|Выбрана|Таңдалған
aiConnect|Подключить|Connect|Conectar|Connecter|Verbinden|Підключити|Падключыць|Қосу
aiDisconnect|Отключить|Disconnect|Desconectar|Déconnecter|Trennen|Відключити|Адключыць|Ажырату
aiApiKey|API-ключ|API key|Clave API|Clé API|API-Schlüssel|API-ключ|API-ключ|API кілті
aiModelId|Модель|Model|Modelo|Modèle|Modell|Модель|Мадэль|Модель
aiEndpoint|Endpoint|Endpoint|Endpoint|Endpoint|Endpoint|Endpoint|Endpoint|Endpoint
aiTestConnection|Проверить подключение|Test connection|Probar conexión|Tester la connexion|Verbindung testen|Перевірити підключення|Праверыць падключэнне|Қосылымды тексеру
aiSaveConnection|Сохранить подключение|Save connection|Guardar conexión|Enregistrer la connexion|Verbindung speichern|Зберегти підключення|Захаваць падключэнне|Қосылымды сақтау
aiConnectionOk|Подключение работает|Connection works|La conexión funciona|La connexion fonctionne|Verbindung funktioniert|Підключення працює|Падключэнне працуе|Қосылым жұмыс істейді
aiConnectionFailed|Не удалось подключиться|Connection failed|Error de conexión|Échec de connexion|Verbindung fehlgeschlagen|Не вдалося підключитися|Не ўдалося падключыцца|Қосылу мүмкін болмады
aiPrivacy|Приватность|Privacy|Privacidad|Confidentialité|Datenschutz|Приватність|Прыватнасць|Құпиялық
aiPrivacyWarning|При использовании внешнего ИИ содержимое заметок, транскрипций и/или аудио может передаваться стороннему провайдеру. Данные перестают быть полностью локальными.|When external AI is used, notes, transcripts and/or audio may be sent to a third-party provider. Data is no longer fully local.|Al usar IA externa, las notas, transcripciones y/o audio pueden enviarse a un proveedor externo. Los datos dejan de ser totalmente locales.|Avec une IA externe, les notes, transcriptions et/ou l’audio peuvent être envoyés à un fournisseur tiers. Les données ne restent plus entièrement locales.|Bei externer KI können Notizen, Transkripte und/oder Audio an einen Drittanbieter übertragen werden. Die Daten bleiben nicht mehr vollständig lokal.|Під час використання зовнішнього ШІ нотатки, транскрипції та/або аудіо можуть передаватися сторонньому провайдеру. Дані більше не є повністю локальними.|Пры выкарыстанні знешняга ШІ нататкі, транскрыпцыі і/або аўдыя могуць перадавацца старонняму правайдару. Даныя больш не цалкам лакальныя.|Сыртқы ЖИ қолданылса, жазбалар, транскрипциялар және/немесе аудио үшінші тарап провайдеріне жіберілуі мүмкін. Деректер толық жергілікті болмайды.
aiConsent|Я понимаю и разрешаю передачу указанных данных|I understand and allow the listed data to be sent|Entiendo y autorizo el envío de los datos indicados|Je comprends et autorise l’envoi des données indiquées|Ich verstehe und erlaube die Übertragung der genannten Daten|Я розумію та дозволяю передачу зазначених даних|Я разумею і дазваляю перадачу названых даных|Түсіндім және көрсетілген деректерді жіберуге рұқсат етемін
aiSends|Будут передаваться|Will be sent|Se enviará|Sera envoyé|Wird übertragen|Буде передаватися|Будзе перадавацца|Жіберіледі
aiDataAudio|аудио|audio|audio|audio|Audio|аудіо|аўдыя|аудио
aiDataNote|текст заметок|note text|texto de notas|texte des notes|Notiztext|текст нотаток|тэкст нататак|жазба мәтіні
aiDataProjectTitles|названия проектов|project titles|nombres de proyectos|titres des projets|Projektnamen|назви проєктів|назвы праектаў|жоба атаулары
aiDataProjectInstructions|инструкции проектов|project instructions|instrucciones de proyectos|instructions des projets|Projektanweisungen|інструкції проєктів|інструкцыі праектаў|жоба нұсқаулары
aiUnavailable|Недоступно на этой платформе|Unavailable on this platform|No disponible en esta plataforma|Indisponible sur cette plateforme|Auf dieser Plattform nicht verfügbar|Недоступно на цій платформі|Недаступна на гэтай платформе|Бұл платформада қолжетімсіз
    """.trimIndent().lineSequence().filter { it.isNotBlank() }.associate { line ->
        val cells = line.split('|')
        require(cells.size == 9) { "Incomplete Kasha localization: ${cells.first()}" }
        cells.first() to cells.drop(1)
    }

    fun has(key: String): Boolean = key in rows
    fun text(language: String, key: String): String? = rows[key]?.getOrNull(Languages.codes.indexOf(language).coerceAtLeast(0))
}
