package es.us.ussync.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Transaction
import es.us.ussync.blackboard.EvDocument
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Entity(tableName = "remote_documents")
data class RemoteDocumentEntity(
    @PrimaryKey val key: String,
    val source: String,
    val courseId: String,
    val courseName: String,
    val relativePath: String,
    val filename: String,
    val revision: String?,
    val size: Long?,
    val firstSeen: String,
    val lastSeen: String,
    val remoteAvailable: Boolean,
    val lastDownloadedHash: String? = null,
    val lastDownloadedRevision: String? = null,
    val availableFrom: String? = null,
)

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val source: String,
    val remoteId: String,
    val name: String,
    val folder: String? = null,
    val selected: Boolean = false,
    val lastSeen: String,
)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val priority: Int,
    val source: String? = null,
    val courseId: String? = null,
    val pathPrefix: String? = null,
    val extension: String? = null,
    val nameContains: String? = null,
    val minSize: Long? = null,
    val maxSize: Long? = null,
    val changeKind: String? = null,
    val action: String,
    val enabled: Boolean = true,
    val createdAt: String,
)

@Entity(tableName = "download_records")
data class DownloadRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentKey: String,
    val remoteRevision: String?,
    val targetUri: String,
    val sha256: String,
    val bytes: Long,
    val result: String,
    val createdAt: String,
)

data class AutomaticDownload(
    val id: Long,
    val filename: String,
    val courseName: String,
    val createdAt: String,
)

data class RecentDownload(
    val id: Long,
    val filename: String,
    val courseName: String,
    val relativePath: String,
    val createdAt: String,
)

@Entity(tableName = "sevius_selections")
data class SeviusSelectionEntity(
    @PrimaryKey val id: String,
    val subjectCode: String,
    val center: String,
    val year: String,
    val group: String?,
    val projectValue: String?,
    val programValue: String?,
    val courseId: String?,
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: String,
    val finishedAt: String? = null,
    val source: String,
    val outcome: String? = null,
    val errorSummary: String? = null,
)

@Entity(tableName = "document_observations")
data class DocumentObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scanId: Long,
    val documentKey: String,
    val classification: String,
)

@Entity(tableName = "inbox_items")
data class InboxItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentKey: String,
    val revision: String?,
    val kind: String,
    val state: String = "PENDING",
    val ruleAction: String = "ASK",
    val createdAt: String,
    val resolvedAt: String? = null,
)

data class InboxDocument(
    val inboxId: Long,
    val documentKey: String,
    val revision: String?,
    val kind: String,
    val state: String,
    val courseName: String,
    val relativePath: String,
    val filename: String,
    val size: Long?,
    val availableFrom: String? = null,
)

/** A document suppressed by an IGNORE rule, shown in the library so it can be recovered. */
data class IgnoredDocument(
    val documentKey: String,
    val courseFolder: String,
    val relativePath: String,
    val filename: String,
    val size: Long?,
    val localUri: String?,
    val precise: Boolean,
)

@Dao
interface CatalogDao {
    @Query("SELECT * FROM sevius_selections")
    fun observeSevius(): Flow<List<SeviusSelectionEntity>>

    @Query("SELECT * FROM sevius_selections")
    suspend fun seviusSelections(): List<SeviusSelectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSevius(selection: SeviusSelectionEntity)

    @Query("DELETE FROM sevius_selections WHERE courseId = :courseId")
    suspend fun removeSevius(courseId: String)

    @Query("SELECT * FROM app_settings")
    fun observeSettings(): Flow<List<AppSettingsEntity>>

    @Query("SELECT * FROM rules ORDER BY priority DESC, id DESC")
    fun observeRules(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY priority DESC, id DESC")
    suspend fun savedRules(): List<RuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRule(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Query("SELECT i.id AS inboxId, i.documentKey, i.revision, i.kind, i.state, d.courseName, d.relativePath, d.filename, d.size, d.availableFrom FROM inbox_items i JOIN remote_documents d ON d.`key` = i.documentKey WHERE i.state = 'PENDING'")
    suspend fun pendingDocuments(): List<InboxDocument>

    @Query("SELECT * FROM download_records ORDER BY id DESC")
    fun downloads(): Flow<List<DownloadRecordEntity>>

    @Query("SELECT * FROM remote_documents WHERE remoteAvailable = 1")
    fun availableDocuments(): Flow<List<RemoteDocumentEntity>>

    @Query("SELECT * FROM courses WHERE source = 'EV'")
    fun observeEvCourses(): Flow<List<CourseEntity>>

    @Query("""
        SELECT r.id, d.filename, COALESCE(c.folder, d.courseName) AS courseName, d.relativePath, r.createdAt
        FROM download_records r
        LEFT JOIN remote_documents d ON d.`key` = r.documentKey
        LEFT JOIN courses c ON c.remoteId = d.courseId
        WHERE r.result = 'DOWNLOADED'
        ORDER BY r.id DESC
        LIMIT 30
    """)
    fun recentDownloads(): Flow<List<RecentDownload>>

    @Query("SELECT * FROM scans ORDER BY id DESC LIMIT 1")
    fun observeLatestScan(): Flow<ScanEntity?>

    @Query("""
        SELECT r.id, COALESCE(d.filename, r.documentKey) AS filename,
               COALESCE(d.courseName, 'Documento guardado') AS courseName, r.createdAt
        FROM download_records r
        LEFT JOIN remote_documents d ON d.`key` = r.documentKey
        WHERE r.result = 'AUTO_DOWNLOADED'
        ORDER BY r.id DESC
        LIMIT 5
    """)
    fun automaticDownloads(): Flow<List<AutomaticDownload>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocuments(documents: List<RemoteDocumentEntity>)

    @Query("SELECT * FROM remote_documents WHERE `key` IN (:keys)")
    suspend fun documents(keys: List<String>): List<RemoteDocumentEntity>

    @Query("SELECT * FROM remote_documents WHERE `key` = :key")
    suspend fun document(key: String): RemoteDocumentEntity?

    @Query("UPDATE remote_documents SET remoteAvailable = 0 WHERE source = :source AND courseId IN (:courseIds) AND remoteAvailable = 1 AND lastSeen != :scanTime")
    suspend fun markMissing(source: String, courseIds: List<String>, scanTime: String): Int

    @Insert
    suspend fun startScan(scan: ScanEntity): Long

    @Query("UPDATE scans SET finishedAt = :finishedAt, outcome = :outcome, errorSummary = :error WHERE id = :scanId")
    suspend fun finishScan(scanId: Long, finishedAt: String, outcome: String, error: String? = null)

    @Insert
    suspend fun observations(items: List<DocumentObservationEntity>)

    @Query("SELECT id FROM inbox_items WHERE documentKey = :key AND revision IS :revision AND state = 'PENDING' LIMIT 1")
    suspend fun pendingInboxId(key: String, revision: String?): Long?

    @Insert
    suspend fun inbox(item: InboxItemEntity): Long

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun setting(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSetting(setting: AppSettingsEntity)

    @Query("SELECT * FROM download_records WHERE documentKey = :key ORDER BY id DESC LIMIT 1")
    suspend fun latestDownload(key: String): DownloadRecordEntity?

    @Query("DELETE FROM download_records WHERE documentKey = :key")
    suspend fun deleteDownloadRecords(key: String)

    @Query("SELECT * FROM download_records ORDER BY id DESC")
    suspend fun allDownloads(): List<DownloadRecordEntity>

    @Insert
    suspend fun recordDownload(record: DownloadRecordEntity)

    @Query("UPDATE remote_documents SET lastDownloadedHash = :hash, lastDownloadedRevision = :revision WHERE `key` = :key")
    suspend fun markDownloaded(key: String, hash: String, revision: String?)

    @Query("UPDATE remote_documents SET lastDownloadedHash = NULL, lastDownloadedRevision = NULL WHERE `key` = :key")
    suspend fun clearDownloadState(key: String)

    @Query("SELECT * FROM courses WHERE source = 'EV'")
    suspend fun evCourses(): List<CourseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCourses(courses: List<CourseEntity>)

    @Query("""
        SELECT i.id AS inboxId, i.documentKey, i.revision, i.kind, i.state,
               d.courseName, d.relativePath, d.filename, d.size, d.availableFrom
        FROM inbox_items i JOIN remote_documents d ON d.`key` = i.documentKey
        WHERE i.state IN ('PENDING', 'LATER', 'READY')
        ORDER BY d.courseName, d.relativePath, d.filename
    """)
    fun openInbox(): Flow<List<InboxDocument>>

    @Query("UPDATE inbox_items SET state = :state, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun updateInboxState(id: Long, state: String, resolvedAt: String? = null)

    @Query("UPDATE inbox_items SET state = :state, resolvedAt = :resolvedAt WHERE documentKey IN (:keys) AND state IN ('PENDING', 'LATER', 'READY')")
    suspend fun resolveInboxByDocumentKeys(keys: Collection<String>, state: String = "DOWNLOADED", resolvedAt: String? = null)

    @Transaction
    suspend fun recordEvScan(
        documents: List<EvDocument>,
        scannedIds: List<String> = documents.map { it.key.split(":").getOrElse(1) { "" } }.distinct(),
        forcePendingKeys: Set<String> = emptySet(),
        blockedExtensions: Set<String> = emptySet(),
        alreadyDownloadedKeys: Set<String> = emptySet(),
    ): ChangeSummary {
        val now = Instant.now().toString()
        val scanId = startScan(ScanEntity(startedAt = now, source = "EV"))
        val existing = documents.takeIf { it.isNotEmpty() }?.let { docs ->
            this.documents(docs.map { it.key })
        }.orEmpty().associateBy { it.key }
        val changes = documents.map { document ->
            val before = existing[document.key]
            val kind = when {
                document.key in forcePendingKeys -> ChangeKind.NEW
                document.key in alreadyDownloadedKeys -> ChangeKind.UNCHANGED
                before == null -> ChangeKind.NEW
                before.revision != document.revision || before.size != document.size ||
                    before.filename != document.filename || before.relativePath != document.path.joinToString("/") -> ChangeKind.UPDATED
                else -> ChangeKind.UNCHANGED
            }
            document to kind
        }
        upsertDocuments(documents.map { document ->
            val before = existing[document.key]
            val isRecognized = document.key in alreadyDownloadedKeys
            RemoteDocumentEntity(
                key = document.key,
                source = "EV",
                courseId = document.key.split(":").getOrElse(1) { "" },
                courseName = document.courseName,
                relativePath = document.path.joinToString("/"),
                filename = document.filename,
                revision = document.revision,
                size = document.size,
                firstSeen = before?.firstSeen ?: now,
                lastSeen = now,
                remoteAvailable = true,
                lastDownloadedHash = before?.lastDownloadedHash ?: if (isRecognized) "" else null,
                lastDownloadedRevision = before?.lastDownloadedRevision ?: if (isRecognized) document.revision else null,
                availableFrom = document.availableFrom ?: before?.availableFrom,
            )
        })
        val scannedCourseIds = scannedIds
        val removed = if (scannedCourseIds.isEmpty()) 0 else markMissing("EV", scannedCourseIds, now)
        observations(changes.map { (document, kind) ->
            DocumentObservationEntity(scanId = scanId, documentKey = document.key, classification = kind.name)
        })
        val resolvedKeys = changes.filter { (doc, kind) ->
            kind == ChangeKind.UNCHANGED && (doc.key in alreadyDownloadedKeys || existing[doc.key]?.lastDownloadedHash != null)
        }.map { it.first.key }.toSet()
        if (resolvedKeys.isNotEmpty()) {
            resolveInboxByDocumentKeys(resolvedKeys, "DOWNLOADED", now)
        }
        for ((document, kind) in changes.filter { it.second != ChangeKind.UNCHANGED }) {
            if (isBlockedExtension(document.filename, blockedExtensions)) continue
            if (pendingInboxId(document.key, document.revision) == null) {
                inbox(InboxItemEntity(
                    documentKey = document.key,
                    revision = document.revision,
                    kind = kind.name,
                    createdAt = now,
                ))
            }
        }
        finishScan(scanId, Instant.now().toString(), "SUCCESS")
        return ChangeSummary(
            new = changes.count { it.second == ChangeKind.NEW },
            updated = changes.count { it.second == ChangeKind.UPDATED },
            unchanged = changes.count { it.second == ChangeKind.UNCHANGED },
            removed = removed,
        )
    }
}

enum class ChangeKind { NEW, UPDATED, UNCHANGED, REMOVED }
data class ChangeSummary(val new: Int, val updated: Int, val unchanged: Int, val removed: Int)

@Database(
    entities = [
        CourseEntity::class,
        RemoteDocumentEntity::class,
        ScanEntity::class,
        DocumentObservationEntity::class,
        InboxItemEntity::class,
        RuleEntity::class,
        DownloadRecordEntity::class,
        SeviusSelectionEntity::class,
        AppSettingsEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `courses` (`id` TEXT NOT NULL, `source` TEXT NOT NULL, `remoteId` TEXT NOT NULL, `name` TEXT NOT NULL, `folder` TEXT, `selected` INTEGER NOT NULL, `lastSeen` TEXT NOT NULL, PRIMARY KEY(`id`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `priority` INTEGER NOT NULL, `source` TEXT, `courseId` TEXT, `pathPrefix` TEXT, `extension` TEXT, `nameContains` TEXT, `minSize` INTEGER, `maxSize` INTEGER, `changeKind` TEXT, `action` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` TEXT NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `download_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentKey` TEXT NOT NULL, `remoteRevision` TEXT, `targetUri` TEXT NOT NULL, `sha256` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `result` TEXT NOT NULL, `createdAt` TEXT NOT NULL)")
                database.execSQL("CREATE TABLE IF NOT EXISTS `sevius_selections` (`id` TEXT NOT NULL, `subjectCode` TEXT NOT NULL, `center` TEXT NOT NULL, `year` TEXT NOT NULL, `group` TEXT, `projectValue` TEXT, `programValue` TEXT, `courseId` TEXT, PRIMARY KEY(`id`))")
                database.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            }
        }

        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `remote_documents` ADD COLUMN `availableFrom` TEXT")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context, AppDatabase::class.java, "ussync.db")
                .addMigrations(migration1To2, migration2To3)
                .build()
                .also { instance = it }
        }
    }
}
