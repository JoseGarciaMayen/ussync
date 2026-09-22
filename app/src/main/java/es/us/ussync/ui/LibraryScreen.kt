package es.us.ussync.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import es.us.ussync.blackboard.EvCourse
import es.us.ussync.data.DownloadRecordEntity
import es.us.ussync.data.IgnoredDocument
import es.us.ussync.data.RuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LibraryEntry(val uri: String, val name: String, val folder: Boolean, val bytes: Long)

/** Everything the library screen shows for one folder path, so a path change swaps it atomically. */
private data class LibraryListing(
    val path: List<String>,
    val entries: List<LibraryEntry>,
    val stats: Map<String, FolderStats>,
    val total: FolderStats,
)

/** Recursive totals for a folder: what it occupies and what it contains. */
private data class FolderStats(val bytes: Long, val files: Int, val folders: Int)

/** Keeps the last real listing for each folder during this app session. */
private object LibraryPreviewCache {
    private val listings = mutableMapOf<String, List<LibraryEntry>>()
    private val stats = mutableMapOf<String, FolderStats>()
    private val prefetches = mutableSetOf<String>()

    private fun key(tree: String, path: List<String>) = "$tree\u0000${path.joinToString("\u0000")}"
    private fun statsKey(tree: String, path: List<String>, showHiddenFolders: Boolean) =
        "$tree\u0000$showHiddenFolders\u0000${path.joinToString("\u0000")}"

    fun get(tree: String, path: List<String>): List<LibraryEntry>? = synchronized(this) { listings[key(tree, path)] }

    fun put(tree: String, path: List<String>, entries: List<LibraryEntry>) {
        synchronized(this) { listings[key(tree, path)] = entries }
    }

    /** Background prefetch must never replace a listing refreshed by the user. */
    fun putIfAbsent(tree: String, path: List<String>, entries: List<LibraryEntry>) {
        synchronized(this) { listings.putIfAbsent(key(tree, path), entries) }
    }

    fun stats(tree: String, path: List<String>, showHiddenFolders: Boolean): FolderStats? =
        synchronized(this) { stats[statsKey(tree, path, showHiddenFolders)] }

    fun putStats(tree: String, path: List<String>, showHiddenFolders: Boolean, value: FolderStats) {
        synchronized(this) { stats[statsKey(tree, path, showHiddenFolders)] = value }
    }

    fun beginPrefetch(tree: String, showHiddenFolders: Boolean): Boolean = synchronized(this) {
        prefetches.add("$tree:$showHiddenFolders")
    }

    /** Drops cached totals after local files changed, keeping listings for an instant preview. */
    fun invalidateStats(tree: String) = synchronized(this) {
        val prefix = "$tree\u0000"
        stats.keys.removeAll { it.startsWith(prefix) }
    }
}

/** Resolves a library path, verifying that the folder still exists and can be read. */
private fun resolveLibraryFolder(context: android.content.Context, tree: String, path: List<String>): DocumentFile {
    var folder = requireNotNull(DocumentFile.fromTreeUri(context, Uri.parse(tree)))
    check(folder.exists() && folder.canRead()) { "No se puede acceder a la biblioteca. Revisa la carpeta elegida en Ajustes." }
    path.forEach { name -> folder = requireNotNull(folder.findFile(name)) { "La carpeta ya no existe. Vuelve al nivel anterior." } }
    check(folder.isDirectory && folder.canRead()) { "No se puede leer esta carpeta." }
    return folder
}

/** Total size and item counts of a folder, computed bottom-up and cached for the session. */
private fun folderStats(context: android.content.Context, tree: String, path: List<String>, showHiddenFolders: Boolean): FolderStats {
    LibraryPreviewCache.stats(tree, path, showHiddenFolders)?.let { return it }
    val files = runCatching { resolveLibraryFolder(context, tree, path).listFiles().toList() }
        .getOrElse { return FolderStats(0, 0, 0) }
        .filter { file -> showHiddenFolders || !file.isDirectory || !file.name.orEmpty().startsWith('.') }
    var bytes = 0L
    var fileCount = 0
    var folderCount = 0
    for (file in files) {
        if (file.isDirectory) {
            val child = folderStats(context, tree, path + (file.name ?: "Sin nombre"), showHiddenFolders)
            bytes += child.bytes
            fileCount += child.files
            folderCount += child.folders + 1
        } else {
            bytes += file.length()
            fileCount += 1
        }
    }
    return FolderStats(bytes, fileCount, folderCount).also { LibraryPreviewCache.putStats(tree, path, showHiddenFolders, it) }
}

private fun formatLibrarySize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes.toDouble() / 1024 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes.toDouble() / 1024 / 1024)
    bytes >= 1024L -> "%.0f KB".format(bytes.toDouble() / 1024)
    else -> "$bytes B"
}

private fun folderSummary(stats: FolderStats?): String = when {
    stats == null -> "Carpeta · Toca para abrir"
    stats.files == 0 && stats.folders == 0 -> "Carpeta vacía · Toca para abrir"
    else -> buildString {
        append("${formatLibrarySize(stats.bytes)} · ${stats.files} archivos")
        if (stats.folders > 0) append(" · ${stats.folders} carpetas")
    }
}

/** Preloads real directory listings and folder totals once per library and app session, away from the UI thread. */
suspend fun prefetchLibraryPreview(context: android.content.Context, tree: String, showHiddenFolders: Boolean) {
    if (!LibraryPreviewCache.beginPrefetch(tree, showHiddenFolders)) return
    withContext(Dispatchers.IO) {
        fun visit(folder: DocumentFile, path: List<String>): FolderStats {
            val files = runCatching { folder.listFiles().toList() }.getOrElse { return FolderStats(0, 0, 0) }
                .filter { file -> showHiddenFolders || !file.isDirectory || !file.name.orEmpty().startsWith('.') }
            val entries = files.map { LibraryEntry(it.uri.toString(), it.name ?: "Sin nombre", it.isDirectory, it.length()) }
                .sortedWith(compareByDescending<LibraryEntry> { it.folder }.thenBy { it.name.lowercase() })
            LibraryPreviewCache.putIfAbsent(tree, path, entries)
            var bytes = 0L
            var fileCount = 0
            var folderCount = 0
            files.forEach { file ->
                if (file.isDirectory) {
                    val child = file.name?.let { visit(file, path + it) } ?: FolderStats(0, 0, 0)
                    bytes += child.bytes
                    fileCount += child.files
                    folderCount += child.folders + 1
                } else {
                    bytes += file.length()
                    fileCount += 1
                }
            }
            return FolderStats(bytes, fileCount, folderCount).also {
                LibraryPreviewCache.putStats(tree, path, showHiddenFolders, it)
            }
        }
        runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(tree))?.let { visit(it, emptyList()) }
        }
    }
}

@Composable
private fun LibraryLoadingEntry() {
    val block = MaterialTheme.colorScheme.surfaceContainerHigh
    EditorialCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = block, modifier = Modifier.size(44.dp)) {}
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp), color = block, modifier = Modifier.fillMaxWidth(.68f).height(14.dp)) {}
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp), color = block, modifier = Modifier.fillMaxWidth(.42f).height(11.dp)) {}
            }
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = block, modifier = Modifier.size(36.dp)) {}
        }
    }
}

@Composable
fun LibraryScreen(
    tree: String?,
    records: List<DownloadRecordEntity>,
    ignored: List<IgnoredDocument>,
    rules: List<RuleEntity>,
    evUris: Set<String>,
    showHiddenFolders: Boolean,
    courses: List<EvCourse>,
    onOpen: (Uri) -> Unit,
    onForget: (List<String>) -> Unit,
    onIgnoreFolder: (String?, String) -> Unit,
    onUnignoreFolder: (String?, String) -> Unit,
    onSetIgnored: (String, Boolean) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by rememberSaveable(tree) { mutableStateOf(listOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<LibraryTarget?>(null) }
    // The real listing for the current path, filled by the effect below.
    var listing by remember { mutableStateOf<LibraryListing?>(null) }

    // Builds the preview for a path straight from the session cache, synchronously. Keeping this
    // in composition means the first frame after changing folders already shows the right totals.
    fun cachedListing(folderPath: List<String>): LibraryListing? {
        val cached = LibraryPreviewCache.get(tree.orEmpty(), folderPath) ?: return null
        val stats = cached.filter { it.folder }.mapNotNull { entry ->
            LibraryPreviewCache.stats(tree.orEmpty(), folderPath + entry.name, showHiddenFolders)?.let { entry.uri to it }
        }.toMap()
        return LibraryListing(
            folderPath, cached, stats,
            LibraryPreviewCache.stats(tree.orEmpty(), folderPath, showHiddenFolders) ?: FolderStats(0, 0, 0),
        )
    }

    val cached = remember(tree, path, showHiddenFolders) { cachedListing(path) }
    val shown = listing?.takeIf { it.path == path } ?: cached
        ?: LibraryListing(path, emptyList(), emptyMap(), FolderStats(0, 0, 0))
    val loading = shown.entries.isEmpty()

    val decodedEvUris = remember(evUris) { evUris.map { Uri.decode(it) }.toSet() }

    val ignoredByUri = ignored.filter { it.localUri != null }.associateBy { it.localUri!! }

    // Records under a folder. Child document URIs extend the parent URI with "%2F" or "/";
    // checking the following character avoids matching a sibling such as "Tema 10" for "Tema 1".
    fun containedDocumentKeys(folderUri: String): List<String> = records
        .filter { record -> record.targetUri.startsWith(folderUri) &&
            (record.targetUri.length == folderUri.length ||
                record.targetUri[folderUri.length] == '%' || record.targetUri[folderUri.length] == '/') }
        .map { it.documentKey }
        .distinct()

    // A library folder maps to a course plus its path inside that course.
    fun courseAndRelative(folderName: String): Pair<String?, String> {
        val components = path + folderName
        val course = courses.firstOrNull { (it.folder ?: it.name) == components.firstOrNull() }
        return if (course != null) course.id to components.drop(1).joinToString("/")
        else null to components.joinToString("/")
    }

    // Rule currently suppressing downloads for a folder, including a parent folder rule.
    fun folderIgnoreRuleFor(folderName: String): RuleEntity? {
        val (courseId, relative) = courseAndRelative(folderName)
        return rules.firstOrNull { rule ->
            rule.action == "IGNORE" && rule.extension == null && rule.nameContains == null && rule.pathPrefix != null &&
                (rule.courseId == null || rule.courseId == courseId) &&
                (rule.pathPrefix.trim('/').isEmpty() || relative == rule.pathPrefix.trim('/') ||
                    relative.startsWith(rule.pathPrefix.trim('/') + "/"))
        }
    }

    fun applyToggle(entry: LibraryEntry, ignoredValue: Boolean) {
        if (entry.folder) {
            if (ignoredValue) {
                val (courseId, relative) = courseAndRelative(entry.name)
                onIgnoreFolder(courseId, relative)
            } else {
                folderIgnoreRuleFor(entry.name)?.let { onUnignoreFolder(it.courseId, it.pathPrefix ?: "") }
            }
        }
    }

    fun deleteEntry(entry: LibraryEntry, wasIgnored: Boolean) {
        val currentTree = tree ?: return
        val keys = if (entry.folder) containedDocumentKeys(entry.uri)
        else records.firstOrNull { it.targetUri == entry.uri }?.let { listOf(it.documentKey) }.orEmpty()
        scope.launch {
            busy = true
            val deleted = withContext(Dispatchers.IO) {
                runCatching {
                    // Resolve through the tree URI: child operations (listFiles) fail on single URIs.
                    val parent = resolveLibraryFolder(context, currentTree, path)
                    if (entry.folder) {
                        parent.findFile(entry.name)?.listFiles()?.all { it.delete() } == true
                    } else {
                        parent.findFile(entry.name)?.delete() == true
                    }
                }.getOrDefault(false)
            }
            if (deleted) {
                // Keep the ignore state: drop the download history so the scan does not re-queue it.
                if (wasIgnored && keys.isNotEmpty()) onForget(keys)
                LibraryPreviewCache.invalidateStats(currentTree)
                target = null
                refresh++
                onMessage(if (entry.folder) "Carpeta vaciada." else "Archivo borrado.")
            } else {
                onMessage("No se pudo borrar. Comprueba que la carpeta sigue accesible.")
            }
            busy = false
        }
    }

    BackHandler(path.isNotEmpty()) { path = path.dropLast(1); query = "" }
    // Download records change for every completed download. They are only needed by the
    // details dialog, so they must not make a potentially large document tree reload.
    LaunchedEffect(tree, path, refresh, showHiddenFolders) {
        failure = null
        if (tree == null) return@LaunchedEffect
        try {
            val loaded = withContext(Dispatchers.IO) {
                // Do this before asking the provider for each item's metadata. On a synced
                // library, those calls can be comparatively expensive for a large folder.
                resolveLibraryFolder(context, tree, path).listFiles().asSequence()
                    .filter { file -> showHiddenFolders || !file.isDirectory || !file.name.orEmpty().startsWith('.') }
                    .map { LibraryEntry(it.uri.toString(), it.name ?: "Sin nombre", it.isDirectory, it.length()) }
                    .sortedWith(compareByDescending<LibraryEntry> { it.folder }.thenBy { it.name.lowercase() })
                    .toList()
            }
            LibraryPreviewCache.put(tree, path, loaded)
            val loadedStats = withContext(Dispatchers.IO) {
                loaded.filter { it.folder }.associate { entry ->
                    entry.uri to folderStats(context, tree, path + entry.name, showHiddenFolders)
                }
            }
            val total = withContext(Dispatchers.IO) { folderStats(context, tree, path, showHiddenFolders) }
            val next = LibraryListing(path, loaded, loadedStats, total)
            // Only touch state when something actually changed, so matching copies never flash.
            if (next != listing) listing = next
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failure = error.message ?: "No se pudo leer la carpeta."
        }
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(path.lastOrNull() ?: "Biblioteca", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append(if (path.isEmpty()) "${shown.entries.size} elementos guardados" else path.joinToString("  ›  "))
                        if (shown.total.bytes > 0L || shown.total.files > 0) append("  ·  ${formatLibrarySize(shown.total.bytes)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(onClick = { refresh++ }, enabled = !loading, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(42.dp)) { Box(contentAlignment = Alignment.Center) { SyncIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) } }
        } }
        if (tree == null) item { Text("Elige la carpeta de la biblioteca en Ajustes para empezar.") }
        else {
            if (path.isNotEmpty()) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { path = path.dropLast(1); query = "" }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text("←  Subir") }
                if (path.size > 1) TextButton(onClick = { path = emptyList(); query = "" }) { Text("Raíz") }
            } }
            item { OutlinedTextField(query, { query = it }, label = { Text("Buscar en esta carpeta") }, leadingIcon = { SearchIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) }
            failure?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            val visible = shown.entries.filter { it.name.contains(query, true) }
            if (loading && visible.isEmpty()) {
                items(4, key = { "library-loading-$it" }) { LibraryLoadingEntry() }
            }
            if (!loading && failure == null && visible.isEmpty()) item { Text(if (query.isBlank()) "Esta carpeta todavía está vacía." else "No hay archivos con ese nombre aquí.") }
            items(visible, key = { it.uri }) { entry ->
                val record = records.firstOrNull { it.targetUri == entry.uri }
                val isIgnored = if (entry.folder) folderIgnoreRuleFor(entry.name) != null else ignoredByUri.containsKey(entry.uri)
                val inEv = entry.folder || entry.uri in evUris || Uri.decode(entry.uri) in decodedEvUris
                EditorialCard(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                    if (entry.folder) { path = path + entry.name; query = "" } else onOpen(Uri.parse(entry.uri))
                }) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (entry.folder) Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { FolderIcon(MaterialTheme.colorScheme.primary, Modifier.size(23.dp)) } } else FileTypeAvatar(entry.name)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium, color = if (isIgnored) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                buildString {
                                    append(if (entry.folder) folderSummary(shown.stats[entry.uri]) else formatLibrarySize(entry.bytes))
                                    if (isIgnored) append("  ·  No se descargará")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isIgnored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!inEv) Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Text("NO EV", Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Surface(onClick = {
                        target = LibraryTarget(entry.name, if (entry.folder) shown.stats[entry.uri]?.bytes else entry.bytes,
                            entry.folder, isIgnored, record?.documentKey, entry)
                    }, enabled = !busy, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(36.dp)) { Box(contentAlignment = Alignment.Center) { Text("⋮", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) } }
                } }
            }
            val subPath = path.drop(1).joinToString("/")
            val placeholders = if (path.isEmpty()) emptyList() else ignored.filter {
                it.localUri == null && it.precise && it.courseFolder == path.first() && it.relativePath == subPath && it.filename.contains(query, true)
            }
            if (placeholders.isNotEmpty()) {
                item("ignored-header") { Text("No descargados · ignorados", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(placeholders, key = { "ignored:${it.documentKey}" }) { document ->
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        target = LibraryTarget(document.filename, document.size, false, true, document.documentKey, null)
                    }) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileTypeAvatar(document.filename)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(document.filename, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("No está en el dispositivo · No se descargará", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(36.dp)) { Box(contentAlignment = Alignment.Center) { Text("⋮", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) } }
                    } }
                }
            }
        }
    }
    val current = target
    if (current != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) target = null },
            title = { Text(current.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    current.size?.let { Text(formatLibrarySize(it), style = MaterialTheme.typography.bodyMedium) }
                    if (current.entry == null) Text("No está en el dispositivo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (current.folder) Text("Vaciar borra el contenido de esta carpeta del dispositivo. La carpeta se mantiene.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("No volver a descargar", style = MaterialTheme.typography.labelLarge)
                            Text("Ignora este ${if (current.folder) "carpeta y su contenido" else "archivo"} en las próximas consultas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = current.ignored, enabled = !busy && (current.folder || current.documentKey != null),
                            onCheckedChange = { value ->
                                if (current.folder) current.entry?.let { applyToggle(it, value) } else current.documentKey?.let { onSetIgnored(it, value) }
                                target = current.copy(ignored = value)
                            })
                    }
                }
            },
            confirmButton = {
                current.entry?.let { entry -> Button(onClick = { deleteEntry(entry, current.ignored) }, enabled = !busy) { Text(if (entry.folder) "Vaciar" else "Borrar") } }
            },
            dismissButton = { TextButton(onClick = { target = null }, enabled = !busy) { Text("Cancelar") } },
        )
    }
}

private data class LibraryTarget(
    val name: String,
    val size: Long?,
    val folder: Boolean,
    val ignored: Boolean,
    val documentKey: String?,
    val entry: LibraryEntry?,
)
