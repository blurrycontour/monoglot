package io.blurrycontour.monoglot.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One downloaded item. The bundle is stored as its original JSON: it is
 * already exactly the shape the player needs, and shredding it across relational
 * tables would buy nothing.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val itemId: Int,
    val title: String,
    @ColumnInfo(name = "source_slug") val sourceSlug: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Int,
    @ColumnInfo(name = "bundle_json") val bundleJson: String,
    @ColumnInfo(name = "audio_path") val audioPath: String,
    @ColumnInfo(name = "audio_bytes") val audioBytes: Long,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long,
)

/** Locally recorded playback position, so resume works with no network. */
@Entity(tableName = "local_progress")
data class ProgressEntity(
    @PrimaryKey val itemId: Int,
    @ColumnInfo(name = "position_ms") val positionMs: Int,
    val completed: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** False until the position has been accepted by the server. */
    val synced: Boolean = false,
)

/**
 * Time listened on one local day, waiting to reach the server.
 *
 * Buffered here rather than posted as it accrues: playing generates a tick ten
 * times a second, and the server does not need to hear about each one. Cleared
 * once the server has it.
 */
@Entity(tableName = "listening_buffer")
data class ListeningEntity(
    @PrimaryKey val day: String,
    val ms: Long,
)

@Dao
interface ListeningDao {
    @Query("SELECT * FROM listening_buffer")
    suspend fun all(): List<ListeningEntity>

    @Query("""
        INSERT INTO listening_buffer (day, ms) VALUES (:day, :ms)
        ON CONFLICT (day) DO UPDATE SET ms = ms + :ms
    """)
    suspend fun add(day: String, ms: Long)

    /** Subtracts what was sent rather than clearing the row: time may have
     *  accrued between reading the buffer and the server accepting it. */
    @Query("UPDATE listening_buffer SET ms = ms - :ms WHERE day = :day")
    suspend fun settle(day: String, ms: Long)

    @Query("DELETE FROM listening_buffer WHERE ms <= 0")
    suspend fun dropEmpty()

    @Query("DELETE FROM listening_buffer")
    suspend fun deleteAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloaded_at DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun byId(itemId: Int): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE itemId = :itemId")
    suspend fun delete(itemId: Int)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM local_progress WHERE itemId = :itemId")
    suspend fun byId(itemId: Int): ProgressEntity?

    @Query("SELECT * FROM local_progress WHERE synced = 0")
    suspend fun unsynced(): List<ProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProgressEntity)

    @Query("UPDATE local_progress SET synced = 1 WHERE itemId = :itemId")
    suspend fun markSynced(itemId: Int)

    @Query("DELETE FROM local_progress")
    suspend fun deleteAll()
}

@Database(
    entities = [DownloadEntity::class, ProgressEntity::class, ListeningEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    abstract fun progress(): ProgressDao
    abstract fun listening(): ListeningDao
}

/**
 * Owns offline state: the Room database plus the downloaded audio files.
 */
class OfflineStore(private val context: Context) {

    private val db: AppDatabase = Room.databaseBuilder(
        context.applicationContext, AppDatabase::class.java, "monoglot.db"
    )
        // The only thing here is a cache and a send buffer: rebuilding it costs
        // a re-download, never a lost listening position — those are on the
        // server. Not worth a hand-written migration per schema change.
        .fallbackToDestructiveMigration()
        .build()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    val downloads: DownloadDao get() = db.downloads()
    val progress: ProgressDao get() = db.progress()
    val listening: ListeningDao get() = db.listening()

    fun observeDownloads(): Flow<List<DownloadEntity>> = db.downloads().observeAll()

    private fun audioDir(): File = File(context.filesDir, "audio").apply { mkdirs() }

    fun audioFile(itemId: Int): File = File(audioDir(), "$itemId.mp3")

    suspend fun isDownloaded(itemId: Int): Boolean = withContext(Dispatchers.IO) {
        val row = db.downloads().byId(itemId) ?: return@withContext false
        File(row.audioPath).exists()
    }

    suspend fun bundle(itemId: Int): Bundle? = withContext(Dispatchers.IO) {
        val row = db.downloads().byId(itemId) ?: return@withContext null
        runCatching { json.decodeFromString<Bundle>(row.bundleJson) }.getOrNull()
    }

    suspend fun save(bundle: Bundle, audio: File, bundleJson: String) = withContext(Dispatchers.IO) {
        db.downloads().upsert(
            DownloadEntity(
                itemId = bundle.item.id,
                title = bundle.item.title,
                sourceSlug = bundle.item.sourceSlug,
                durationMs = bundle.item.durationMs,
                bundleJson = bundleJson,
                audioPath = audio.absolutePath,
                audioBytes = audio.length(),
                downloadedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun remove(itemId: Int) = withContext(Dispatchers.IO) {
        db.downloads().byId(itemId)?.let { File(it.audioPath).delete() }
        db.downloads().delete(itemId)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        db.downloads().all().forEach { File(it.audioPath).delete() }
        db.downloads().deleteAll()
        audioDir().listFiles()?.forEach { it.delete() }
    }

    /**
     * Everything local, including saved positions. Used when the server
     * changes: item ids are per-server, so a downloaded episode 42 from one
     * instance would be served up as episode 42 of another.
     */
    suspend fun clearForServerChange() = withContext(Dispatchers.IO) {
        clearAll()
        db.progress().deleteAll()
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        db.downloads().all().sumOf { it.audioBytes }
    }
}
