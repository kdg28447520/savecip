package app.saveclip.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.saveclip.core.browser.HomeShortcuts
import app.saveclip.core.browser.PageAddress
import app.saveclip.core.policy.YouTubeBlocklist
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["url"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val createdAt: Long,
)

@Entity(
    tableName = "history",
    indices = [Index(value = ["url"], unique = true)],
)
data class HistoryEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    val visitedAt: Long,
)

@Entity(
    tableName = "shortcuts",
    indices = [Index(value = ["url"], unique = true)],
)
data class ShortcutEntity(
    @PrimaryKey val id: String,
    val label: String,
    val url: String,
    val position: Int,
)

@Entity(tableName = "playback")
data class PlaybackEntity(
    @PrimaryKey val uri: String,
    val positionMs: Long,
    val updatedAt: Long,
)

@Entity(tableName = "block_exceptions")
data class BlockExceptionEntity(
    @PrimaryKey val host: String,
    val createdAt: Long,
)

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observe(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 100")
    fun observe(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM shortcuts ORDER BY position ASC")
    fun observe(): Flow<List<ShortcutEntity>>

    @Query("SELECT COUNT(*) FROM shortcuts")
    suspend fun count(): Int

    @Query("SELECT * FROM shortcuts WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): ShortcutEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ShortcutEntity): Long

    @Query("DELETE FROM shortcuts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback WHERE uri = :uri LIMIT 1")
    suspend fun get(uri: String): PlaybackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackEntity)
}

@Dao
interface BlockExceptionDao {
    @Query("SELECT * FROM block_exceptions ORDER BY createdAt DESC")
    fun observe(): Flow<List<BlockExceptionEntity>>

    @Query("SELECT * FROM block_exceptions WHERE host = :host LIMIT 1")
    suspend fun get(host: String): BlockExceptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: BlockExceptionEntity)

    @Query("DELETE FROM block_exceptions WHERE host = :host")
    suspend fun delete(host: String)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `delivery` TEXT NOT NULL DEFAULT 'video'")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_url` ON `bookmarks` (`url`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `history` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, `visitedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_history_url` ON `history` (`url`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `shortcuts` (`id` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_shortcuts_url` ON `shortcuts` (`url`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playback` (`uri` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`uri`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `block_exceptions` (`host` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`host`))",
        )
    }
}

class BrowserRepository(private val db: AppDatabase) {
    fun bookmarks(): Flow<List<BookmarkEntity>> = db.bookmarkDao().observe()
    fun history(): Flow<List<HistoryEntity>> = db.historyDao().observe()
    fun shortcuts(): Flow<List<ShortcutEntity>> = db.shortcutDao().observe()
    fun exceptions(): Flow<List<BlockExceptionEntity>> = db.exceptionDao().observe()

    suspend fun seedShortcutsIfEmpty() {
        HomeShortcuts.retiredSeedUrls.forEach { url ->
            val row = db.shortcutDao().byUrl(url) ?: return@forEach
            db.shortcutDao().delete(row.id)
        }
        if (db.shortcutDao().count() > 0) return
        HomeShortcuts.defaults.forEachIndexed { index, shortcut ->
            db.shortcutDao().insert(
                ShortcutEntity(
                    id = UUID.randomUUID().toString(),
                    label = shortcut.label,
                    url = shortcut.url,
                    position = index,
                ),
            )
        }
    }

    suspend fun addBookmark(title: String, rawUrl: String): Boolean {
        val url = PageAddress.openable(rawUrl) ?: return false
        if (db.bookmarkDao().byUrl(url) != null) return false
        db.bookmarkDao().insert(
            BookmarkEntity(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { url },
                url = url,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    suspend fun deleteBookmark(id: String) {
        db.bookmarkDao().delete(id)
    }

    suspend fun visit(rawUrl: String, title: String) {
        val url = PageAddress.openable(rawUrl) ?: return
        val existing = db.historyDao().byUrl(url)
        if (existing == null) {
            db.historyDao().insert(
                HistoryEntity(
                    id = UUID.randomUUID().toString(),
                    title = title.ifBlank { url },
                    url = url,
                    visitedAt = System.currentTimeMillis(),
                ),
            )
        } else {
            db.historyDao().insert(
                existing.copy(title = title.ifBlank { existing.title }, visitedAt = System.currentTimeMillis()),
            )
        }
    }

    suspend fun clearHistory() {
        db.historyDao().clear()
    }

    suspend fun addShortcut(label: String, rawUrl: String): Boolean {
        val url = HomeShortcuts.accepted(rawUrl) ?: return false
        if (db.shortcutDao().byUrl(url) != null) return false
        db.shortcutDao().insert(
            ShortcutEntity(
                id = UUID.randomUUID().toString(),
                label = label.ifBlank { url },
                url = url,
                position = db.shortcutDao().count(),
            ),
        )
        return true
    }

    suspend fun deleteShortcut(id: String) {
        db.shortcutDao().delete(id)
    }

    suspend fun toggleException(host: String): Boolean? {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return null
        if (YouTubeBlocklist.isBlocked("https://$normalized/")) return null
        val existing = db.exceptionDao().get(normalized)
        return if (existing == null) {
            db.exceptionDao().insert(BlockExceptionEntity(normalized, System.currentTimeMillis()))
            true
        } else {
            db.exceptionDao().delete(normalized)
            false
        }
    }
}
