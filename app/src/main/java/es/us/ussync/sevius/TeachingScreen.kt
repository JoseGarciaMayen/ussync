package es.us.ussync.sevius

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import es.us.ussync.blackboard.EvCourse
import es.us.ussync.data.*
import es.us.ussync.ui.EditorialCard
import es.us.ussync.ui.EditorialPrimaryButton
import es.us.ussync.ui.EditorialSection
import es.us.ussync.ui.SubjectBadge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TeachingState(val subjects: List<SeviusSubject> = emptyList(), val suggested: SeviusSubject? = null, val subject: SeviusSubject? = null,
    val documents: List<TeachingDocument> = emptyList(), val busy: Boolean = false, val message: String? = null)

class TeachingViewModel(application: Application) : AndroidViewModel(application) {
    private val catalog = AppDatabase.get(application).catalogDao()
    val state = MutableStateFlow(TeachingState())
    val saved = catalog.observeSevius().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private fun run(block: suspend () -> Unit) {
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                state.value = state.value.copy(message = error.message ?: "No se pudo consultar SEVIUS.")
            } finally { state.value = state.value.copy(busy = false) }
        }
    }
    fun load(degree: String, centers: String, courseName: String? = null) = run {
        require(degree.isNotBlank() && centers.split(',').all { it.trim().isNotEmpty() }) { "Indica titulación y centros." }
        catalog.putSetting(AppSettingsEntity("sevius_degree", degree.trim()))
        catalog.putSetting(AppSettingsEntity("sevius_centers", centers))
        val subjects = withContext(Dispatchers.IO) { SeviusClient().subjects(degree.trim(), centers.split(',').map { it.trim() }) }
        state.value = state.value.copy(subjects = subjects, suggested = courseName?.let { automaticSubjectMatch(it, subjects) }, subject = null, documents = emptyList())
    }
    fun choose(subject: SeviusSubject) = run {
        val degree = catalog.setting("sevius_degree") ?: "247"
        val docs = withContext(Dispatchers.IO) { SeviusClient().documents(subject, degree) }
        state.value = state.value.copy(subject = subject, suggested = null, documents = docs)
    }
    fun save(course: EvCourse, document: TeachingDocument) = run {
        val subject = requireNotNull(state.value.subject)
        val selection = SeviusSelectionEntity("sevius:${course.id}", subject.code, subject.center, document.year,
            document.value.substringAfterLast('/'), document.value, document.program, course.id)
        catalog.saveSevius(selection)
        withContext(Dispatchers.IO) { downloadTeachingSelection(getApplication(), selection) }
        state.value = state.value.copy(message = "Programa y proyecto guardados en ${course.folder ?: course.name} / Información docente.")
    }
    fun retry(selection: SeviusSelectionEntity) = run {
        withContext(Dispatchers.IO) { downloadTeachingSelection(getApplication(), selection) }
        state.value = state.value.copy(message = "Documentos actualizados en la biblioteca.")
    }
    fun remove(courseId: String) = run {
        catalog.removeSevius(courseId)
        state.value = state.value.copy(message = "Selección eliminada. Los archivos descargados se conservan.")
    }
}

@Composable
fun TeachingScreen(course: EvCourse, settings: Map<String, String>, back: () -> Unit) {
    val model: TeachingViewModel = viewModel(key = "teaching:${course.id}")
    val state by model.state.collectAsState()
    val saved by model.saved.collectAsState()
    var degree by rememberSaveable { mutableStateOf(settings["sevius_degree"] ?: "247") }
    var centers by rememberSaveable { mutableStateOf(settings["sevius_centers"] ?: "17,3") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf<TeachingDocument?>(null) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var manualSubjectSearch by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.load(degree, centers, course.name) }
    BackHandler { back() }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(onClick = back, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(38.dp)) { Box(contentAlignment = Alignment.Center) { Text("←", style = MaterialTheme.typography.titleMedium) } }
            Column { Text("Proyecto docente", style = MaterialTheme.typography.headlineSmall); Text(course.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        saved.firstOrNull { it.courseId == course.id }?.let { selection -> item {
            EditorialCard { Column(Modifier.padding(16.dp)) {
                SubjectBadge("SELECCIÓN GUARDADA")
                Spacer(Modifier.height(8.dp))
                Text("Selección guardada · ${selection.year} · Grupo ${selection.group}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { model.retry(selection) }, enabled = !state.busy) { Text("Descargar / actualizar") }
                TextButton(onClick = { model.remove(course.id) }, enabled = !state.busy) { Text("Quitar selección") }
            } }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Asignatura", "Año", "Grupo").forEachIndexed { index, label -> SubjectBadge("${index + 1}  $label", lavender = index == step) } } }
        item { Text(when (step) { 0 -> "Busca la asignatura publicada en SEVIUS."; 1 -> "Elige el curso académico."; else -> "Elige tu grupo y guarda el proyecto." }, style = MaterialTheme.typography.bodyLarge) }
        item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Ocultar titulación" else "Cambiar titulación y centros") } }
        if (advanced) {
            item { OutlinedTextField(degree, { degree = it }, label = { Text("Código de titulación") }, singleLine = true) }
            item { OutlinedTextField(centers, { centers = it }, label = { Text("Códigos de centros, separados por comas") }, singleLine = true) }
        }
        if (state.subjects.isEmpty() && state.suggested == null && !state.busy) item { EditorialPrimaryButton("Consultar catálogo", { chosen = null; year = ""; model.load(degree, centers, course.name) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.message?.let { item { Text(it) } }
        if (step == 0 && state.subjects.isNotEmpty()) {
            state.suggested?.let { suggestion -> item { EditorialCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubjectBadge("COINCIDENCIA SUGERIDA")
                Text(suggestion.name, style = MaterialTheme.typography.titleMedium)
                Text("Coincide con el nombre de tu asignatura. Compruébalo antes de continuar.", style = MaterialTheme.typography.bodySmall)
                Row { EditorialPrimaryButton("Usar esta", { model.choose(suggestion) }); TextButton(onClick = { manualSubjectSearch = true }) { Text("Buscar otra") } }
            } } } }
            if (manualSubjectSearch || state.suggested == null) item { OutlinedTextField(query, { query = it }, label = { Text("Buscar asignatura en SEVIUS") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        }
        if (step == 0 && state.subject == null && state.subjects.isNotEmpty() && (manualSubjectSearch || state.suggested == null)) {
            items(state.subjects.filter { it.name.contains(query, true) || it.code.contains(query) }.take(30), key = { it.code }) { subject ->
                EditorialCard(Modifier.fillMaxWidth()) { TextButton(onClick = { chosen = null; year = ""; model.choose(subject) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.subject == subject) "✓ ${subject.name}" else subject.name) } }
            }
        }
        if (step == 0 && state.subject != null) item { EditorialCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { SubjectBadge("ASIGNATURA", lavender = true); Text(state.subject!!.name, style = MaterialTheme.typography.titleMedium); TextButton(onClick = { step = 1 }) { Text("Continuar") } } } }
        if (step >= 1 && state.subject != null) {
            item { Text("Año académico", style = MaterialTheme.typography.titleLarge) }
            val projects = state.documents.filter { it.kind == "proyecto" }
            if (projects.isEmpty()) item { Text("No hay proyectos docentes publicados para esta asignatura.") }
            items(projects.map { it.year }.distinct().sortedDescending(), key = { "year:$it" }) { value ->
                FilterChip(year == value, { year = value; chosen = null }, label = { Text(value) })
            }
            if (step == 1 && year.isNotBlank()) item { EditorialPrimaryButton("Continuar", { step = 2 }, modifier = Modifier.fillMaxWidth()) }
            if (step >= 2) {
                item { Text("Grupo", style = MaterialTheme.typography.titleLarge) }
                items(projects.filter { it.year == year }, key = { it.value }) { doc ->
                    FilterChip(chosen == doc, { chosen = doc }, label = { Text(doc.label) })
                }
            }
            chosen?.let { doc -> if (step >= 2) item { EditorialPrimaryButton("Guardar y descargar", { model.save(course, doc) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) } }
        }
    }
}
