package brain.studio

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import brain.domain.NoteText
import brain.model.*
import kotlinx.coroutines.launch

private fun tx(s: StudioState, key: String) = KashaCopy.text(s.language, key) ?: s.tr(key)
private fun sortLabels(s: StudioState) = mapOf(
    SortMode.ALPHABETICAL to tx(s, "sortAlphabetical"),
    SortMode.CREATED to tx(s, "sortCreated"),
    SortMode.UPDATED to tx(s, "sortUpdated"),
    SortMode.MANUAL to tx(s, "sortManual"),
)

@Composable
private fun ProjectLine(p: Project, onClick: () -> Unit, onEdit: (() -> Unit)? = null, editLabel: String = "", dragging: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    KashaListCard(onClick = onClick, modifier = Modifier.graphicsLayer { alpha = if (dragging) .72f else 1f }) {
        KashaIcon(if (p.pinned) Glyph.PIN else Glyph.FOLDER, Modifier.size(21.dp), animated = dragging)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            val detail = p.instruction.ifBlank { p.description }
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onEdit != null) IconAction(editLabel, Glyph.MORE, onEdit, modifier = Modifier.size(32.dp))
        else KashaIcon(Glyph.NEXT, Modifier.size(16.dp), colors.onSurfaceVariant)
    }
}

@Composable
internal fun ProjectsScreen(s: StudioState) {
    val project = s.snapshot.projects.firstOrNull { it.id == s.selectedProjectId }
    val note = s.snapshot.notes.firstOrNull { it.id == s.selectedNoteId }
    val scope = rememberCoroutineScope()
    when {
        note != null && s.editingNoteId == note.id -> NoteEditorScreen(s, note)
        note != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 22.dp)) {
            Heading(NoteText.title(note.body), { s.selectedNoteId = null; s.editingNoteId = null }, s.tr("back")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconAction(s.tr(if (note.pinned) "unpin" else "pin"), Glyph.PIN, { scope.launch { s.pinNote(note) } }, filled = note.pinned, modifier = Modifier.size(34.dp))
                    IconAction(s.tr("edit"), Glyph.EDIT, { s.beginNoteEdit(note.id) }, modifier = Modifier.size(34.dp))
                }
            }
            KashaNoteText(
                value = note.body,
                onValueChange = {},
                placeholder = tx(s, "noteText"),
                readOnly = true,
                onEditRequest = { s.beginNoteEdit(note.id) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(28.dp))
            Text(s.tr("sources"), style = MaterialTheme.typography.titleSmall)
            Text(s.tr("sourceHint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            s.noteSources(note.id).forEach { source ->
                Spacer(Modifier.height(10.dp))
                KashaListCard(onClick = { scope.launch { s.requestListen(source.id) } }) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Wave(source.waveform, Modifier.fillMaxWidth().height(30.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(clock(source.durationSeconds.toLong()), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        project != null -> Column {
            Heading(project.title, { s.selectedProjectId = null }, s.tr("back")) {
                IconAction(s.tr("edit"), Glyph.MORE, { s.editingProjectId = project.id })
            }
            KashaSortBar(s.preferences.noteSort, sortLabels(s), { scope.launch { s.setNoteSort(it) } })
            Spacer(Modifier.height(14.dp))
            val notes = s.projectNotes(project.id)
            if (notes.isEmpty()) Text(s.tr("noNotes"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else KashaReorderableList(
                items = notes,
                key = { it.id },
                manual = s.preferences.noteSort == SortMode.MANUAL,
                onManualOrder = { ids -> scope.launch { s.reorderNotes(project.id, ids) } },
                modifier = Modifier.fillMaxSize(),
            ) { n, dragging -> NoteLine(n, dragging) { s.openNote(n.id) } }
        }
        else -> Column {
            Heading(s.tr("projects")) { IconAction(s.tr("newProject"), Glyph.PLUS, { s.beginProjectCreation() }) }
            KashaSortBar(s.preferences.projectSort, sortLabels(s), { scope.launch { s.setProjectSort(it) } })
            Spacer(Modifier.height(14.dp))
            val projects = s.projects()
            if (projects.isEmpty()) Text(s.tr("noProjects"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else KashaReorderableList(
                items = projects,
                key = { it.id },
                manual = s.preferences.projectSort == SortMode.MANUAL,
                onManualOrder = { ids -> scope.launch { s.reorderProjects(ids) } },
                modifier = Modifier.fillMaxSize(),
                spacing = 12.dp,
            ) { p, dragging ->
                ProjectLine(p, { s.selectedProjectId = p.id; s.selectedNoteId = null }, { s.editingProjectId = p.id }, s.tr("edit"), dragging)
            }
        }
    }
}

@Composable
private fun NoteLine(note: Note, dragging: Boolean = false, onClick: () -> Unit) {
    KashaListCard(onClick = onClick, modifier = Modifier.graphicsLayer { alpha = if (dragging) .72f else 1f }) {
        Column(Modifier.weight(1f)) {
            Text(NoteText.title(note.body), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val preview = NoteText.preview(note.body)
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (note.pinned) { Spacer(Modifier.width(10.dp)); KashaIcon(Glyph.PIN, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant, animated = dragging) }
    }
}

@Composable
private fun NoteEditorScreen(s: StudioState, note: Note) {
    val scope = rememberCoroutineScope()
    var body by remember(note.id) { mutableStateOf(note.body) }
    Column(Modifier.fillMaxSize().padding(bottom = 14.dp)) {
        Heading(NoteText.title(body), s::cancelNoteEdit, s.tr("back"))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            KashaNoteText(
                value = body,
                onValueChange = { body = it },
                placeholder = tx(s, "noteText"),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(18.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietAction(s.tr("cancel"), s::cancelNoteEdit)
            Spacer(Modifier.width(12.dp))
            Action(s.tr("save"), { scope.launch { s.saveNote(note.id, body) } }, primary = true, enabled = body.isNotBlank() && !s.busy, modifier = Modifier.weight(1f))
        }
    }
}

/** Выбор проекта теперь относится только к сохранению заметки. */
@Composable
internal fun DestinationScreen(s: StudioState) {
    val scope = rememberCoroutineScope()
    val project = s.snapshot.projects.firstOrNull { it.id == s.targetProjectId }
    Column(Modifier.fillMaxSize()) {
        Heading(if (project == null) s.tr("chooseProject") else project.title, {
            if (project == null) s.choosingProject = false else s.targetProjectId = null
        }, s.tr("back"))

        if (project == null) {
            Action(s.tr("createProject"), { s.beginProjectCreation(fromPicker = true) }, glyph = Glyph.PLUS,
                enabled = !s.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            KashaReorderableList(
                items = s.orderedProjects(), key = { it.id }, manual = false, onManualOrder = {}, modifier = Modifier.weight(1f), spacing = 12.dp,
            ) { p, _ -> ProjectLine(p, { s.targetProjectId = p.id }) }
        } else {
            Action(s.tr("newNote"), { scope.launch { s.distribute(project.id) } }, primary = true, glyph = Glyph.PLUS,
                enabled = !s.busy, modifier = Modifier.fillMaxWidth())
            Text(s.tr("appendHint"), Modifier.padding(vertical = 22.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KashaReorderableList(
                items = s.projectNotes(project.id), key = { it.id }, manual = false, onManualOrder = {}, modifier = Modifier.weight(1f),
            ) { n, _ -> NoteLine(n) { if (!s.busy) scope.launch { s.distribute(project.id, n.id) } } }
        }
    }
}

@Composable
internal fun ProjectEditor(s: StudioState) {
    val scope = rememberCoroutineScope()
    val project = s.snapshot.projects.firstOrNull { it.id == s.editingProjectId }
    var title by remember(s.editingProjectId) { mutableStateOf(project?.title.orEmpty()) }
    var instruction by remember(s.editingProjectId) { mutableStateOf(project?.instruction.orEmpty()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
        Heading(if (project == null) s.tr("newProject") else s.tr("edit"), s::cancelProjectEdit, s.tr("back"))
        Editor(title, { title = it }, s.tr("projectName"), Modifier.fillMaxWidth(), title = true)
        Spacer(Modifier.height(18.dp))
        Editor(instruction, { instruction = it }, s.tr("instruction"), Modifier.fillMaxWidth().heightIn(min = 130.dp))
        Spacer(Modifier.height(7.dp))
        Text(s.tr("instructionHint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        Action(s.tr("save"), { scope.launch { if (project == null) s.createProject(title, instruction) else s.updateProject(project.id, title, instruction) } },
            primary = true, enabled = title.isNotBlank() && !s.busy, modifier = Modifier.fillMaxWidth())
        if (project != null) {
            ToggleRow(s.tr("pin"), project.pinned) { scope.launch { s.pin(project) } }
            if (project.pinned) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action(s.tr("up"), { scope.launch { s.movePin(project, -1) } }, glyph = Glyph.UP, modifier = Modifier.weight(1f))
                Action(s.tr("down"), { scope.launch { s.movePin(project, 1) } }, glyph = Glyph.DOWN, modifier = Modifier.weight(1f))
            }
        }
    }
}
