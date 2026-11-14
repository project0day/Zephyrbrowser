package com.zephyr.browser.db

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// ---------------- Entities ----------------

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val folderId: Long = 0,
    val incognito: Boolean = false,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "bookmark_folders")
data class BookmarkFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "passwords")
data class PasswordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val origin: String,
    val username: String,
    val passwordEnc: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "scripts")
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val code: String,
    val enabled: Boolean = true,
    val domains: String = "*",
    val position: Int = 0
)

@Entity(tableName = "quick_sites")
data class QuickSiteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val position: Int = 0
)

@Entity(tableName = "downloads")
data class DownloadEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val mime: String,
    val path: String,
    val size: Long = 0,
    val status: Int = 0,           // 0 running/waiting 1 done 2 failed 3 paused 4 cancelled
    val addedAt: Long = System.currentTimeMillis(),
    // v1.7.8 download-manager columns: resume bookkeeping + queue priority + the
    // request headers (UA/cookies/referer) needed to re-issue a paused download.
    val totalBytes: Long = 0,
    val downloaded: Long = 0,
    val priority: Int = 0,
    val headers: String = ""       // JSON {ua, cookies, referer}
)

@Entity(tableName = "filter_lists")
data class FilterListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val ruleCount: Int = 0,
    val updatedAt: Long = 0
)

@Entity(tableName = "domain_settings")
data class DomainSettingsEntity(
    @PrimaryKey val domain: String,
    val javaScript: Boolean? = null,
    val cookies: Boolean? = null,
    val images: Boolean? = null,
    val popups: Boolean? = null,
    val desktop: Boolean? = null,
    // v1.7.5: PER-SITE page appearance override (0 system … 5 contrast),
    // null = no override → the global page-appearance pref applies.
    val appearance: Int? = null
)

@Entity(tableName = "autofill")
data class AutofillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val fields: String   // JSON {key: value}
)

@Entity(tableName = "site_permissions")
data class SitePermissionEntity(
    @PrimaryKey val origin: String,
    val location: Int? = null,   // 0 deny 1 allow
    val camera: Int? = null,
    val mic: Int? = null
)

/** Payment method (bank card / PayPal) for quick & secret form filling. */
@Entity(tableName = "payments")
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,            // "card" | "paypal"
    val label: String,           // visible label e.g. "Visa • 4242"
    val numberEnc: String,       // encrypted PAN (or PayPal email)
    val holder: String = "",
    val expMonth: Int = 0,
    val expYear: Int = 0,
    val cvvEnc: String = "",     // encrypted CVV
    val billingZip: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** v1.7.6: page saved for offline reading (WebView web-archive .mht snapshot). */
@Entity(tableName = "offline_pages")
data class OfflinePageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val path: String,          // absolute path of the saved archive in filesDir/offline
    val savedAt: Long = System.currentTimeMillis()
)

/** v1.7.7: Stylus-like user CSS theme applied to every page of a domain. */
@Entity(tableName = "site_styles")
data class SiteStyleEntity(
    @PrimaryKey val domain: String,
    val css: String,
    val enabled: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

/** v1.7.7: Reader-library article — the EXTRACTED clean article (not an .mht),
 *  rendered by the offline reader with its own typography. */
@Entity(tableName = "reader_articles")
data class ReaderArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val byline: String = "",
    val site: String = "",
    val url: String = "",
    val html: String,          // extracted clean article HTML
    val savedAt: Long = System.currentTimeMillis()
)

// ---------------- DAOs ----------------

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY position, createdAt DESC")
    fun all(): Flow<List<BookmarkEntity>>
    @Query("SELECT * FROM bookmarks")
    suspend fun allOnce(): List<BookmarkEntity>
    @Query("SELECT * FROM bookmarks WHERE incognito = 0 ORDER BY position, createdAt DESC LIMIT :limit")
    suspend fun regular(limit: Int): List<BookmarkEntity>
    @Query("SELECT * FROM bookmarks WHERE incognito = 1 ORDER BY position, createdAt DESC LIMIT :limit")
    suspend fun privateBookmarks(limit: Int): List<BookmarkEntity>
    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): BookmarkEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(b: BookmarkEntity): Long
    @Delete
    suspend fun delete(b: BookmarkEntity)
    @Query("DELETE FROM bookmarks")
    suspend fun clear()
    @Query("UPDATE bookmarks SET position = :pos WHERE id = :id")
    suspend fun updatePosition(id: Long, pos: Int)
}

@Dao
interface BookmarkFolderDao {
    @Query("SELECT * FROM bookmark_folders ORDER BY name")
    suspend fun all(): List<BookmarkFolderEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(f: BookmarkFolderEntity): Long
    @Delete
    suspend fun delete(f: BookmarkFolderEntity)
    @Query("UPDATE bookmarks SET folderId = 0 WHERE folderId = :id")
    suspend fun detachChildren(id: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 2000")
    fun all(): Flow<List<HistoryEntity>>
    @Query("SELECT DISTINCT url, title, id, visitedAt FROM history WHERE visitedAt > :since ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun recentSince(since: Long, limit: Int): List<HistoryEntity>
    @Query("SELECT * FROM history WHERE title LIKE '%' || :q || '%' OR url LIKE '%' || :q || '%' ORDER BY visitedAt DESC")
    suspend fun search(q: String): List<HistoryEntity>
    @Insert
    suspend fun insert(h: HistoryEntity)
    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)
    @Query("DELETE FROM history WHERE visitedAt >= :from AND visitedAt <= :to")
    suspend fun deleteRange(from: Long, to: Long)
    // v1.7.5: Clear Data time ranges — delete everything NEWER than a timestamp.
    @Query("DELETE FROM history WHERE visitedAt >= :since")
    suspend fun deleteSince(since: Long)
    @Query("SELECT COUNT(*) FROM history WHERE visitedAt >= :since")
    suspend fun countSince(since: Long): Int
    @Query("DELETE FROM history WHERE url LIKE '%' || :host || '%'")
    suspend fun deleteHost(host: String)
    @Query("DELETE FROM history")
    suspend fun clear()
    @Query("SELECT COUNT(*) FROM history")
    suspend fun count(): Int
}

@Dao
interface PasswordDao {
    @Query("SELECT * FROM passwords ORDER BY origin")
    fun all(): Flow<List<PasswordEntity>>
    @Query("SELECT * FROM passwords")
    suspend fun allOnce(): List<PasswordEntity>
    @Query("SELECT * FROM passwords WHERE origin = :origin LIMIT 1")
    suspend fun byOrigin(origin: String): PasswordEntity?
    @Query("SELECT * FROM passwords WHERE origin LIKE '%' || :domain || '%'")
    suspend fun byDomain(domain: String): List<PasswordEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: PasswordEntity): Long
    @Update
    suspend fun update(p: PasswordEntity)   // v1.4.2 FIX 7: password rotation updates in place
    @Delete
    suspend fun delete(p: PasswordEntity)
    @Query("DELETE FROM passwords")
    suspend fun clear()
}

@Dao
interface ScriptDao {
    @Query("SELECT * FROM scripts ORDER BY position")
    fun all(): Flow<List<ScriptEntity>>
    @Query("SELECT * FROM scripts WHERE enabled = 1 ORDER BY position")
    suspend fun enabled(): List<ScriptEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: ScriptEntity): Long
    @Delete
    suspend fun delete(s: ScriptEntity)
    @Query("SELECT * FROM scripts ORDER BY position")
    suspend fun allOnce(): List<ScriptEntity>
    @Query("SELECT * FROM scripts WHERE id = :id")
    suspend fun byId(id: Long): ScriptEntity?
    @Query("UPDATE scripts SET enabled = :en WHERE id = :id")
    suspend fun setEnabled(id: Long, en: Boolean)
}

@Dao
interface QuickSiteDao {
    @Query("SELECT * FROM quick_sites ORDER BY position")
    fun all(): Flow<List<QuickSiteEntity>>
    @Query("SELECT * FROM quick_sites ORDER BY position")
    suspend fun allOnce(): List<QuickSiteEntity>
    /** v1.7.3: duplicate guard for the "add to quick access" site-menu action. */
    @Query("SELECT * FROM quick_sites WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): QuickSiteEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(q: QuickSiteEntity): Long
    @Delete
    suspend fun delete(q: QuickSiteEntity)
    @Query("UPDATE quick_sites SET position = :pos WHERE id = :id")
    suspend fun updatePosition(id: Long, pos: Int)
    /** v1.7.3: "Clear data" → quick-access tiles category. */
    @Query("DELETE FROM quick_sites")
    suspend fun clear()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY addedAt DESC")
    fun all(): Flow<List<DownloadEntry>>
    @Query("SELECT * FROM downloads")
    suspend fun allOnce(): List<DownloadEntry>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(d: DownloadEntry): Long
    /** v1.7.3: also persists the resolved mime and the final (extension-repaired) file name. */
    @Query("UPDATE downloads SET status = :status, size = :size, path = :path, mime = :mime, fileName = :name WHERE id = :id")
    suspend fun update(id: Long, status: Int, size: Long, path: String, mime: String, name: String)
    /** v1.7.3: repairs the mime of legacy rows without touching anything else (schema unchanged). */
    @Query("UPDATE downloads SET mime = :mime WHERE id = :id")
    suspend fun updateMime(id: Long, mime: String)
    // v1.7.8 download manager: live progress + status/priority writes
    @Query("UPDATE downloads SET downloaded = :downloaded, totalBytes = :total WHERE id = :id")
    suspend fun updateProgress(id: Long, downloaded: Long, total: Long)
    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: Int)
    @Query("UPDATE downloads SET priority = :priority WHERE id = :id")
    suspend fun setPriority(id: Long, priority: Int)
    @Query("UPDATE downloads SET fileName = :name, mime = :mime WHERE id = :id")
    suspend fun updateNameMime(id: Long, name: String, mime: String)
    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: Long): DownloadEntry?
    @Delete
    suspend fun delete(d: DownloadEntry)
    @Query("DELETE FROM downloads WHERE status = 0")
    suspend fun clearRunning()
    // v1.7.5: Clear Data time ranges — journal rows newer than a timestamp.
    @Query("DELETE FROM downloads WHERE addedAt >= :since")
    suspend fun deleteSince(since: Long)
    @Query("SELECT COUNT(*) FROM downloads WHERE addedAt >= :since")
    suspend fun countSince(since: Long): Int
}

@Dao
interface FilterListDao {
    @Query("SELECT * FROM filter_lists ORDER BY name")
    fun all(): Flow<List<FilterListEntity>>
    @Query("SELECT * FROM filter_lists")
    suspend fun allOnce(): List<FilterListEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(f: FilterListEntity): Long
    @Query("UPDATE filter_lists SET enabled = :en WHERE id = :id")
    suspend fun setEnabled(id: Long, en: Boolean)
    @Query("UPDATE filter_lists SET ruleCount = :count, updatedAt = :at WHERE id = :id")
    suspend fun updateStats(id: Long, count: Int, at: Long)
    @Delete
    suspend fun delete(f: FilterListEntity)
}

@Dao
interface DomainSettingsDao {
    @Query("SELECT * FROM domain_settings ORDER BY domain")
    fun all(): Flow<List<DomainSettingsEntity>>
    @Query("SELECT * FROM domain_settings")
    suspend fun allOnce(): List<DomainSettingsEntity>
    @Query("SELECT * FROM domain_settings WHERE domain = :domain")
    suspend fun byDomain(domain: String): DomainSettingsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: DomainSettingsEntity)
    @Delete
    suspend fun delete(s: DomainSettingsEntity)
}

@Dao
interface AutofillDao {
    @Query("SELECT * FROM autofill ORDER BY label")
    fun all(): Flow<List<AutofillEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(a: AutofillEntity): Long
    @Delete
    suspend fun delete(a: AutofillEntity)
}

@Dao
interface SitePermissionDao {
    @Query("SELECT * FROM site_permissions")
    suspend fun allOnce(): List<SitePermissionEntity>
    @Query("SELECT * FROM site_permissions WHERE origin = :origin")
    suspend fun byOrigin(origin: String): SitePermissionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: SitePermissionEntity)
    @Query("DELETE FROM site_permissions WHERE origin = :origin")
    suspend fun clear(origin: String)
}

@Dao
interface SiteStyleDao {
    @Query("SELECT * FROM site_styles ORDER BY domain")
    fun all(): Flow<List<SiteStyleEntity>>
    @Query("SELECT * FROM site_styles")
    suspend fun allOnce(): List<SiteStyleEntity>
    @Query("SELECT * FROM site_styles WHERE domain = :domain")
    suspend fun byDomain(domain: String): SiteStyleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: SiteStyleEntity)
    @Query("DELETE FROM site_styles WHERE domain = :domain")
    suspend fun delete(domain: String)
}

@Dao
interface ReaderArticleDao {
    @Query("SELECT * FROM reader_articles ORDER BY savedAt DESC")
    fun all(): Flow<List<ReaderArticleEntity>>
    @Query("SELECT * FROM reader_articles")
    suspend fun allOnce(): List<ReaderArticleEntity>
    @Query("SELECT * FROM reader_articles WHERE id = :id")
    suspend fun byId(id: Long): ReaderArticleEntity?
    @Query("SELECT * FROM reader_articles WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): ReaderArticleEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(a: ReaderArticleEntity): Long
    @Delete
    suspend fun delete(a: ReaderArticleEntity)
    @Query("DELETE FROM reader_articles")
    suspend fun clear()
}

@Dao
interface OfflinePageDao {
    @Query("SELECT * FROM offline_pages ORDER BY savedAt DESC")
    fun all(): Flow<List<OfflinePageEntity>>
    @Query("SELECT * FROM offline_pages")
    suspend fun allOnce(): List<OfflinePageEntity>
    @Query("SELECT * FROM offline_pages WHERE id = :id")
    suspend fun byId(id: Long): OfflinePageEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: OfflinePageEntity): Long
    @Delete
    suspend fun delete(p: OfflinePageEntity)
    @Query("DELETE FROM offline_pages")
    suspend fun clear()
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments ORDER BY createdAt DESC")
    fun all(): Flow<List<PaymentEntity>>
    @Query("SELECT * FROM payments")
    suspend fun allOnce(): List<PaymentEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: PaymentEntity): Long
    @Delete
    suspend fun delete(p: PaymentEntity)
    @Query("DELETE FROM payments")
    suspend fun clear()
}

// ---------------- v1.7.9: Trash (30-day soft delete) ----------------

/** A file/folder moved to the Zephyr trash (Files app). The payload lives in
 *  getExternalFilesDir("trash")/<id> — SAME volume as external storage, so a
 *  rename is instant; auto-purged after 30 days. */
@Entity(tableName = "trash")
data class TrashEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalPath: String,      // absolute path the item came from
    val fileName: String,
    val storedPath: String,        // absolute path inside the trash dir
    val isDir: Boolean = false,
    val size: Long = 0,
    val deletedAt: Long = System.currentTimeMillis()
)

@Dao
interface TrashDao {
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun all(): Flow<List<TrashEntry>>
    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    suspend fun allOnce(): List<TrashEntry>
    @Query("SELECT * FROM trash WHERE id = :id")
    suspend fun byId(id: Long): TrashEntry?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(t: TrashEntry): Long
    @Delete
    suspend fun delete(t: TrashEntry)
    @Query("DELETE FROM trash WHERE deletedAt < :ts")
    suspend fun purgeBefore(ts: Long)
    @Query("SELECT COUNT(*) FROM trash")
    suspend fun count(): Int
    @Query("DELETE FROM trash")
    suspend fun clear()
}

// ---------------- v1.7.9: per-site data (cache manager) ----------------

/** Per-site footprint snapshot: localStorage/IndexedDB bytes (WebStorage),
 *  cookie count, intercepted-request counter + last visit — powers the
 *  «Данные сайтов» manager with selective cleanup. */
@Entity(tableName = "site_data")
data class SiteDataEntity(
    @PrimaryKey val domain: String,
    val storageBytes: Long = 0,
    val cookies: Int = 0,
    val requests: Long = 0,
    val lastUsed: Long = System.currentTimeMillis()
)

@Dao
interface SiteDataDao {
    @Query("SELECT * FROM site_data ORDER BY storageBytes DESC, requests DESC")
    fun all(): Flow<List<SiteDataEntity>>
    @Query("SELECT * FROM site_data ORDER BY storageBytes DESC, requests DESC")
    suspend fun allOnce(): List<SiteDataEntity>
    /**
     * v1.7.69 (user: «Данные сайтов всё ещё открывается трудно — может, потому
     * что там тысячи строк»). Exactly that: the table grows by one row per
     * domain ever visited, and every query above reads all of them. The screen
     * only ever shows the heaviest few, so it asks for the heaviest few.
     */
    @Query("SELECT * FROM site_data ORDER BY storageBytes DESC, requests DESC LIMIT :limit")
    suspend fun top(limit: Int): List<SiteDataEntity>
    @Query("SELECT COUNT(*) FROM site_data")
    suspend fun count(): Int
    @Query("SELECT SUM(storageBytes) FROM site_data")
    suspend fun totalBytes(): Long?
    @Query("SELECT SUM(requests) FROM site_data")
    suspend fun totalRequests(): Long?
    @Query("SELECT * FROM site_data WHERE domain = :domain")
    suspend fun byDomain(domain: String): SiteDataEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SiteDataEntity)
    @Query("UPDATE site_data SET requests = requests + 1, lastUsed = :now WHERE domain = :domain")
    suspend fun bumpRequest(domain: String, now: Long)
    @Query("DELETE FROM site_data WHERE domain = :domain")
    suspend fun deleteDomain(domain: String)
    @Query("DELETE FROM site_data")
    suspend fun clear()
}

// ---------------- v1.7.54: tasks & reminders (Дела) ----------------

/**
 * v1.7.54 (user: «внутреннее напоминание дел, рандеву, чтобы бот мог создавать
 * и работал бы будильник — уведомление в определённое время»).
 *
 * One row = one thing to do at a time. [dueAt] is absolute epoch-ms, so a
 * reminder survives a timezone change as the instant it was set for.
 *
 * [repeat] re-arms the alarm after it fires instead of storing a rule the UI
 * would have to re-evaluate: 0 once, 1 daily, 2 weekdays (Mon–Fri), 3 weekly,
 * 4 monthly. [alarm] picks how loud it is — a full-screen alarm-style
 * notification versus an ordinary one.
 */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    /** When it fires. Absolute epoch millis. */
    val dueAt: Long,
    /** 0 once · 1 daily · 2 weekdays · 3 weekly · 4 monthly */
    val repeat: Int = 0,
    val done: Boolean = false,
    /** true → alarm-style (heads-up + vibration), false → quiet notification. */
    val alarm: Boolean = true,
    /**
     * v1.7.57: the page this is about, if any. Tapping the reminder opens it
     * instead of the task list — «напомни про эту страницу в четверг» is the
     * common case, and a reminder that cannot take you back to what it is about
     * makes you search for it again.
     */
    val url: String = "",
    /** Where it came from: "" user, "ai" created by the assistant. */
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY dueAt ASC")
    fun all(): Flow<List<ReminderEntity>>
    @Query("SELECT * FROM reminders ORDER BY dueAt ASC")
    suspend fun allOnce(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): ReminderEntity?
    /** Everything still due — what the boot receiver re-arms. */
    @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY dueAt ASC")
    suspend fun pending(): List<ReminderEntity>
    @Query("SELECT * FROM reminders WHERE dueAt BETWEEN :from AND :to ORDER BY dueAt ASC")
    suspend fun between(from: Long, to: Long): List<ReminderEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: ReminderEntity): Long
    @Update
    suspend fun update(r: ReminderEntity)
    @Delete
    suspend fun delete(r: ReminderEntity)
    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)
    @Query("SELECT COUNT(*) FROM reminders WHERE done = 0")
    suspend fun pendingCount(): Int
    @Query("DELETE FROM reminders")
    suspend fun clear()
}

// ---------------- Database ----------------

@Database(
    entities = [
        BookmarkEntity::class, BookmarkFolderEntity::class, HistoryEntity::class,
        PasswordEntity::class, ScriptEntity::class, QuickSiteEntity::class,
        DownloadEntry::class, FilterListEntity::class, DomainSettingsEntity::class,
        AutofillEntity::class, SitePermissionEntity::class, PaymentEntity::class,
        OfflinePageEntity::class, SiteStyleEntity::class, ReaderArticleEntity::class,
        TrashEntry::class, SiteDataEntity::class, ReminderEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun bookmarkFolderDao(): BookmarkFolderDao
    abstract fun historyDao(): HistoryDao
    abstract fun passwordDao(): PasswordDao
    abstract fun scriptDao(): ScriptDao
    abstract fun quickSiteDao(): QuickSiteDao
    abstract fun downloadDao(): DownloadDao
    abstract fun filterListDao(): FilterListDao
    abstract fun domainSettingsDao(): DomainSettingsDao
    abstract fun autofillDao(): AutofillDao
    abstract fun sitePermissionDao(): SitePermissionDao
    abstract fun paymentDao(): PaymentDao
    abstract fun offlinePageDao(): OfflinePageDao
    abstract fun siteStyleDao(): SiteStyleDao
    abstract fun readerArticleDao(): ReaderArticleDao
    abstract fun trashDao(): TrashDao
    abstract fun siteDataDao(): SiteDataDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        /** v1 → v2: payments table. MUST be additive — destructive migration would wipe
         *  user bookmarks/history/passwords on update. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `payments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `label` TEXT NOT NULL, `numberEnc` TEXT NOT NULL, `holder` TEXT NOT NULL, `expMonth` INTEGER NOT NULL, `expYear` INTEGER NOT NULL, `cvvEnc` TEXT NOT NULL, `billingZip` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }
        /** v2 → v3 (v1.7.5): per-site page appearance column. Additive. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `domain_settings` ADD COLUMN `appearance` INTEGER")
            }
        }
        /** v3 → v4 (v1.7.6): offline reading pages. Additive. */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `offline_pages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `url` TEXT NOT NULL, `path` TEXT NOT NULL, `savedAt` INTEGER NOT NULL)")
            }
        }
        /** v4 → v5 (v1.7.7): Stylus-like site styles + reader library. Additive. */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `site_styles` (`domain` TEXT NOT NULL PRIMARY KEY, `css` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `reader_articles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `byline` TEXT NOT NULL, `site` TEXT NOT NULL, `url` TEXT NOT NULL, `html` TEXT NOT NULL, `savedAt` INTEGER NOT NULL)")
            }
        }
        /** v5 → v6 (v1.7.8): download-manager bookkeeping. Additive. */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `totalBytes` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `downloaded` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `downloads` ADD COLUMN `headers` TEXT NOT NULL DEFAULT ''")
            }
        }
        /** v6 → v7 (v1.7.9): trash (30-day soft delete) + per-site data. Additive. */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `trash` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `originalPath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `storedPath` TEXT NOT NULL, `isDir` INTEGER NOT NULL, `size` INTEGER NOT NULL, `deletedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `site_data` (`domain` TEXT NOT NULL PRIMARY KEY, `storageBytes` INTEGER NOT NULL, `cookies` INTEGER NOT NULL, `requests` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL)")
            }
        }
        /** v7 → v8 (v1.7.54): tasks & reminders. Additive. */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `reminders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `notes` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `repeat` INTEGER NOT NULL, `done` INTEGER NOT NULL, `alarm` INTEGER NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }
        /** v8 → v9 (v1.7.57): a page attached to a reminder. Additive. */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `reminders` ADD COLUMN `url` TEXT NOT NULL DEFAULT ''")
            }
        }
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            // allowMainThreadQueries: v1.2.1 parity — all DBs are tiny local SQLite tables
            // (bookmarks/history/passwords), sync UI reads are sub-millisecond. Losing this flag
            // in the v1.3.0 rewrite caused IllegalStateException on every sync DAO call:
            // wrapped calls failed silently (empty bookmarks/home/suggestions) and unwrapped
            // ones crashed panels & settings screens.
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "zephyr.db")
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}

val Context.db: AppDb get() = AppDb.get(this)
