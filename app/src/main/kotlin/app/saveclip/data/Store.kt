package app.saveclip.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.saveclip.core.download.Delivery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

object DownloadStatuses {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val mediaUrl: String,
    val pageUrl: String,
    val title: String,
    val kind: String,
    val qualityLabel: String,
    val variantUrl: String?,
    val videoRepresentationId: String?,
    val audioRepresentationId: String?,
    val status: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val segmentDone: Int,
    val segmentTotal: Int,
    val errorMessage: String?,
    val outputUri: String?,
    val headersJson: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "'video'")
    val delivery: String = Delivery.VIDEO,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun byStatus(status: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY createdAt DESC")
    suspend fun completed(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE downloads SET status = 'PAUSED' WHERE id = :id AND status IN ('QUEUED','RUNNING')")
    suspend fun markPaused(id: String)

    @Query("UPDATE downloads SET status = 'QUEUED', errorMessage = NULL WHERE id = :id")
    suspend fun markQueued(id: String)

    @Query(
        "UPDATE downloads SET status = 'RUNNING', errorMessage = NULL " +
            "WHERE id = :id AND status IN ('QUEUED','FAILED','RUNNING')",
    )
    suspend fun markRunning(id: String): Int

    @Query(
        "UPDATE downloads SET bytesDone = :done, bytesTotal = :total, segmentDone = :segmentDone, " +
            "segmentTotal = :segmentTotal WHERE id = :id AND status = 'RUNNING'",
    )
    suspend fun updateProgress(id: String, done: Long, total: Long, segmentDone: Int, segmentTotal: Int)

    @Query(
        "UPDATE downloads SET status = 'COMPLETED', outputUri = :uri, bytesDone = :done, errorMessage = NULL " +
            "WHERE id = :id AND status = 'RUNNING'",
    )
    suspend fun markCompleted(id: String, uri: String, done: Long): Int

    @Query(
        "UPDATE downloads SET status = 'FAILED', errorMessage = :message " +
            "WHERE id = :id AND status = 'RUNNING'",
    )
    suspend fun markFailed(id: String, message: String): Int
}

@Database(
    entities = [
        DownloadEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        ShortcutEntity::class,
        PlaybackEntity::class,
        BlockExceptionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun historyDao(): HistoryDao
    abstract fun shortcutDao(): ShortcutDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun exceptionDao(): BlockExceptionDao

    companion object {
        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, "saveclip.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

data class AppSettings(
    val wifiOnly: Boolean = false,
    val parallelism: Int = 4,
    val suggestCopiedLink: Boolean = false,
    val desktopSite: Boolean = false,
    val blockAds: Boolean = false,
    val hintSeen: Boolean = false,
    val policyFetchedAt: Long = 0L,
)

class SettingsStore(context: Context) {
    private val store = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("settings") },
    )

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            wifiOnly = prefs[WIFI] ?: false,
            parallelism = (prefs[PARALLEL] ?: 4).coerceIn(1, 6),
            suggestCopiedLink = prefs[CLIPBOARD] ?: false,
            desktopSite = prefs[DESKTOP] ?: false,
            blockAds = prefs[ADS] ?: false,
            hintSeen = prefs[HINT] ?: false,
            policyFetchedAt = prefs[POLICY_AT] ?: 0L,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setWifiOnly(value: Boolean) {
        store.edit { it[WIFI] = value }
    }

    suspend fun setParallelism(value: Int) {
        store.edit { it[PARALLEL] = value.coerceIn(1, 6) }
    }

    suspend fun setSuggestCopiedLink(value: Boolean) {
        store.edit { it[CLIPBOARD] = value }
    }

    suspend fun setDesktopSite(value: Boolean) {
        store.edit { it[DESKTOP] = value }
    }

    suspend fun setBlockAds(value: Boolean) {
        store.edit { it[ADS] = value }
    }

    suspend fun setHintSeen(value: Boolean) {
        store.edit { it[HINT] = value }
    }

    suspend fun setPolicyFetchedAt(value: Long) {
        store.edit { it[POLICY_AT] = value }
    }

    private companion object {
        val WIFI = booleanPreferencesKey("wifi_only")
        val PARALLEL = intPreferencesKey("parallelism")
        val CLIPBOARD = booleanPreferencesKey("suggest_copied_link")
        val DESKTOP = booleanPreferencesKey("desktop_site")
        val ADS = booleanPreferencesKey("block_ads")
        val HINT = booleanPreferencesKey("hint_seen")
        val POLICY_AT = longPreferencesKey("policy_fetched_at")
    }
}
