package es.us.ussync.data

/** Extensions the user never wants downloaded, stored as "mp4,mp3,...". */
fun parseExtensionList(value: String?): Set<String> = value.orEmpty()
    .split(',', ';', ' ', '\n', '\t')
    .map { it.trim().trimStart('.').lowercase() }
    .filter { it.isNotBlank() }
    .toSet()

fun Set<String>.asExtensionList(): String = sorted().joinToString(",")

fun isBlockedExtension(filename: String, blockedExtensions: Set<String>): Boolean {
    if (blockedExtensions.isEmpty()) return false
    val extension = filename.substringAfterLast('.', "").trim().lowercase()
    return extension.isNotBlank() && extension in blockedExtensions
}

/** A folder rule matches that folder and descendants, not similarly named siblings. */
fun RuleEntity.matches(document: InboxDocument): Boolean {
    val key = document.documentKey.split(":")
    val path = pathPrefix?.trim('/')
    return enabled &&
        (source == null || source.equals(key.firstOrNull(), true)) &&
        (courseId == null || courseId == key.getOrNull(1)) &&
        (path == null || path.isEmpty() || document.relativePath == path || document.relativePath.startsWith("$path/")) &&
        (extension == null || document.filename.endsWith(".${extension.trimStart('.')}", true)) &&
        (nameContains == null || document.filename.contains(nameContains, true)) &&
        (minSize == null || document.size?.let { it >= minSize } == true) &&
        (maxSize == null || document.size?.let { it <= maxSize } == true) &&
        (changeKind == null || changeKind == document.kind)
}
