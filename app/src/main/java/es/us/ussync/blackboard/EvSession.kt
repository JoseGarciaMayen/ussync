package es.us.ussync.blackboard

import android.webkit.CookieManager
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import es.us.ussync.data.AppDatabase
import es.us.ussync.data.ChangeSummary
import es.us.ussync.data.InboxDocument
import es.us.ussync.data.AppSettingsEntity
import es.us.ussync.data.DownloadRecordEntity
import es.us.ussync.data.CourseEntity
import es.us.ussync.data.RuleEntity
import es.us.ussync.data.IgnoredDocument
import es.us.ussync.data.InboxItemEntity
import es.us.ussync.data.isBlockedExtension
import es.us.ussync.data.matches
import es.us.ussync.data.parseExtensionList
import es.us.ussync.data.reconcileMissingDownloads
import es.us.ussync.data.reconcileExistingLibraryFiles
import es.us.ussync.data.cleanCourseName
import es.us.ussync.data.normalizeForMatching
import es.us.ussync.data.subjectInitials
import es.us.ussync.storage.LibraryDownloader
import es.us.ussync.storage.LibraryLocationStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.io.File
import org.jsoup.Jsoup

private const val EV_HOST = "ev.us.es"
private const val EV_ORIGIN = "https://$EV_HOST"

internal object EvEndpoints {
    val loginUrl = "$EV_ORIGIN/ultra".toHttpUrl()
    val profileUrls = listOf(
        "$EV_ORIGIN/learn/api/public/v1/users/me".toHttpUrl(),
        "$EV_ORIGIN/learn/api/v1/users/me".toHttpUrl(),
    )

    fun isTrusted(url: String): Boolean = runCatching {
        val parsed = url.toHttpUrl()
        parsed.isHttps && parsed.host == EV_HOST && parsed.port == 443
    }.getOrDefault(false)
}

/** Blackboard usa tipos de contenido distintos para las carpetas según el curso. */
internal fun hasChildren(item: JSONObject): Boolean = item.optBoolean("hasChildren", true)

internal class WebViewCookieInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain) = chain.proceed(
        chain.request().newBuilder().apply {
            val url = chain.request().url
            if (url.isHttps && url.host == EV_HOST && url.port == 443) {
                CookieManager.getInstance().getCookie(url.toString())?.let { header("Cookie", it) }
            }
        }.build()
    )
}

data class EvUser(val id: String, val displayName: String, val apiPrefix: String)
data class EvCourse(val id: String, val name: String, val courseId: String, val folder: String? = null)
data class EvDocument(
    val key: String,
    val courseName: String,
    val path: List<String>,
    val filename: String,
    val revision: String?,
    val size: Long?,
    val availableFrom: String? = null,
)

internal fun resolveItemParents(parents: List<String>, title: String): List<String> {
    val cleanTitle = title.trim()
    val lastParent = parents.lastOrNull()?.trim()
    return if (cleanTitle.isBlank() ||
        cleanTitle.equals("ultraDocumentBody", ignoreCase = true) ||
        cleanTitle.equals(lastParent, ignoreCase = true)
    ) {
        parents
    } else {
        parents + cleanTitle
    }
}

internal fun extensionForMimeType(mimeType: String): String? = when (mimeType.trim().lowercase()) {
    "application/pdf" -> ".pdf"
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx"
    "application/vnd.ms-powerpoint" -> ".ppt"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx"
    "application/msword" -> ".doc"
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
    "application/vnd.ms-excel" -> ".xls"
    "application/zip", "application/x-zip-compressed" -> ".zip"
    "application/x-ipynb+json" -> ".ipynb"
    "image/png" -> ".png"
    "image/jpeg", "image/jpg" -> ".jpg"
    "text/plain" -> ".txt"
    else -> null
}

internal fun resolveFilename(
    displayName: String,
    linkName: String,
    innerText: String,
    mimeType: String,
    webdavPath: String,
): String {
    var name = displayName.trim().ifBlank { linkName.trim() }.ifBlank { innerText.trim() }
    if (name.isBlank()) {
        name = webdavPath.substringAfterLast('/').ifBlank { "documento" }
    }
    val hasExtension = Regex("""\.[a-zA-Z0-9]{2,5}$""").containsMatchIn(name)
    if (!hasExtension) {
        val linkExt = Regex("""\.[a-zA-Z0-9]{2,5}$""").find(linkName.trim())?.value
        if (linkExt != null) {
            name = name.trimEnd('.', ' ') + linkExt
        } else {
            val mimeExt = extensionForMimeType(mimeType)
            if (mimeExt != null) {
                name = name.trimEnd('.', ' ') + mimeExt
            }
        }
    }
    name = name.replace(Regex("""\s+\."""), ".")
    return LibraryDownloader.safeName(name)
}

internal fun extractEmbeddedDocuments(
    course: EvCourse,
    contentId: String,
    parents: List<String>,
    revision: String?,
    availableFrom: String?,
    bodyHtml: String,
): List<EvDocument> {
    if (bodyHtml.isBlank() || !bodyHtml.contains("bbcswebdav")) return emptyList()
    val documents = mutableListOf<EvDocument>()
    val seenPaths = mutableSetOf<String>()

    val soup = Jsoup.parse(bodyHtml)
    val links = soup.select("a")
    for (link in links) {
        val bbfileAttr = link.attr("data-bbfile")
        val href = link.attr("href").trim()
        val bbfile = if (bbfileAttr.isNotBlank()) {
            runCatching { JSONObject(bbfileAttr) }.getOrNull()
        } else null

        val viewerUrl = bbfile?.optString("viewerUrl").orEmpty().trim()
        val rawUrl = href.ifBlank { viewerUrl }
        if (!rawUrl.contains("/bbcswebdav/")) continue

        val webdavPath = when {
            rawUrl.startsWith("/bbcswebdav/") -> rawUrl.substringBefore('?')
            rawUrl.contains("/bbcswebdav/") -> runCatching {
                rawUrl.toHttpUrl().encodedPath
            }.getOrNull()
            else -> null
        } ?: continue

        if (!seenPaths.add(webdavPath)) continue

        val displayName = bbfile?.optString("displayName").orEmpty()
        val linkName = bbfile?.optString("linkName").orEmpty()
        val innerText = link.text()
        val mimeType = bbfile?.optString("mimeType").orEmpty()

        val filename = resolveFilename(
            displayName = displayName,
            linkName = linkName,
            innerText = innerText,
            mimeType = mimeType,
            webdavPath = webdavPath,
        )

        val encodedPath = URLEncoder.encode(webdavPath, StandardCharsets.UTF_8.name())
        val key = "ev:${course.id}:$contentId:webdav_$encodedPath"
        documents += EvDocument(
            key = key,
            courseName = course.name,
            path = parents,
            filename = filename,
            revision = revision,
            size = null,
            availableFrom = availableFrom,
        )
    }
    return documents
}

internal sealed interface ProfileResult {
    data class Valid(val user: EvUser) : ProfileResult
    data object Expired : ProfileResult
    data class Failed(val message: String) : ProfileResult
}

internal class EvSessionExpiredException : IllegalStateException("La sesión ha caducado.")

internal class EvProfileClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(WebViewCookieInterceptor())
        .build(),
) {
    fun verify(): ProfileResult {
        var hasUnauthorized = false
        var hasUnavailable = false
        for (url in EvEndpoints.profileUrls) {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val json = JSONObject(response.body?.string().orEmpty())
                        val id = json.optString("id")
                        if (id.isNotBlank()) {
                            val nameObject = json.optJSONObject("name")
                            val name = listOf(
                                nameObject?.optString("given").orEmpty(),
                                nameObject?.optString("family").orEmpty(),
                            ).filter { it.isNotBlank() }.joinToString(" ").ifBlank {
                                json.optString("name").takeUnless { it.startsWith("{") }
                                    ?: json.optString("userName", id)
                            }
                            return ProfileResult.Valid(EvUser(id, name, url.encodedPath.removeSuffix("/users/me")))
                        }
                    }
                    401 -> hasUnauthorized = true
                    429, in 500..599 -> hasUnavailable = true
                }
            }
        }
        return when {
            hasUnauthorized -> ProfileResult.Expired
            hasUnavailable -> ProfileResult.Failed("Enseñanza Virtual no está disponible ahora.")
            else -> ProfileResult.Failed("No se encontró una API de perfil compatible.")
        }
    }
}

internal class BlackboardClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 16
        })
        .addInterceptor(WebViewCookieInterceptor())
        .build(),
) {
    private fun page(url: okhttp3.HttpUrl): String {
        require(EvEndpoints.isTrusted(url.toString()))
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            when (response.code) {
                401 -> throw EvSessionExpiredException()
                403 -> throw IllegalStateException("Enseñanza Virtual ha denegado el acceso.")
                in 400..599 -> throw IllegalStateException(
                    "Enseñanza Virtual respondió HTTP ${response.code} en ${url.encodedPath}."
                )
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun paged(first: okhttp3.HttpUrl): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        var next: okhttp3.HttpUrl? = first
        while (next != null) {
            check(seen.add(next.toString())) { "La API repitió una página." }
            val body = JSONTokener(page(next)).nextValue()
            if (body is JSONArray) {
                for (index in 0 until body.length()) result += body.getJSONObject(index)
                break
            }
            val objectBody = body as? JSONObject
                ?: throw IllegalStateException("Formato de listado de Enseñanza Virtual no compatible.")
            val items = objectBody.optJSONArray("results") ?: objectBody.optJSONArray("items")
                ?: throw IllegalStateException("Formato de listado de Enseñanza Virtual no compatible.")
            for (index in 0 until items.length()) result += items.getJSONObject(index)
            val nextPage = objectBody.optJSONObject("paging")?.optString("nextPage").orEmpty()
            next = if (nextPage.isBlank()) null else EV_ORIGIN.toHttpUrl().resolve(nextPage)
                ?.takeIf { EvEndpoints.isTrusted(it.toString()) }
                ?: throw IllegalStateException("La API devolvió una página fuera de ev.us.es.")
        }
        return result
    }

    private fun extractAvailableFrom(item: JSONObject): String? {
        val rules = item.optJSONObject("adaptiveReleaseRules")?.optJSONArray("rules") ?: return null
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val criteria = rule.optJSONArray("criteria") ?: continue
            for (j in 0 until criteria.length()) {
                val criterion = criteria.optJSONObject(j) ?: continue
                if (criterion.optString("type") == "Date_Range" && !criterion.optBoolean("negate", false)) {
                    val start = criterion.optString("start").ifBlank { null }
                    if (start != null) return start
                }
            }
        }
        return null
    }

    fun courses(user: EvUser): List<EvCourse> {
        val encoded = URLEncoder.encode(user.id, StandardCharsets.UTF_8.name())
        return paged("$EV_ORIGIN${user.apiPrefix}/users/$encoded/courses?expand=course".toHttpUrl())
            .mapNotNull { membership ->
                val course = membership.optJSONObject("course") ?: JSONObject()
                val id = course.optString("id").ifBlank { membership.optString("courseId") }
                id.takeIf { it.isNotBlank() }?.let {
                    EvCourse(it, course.optString("name", it), course.optString("courseId"))
                }
            }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun documents(user: EvUser, courses: List<EvCourse>, dao: es.us.ussync.data.CatalogDao? = null): List<EvDocument> = coroutineScope {
        courses.map { course ->
            async(Dispatchers.IO) {
                scanCourse(user, course, dao)
            }
        }.awaitAll().flatten()
    }

    private suspend fun scanCourse(user: EvUser, course: EvCourse, dao: es.us.ussync.data.CatalogDao? = null): List<EvDocument> {
        val result = java.util.concurrent.ConcurrentLinkedDeque<EvDocument>()
        val folders = setOf("resource/x-bb-folder", "resource/x-bb-lesson", "resource/x-bb-module")
        val courseId = URLEncoder.encode(course.id, StandardCharsets.UTF_8.name())

        // Caché diferencial: contentId → (modified, childrenFetched)
        val cacheMap: Map<String, es.us.ussync.data.ContentCacheEntity> = dao
            ?.contentCacheForCourse(course.id)
            ?.associateBy { it.contentId }
            .orEmpty()
        // Documentos ya conocidos para esta asignatura (para la verificación conservadora)
        val knownContentIds: Set<String> = if (dao != null) {
            dao.allAvailableDocuments()
                .filter { it.courseId == course.id }
                .map { it.key.split(":").getOrElse(2) { "" } }
                .filter { it.isNotBlank() }
                .toSet()
        } else emptySet()
        val cacheUpdates = java.util.concurrent.ConcurrentLinkedDeque<es.us.ussync.data.ContentCacheEntity>()

        data class Pending(
            val url: okhttp3.HttpUrl,
            val parents: List<String>,
            val isChildrenRequest: Boolean,
        )
        val queue = java.util.concurrent.ConcurrentLinkedDeque<Pending>()
        queue += Pending(
            "$EV_ORIGIN${user.apiPrefix}/courses/$courseId/contents".toHttpUrl(),
            emptyList(),
            false,
        )
        val visited = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // Semáforo: máximo 8 peticiones HTTP concurrentes por asignatura
        val semaphore = kotlinx.coroutines.sync.Semaphore(8)

        // BFS paralelo: procesa hasta que la cola esté vacía
        coroutineScope {
            while (true) {
                // Drena la cola actual (puede crecer mientras se procesa)
                val batch = mutableListOf<Pending>()
                while (true) { batch += queue.pollFirst() ?: break }
                if (batch.isEmpty()) break

                val jobs = batch.map { request ->
                    async(Dispatchers.IO) {
                        val items = semaphore.withPermit {
                            try {
                                paged(request.url)
                            } catch (error: IllegalStateException) {
                                if (request.isChildrenRequest && (
                                        error.message.orEmpty().contains("HTTP 400") ||
                                            error.message.orEmpty().contains("HTTP 404")
                                    )
                                ) {
                                    return@async
                                }
                                throw error
                            }
                        }
                        for (item in items) {
                            val contentId = item.optString("id")
                            if (contentId.isBlank() || !visited.add(contentId)) continue
                            val title = item.optString("title", "Contenido")
                            val handler = item.optJSONObject("contentHandler")?.optString("id").orEmpty()
                            val isFolder = handler in folders || handler.contains("folder") || handler.contains("lesson")
                            val encodedContent = URLEncoder.encode(contentId, StandardCharsets.UTF_8.name())
                            val itemModified = item.optString("modified").ifBlank { null }

                            if (hasChildren(item) && handler != "resource/x-bb-document") {
                                val childrenUrl = "$EV_ORIGIN${user.apiPrefix}/courses/$courseId/contents/$encodedContent/children".toHttpUrl()

                                // --- Caché diferencial conservadora ---
                                val cached = cacheMap[contentId]
                                val canSkip = cached != null &&
                                    itemModified != null &&
                                    cached.modified == itemModified &&
                                    cached.childrenFetched &&
                                    contentId in knownContentIds

                                if (!canSkip) {
                                    queue += Pending(childrenUrl, request.parents + title, true)
                                }
                                // Actualizar caché si hay modified
                                if (itemModified != null) {
                                    cacheUpdates += es.us.ussync.data.ContentCacheEntity(
                                        contentId = contentId,
                                        courseId = course.id,
                                        modified = itemModified,
                                        childrenFetched = true,
                                        lastSeenAt = java.time.Instant.now().toString(),
                                    )
                                }
                            }
                            val available = item.optJSONObject("availability")?.optString("available")
                            var availableFrom = extractAvailableFrom(item)
                            if (availableFrom == null && (available == "PartiallyVisible" || item.optString("visibility") == "PARTIALLY_VISIBLE")) {
                                availableFrom = runCatching {
                                    val detailUrl = "$EV_ORIGIN/learn/api/v1/courses/$courseId/contents/$encodedContent".toHttpUrl()
                                    val detail = JSONObject(page(detailUrl))
                                    extractAvailableFrom(detail)
                                }.getOrNull()
                            }
                            val bodyHtml = item.optString("body")
                            if (bodyHtml.isNotBlank() && bodyHtml.contains("bbcswebdav")) {
                                result.addAll(extractEmbeddedDocuments(
                                    course = course,
                                    contentId = contentId,
                                    parents = resolveItemParents(request.parents, title),
                                    revision = item.optString("modified").ifBlank { null },
                                    availableFrom = availableFrom,
                                    bodyHtml = bodyHtml,
                                ))
                            }
                            if (isFolder || handler.contains("externallink") || handler.contains("blti") || handler == "resource/x-bb-document") continue
                            val attachmentUrl = "$EV_ORIGIN${user.apiPrefix}/courses/$courseId/contents/$encodedContent/attachments".toHttpUrl()
                            try {
                                for (attachment in semaphore.withPermit { paged(attachmentUrl) }) {
                                    val attachmentId = attachment.optString("id")
                                    if (attachmentId.isBlank()) continue
                                    val name = attachment.optString("fileName").ifBlank {
                                        attachment.optString("name", attachmentId)
                                    }
                                    val size = sequenceOf("size", "fileSize", "sizeBytes", "contentLength")
                                        .mapNotNull { key -> attachment.optLong(key, -1).takeIf { it >= 0 } }
                                        .firstOrNull()
                                    result += EvDocument(
                                        "ev:${course.id}:$contentId:$attachmentId",
                                        course.name,
                                        resolveItemParents(request.parents, title),
                                        name,
                                        attachment.optString("modified").ifBlank { item.optString("modified") }.ifBlank { null },
                                        size,
                                        availableFrom,
                                    )
                                }
                            } catch (error: IllegalStateException) {
                                val message = error.message.orEmpty()
                                if (!message.contains("HTTP 400") && !message.contains("HTTP 404")) throw error
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        }

        // Persiste actualizaciones de caché en batch
        if (dao != null && cacheUpdates.isNotEmpty()) {
            dao.upsertContentCache(cacheUpdates.toList())
        }

        return result.toList()
    }

    fun download(user: EvUser, document: EvDocument, target: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val parts = document.key.split(":")
        require(parts.size == 4 && parts.first() == "ev")
        val course = URLEncoder.encode(parts[1], StandardCharsets.UTF_8.name())
        val content = URLEncoder.encode(parts[2], StandardCharsets.UTF_8.name())
        val isWebdav = parts[3].startsWith("webdav_")
        val url = if (isWebdav) {
            val path = URLDecoder.decode(parts[3].removePrefix("webdav_"), StandardCharsets.UTF_8.name())
            "$EV_ORIGIN$path".toHttpUrl()
        } else {
            val attachment = URLEncoder.encode(parts[3], StandardCharsets.UTF_8.name())
            "$EV_ORIGIN${user.apiPrefix}/courses/$course/contents/$content/attachments/$attachment/download".toHttpUrl()
        }
        repeat(4) { attempt ->
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.code in listOf(429, 500, 502, 503, 504) && attempt < 3) {
                    Thread.sleep(response.header("Retry-After")?.toLongOrNull()?.coerceAtMost(120)?.times(1_000) ?: (1L shl attempt) * 1_000)
                    return@use
                }
                if (response.code == 401) throw EvSessionExpiredException()
                if (response.code == 404 || response.code == 403) {
                    val availableDate = document.availableFrom ?: runCatching {
                        val detail = JSONObject(page("$EV_ORIGIN/learn/api/v1/courses/$course/contents/$content".toHttpUrl()))
                        extractAvailableFrom(detail)
                    }.getOrNull()
                    if (availableDate != null) {
                        val formatted = es.us.ussync.ui.localDateShort(availableDate)
                        throw IllegalStateException("Disponible a partir del $formatted.")
                    }
                }
                if (!response.isSuccessful) throw IllegalStateException("Descarga EV HTTP ${response.code}.")
                val body = response.body ?: throw IllegalStateException("EV no devolvió contenido.")
                val total = body.contentLength()
                var received = 0L
                target.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            progress(received, total)
                        }
                    }
                }
                val head = target.inputStream().use { input ->
                    val buffer = ByteArray(1024)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    buffer.take(count).dropWhile { byte -> byte.toInt().toChar().isWhitespace() }.toByteArray()
                }
                if (document.filename.endsWith(".pdf", true) && !head.decodeToString().startsWith("%PDF-")) {
                    throw IllegalStateException("Se esperaba un PDF, pero EV devolvió otro contenido.")
                }
                if (target.length() == 0L) throw IllegalStateException("EV devolvió un archivo vacío.")
                return
            }
        }
        throw IllegalStateException("Descarga interrumpida tras varios intentos.")
    }
}

sealed interface EvSessionState {
    data object Welcome : EvSessionState
    data object Restoring : EvSessionState
    data object Login : EvSessionState
    data object Verifying : EvSessionState
    data class Authenticated(
        val user: EvUser,
        val courses: List<EvCourse> = emptyList(),
        val selectedCourseIds: Set<String> = emptySet(),
        val documents: List<EvDocument> = emptyList(),
        val changes: ChangeSummary? = null,
        val loading: Boolean = false,
        val error: String? = null,
    ) : EvSessionState
    data class Failed(val message: String) : EvSessionState
}

class EvSessionViewModel(application: Application) : AndroidViewModel(application) {
    private val profileClient = EvProfileClient()
    private val blackboardClient = BlackboardClient()
    private val catalog = AppDatabase.get(application).catalogDao()
    @Volatile private var downloadSlots = Semaphore(4)
    private val publicationLock = es.us.ussync.sync.SyncLocks.publication
    val settings = catalog.observeSettings().map { values -> values.associate { it.key to it.value } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    fun setBackgroundSetting(key: String, value: String) = viewModelScope.launch {
        catalog.putSetting(AppSettingsEntity(key, value))
        es.us.ussync.workers.SyncSchedule.update(getApplication(),
            catalog.setting("scan_minutes")?.toLongOrNull() ?: 0,
            catalog.setting("wifi_only") != "false", catalog.setting("battery_not_low") != "false")
        if (key == "default_action") {
            applyRulesToPending(catalog.evCourses().filter { it.selected }.map { it.remoteId }.toSet())
        }
    }
    val downloads = catalog.downloads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recentDownloads = catalog.recentDownloads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latestScan = catalog.observeLatestScan()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val rules = catalog.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Remote documents suppressed by an IGNORE rule, with their local copy when it exists. */
    val ignoredDocuments = combine(
        catalog.availableDocuments(), catalog.observeRules(), catalog.downloads(), catalog.observeEvCourses(),
    ) { documents, rules, downloads, courses ->
        val byKey = downloads.associateBy { it.documentKey }
        val folders = courses.associate { it.remoteId to (it.folder ?: it.name) }
        documents.mapNotNull { document ->
            val inboxLike = InboxDocument(0, document.key, document.revision, "NEW", "PENDING",
                document.courseName, document.relativePath, document.filename, document.size)
            val matching = rules.filter { it.action == "IGNORE" && it.matches(inboxLike) }
            if (matching.isEmpty()) null
            else IgnoredDocument(document.key, folders[document.courseId] ?: document.courseName,
                document.relativePath, document.filename, document.size,
                byKey[document.key]?.targetUri, matching.any { it.nameContains != null })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Local files that still exist in Enseñanza Virtual, used to flag files added locally. */
    val evLibraryUris = combine(catalog.downloads(), catalog.availableDocuments()) { downloads, documents ->
        val evKeys = documents.asSequence().filter { it.key.startsWith("ev:") }.map { it.key }.toSet()
        downloads.asSequence().filter { it.documentKey in evKeys }.map { it.targetUri }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun saveRule(course: String, path: String?, action: String, extension: String? = null, maxSize: Long? = null) = viewModelScope.launch {
        val existing = catalog.savedRules().firstOrNull { it.courseId == course && it.pathPrefix == path && it.extension == extension && it.maxSize == maxSize }
        val priority = (if (path == null) 0 else 100 * (1 + path.count { it == '/' } + if (path.isBlank()) 0 else 1)) +
            (if (extension == null) 0 else 10) + (if (maxSize == null) 0 else 1)
        catalog.saveRule(RuleEntity(id = existing?.id ?: 0, priority = priority,
            source = "EV", courseId = course, pathPrefix = path, extension = extension, maxSize = maxSize, action = action, createdAt = Instant.now().toString()))
    }
    fun removeRule(id: Long) = viewModelScope.launch { catalog.deleteRule(id) }
    val appearance = MutableStateFlow("system")
    val wifiOnly = MutableStateFlow(true)
    fun setWifiOnly(value: Boolean) = viewModelScope.launch {
        catalog.putSetting(AppSettingsEntity("wifi_only", value.toString()))
        wifiOnly.value = value
        es.us.ussync.workers.SyncSchedule.update(getApplication(), catalog.setting("scan_minutes")?.toLongOrNull() ?: 0,
            value, catalog.setting("battery_not_low") != "false")
    }
    val downloadErrors = MutableStateFlow<Map<Long, String>>(emptyMap())
    fun setAppearance(value: String) = viewModelScope.launch {
        catalog.putSetting(AppSettingsEntity("appearance", value))
        appearance.value = value
    }
    fun restoreInbox(item: InboxDocument) = viewModelScope.launch {
        catalog.updateInboxState(item.inboxId, item.state)
    }

    /**
     * Forgets downloaded documents: drops their download history, marks them ignored so
     * future scans neither flag them as missing nor download them again, and hides any
     * pending inbox entry. Use after the user deletes the local files.
     */
    fun forgetDocuments(documentKeys: List<String>) = viewModelScope.launch {
        val now = Instant.now().toString()
        val pending = catalog.pendingDocuments()
        documentKeys.distinct().forEach { key ->
            val remote = catalog.document(key) ?: return@forEach
            val extension = remote.filename.substringAfterLast('.', "").trim().lowercase().ifBlank { null }
            val existing = catalog.savedRules().firstOrNull {
                it.courseId == remote.courseId && it.nameContains == remote.filename && it.extension == extension
            }
            catalog.saveRule(RuleEntity(
                id = existing?.id ?: 0, priority = 5000, source = remote.source, courseId = remote.courseId,
                nameContains = remote.filename, extension = extension, action = "IGNORE", createdAt = now,
            ))
            catalog.clearDownloadState(key)
            catalog.deleteDownloadRecords(key)
            pending.filter { it.documentKey == key }.forEach { catalog.updateInboxState(it.inboxId, "IGNORED", now) }
        }
    }

    /** Ignores every current and future document inside a course folder. */
    fun ignoreFolder(courseId: String?, pathPrefix: String) = viewModelScope.launch {
        val existing = catalog.savedRules().firstOrNull {
            it.courseId == courseId && it.pathPrefix == pathPrefix && it.extension == null && it.nameContains == null
        }
        catalog.saveRule(RuleEntity(
            id = existing?.id ?: 0, priority = 5000, source = null, courseId = courseId,
            pathPrefix = pathPrefix, action = "IGNORE", createdAt = Instant.now().toString(),
        ))
        val rules = catalog.savedRules()
        val now = Instant.now().toString()
        catalog.pendingDocuments().forEach { item ->
            if (rules.firstOrNull { it.matches(item) }?.action == "IGNORE") catalog.updateInboxState(item.inboxId, "IGNORED", now)
        }
    }

    /** Removes the exact IGNORE rule that keeps a folder (or its parent) from downloading. */
    fun unignoreFolder(courseId: String?, pathPrefix: String) = viewModelScope.launch {
        catalog.savedRules().filter {
            it.action == "IGNORE" && it.courseId == courseId && it.pathPrefix == pathPrefix &&
                it.extension == null && it.nameContains == null
        }.forEach { catalog.deleteRule(it.id) }
    }

    /**
     * Turns the "no volver a descargar" switch on or off for one document. Turning it off when
     * the local copy is gone queues it again so it can be downloaded.
     */
    fun setDocumentIgnored(documentKey: String, ignored: Boolean) = viewModelScope.launch {
        val remote = catalog.document(documentKey) ?: return@launch
        val now = Instant.now().toString()
        if (ignored) {
            val extension = remote.filename.substringAfterLast('.', "").trim().lowercase().ifBlank { null }
            val existing = catalog.savedRules().firstOrNull {
                it.courseId == remote.courseId && it.nameContains == remote.filename && it.extension == extension
            }
            catalog.saveRule(RuleEntity(
                id = existing?.id ?: 0, priority = 5000, source = remote.source, courseId = remote.courseId,
                nameContains = remote.filename, extension = extension, action = "IGNORE", createdAt = now,
            ))
            catalog.pendingDocuments().filter { it.documentKey == documentKey }
                .forEach { catalog.updateInboxState(it.inboxId, "IGNORED", now) }
            return@launch
        }
        catalog.savedRules().filter {
            it.action == "IGNORE" && it.courseId == remote.courseId && it.nameContains == remote.filename
        }.forEach { catalog.deleteRule(it.id) }
        if (catalog.latestDownload(documentKey) == null) {
            val course = catalog.evCourses().firstOrNull { it.remoteId == remote.courseId }
            val inboxId = catalog.pendingInboxId(documentKey, remote.revision)
                ?: catalog.inbox(InboxItemEntity(documentKey = documentKey, revision = remote.revision, kind = "NEW", createdAt = now))
            downloadInbox(InboxDocument(
                inboxId = inboxId, documentKey = documentKey, revision = remote.revision, kind = "NEW", state = "PENDING",
                courseName = course?.name ?: remote.courseName, relativePath = remote.relativePath,
                filename = remote.filename, size = remote.size,
            ))
        }
    }

    val inboxItems = catalog.openInbox().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    private val mutableScreen = MutableStateFlow(AppScreen.HOME)
    val screen: StateFlow<AppScreen> = mutableScreen.asStateFlow()
    private val mutableLibraryUri = MutableStateFlow<String?>(null)
    val libraryUri: StateFlow<String?> = mutableLibraryUri.asStateFlow()
    private val mutableDownloadProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<Long, Float>> = mutableDownloadProgress.asStateFlow()
    private val mutableState = MutableStateFlow<EvSessionState>(EvSessionState.Restoring)
    val state: StateFlow<EvSessionState> = mutableState.asStateFlow()
    private var restoreJob: kotlinx.coroutines.Job? = null

    init {
        // Local settings must finish loading even if the user opens the login while
        // the remote EV session is still being checked.
        viewModelScope.launch {
            val savedLibrary = catalog.setting("library_tree_uri")
            val libraryStore = LibraryLocationStore(getApplication())
            val resolvedLibrary = libraryStore.resolve(savedLibrary)
            mutableLibraryUri.value = resolvedLibrary
            if (resolvedLibrary != null) {
                libraryStore.remember(resolvedLibrary)
                if (resolvedLibrary != savedLibrary) {
                    // Repair a Room write that was interrupted after Android had already
                    // persisted the folder permission.
                    catalog.putSetting(AppSettingsEntity("library_tree_uri", resolvedLibrary))
                }
            }
            appearance.value = catalog.setting("appearance") ?: "system"
            wifiOnly.value = catalog.setting("wifi_only") != "false"
        }
        restoreJob = viewModelScope.launch { restoreSavedSession() }
    }

    private suspend fun restoreSavedSession() {
        val result = try {
            withContext(Dispatchers.Main) { CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().flush() }
            withContext(Dispatchers.IO) { profileClient.verify() }
        }
        catch (_: Exception) { ProfileResult.Failed("No se pudo comprobar la sesión guardada.") }
        when (result) {
            is ProfileResult.Valid -> {
                mutableState.value = EvSessionState.Authenticated(result.user)
                loadCourses()
            }
            else -> mutableState.value = EvSessionState.Welcome
        }
    }

    fun showLogin() {
        restoreJob?.cancel()
        CookieManager.getInstance().setAcceptCookie(true)
        mutableState.value = EvSessionState.Login
    }

    fun showInbox() { mutableScreen.value = AppScreen.INBOX }
    fun showHome() { mutableScreen.value = AppScreen.HOME }

    fun setLibraryUri(uri: String) {
        check(LibraryLocationStore(getApplication()).remember(uri)) {
            "No se pudo guardar la carpeta seleccionada."
        }
        mutableLibraryUri.value = uri
        viewModelScope.launch {
            catalog.putSetting(AppSettingsEntity("library_tree_uri", uri))
        }
    }

    fun ignoreInbox(item: InboxDocument) = viewModelScope.launch {
        catalog.updateInboxState(item.inboxId, "IGNORED", Instant.now().toString())
    }

    fun laterInbox(item: InboxDocument) = viewModelScope.launch {
        catalog.updateInboxState(item.inboxId, "LATER")
    }

    fun downloadInbox(item: InboxDocument) = viewModelScope.launch {
        if (item.inboxId in mutableDownloadProgress.value) return@launch
        val library = mutableLibraryUri.value ?: return@launch
        val current = mutableState.value as? EvSessionState.Authenticated ?: return@launch
        val remote = catalog.document(item.documentKey) ?: return@launch
        val savedCourse = catalog.evCourses().firstOrNull { it.remoteId == remote.courseId }
        val doc = EvDocument(
            remote.key, savedCourse?.folder ?: remote.courseName, remote.relativePath.takeIf { it.isNotBlank() }?.split("/").orEmpty(),
            remote.filename, remote.revision, remote.size, remote.availableFrom,
        )
        if (doc.availableFrom != null && es.us.ussync.ui.isFutureDate(doc.availableFrom)) {
            val formatted = es.us.ussync.ui.localDateShort(doc.availableFrom)
            downloadErrors.value = downloadErrors.value + (item.inboxId to "Disponible a partir del $formatted.")
            return@launch
        }
        // The database reads suspend; another tap may have queued this item meanwhile.
        if (item.inboxId in mutableDownloadProgress.value) return@launch
        mutableDownloadProgress.value = mutableDownloadProgress.value + (item.inboxId to -1f)
        downloadErrors.value = downloadErrors.value - item.inboxId
        var temporary: File? = null
        try {
            val downloaded = downloadSlots.withPermit { withContext(Dispatchers.IO) {
                if (wifiOnly.value) {
                    val manager = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
                    check(manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        "Solo Wi‑Fi está activado. Conéctate a una red Wi‑Fi y pulsa Reintentar."
                    }
                }
                File.createTempFile("ussync-", ".part", getApplication<Application>().cacheDir).also { file ->
                    temporary = file
                    blackboardClient.download(current.user, doc, file) { received, total ->
                        viewModelScope.launch {
                            if (item.inboxId in mutableDownloadProgress.value) {
                                mutableDownloadProgress.value = mutableDownloadProgress.value + (item.inboxId to
                                    if (total > 0) received.toFloat() / total else -1f)
                            }
                        }
                    }
                }
            } }
            val previous = catalog.latestDownload(remote.key)?.let { es.us.ussync.storage.LocalFileState(it.targetUri, it.sha256) }
            val published = publicationLock.withLock { withContext(Dispatchers.IO) { LibraryDownloader(getApplication<Application>())
                .publish(library, doc, downloaded, previous) } }
            catalog.recordDownload(DownloadRecordEntity(
                documentKey = remote.key, remoteRevision = remote.revision, targetUri = published.uri,
                sha256 = published.sha256, bytes = downloaded.length(), result = "DOWNLOADED", createdAt = Instant.now().toString(),
            ))
            catalog.markDownloaded(remote.key, published.sha256, remote.revision)
            catalog.updateInboxState(item.inboxId, "DOWNLOADED", Instant.now().toString())
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            downloadErrors.value = downloadErrors.value + (item.inboxId to (error.message ?: "No se pudo descargar. Vuelve a intentarlo."))
        } finally {
            temporary?.delete()
            mutableDownloadProgress.value = mutableDownloadProgress.value - item.inboxId
        }
    }

    fun showWelcome() {
        mutableState.value = EvSessionState.Welcome
    }

    fun verifySession(silent: Boolean = false) {
        // A silent check must not change the visible screen. Flipping to Verifying and back
        // removes and recreates the login WebView, reloading it and flashing white each time.
        if (!silent) mutableState.value = EvSessionState.Verifying
        viewModelScope.launch {
            CookieManager.getInstance().flush()
            val result = try { withContext(Dispatchers.IO) { profileClient.verify() } }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                ProfileResult.Failed("No se pudo conectar con Enseñanza Virtual. Comprueba tu conexión y vuelve a intentarlo.")
            }
            when (result) {
                is ProfileResult.Valid -> {
                    getApplication<Application>().getSystemService(android.app.NotificationManager::class.java)
                        .cancel(es.us.ussync.workers.SyncWorker.SESSION_NOTIFICATION_ID)
                    catalog.putSetting(AppSettingsEntity("background_status", "Sesión conectada. Las consultas pueden continuar."))
                    mutableState.value = EvSessionState.Authenticated(result.user)
                    loadCourses()
                }
                ProfileResult.Expired -> if (!silent) mutableState.value = EvSessionState.Failed(
                    "La sesión no es válida o ha caducado. Completa el acceso de nuevo.",
                )
                is ProfileResult.Failed -> if (!silent) mutableState.value = EvSessionState.Failed(result.message)
            }
        }
    }

    fun loadCourses() = withAuthenticated(
        loading = { current -> current.copy(loading = true, error = null) },
        operation = { current ->
            val stored = catalog.evCourses().associateBy { it.remoteId }
            val libraryTree = catalog.setting("library_tree_uri")
            val rootDirs = if (!libraryTree.isNullOrBlank()) {
                runCatching {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(getApplication(), android.net.Uri.parse(libraryTree))
                        ?.listFiles()?.filter { it.isDirectory && !it.name.orEmpty().startsWith(".") }
                }.getOrNull().orEmpty()
            } else emptyList()

            val courses = blackboardClient.courses(current.user).map { course ->
                val existingFolder = stored[course.id]?.folder
                val discoveredFolder = if (existingFolder == null && rootDirs.isNotEmpty()) {
                    val initials = subjectInitials(course.name)
                    rootDirs.firstOrNull { dir ->
                        val dirName = dir.name ?: return@firstOrNull false
                        dirName.uppercase() in initials ||
                        normalizeForMatching(dirName).uppercase() in initials ||
                        dirName.equals(course.name, ignoreCase = true) ||
                        dirName.equals(cleanCourseName(course.name), ignoreCase = true)
                    }?.name
                } else null
                course.copy(folder = existingFolder ?: discoveredFolder)
            }
            catalog.upsertCourses(courses.map { course ->
                CourseEntity("ev:${course.id}", "EV", course.id, course.name, course.folder,
                    course.id in current.selectedCourseIds || stored[course.id]?.selected == true,
                    Instant.now().toString())
            })
            if (!libraryTree.isNullOrBlank()) {
                val cachedDocs = catalog.allAvailableDocuments().map { entity ->
                    EvDocument(
                        key = entity.key,
                        courseName = entity.courseName,
                        path = entity.relativePath.split("/").filter { it.isNotBlank() },
                        filename = entity.filename,
                        revision = entity.revision,
                        size = entity.size,
                        availableFrom = entity.availableFrom,
                    )
                }
                if (cachedDocs.isNotEmpty()) {
                    val folders = courses.mapNotNull { c -> c.folder?.let { c.id to it } }.toMap()
                    catalog.reconcileExistingLibraryFiles(getApplication(), libraryTree, cachedDocs, folders)
                }
            }
            current.copy(
                courses = courses,
                selectedCourseIds = courses.filter { it.id in current.selectedCourseIds || stored[it.id]?.selected == true }.map { it.id }.toSet(),
                loading = false,
            )
        },
    )

    fun toggleCourse(courseId: String) {
        val current = mutableState.value as? EvSessionState.Authenticated ?: return
        val selection = current.selectedCourseIds.toMutableSet()
        if (!selection.add(courseId)) selection.remove(courseId)
        mutableState.value = current.copy(selectedCourseIds = selection, documents = emptyList())
        val course = current.courses.firstOrNull { it.id == courseId }
        if (course != null) viewModelScope.launch(Dispatchers.IO) {
            catalog.upsertCourses(listOf(CourseEntity("ev:${course.id}", "EV", course.id, course.name, course.folder,
                courseId in selection, Instant.now().toString())))
        }
    }

    fun setCourseFolder(courseId: String, name: String) {
        val current = mutableState.value as? EvSessionState.Authenticated ?: return
        val courses = current.courses.map { if (it.id == courseId) it.copy(folder = name.ifBlank { null }) else it }
        mutableState.value = current.copy(courses = courses)
        val course = courses.firstOrNull { it.id == courseId } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            catalog.upsertCourses(listOf(CourseEntity("ev:${course.id}", "EV", course.id, course.name, course.folder,
                course.id in current.selectedCourseIds, Instant.now().toString())))
        }
    }

    fun scanSelectedCourses() = withAuthenticated(
        applyRules = true,
        loading = { current -> current.copy(loading = true, error = null) },
        operation = { current ->
            val selected = current.courses.filter { it.id in current.selectedCourseIds }
            es.us.ussync.sync.SyncLocks.scan.withLock {
            val documents = blackboardClient.documents(current.user, selected, catalog)
            val libraryTree = catalog.setting("library_tree_uri")
            val courseFolders = selected.mapNotNull { c -> c.folder?.let { c.id to it } }.toMap()
            val newlyDiscoveredFolders = mutableMapOf<String, String>()
            val existingFiles = catalog.reconcileExistingLibraryFiles(
                getApplication<Application>(),
                libraryTree,
                documents,
                courseFolders,
            ) { courseId, folderName ->
                newlyDiscoveredFolders[courseId] = folderName
            }
            val missingDownloads = catalog.reconcileMissingDownloads(getApplication<Application>(), libraryTree)
            val blocked = parseExtensionList(catalog.setting("blocked_extensions"))
            val changes = catalog.recordEvScan(documents, selected.map { it.id }, missingDownloads, blocked, existingFiles)
            catalog.putSetting(AppSettingsEntity("last_scan", Instant.now().toString()))
            val updatedCourses = if (newlyDiscoveredFolders.isNotEmpty()) {
                current.courses.map { c ->
                    val found = newlyDiscoveredFolders[c.id]
                    if (found != null && c.folder == null) c.copy(folder = found) else c
                }
            } else current.courses
            current.copy(
                courses = updatedCourses,
                documents = documents,
                changes = changes,
                loading = false,
            )
            }
        },
    )

    private suspend fun applyRulesToPending(courseIds: Set<String>?) {
        val savedRules = catalog.savedRules()
        val blocked = parseExtensionList(catalog.setting("blocked_extensions"))
        val default = catalog.setting("default_action") ?: "ASK"
        catalog.pendingDocuments()
            .filter { courseIds == null || it.documentKey.split(":").getOrNull(1) in courseIds }
            .forEach { item ->
                val action = savedRules.firstOrNull { it.matches(item) }?.action ?: default
                when {
                    action == "IGNORE" -> ignoreInbox(item)
                    isBlockedExtension(item.filename, blocked) -> ignoreInbox(item)
                    action == "AUTO_DOWNLOAD" -> downloadInbox(item)
                }
            }
    }

    private fun withAuthenticated(
        applyRules: Boolean = false,
        loading: (EvSessionState.Authenticated) -> EvSessionState.Authenticated,
        operation: suspend (EvSessionState.Authenticated) -> EvSessionState.Authenticated,
    ) {
        val current = mutableState.value as? EvSessionState.Authenticated ?: return
        mutableState.value = loading(current)
        viewModelScope.launch {
            try {
                mutableState.value = withContext(Dispatchers.IO) { operation(current) }
                if (!applyRules) return@launch
                applyRulesToPending(current.selectedCourseIds)
            } catch (error: Exception) {
                mutableState.value = current.copy(loading = false, error = error.message ?: "Error al consultar EV.")
            }
        }
    }
}

enum class AppScreen { HOME, INBOX }
