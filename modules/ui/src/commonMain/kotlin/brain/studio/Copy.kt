package brain.studio

/** Восемь полных наборов строк. Автоязык интерфейса не задаёт язык распознавания речи. */
object Copy {
    private val rows = """
firstProject|Твой первый проект|Your first project|Tu primer proyecto|Ton premier projet|Dein erstes Projekt|Твій перший проєкт|Твой першы праект|Алғашқы жобаңыз
createProject|Создать проект|Create project|Crear proyecto|Créer un projet|Projekt erstellen|Створити проєкт|Стварыць праект|Жоба жасау
submitRecording|Отправить|Send|Enviar|Envoyer|Senden|Надіслати|Адправіць|Жіберу
home|Главная|Home|Inicio|Accueil|Start|Головна|Галоўная|Басты
projects|Проекты|Projects|Proyectos|Projets|Projekte|Проєкти|Праекты|Жобалар
settings|Настройки|Settings|Ajustes|Réglages|Einstellungen|Налаштування|Налады|Баптаулар
appLanguage|Язык приложения|App language|Idioma|Langue|App-Sprache|Мова застосунку|Мова праграмы|Қолданба тілі
system|Системная|System|Sistema|Système|System|Системна|Сістэмная|Жүйелік
systemLanguage|Язык системы|System language|Idioma del sistema|Langue du système|Systemsprache|Мова системи|Мова сістэмы|Жүйе тілі
light|Светлая|Light|Claro|Clair|Hell|Світла|Светлая|Ашық
dark|Тёмная|Dark|Oscuro|Sombre|Dunkel|Темна|Цёмная|Қараңғы
theme|Тема|Appearance|Tema|Apparence|Darstellung|Тема|Тэма|Тақырып
record|Запись|Record|Grabar|Enregistrer|Aufnehmen|Запис|Запіс|Жазу
pause|Пауза|Pause|Pausa|Pause|Pause|Пауза|Паўза|Кідірту
resume|Продолжить|Resume|Continuar|Reprendre|Fortsetzen|Продовжити|Працягнуць|Жалғастыру
stop|Стоп|Stop|Detener|Arrêter|Stopp|Стоп|Стоп|Тоқтату
play|Воспроизвести|Play|Reproducir|Écouter|Abspielen|Відтворити|Прайграць|Ойнату
speed|Скорость|Speed|Velocidad|Vitesse|Tempo|Швидкість|Хуткасць|Жылдамдық
recording|Идёт запись|Recording|Grabando|Enregistrement|Aufnahme läuft|Триває запис|Ідзе запіс|Жазылуда
paused|Запись на паузе|Recording paused|Grabación en pausa|Enregistrement en pause|Aufnahme pausiert|Запис на паузі|Запіс на паўзе|Жазу кідіртілді
processing|Обрабатываем запись|Processing recording|Procesando grabación|Traitement en cours|Aufnahme verarbeiten|Обробляємо запис|Апрацоўваем запіс|Жазба өңделуде
transcribing|Распознаём речь|Transcribing|Transcribiendo|Transcription|Transkription|Розпізнаємо мовлення|Распазнаём маўленне|Сөйлеуді тану
compacting|Сокращаем паузы|Removing pauses|Reduciendo pausas|Réduction des pauses|Pausen kürzen|Скорочуємо паузи|Скарачаем паўзы|Үзілістерді қысқарту
preparing|Готовим заметку|Preparing note|Preparando nota|Préparation de la note|Notiz vorbereiten|Готуємо нотатку|Рыхтуем нататку|Жазбаны дайындау
untitled|Название заметки|Note title|Título de la nota|Titre de la note|Titel der Notiz|Назва нотатки|Назва нататкі|Жазба атауы
body|Текст заметки|Note text|Texto de la nota|Texte de la note|Notiztext|Текст нотатки|Тэкст нататкі|Жазба мәтіні
emptyTitle|Освободите\nмысли.|Make room\nfor thoughts.|Espacio para\ntus ideas.|Libérez\nvos idées.|Raum für\nGedanken.|Звільніть\nдумки.|Вызваліце\nдумкі.|Ойларға\nорын беріңіз.
emptyBody|Нажмите запись. Остальное — после.|Press record. Everything else can wait.|Pulsa grabar. Lo demás puede esperar.|Enregistrez. Le reste peut attendre.|Aufnehmen. Alles andere kann warten.|Натисніть запис. Решта — потім.|Націсніце запіс. Астатняе — потым.|Жазуды басыңыз. Қалғаны — кейін.
send|Отправить в проект|Send to project|Enviar al proyecto|Envoyer au projet|Zum Projekt senden|Надіслати в проєкт|Адправіць у праект|Жобаға жіберу
tidy|Привести в порядок|Tidy up|Ordenar texto|Mettre en forme|Text aufräumen|Упорядкувати|Упарадкаваць|Ретке келтіру
tidying|Приводим в порядок|Tidying up|Ordenando|Mise en forme|Text wird aufgeräumt|Упорядковуємо|Упарадкоўваем|Реттелуде
cancelNote|Отменить заметку|Discard note|Descartar nota|Supprimer la note|Notiz verwerfen|Скасувати нотатку|Скасаваць нататку|Жазбадан бас тарту
deleteTitle|Удалить эту запись?|Delete this recording?|¿Eliminar esta grabación?|Supprimer cet enregistrement ?|Aufnahme löschen?|Видалити цей запис?|Выдаліць гэты запіс?|Осы жазбаны жою керек пе?
deleteBody|Аудио и текст будут удалены. Отменить это действие нельзя.|Audio and text will be deleted. This cannot be undone.|Se eliminarán el audio y el texto. No se puede deshacer.|L’audio et le texte seront supprimés. Action irréversible.|Audio und Text werden unwiderruflich gelöscht.|Аудіо й текст буде видалено. Це неможливо скасувати.|Аўдыя і тэкст будуць выдалены. Дзеянне нельга адмяніць.|Аудио мен мәтін жойылады. Бұл әрекетті кері қайтару мүмкін емес.
delete|Удалить|Delete|Eliminar|Supprimer|Löschen|Видалити|Выдаліць|Жою
cancel|Отмена|Cancel|Cancelar|Annuler|Abbrechen|Скасувати|Скасаваць|Бас тарту
back|Назад|Back|Atrás|Retour|Zurück|Назад|Назад|Артқа
save|Сохранить|Save|Guardar|Enregistrer|Speichern|Зберегти|Захаваць|Сақтау
chooseProject|Выбрать проект|Choose a project|Elegir proyecto|Choisir un projet|Projekt wählen|Вибрати проєкт|Выбраць праект|Жобаны таңдау
chooseNote|Куда добавить?|Where to add it?|¿Dónde añadirla?|Où l’ajouter ?|Wohin hinzufügen?|Куди додати?|Куды дадаць?|Қайда қосу керек?
newNote|Новая заметка|New note|Nueva nota|Nouvelle note|Neue Notiz|Нова нотатка|Новая нататка|Жаңа жазба
appendHint|Или добавить в конец существующей|Or append to an existing note|O añadir al final de una nota|Ou ajouter à la fin d’une note|Oder an eine Notiz anhängen|Або додати в кінець наявної|Або дадаць у канец існай|Немесе бар жазбаның соңына қосу
newProject|Новый проект|New project|Nuevo proyecto|Nouveau projet|Neues Projekt|Новий проєкт|Новы праект|Жаңа жоба
projectName|Название проекта|Project name|Nombre del proyecto|Nom du projet|Projektname|Назва проєкту|Назва праекта|Жоба атауы
instruction|Что сюда складывать|What belongs here|Qué guardar aquí|Quoi ranger ici|Was gehört hierher|Що сюди складати|Што сюды складаць|Мұнда не сақтау керек
instructionHint|Необязательно. Название тоже помогает выбрать проект.|Optional. The name also helps match the project.|Opcional. El nombre también ayuda a elegir.|Facultatif. Le nom aide aussi au classement.|Optional. Der Name hilft ebenfalls bei der Zuordnung.|Необов’язково. Назва теж допомагає вибрати проєкт.|Неабавязкова. Назва таксама дапамагае выбраць праект.|Міндетті емес. Атау да жобаны таңдауға көмектеседі.
noProjects|Пока нет проектов|No projects yet|Aún no hay proyectos|Aucun projet|Noch keine Projekte|Поки немає проєктів|Пакуль няма праектаў|Әзірге жобалар жоқ
createInProjects|Создайте проект в разделе «Проекты». Запись останется на Главной.|Create a project in Projects. Your recording stays on Home.|Crea un proyecto en Proyectos. La grabación queda en Inicio.|Créez un projet dans Projets. L’enregistrement reste à l’accueil.|Unter Projekte ein Projekt erstellen. Die Aufnahme bleibt auf Start.|Створіть проєкт у розділі «Проєкти». Запис залишиться на Головній.|Стварыце праект у «Праектах». Запіс застанецца на Галоўнай.|«Жобалар» бөлімінде жоба жасаңыз. Жазба Басты бетте қалады.
noNotes|Пока нет заметок|No notes yet|Aún no hay notas|Aucune note|Noch keine Notizen|Поки немає нотаток|Пакуль няма нататак|Әзірге жазбалар жоқ
notes|Заметки|Notes|Notas|Notes|Notizen|Нотатки|Нататкі|Жазбалар
sources|Аудиозаписи|Audio recordings|Grabaciones|Enregistrements|Audioaufnahmen|Аудіозаписи|Аўдыязапісы|Аудиожазбалар
sourceHint|Выберите запись для плеера|Select a recording for the player|Elige una grabación|Choisissez un enregistrement|Aufnahme für den Player wählen|Виберіть запис для плеєра|Выберыце запіс для плэера|Ойнатқыш үшін жазбаны таңдаңыз
pin|Закрепить|Pin|Fijar|Épingler|Anheften|Закріпити|Замацаваць|Бекіту
unpin|Открепить|Unpin|Desfijar|Détacher|Lösen|Відкріпити|Адмацаваць|Бекітуді алу
up|Выше|Up|Subir|Monter|Nach oben|Вище|Вышэй|Жоғары
down|Ниже|Down|Bajar|Descendre|Nach unten|Нижче|Ніжэй|Төмен
edit|Править|Edit|Editar|Modifier|Bearbeiten|Редагувати|Змяніць|Өңдеу
recordSettings|Запись и сохранение|Recording & saving|Grabación y guardado|Enregistrement|Aufnehmen & speichern|Запис і збереження|Запіс і захаванне|Жазу және сақтау
quality|Качество аудио|Audio quality|Calidad de audio|Qualité audio|Audioqualität|Якість аудіо|Якасць аўдыя|Аудио сапасы
economy|Экономное|Compact|Compacto|Compact|Kompakt|Економне|Эканомнае|Үнемді
high|Высокое|High|Alta|Haute|Hoch|Висока|Высокая|Жоғары
savedSpeed|Скорость сохраняемой записи|Saved audio speed|Velocidad del audio guardado|Vitesse de l’audio enregistré|Tempo gespeicherter Aufnahmen|Швидкість збереженого запису|Хуткасць захаванага запісу|Сақталатын жазба жылдамдығы
autoRecord|Начать запись при включении|Record on launch|Grabar al abrir|Enregistrer au lancement|Beim Start aufnehmen|Починати запис при запуску|Пачынаць запіс пры запуску|Іске қосылғанда жазуды бастау
autoRoute|Сразу выбирать проект после записи|Choose project after recording|Elegir proyecto tras grabar|Choisir un projet après l’enregistrement|Nach Aufnahme Projekt wählen|Одразу вибирати проєкт після запису|Адразу выбіраць праект пасля запісу|Жазудан кейін бірден жоба таңдау
appearance|Внешний вид|Appearance|Apariencia|Apparence|Darstellung|Зовнішній вигляд|Выгляд|Сыртқы түрі
confirmListen|Остановить запись и начать прослушивание?|Stop recording and play audio?|¿Detener la grabación y reproducir?|Arrêter l’enregistrement et écouter ?|Aufnahme stoppen und abspielen?|Зупинити запис і почати прослуховування?|Спыніць запіс і пачаць праслухоўванне?|Жазуды тоқтатып, тыңдауды бастау керек пе?
stopAndPlay|Остановить и слушать|Stop and play|Detener y reproducir|Arrêter et écouter|Stoppen und abspielen|Зупинити й слухати|Спыніць і слухаць|Тоқтату және тыңдау
preserveRecording|Текущая запись сохранится на Главной.|The current recording will remain on Home.|La grabación actual quedará en Inicio.|L’enregistrement actuel restera à l’accueil.|Die aktuelle Aufnahme bleibt auf Start.|Поточний запис залишиться на Головній.|Бягучы запіс застанецца на Галоўнай.|Ағымдағы жазба Басты бетте сақталады.
demoBadge|ТЕСТ · ИИ ИМИТИРУЕТСЯ|TEST · SIMULATED AI|PRUEBA · IA SIMULADA|TEST · IA SIMULÉE|TEST · KI-SIMULATION|ТЕСТ · ІМІТАЦІЯ ШІ|ТЭСТ · ІМІТАЦЫЯ ШІ|СЫНАҚ · ЖИ ЕЛІКТЕМЕСІ
demoNotice|Текст — пример, не расшифровка вашего голоса. Запись аудио настоящая.|Text is a sample, not your speech transcript. Audio recording is real.|El texto es de ejemplo, no una transcripción. El audio grabado es real.|Le texte est un exemple, pas votre transcription. L’audio est réel.|Der Text ist ein Beispiel, keine Transkription. Die Audioaufnahme ist echt.|Текст — приклад, не розшифровка вашого голосу. Аудіозапис справжній.|Тэкст — прыклад, не расшыфроўка вашага голасу. Аўдыязапіс сапраўдны.|Мәтін — мысал, дауысыңыздың транскрипті емес. Аудиожазба нақты.
demoButton|Попробовать без микрофона|Try without microphone|Probar sin micrófono|Essayer sans microphone|Ohne Mikrofon testen|Спробувати без мікрофона|Паспрабаваць без мікрафона|Микрофонсыз сынап көру
demoSettings|Имитация ИИ|AI simulation|Simulación de IA|Simulation IA|KI-Simulation|Імітація ШІ|Імітацыя ШІ|ЖИ еліктемесі
demoExample|Тестовый текст|Sample text|Texto de ejemplo|Texte d’exemple|Beispieltext|Тестовий текст|Тэставы тэкст|Сынақ мәтіні
idea|Идея приложения|App idea|Idea de aplicación|Idée d’application|App-Idee|Ідея застосунку|Ідэя праграмы|Қолданба идеясы
password|Учебный пароль|Example password|Contraseña de ejemplo|Mot de passe fictif|Beispielpasswort|Навчальний пароль|Навучальны пароль|Сынақ құпиясөзі
cooking|Рецепт|Recipe|Receta|Recette|Rezept|Рецепт|Рэцэпт|Рецепт
retry|Повторить|Retry|Reintentar|Réessayer|Wiederholen|Повторити|Паўтарыць|Қайталау
recover|Восстановить запись|Recover recording|Recuperar grabación|Récupérer l’enregistrement|Aufnahme wiederherstellen|Відновити запис|Аднавіць запіс|Жазбаны қалпына келтіру
recoveryNotice|Есть незавершённая запись. Восстановите её перед новой.|An unfinished recording needs recovery first.|Hay una grabación por recuperar.|Un enregistrement doit être récupéré.|Eine Aufnahme muss wiederhergestellt werden.|Спочатку відновіть незавершений запис.|Спачатку аднавіце незавершаны запіс.|Алдымен аяқталмаған жазбаны қалпына келтіріңіз.
currentExists|Сначала отправьте текущую заметку в проект или отмените её.|Send or discard the current note first.|Envía o descarta primero la nota actual.|Envoyez ou supprimez d’abord la note actuelle.|Aktuelle Notiz zuerst speichern oder verwerfen.|Спочатку надішліть або скасуйте поточну нотатку.|Спачатку адпраўце або скасуйце бягучую нататку.|Алдымен ағымдағы жазбаны жіберіңіз немесе одан бас тартыңыз.
stopPlayback|Сначала остановите воспроизведение.|Stop playback first.|Detén primero la reproducción.|Arrêtez d’abord la lecture.|Zuerst Wiedergabe stoppen.|Спочатку зупиніть відтворення.|Спачатку спыніце прайграванне.|Алдымен ойнатуды тоқтатыңыз.
stopRecording|Сначала остановите запись.|Stop recording first.|Detén primero la grabación.|Arrêtez d’abord l’enregistrement.|Zuerst Aufnahme stoppen.|Спочатку зупиніть запис.|Спачатку спыніце запіс.|Алдымен жазуды тоқтатыңыз.
emptyText|Добавьте текст заметки.|Enter note text.|Escribe el texto de la nota.|Saisissez le texte de la note.|Notiztext eingeben.|Додайте текст нотатки.|Дадайце тэкст нататкі.|Жазба мәтінін енгізіңіз.
actionFailed|Не удалось выполнить действие. Сохранённые данные не удалены.|Action failed. Saved data was not deleted.|No se pudo completar. Los datos guardados siguen intactos.|Échec de l’action. Les données enregistrées sont conservées.|Aktion fehlgeschlagen. Gespeicherte Daten bleiben erhalten.|Не вдалося виконати дію. Збережені дані не видалено.|Не атрымалася выканаць дзеянне. Захаваныя даныя не выдалены.|Әрекет орындалмады. Сақталған деректер жойылған жоқ.
audioFailed|Аудио недоступно. Проверьте микрофон и разрешение приложения в настройках системы.|Audio unavailable. Check the microphone and app permissions in system settings.|Audio no disponible. Revisa el micrófono y los permisos del sistema.|Audio indisponible. Vérifiez le microphone et les autorisations système.|Audio nicht verfügbar. Mikrofon und Systemberechtigungen prüfen.|Аудіо недоступне. Перевірте мікрофон і системні дозволи.|Аўдыя недаступнае. Праверце мікрафон і сістэмныя дазволы.|Аудио қолжетімсіз. Микрофон мен жүйелік рұқсаттарды тексеріңіз.
saveFailed|Не удалось сохранить изменения. Не закрывайте приложение; повторите сохранение.|Could not save changes. Keep the app open and retry.|No se guardaron los cambios. Mantén la app abierta y reintenta.|Enregistrement impossible. Gardez l’application ouverte et réessayez.|Änderungen nicht gespeichert. App offen lassen und erneut versuchen.|Не вдалося зберегти зміни. Не закривайте застосунок; повторіть.|Не атрымалася захаваць змены. Не закрывайце праграму; паўтарыце.|Өзгерістер сақталмады. Қолданбаны жаппай, қайта сақтап көріңіз.
ok|Понятно|OK|Entendido|Compris|OK|Зрозуміло|Зразумела|Түсінікті
processingFailed|Обработка не завершилась. Можно повторить.|Processing did not finish. You can retry.|El proceso no terminó. Puedes reintentar.|Le traitement a échoué. Réessayez.|Verarbeitung fehlgeschlagen. Erneut versuchen.|Обробку не завершено. Можна повторити.|Апрацоўка не завершана. Можна паўтарыць.|Өңдеу аяқталмады. Қайталап көруге болады.
    """.trimIndent().lineSequence().filter { it.isNotBlank() }.associate { line ->
        val cells = line.split('|'); require(cells.size == 9) { "Incomplete localization: ${cells.first()}" }
        cells.first() to cells.drop(1).map { it.replace("\\n", "\n") }
    }
    fun has(key: String) = key in rows
    fun text(language: String, key: String): String = rows.getValue(key)[Languages.codes.indexOf(language).coerceAtLeast(0)]
    fun keys() = rows.keys
}
