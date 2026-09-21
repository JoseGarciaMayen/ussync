package es.us.ussync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import es.us.ussync.blackboard.*
import es.us.ussync.data.*
import es.us.ussync.ui.USSyncTheme
import es.us.ussync.ui.EditorialCard
import es.us.ussync.ui.EditorialPrimaryButton
import es.us.ussync.ui.FileTypeAvatar
import es.us.ussync.ui.FolderIcon
import es.us.ussync.ui.HomeIcon
import es.us.ussync.ui.BookIcon
import es.us.ussync.ui.GearIcon
import es.us.ussync.ui.DownloadIcon
import es.us.ussync.ui.DotIcon
import es.us.ussync.ui.SearchIcon
import es.us.ussync.ui.SubjectBadge
import es.us.ussync.ui.SyncIcon
import es.us.ussync.ui.academicStart
import es.us.ussync.ui.isCurrentAcademic
import es.us.ussync.ui.prefetchLibraryPreview
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { USSyncApp(intent.getBooleanExtra("review_news", false), intent.getBooleanExtra("reconnect_session", false)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun USSyncApp(reviewNews: Boolean, reconnectSession: Boolean) {
    val session: EvSessionViewModel = viewModel()
    val state by session.state.collectAsState()
    val inbox by session.inboxItems.collectAsState()
    val progress by session.downloadProgress.collectAsState()
    val errors by session.downloadErrors.collectAsState()
    val downloads by session.downloads.collectAsState()
    val ignored by session.ignoredDocuments.collectAsState()
    val evUris by session.evLibraryUris.collectAsState()
    val recentDownloads by session.recentDownloads.collectAsState()
    val latestScan by session.latestScan.collectAsState()
    val rules by session.rules.collectAsState()
    val library by session.libraryUri.collectAsState()
    val appearance by session.appearance.collectAsState()
    val wifiOnly by session.wifiOnly.collectAsState()
    val settings by session.settings.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var reviewing by rememberSaveable { mutableStateOf(reviewNews) }
    var reconnectHandled by rememberSaveable { mutableStateOf(false) }
    var showRecentDownloads by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(reconnectSession) {
        if (reconnectSession && !reconnectHandled) {
            reconnectHandled = true
            reviewing = false
            session.showLogin()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(library, settings["show_hidden_folders"]) {
        library?.let { prefetchLibraryPreview(context, it, settings["show_hidden_folders"] == "true") }
    }
    var notificationKey by remember { mutableStateOf("notifications") }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.setBackgroundSetting(notificationKey, granted.toString())
    }
    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun sendNotificationTest(): Boolean {
        val sent = es.us.ussync.notifications.NotificationTest.send(context)
        if (sent) message("Aviso de prueba enviado. Comprueba el panel de notificaciones.")
        return sent
    }
    val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!sendNotificationTest()) message("Las notificaciones siguen bloqueadas en los ajustes de Android.")
    }
    val testPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted || !sendNotificationTest()) {
            notificationSettings.launch(es.us.ussync.notifications.NotificationTest.settingsIntent(context))
        }
    }
    val sessionPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message("Sin permiso de notificaciones, los fallos de sesión solo se mostrarán en Ajustes.")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            session.setLibraryUri(uri.toString())
        }.onFailure { message("No se pudo acceder a la carpeta. Elige otra ubicación.") }
    }

    var availableUpdate by remember { mutableStateOf<es.us.ussync.updater.AppUpdate?>(null) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf(-1f) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var downloadedApk by remember { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(Unit) {
        availableUpdate = es.us.ussync.updater.AppUpdater.checkForUpdate()
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                downloadedApk?.let { apk ->
                    if (es.us.ussync.updater.AppUpdater.canInstallPackages(context) && apk.exists()) {
                        es.us.ussync.updater.AppUpdater.installUpdate(context, apk)
                        downloadedApk = null
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun checkUpdateManual() {
        if (checkingUpdate || updateDownloading) return
        checkingUpdate = true
        updateStatusMessage = null
        updateError = null
        scope.launch {
            try {
                val update = es.us.ussync.updater.AppUpdater.checkForUpdate()
                availableUpdate = update
                if (update == null) {
                    updateStatusMessage = "Tienes la versión más reciente (v${BuildConfig.VERSION_NAME})."
                }
            } catch (e: Exception) {
                updateStatusMessage = "No se pudo comprobar en GitHub: ${e.message}"
            } finally {
                checkingUpdate = false
            }
        }
    }

    fun performUpdate(update: es.us.ussync.updater.AppUpdate) {
        if (updateDownloading) return
        updateDownloading = true
        updateProgress = -1f
        updateError = null
        scope.launch {
            try {
                val apk = es.us.ussync.updater.AppUpdater.downloadUpdate(context, update) { p ->
                    updateProgress = p
                }
                downloadedApk = apk
                if (es.us.ussync.updater.AppUpdater.canInstallPackages(context)) {
                    es.us.ussync.updater.AppUpdater.installUpdate(context, apk)
                } else {
                    showPermissionDialog = true
                }
            } catch (e: Exception) {
                updateError = e.message ?: "Error al descargar la actualización."
            } finally {
                updateDownloading = false
            }
        }
    }

    val authenticated = state as? EvSessionState.Authenticated
    val login = state == EvSessionState.Login || state == EvSessionState.Verifying
    val darkTheme = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())
    val windowView = LocalView.current
    if (!windowView.isInEditMode) SideEffect {
        val window = (windowView.context as Activity).window
        WindowCompat.getInsetsController(window, windowView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    BackHandler(reviewing || login || showRecentDownloads || tab != 0) {
        if (login) session.showWelcome() else if (reviewing) reviewing = false else tab = 0
        if (showRecentDownloads) showRecentDownloads = false
    }
    USSyncTheme(appearance) {
    val names = listOf("Inicio", "Asignaturas", "Biblioteca", "Ajustes")
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { if (reviewing || login) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(if (login) "Enseñanza Virtual" else "Novedades", style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { BackArrowButton { if (login) session.showWelcome() else reviewing = false } },
            ) },
            bottomBar = { if (!login) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                names.forEachIndexed { index, name -> NavigationBarItem(
                    selected = tab == index, onClick = { tab = index; reviewing = false },
                    icon = { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        NavGlyph(index, if (tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(shape = RoundedCornerShape(99.dp), color = if (tab == index) MaterialTheme.colorScheme.primary else Color.Transparent, modifier = Modifier.size(3.dp)) {}
                    } },
                    label = { Text(name, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
                ) }
            } },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (login) EvLoginWebView(onVerify = session::verifySession, onCancel = session::showWelcome, verifying = state == EvSessionState.Verifying)
                else if (reviewing) News(inbox, progress, errors, library != null && authenticated != null,
                    session::downloadInbox, { selected, ignore ->
                        selected.forEach { if (ignore) session.ignoreInbox(it) else session.laterInbox(it) }
                        scope.launch {
                            if (snackbar.showSnackbar(if (ignore) "${selected.size} ignorados" else "Guardados para más tarde", "Deshacer") == SnackbarResult.ActionPerformed)
                                selected.forEach { session.restoreInbox(it) }
                        }
                    }, { item, action, extension, maxSize -> session.saveRule(item.documentKey.split(":")[1], item.relativePath, action, extension, maxSize); message("Regla guardada para próximas consultas") })
                else when (tab) {
                    0 -> Home(state, library, inbox.count { it.state != "LATER" }, progress.size,
                        { session.showLogin() }, { picker.launch(null) },
                        { reviewing = true }, session::scanSelectedCourses, { showRecentDownloads = true },
                        settings, { session.setBackgroundSetting("default_action", it) },
                        availableUpdate, updateDownloading, updateProgress, updateError, ::performUpdate)
                    1 -> Courses(authenticated, rules, { course, action -> session.saveRule(course, null, action) }, session::loadCourses, session::toggleCourse, session::setCourseFolder, { session.showLogin() }, settings, { tab = 0 })
                    2 -> es.us.ussync.ui.LibraryScreen(
                        tree = library,
                        records = downloads,
                        ignored = ignored,
                        rules = rules,
                        evUris = evUris,
                        showHiddenFolders = settings["show_hidden_folders"] == "true",
                        courses = authenticated?.courses.orEmpty(),
                        onOpen = { uri ->
                            runCatching {
                                val mime = context.contentResolver.getType(uri)?.takeUnless { it == "application/octet-stream" }
                                    ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment.orEmpty().substringAfterLast('.').lowercase())
                                    ?: "application/octet-stream"
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }.onFailure { message("No se pudo abrir el archivo. Comprueba que existe y que tienes una aplicación compatible.") }
                        },
                        onForget = { keys -> if (keys.isNotEmpty()) session.forgetDocuments(keys) },
                        onIgnoreFolder = { courseId, path -> session.ignoreFolder(courseId, path) },
                        onUnignoreFolder = { courseId, path -> session.unignoreFolder(courseId, path) },
                        onSetIgnored = { key, ignoredValue -> session.setDocumentIgnored(key, ignoredValue) },
                        onMessage = { message(it) },
                    )
                    3 -> Settings(appearance, authenticated != null, wifiOnly, { session.setWifiOnly(it) }, { session.setAppearance(it) }, { session.showLogin() }, settings,
                        { key, value ->
                            if ((key == "notifications" || key == "notify_session") && value == "true" && android.os.Build.VERSION.SDK_INT >= 33) {
                                notificationKey = key
                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                            else {
                                session.setBackgroundSetting(key, value)
                                if (key == "scan_minutes" && value != "0" && android.os.Build.VERSION.SDK_INT >= 33 &&
                                    context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                    sessionPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }, {
                            if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                testPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            else if (!sendNotificationTest()) notificationSettings.launch(es.us.ussync.notifications.NotificationTest.settingsIntent(context))
                        },
                        availableUpdate, updateDownloading, updateProgress, updateError, checkingUpdate, updateStatusMessage, ::checkUpdateManual, ::performUpdate)
                }
            }
        }
    }
    if (showPermissionDialog) AlertDialog(
        onDismissRequest = { showPermissionDialog = false },
        title = { Text("Permiso para actualizar") },
        text = { Text("Para que USSync pueda actualizarse directamente desde GitHub, activa el permiso de «Instalar aplicaciones desconocidas» para USSync.") },
        confirmButton = { Button(onClick = { showPermissionDialog = false; es.us.ussync.updater.AppUpdater.openInstallPermissionSettings(context) }) { Text("Abrir Ajustes") } },
        dismissButton = { TextButton(onClick = { showPermissionDialog = false }) { Text("Cancelar") } },
    )
    if (showRecentDownloads) AlertDialog(
        onDismissRequest = { showRecentDownloads = false },
        title = { Text("Últimas descargas") },
        text = {
            val last = latestScan
            val items = last?.let { scan -> recentDownloads.filter { it.createdAt >= scan.startedAt }.take(10) }.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(last?.finishedAt?.let { "Última consulta · ${es.us.ussync.ui.localDateTime(it)}" } ?: "Todavía no se ha hecho ninguna consulta", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (items.isEmpty()) Text(if (last == null) "Sin consultas todavía." else "No se descargó ningún documento en la última consulta.")
                else items.forEach { download ->
                    Column {
                        Text(download.filename, style = MaterialTheme.typography.titleMedium)
                        Text("${download.courseName} · ${download.relativePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showRecentDownloads = false }) { Text("Cerrar") } },
    )
}

/** Bottom-bar icons, kept dependency-free and visually consistent. */
@Composable
private fun NavGlyph(index: Int, color: Color) {
    val modifier = Modifier.size(24.dp)
    when (index) {
        0 -> HomeIcon(color, modifier)
        1 -> BookIcon(color, modifier)
        2 -> FolderIcon(color, modifier)
        else -> GearIcon(color, modifier)
    }
}

@Composable
private fun BackArrowButton(onClick: () -> Unit) {
    val arrowColor = MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.padding(start = 10.dp).size(38.dp)) {
        Canvas(Modifier.padding(10.dp)) {
            val stroke = 1.9.dp.toPx()
            drawLine(arrowColor, Offset(size.width * .65f, size.height * .18f), Offset(size.width * .28f, size.height * .5f), stroke, cap = StrokeCap.Round)
            drawLine(arrowColor, Offset(size.width * .28f, size.height * .5f), Offset(size.width * .65f, size.height * .82f), stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineLarge)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Home(state: EvSessionState, library: String?, pending: Int, active: Int, login: () -> Unit, folder: () -> Unit,
    news: () -> Unit, scan: () -> Unit, recent: () -> Unit, settings: Map<String, String>, defaultAction: (String) -> Unit,
    availableUpdate: es.us.ussync.updater.AppUpdate? = null, updateDownloading: Boolean = false, updateProgress: Float = -1f,
    updateError: String? = null, onPerformUpdate: (es.us.ussync.updater.AppUpdate) -> Unit = {}) {
    val user = state as? EvSessionState.Authenticated
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("UNIVERSIDAD DE SEVILLA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text("v${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text("USSync", style = MaterialTheme.typography.headlineSmall)
            }
        } }
        if (availableUpdate != null) item {
            EditorialCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text("NUEVA VERSIÓN", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("v${availableUpdate.versionName}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                    Text("Hay una nueva versión de USSync disponible en GitHub.", style = MaterialTheme.typography.titleMedium)
                    availableUpdate.releaseNotes?.let { notes ->
                        Text(notes.take(160), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    }
                    if (updateDownloading) {
                        if (updateProgress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { updateProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("Descargando actualización...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        EditorialPrimaryButton("Actualizar a v${availableUpdate.versionName}", { onPerformUpdate(availableUpdate) }, modifier = Modifier.fillMaxWidth())
                    }
                    updateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (user == null) item {
            EditorialCard { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state is EvSessionState.Restoring) {
                    Text("Comprobando tu sesión", style = MaterialTheme.typography.headlineSmall)
                    Text("Si sigue activa, podrás continuar sin volver a iniciar sesión.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    return@Column
                }
                Text("Conecta Enseñanza Virtual", style = MaterialTheme.typography.headlineSmall)
                Text("Accede desde la página oficial de la Universidad de Sevilla.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                EditorialPrimaryButton("Conectar", login, modifier = Modifier.fillMaxWidth())
                (state as? EvSessionState.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            } }
        }
        if (library == null) item { EditorialCard { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Biblioteca local", style = MaterialTheme.typography.titleMedium); Text("Elige dónde guardar tus documentos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = folder) { Text("Elegir") }
        } } }
        item { Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (pending == 0) "Todo al día" else "$pending pendientes", style = MaterialTheme.typography.headlineSmall)
                            Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.tertiaryContainer) { Text(if (pending == 0) "SINCRONIZADO" else "PENDIENTE", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer) }
                        }
                        Text(if (pending == 0) "No hay novedades por revisar. Tus materiales están actualizados." else "Hay documentos nuevos esperando tu decisión.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(44.dp)) { Box(contentAlignment = Alignment.Center) { Text(if (pending == 0) "✓" else "!", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold) } }
                }
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.background) { Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${user?.selectedCourseIds?.size ?: 0} asignaturas inscritas", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${if (pending == 0) "✓" else "•"}  $pending pendientes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                FilledTonalButton(onClick = news, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), shape = RoundedCornerShape(12.dp)) { Text(if (pending == 0) "Ver novedades" else "Revisar novedades") }
            }
        } }
        if (active > 0) item { Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.padding(14.dp)) { Text("$active descargas en curso", style = MaterialTheme.typography.labelLarge); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } } }
        if (user != null) {
            item { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) { Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(32.dp)) { Box(contentAlignment = Alignment.Center) { DownloadIcon(MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(18.dp)) } }
                Spacer(Modifier.width(9.dp))
                Text("Últimas descargas", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = recent) { Text("Ver registro  ›") }
            } } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.weight(1f).heightIn(min = 94.dp)) { Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("EVIRTUAL US", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column { Text("Enseñanza Virtual", style = MaterialTheme.typography.labelLarge); Text(if (user.loading) "Consultando…" else "Sesión activa", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary) }
                } }
                Surface(onClick = folder, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.weight(1f).heightIn(min = 94.dp)) { Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("BIBLIOTECA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column {
                        Text(if (library == null) "Sin carpeta" else libraryDisplayName(library) ?: "Biblioteca", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NavGlyph(2, MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(5.dp))
                            Text(if (library == null) "Elige una ubicación" else "Toca para cambiar", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } }
            } }
            item { Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DESCARGAS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val mode = settings["default_action"] ?: "ASK"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("IGNORE" to "Nunca", "ASK" to "Preguntarme", "AUTO_DOWNLOAD" to "Automáticas").forEach { (value, label) ->
                        val active = value == mode
                        Surface(onClick = { defaultAction(value) }, modifier = Modifier.weight(1f).heightIn(min = 42.dp), shape = RoundedCornerShape(11.dp),
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
                Text("También puedes ajustarlo por asignatura en la pestaña Asignaturas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } } }
            item { Button(onClick = scan, enabled = !user.loading && user.selectedCourseIds.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(99.dp)) { Text(if (user.loading) "Consultando materiales…" else "Buscar novedades ahora") } }
            user.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            user.changes?.let { changes -> item { Text("Última consulta · ${changes.new} nuevos · ${changes.updated} actualizados", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
private fun Courses(user: EvSessionState.Authenticated?, rules: List<RuleEntity>, rule: (String, String) -> Unit, refresh: () -> Unit, toggle: (String) -> Unit,
    setFolder: (String, String) -> Unit, login: () -> Unit, settings: Map<String, String>, close: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var showPrevious by rememberSaveable { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EvCourse?>(null) }
    var folder by rememberSaveable { mutableStateOf("") }
    var teaching by remember { mutableStateOf<EvCourse?>(null) }
    teaching?.let { course ->
        es.us.ussync.sevius.TeachingScreen(course, settings) { teaching = null }
        return
    }
    val focusManager = LocalFocusManager.current
    LazyColumn(
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focusManager.clearFocus() },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) { Text("UNIVERSIDAD DE SEVILLA", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text("Asignaturas", style = MaterialTheme.typography.headlineLarge); Text("Activa las que quieras incluir en las consultas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Surface(onClick = refresh, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(42.dp)) { Box(contentAlignment = Alignment.Center) { SyncIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) } }
        } }
        if (user == null) item { EditorialPrimaryButton("Conectar Enseñanza Virtual", login, modifier = Modifier.fillMaxWidth()) }
        else {
            val searching = searchFocused
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Buscar") }, leadingIcon = { SearchIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) }, singleLine = true,
                    modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.isFocused }, shape = RoundedCornerShape(14.dp))
                if (!searching) TextButton(onClick = { showPrevious = !showPrevious }, modifier = Modifier.weight(1f)) { Text(if (showPrevious) "Solo actuales" else "Ver anteriores") }
            } }
            user.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            val visible = user.courses
                .filter { it.name.contains(query, true) }
                .filter { showPrevious || isCurrentAcademic(it) }
                .sortedWith(compareByDescending<EvCourse> { it.id in user.selectedCourseIds }
                    .thenByDescending { academicStart(it) ?: -1 }
                    .thenBy { it.name.lowercase(Locale.getDefault()) })
            if (visible.isEmpty()) item { Text(if (user.courses.isEmpty()) "Consulta tus asignaturas para empezar." else if (showPrevious) "No hay asignaturas con ese nombre." else "No hay asignaturas del curso actual. Pulsa «Mostrar anteriores» para ver cursos pasados.") }
            items(visible, key = { it.id }) { course ->
                EditorialCard {
                    Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(12.dp), color = if (course.id in user.selectedCourseIds) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(40.dp)) { Box(contentAlignment = Alignment.Center) { Text(course.name.firstOrNull()?.uppercase() ?: "A", style = MaterialTheme.typography.titleMedium, color = if (course.id in user.selectedCourseIds) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) } }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(course.name, style = MaterialTheme.typography.titleMedium); Text(if (course.id in user.selectedCourseIds) "Incluida en las consultas" else "No incluida", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Switch(course.id in user.selectedCourseIds, { toggle(course.id) }, enabled = !user.loading)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        TextButton(onClick = { editing = course; folder = course.folder ?: course.name }, enabled = !user.loading) { Text("Nombre") }
                        val mode = rules.firstOrNull { it.courseId == course.id && it.pathPrefix == null && it.extension == null && it.maxSize == null }?.action ?: (settings["default_action"] ?: "ASK")
                        TextButton(onClick = {
                            rule(course.id, when (mode) { "ASK" -> "AUTO_DOWNLOAD"; "AUTO_DOWNLOAD" -> "IGNORE"; else -> "ASK" })
                        }) { Text("${actionLabel(mode)}  ›") }
                        TextButton(onClick = { teaching = course }) { Text("Proyecto") }
                    }
                    if (editing?.id == course.id) {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedTextField(folder, { folder = it }, label = { Text("Nombre de carpeta") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editing = null }) { Text("Cancelar") }
                                Button(onClick = { setFolder(course.id, folder.trim()); editing = null }, enabled = folder.isNotBlank() && '/' !in folder && '\\' !in folder && folder.trim() !in listOf(".", "..")) { Text("Guardar") }
                            }
                        }
                    }
                    val documents = user.documents.filter { it.key.split(":").getOrNull(1) == course.id }
                    if (documents.isNotEmpty()) Text("${documents.size} materiales encontrados", Modifier.padding(horizontal = 16.dp, vertical = 0.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun News(all: List<InboxDocument>, progress: Map<Long, Float>, errors: Map<Long, String>, canDownload: Boolean,
    download: (InboxDocument) -> Unit, resolve: (List<InboxDocument>, Boolean) -> Unit, rememberRule: (InboxDocument, String, String?, Long?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var later by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var ruleTarget by remember { mutableStateOf<InboxDocument?>(null) }
    var pdfOnly by remember(ruleTarget) { mutableStateOf(false) }
    var maxMb by remember(ruleTarget) { mutableStateOf("") }
    val visible = all.filter { (it.state == "LATER") == later && (it.filename.contains(query, true) || it.courseName.contains(query, true) || it.relativePath.contains(query, true)) }
    val selected = visible.filter { it.inboxId in selection && it.inboxId !in progress }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(query, { query = it; selection = emptySet() }, label = { Text("Buscar novedades") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            FilterChip(!later, { later = false; selection = emptySet() }, label = { Text("Pendientes") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.onSurface, selectedLabelColor = MaterialTheme.colorScheme.surface))
            FilterChip(later, { later = true; selection = emptySet() }, label = { Text("Más tarde") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.onSurface, selectedLabelColor = MaterialTheme.colorScheme.surface))
        }
        if (!canDownload) Text("Conecta Enseñanza Virtual y elige una carpeta en Ajustes para descargar.", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { selection = visible.map { it.inboxId }.toSet() }) { Text("Seleccionar todo") }
            if (selection.isNotEmpty()) TextButton(onClick = { selection = emptySet() }) { Text("Limpiar") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (visible.isEmpty()) item { Heading("Todo revisado", if (later) "Aquí aparecerán los documentos que dejes para después." else "No hay novedades que coincidan con esta vista.") }
            visible.groupBy { it.courseName }.forEach { (course, documents) ->
                item("course:$course") { Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(documents.all { it.inboxId in selection }, { checked -> selection = if (checked) selection + documents.map { it.inboxId } else selection - documents.map { it.inboxId }.toSet() })
                    Column(Modifier.weight(1f)) { SubjectBadge(course, lavender = true); Text("${documents.size} documentos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } }
                items(documents, key = { it.inboxId }) { doc ->
                    EditorialCard(Modifier.padding(vertical = 4.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(doc.inboxId in selection, { checked -> selection = if (checked) selection + doc.inboxId else selection - doc.inboxId }, enabled = doc.inboxId !in progress)
                        FileTypeAvatar(doc.filename)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(doc.filename, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            val isFuture = doc.availableFrom != null && es.us.ussync.ui.isFutureDate(doc.availableFrom)
                            val details = listOfNotNull(
                                if (isFuture) "Disponible el ${es.us.ussync.ui.localDateShort(doc.availableFrom)}" else if (doc.kind == "UPDATED") "Actualización" else "Nuevo",
                                doc.size?.let(::formatBytes),
                                doc.relativePath.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                            Text(details, style = MaterialTheme.typography.bodySmall, color = if (isFuture) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                            progress[doc.inboxId]?.let { value -> if (value < 0) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                            errors[doc.inboxId]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Row {
                                TextButton(onClick = { download(doc) }, enabled = canDownload && doc.inboxId !in progress) {
                                    Text(if (isFuture) "Consultar disponibilidad" else if (doc.inboxId in errors) "Reintentar" else "Descargar")
                                }
                                TextButton(onClick = { ruleTarget = doc }) { Text("Regla") }
                            }
                        }
                    } }
                }
            }
        }
        if (selected.isNotEmpty()) Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Button(onClick = { selected.forEach(download); selection = emptySet() }, enabled = canDownload, modifier = Modifier.fillMaxWidth()) { Text("Descargar ${selected.size}") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (!later) TextButton(onClick = { resolve(selected, false); selection = emptySet() }) { Text("Más tarde") }
                    TextButton(onClick = { resolve(selected, true); selection = emptySet() }) { Text("Ignorar") }
                }
            }
        }
    }
    ruleTarget?.let { doc ->
        val limit = maxMb.toLongOrNull()?.takeIf { it in 1..10240 }?.times(1024 * 1024)
        val valid = maxMb.isBlank() || limit != null
        val preview = RuleEntity(priority = 0, courseId = doc.documentKey.split(":")[1], pathPrefix = doc.relativePath,
            extension = if (pdfOnly) "pdf" else null, maxSize = limit, action = "ASK", createdAt = "")
        AlertDialog(onDismissRequest = { ruleTarget = null }, title = { Text("Recordar para esta carpeta") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("${doc.courseName} / ${doc.relativePath.ifBlank { "Todas las carpetas" }}")
            Text("Afectará a los pendientes y futuros documentos de esta carpeta y sus subcarpetas en la próxima consulta. No borra archivos descargados.")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(pdfOnly, { pdfOnly = it }); Text("Solo PDF") }
            OutlinedTextField(maxMb, { maxMb = it }, label = { Text("Tamaño máximo en MB (opcional)") }, singleLine = true, isError = !valid,
                supportingText = { Text("Sin tamaño conocido, se seguirá preguntando si limitas los MB.") })
            Text("Coinciden ${all.count { it.state == "PENDING" && preview.matches(it) }} documentos pendientes.", style = MaterialTheme.typography.labelLarge)
            listOf("ASK", "AUTO_DOWNLOAD", "IGNORE").forEach { action -> TextButton(onClick = { rememberRule(doc, action, preview.extension, limit); ruleTarget = null }, enabled = valid) { Text(actionLabel(action)) } }
        }
    }, confirmButton = { TextButton(onClick = { ruleTarget = null }) { Text("Cancelar") } }) }
}

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "webm", "flv", "m4v", "mpeg", "mpg")
private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "wma")
private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockedExtensionsSetting(value: String, onChange: (String) -> Unit) {
    var custom by remember { mutableStateOf("") }
    val blocked = parseExtensionList(value)
    fun update(next: Set<String>) = onChange(next.asExtensionList())
    fun toggle(extension: String) = update(if (extension in blocked) blocked - extension else blocked + extension)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (blocked.isEmpty()) Text("No hay extensiones bloqueadas. Se descargan todos los formatos que permitan tus reglas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocked.sorted().forEach { extension ->
                Surface(onClick = { toggle(extension) }, shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(extension, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("✕", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
        Text("Añadir por tipo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Vídeo" to VIDEO_EXTENSIONS, "Audio" to AUDIO_EXTENSIONS, "Comprimidos" to ARCHIVE_EXTENSIONS).forEach { (label, group) ->
                val allBlocked = group.all { it in blocked }
                Surface(onClick = { update(if (allBlocked) blocked - group else blocked + group) }, shape = RoundedCornerShape(10.dp),
                    color = if (allBlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = if (allBlocked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                    Text(if (allBlocked) "✓ $label" else "+ $label", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(custom, { custom = it.filter { char -> char.isLetterOrDigit() } }, label = { Text("Otra extensión") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
            FilledTonalButton(onClick = { val extension = custom.trim().trimStart('.').lowercase(); if (extension.isNotBlank()) { toggle(extension); custom = "" } }, enabled = custom.isNotBlank(), shape = RoundedCornerShape(12.dp)) { Text("Añadir") }
        }
        Text("Nunca se descargarán los archivos con estas extensiones, aunque una regla diga lo contrario.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingPicker(label: String, value: String, options: List<Pair<String, String>>, onSelected: (String) -> Unit, supporting: String? = null) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text(value, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                Text("⌄")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(.88f)) {
                options.forEach { (option, optionLabel) ->
                    DropdownMenuItem(text = { Text(optionLabel) }, onClick = { onSelected(option); expanded = false })
                }
            }
        }
        supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SettingsSection(icon: @Composable () -> Unit, title: String, caption: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        caption?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SettingsToggleRow(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit, symbol: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                symbol?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                Text(title, style = MaterialTheme.typography.labelLarge)
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SegmentedSetting(label: String, selected: String, options: List<Pair<String, String>>, onSelected: (String) -> Unit, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            note?.let { Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(it, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            options.forEach { (value, text) ->
                val active = value == selected
                Surface(onClick = { onSelected(value) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(12.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                        Text(if (active) "✓ $text" else text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings(
    appearance: String,
    connected: Boolean,
    wifiOnly: Boolean,
    wifi: (Boolean) -> Unit,
    theme: (String) -> Unit,
    login: () -> Unit,
    settings: Map<String, String>,
    setting: (String, String) -> Unit,
    testNotifications: () -> Unit,
    availableUpdate: es.us.ussync.updater.AppUpdate?,
    updateDownloading: Boolean,
    updateProgress: Float,
    updateError: String?,
    checkingUpdate: Boolean,
    updateStatusMessage: String?,
    onCheckUpdate: () -> Unit,
    onPerformUpdate: (es.us.ussync.updater.AppUpdate) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("CONFIGURACIÓN LOCAL", Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(7.dp)) {}
                    Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
            Text("Ajustes", style = MaterialTheme.typography.headlineLarge)
            Text("Tu biblioteca, tus condiciones.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }

        item { SettingsSection({ DotIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) }, "Actualizaciones", "GitHub") }
        item { EditorialCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Versión instalada: v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge)
                    Text(
                        when {
                            checkingUpdate -> "Buscando actualizaciones..."
                            updateStatusMessage != null -> updateStatusMessage
                            availableUpdate != null -> "Nueva versión v${availableUpdate.versionName} disponible"
                            else -> "Actualizaciones automáticas vía GitHub Releases"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (availableUpdate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(
                    onClick = onCheckUpdate,
                    enabled = !checkingUpdate && !updateDownloading,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (checkingUpdate) "Buscando..." else "Buscar")
                }
            }
            if (availableUpdate != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Actualización v${availableUpdate.versionName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val notes = availableUpdate.releaseNotes.orEmpty()
                        if (notes.isNotBlank()) {
                            Text(if (notes.length > 300) "${notes.take(300)}..." else notes, style = MaterialTheme.typography.bodySmall)
                        }
                        if (updateDownloading) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (updateProgress > 0f) {
                                    LinearProgressIndicator(progress = { updateProgress }, modifier = Modifier.fillMaxWidth())
                                    Text("${(updateProgress * 100).toInt()}% descargado", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text("Descargando...", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            Button(
                                onClick = { onPerformUpdate(availableUpdate) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Instalar actualización")
                            }
                        }
                    }
                }
            }
            updateError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        } } }

        item { SettingsSection({ DownloadIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) }, "Descargas", "Red") }
        item { EditorialCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SettingsToggleRow("Solo con Wi‑Fi", "Evita usar datos móviles antes de iniciar cada descarga.", wifiOnly, wifi)
        } } }

        item { SettingsSection({ SyncIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) }, "Consultas automáticas", "Gestión inteligente") }
        val intervals = listOf("0" to "Solo manualmente", "60" to "Cada hora", "360" to "Cada 6 horas", "1440" to "Una vez al día")
        val hourOptions = (0..23).map { it.toString() to "%02d:00".format(it) }
        val quietStart = settings["quiet_hours_start"]?.toIntOrNull()?.takeIf { it in 0..23 }
        val quietEnd = settings["quiet_hours_end"]?.toIntOrNull()?.takeIf { it in 0..23 }
        item { EditorialCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SettingPicker("Frecuencia de sincronización", intervals.firstOrNull { it.first == (settings["scan_minutes"] ?: "0") }?.second ?: "Solo manualmente", intervals, { setting("scan_minutes", it) }, "Android puede aplazar las consultas para ahorrar batería.")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Pausa nocturna", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text(if (quietStart == null) "Desactivada" else "Activada", style = MaterialTheme.typography.labelMedium, color = if (quietStart == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary) }
                SettingPicker("No consultar desde", quietStart?.let { "%02d:00".format(it) } ?: "Sin pausa", listOf("" to "Sin pausa") + hourOptions, { setting("quiet_hours_start", it) })
                if (quietStart != null) SettingPicker("Reanudar a las", quietEnd?.let { "%02d:00".format(it) } ?: "07:00", hourOptions.filterNot { it.first == quietStart.toString() }, { setting("quiet_hours_end", it) })
                Text("Solo detiene las consultas automáticas; «Buscar novedades ahora» sigue disponible.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsToggleRow("Evitar batería baja", "Pausa las consultas automáticas cuando Android indica poca carga.", settings["battery_not_low"] != "false", { setting("battery_not_low", it.toString()) })
        } } }

        item { SettingsSection({ DotIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) }, "Notificaciones") }
        item { EditorialCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsToggleRow("Avisos de nuevos archivos", "Recibe alertas al detectar materiales nuevos.", settings["notifications"] == "true", { setting("notifications", it.toString()) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsToggleRow("Avisar si se desconecta la sesión", "Recibe un aviso cuando caduque la sesión de Enseñanza Virtual.", settings["notify_session"] != "false", { setting("notify_session", it.toString()) })
            FilledTonalButton(onClick = testNotifications, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Probar notificación") }
        } } }

        item { SettingsSection({ DotIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) }, "Apariencia") }
        item { EditorialCard { Column(Modifier.padding(16.dp)) { SegmentedSetting("Tema de la interfaz", appearance, listOf("light" to "Clara", "dark" to "Oscura", "system" to "Sistema"), theme) } } }

        item { SettingsSection({ DotIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) }, "Extensiones bloqueadas", "Nunca descargar") }
        item { EditorialCard { Column(Modifier.padding(16.dp)) { BlockedExtensionsSetting(settings["blocked_extensions"].orEmpty()) { setting("blocked_extensions", it) } } } }

        item { SettingsSection({ DotIcon(MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) }, "Extras") }
        item { EditorialCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Enseñanza Virtual US", style = MaterialTheme.typography.labelLarge); Text(if (connected) "Sesión activa y conectada" else "Sesión no conectada", style = MaterialTheme.typography.labelMedium, color = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant) }
                    FilledTonalButton(onClick = login, shape = RoundedCornerShape(10.dp)) { Text(if (connected) "Reconectar" else "Conectar") }
                }
                Text("Si caduca la sesión recibirás un aviso para volver a conectarla de forma segura.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            } }
            settings["last_attempt"]?.let { Text("Último intento: ${es.us.ussync.ui.localDateTime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            settings["last_scan"]?.let { Text("Última consulta: ${es.us.ussync.ui.localDateTime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            settings["background_status"]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsToggleRow("Mostrar carpetas ocultas", "Las carpetas que empiezan por «.» no se muestran por defecto en Biblioteca.", settings["show_hidden_folders"] == "true", { setting("show_hidden_folders", it.toString()) })
        } } }

        item { Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Privacidad local estricta", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            Text("Tus documentos y preferencias se guardan en este dispositivo. Las copias modificadas se conservan al descargar una actualización.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        } }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / 1024 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes.toDouble() / 1024 / 1024)
    else -> "%.0f KB".format(bytes.toDouble() / 1024)
}

private fun libraryDisplayName(tree: String?): String? = tree?.let {
    Uri.decode(it).substringAfterLast("tree/").substringAfterLast(":").substringAfterLast("/").takeIf { name -> name.isNotBlank() }
}

private fun actionLabel(action: String): String = when (action) {
    "AUTO_DOWNLOAD" -> "Automáticas"
    "IGNORE" -> "Nunca"
    else -> "Preguntarme"
}
