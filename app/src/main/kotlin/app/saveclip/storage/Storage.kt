package app.saveclip.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.saveclip.data.DownloadDao
import app.saveclip.data.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LibraryItem(
    val uri: Uri,
    val title: String,
    val sizeBytes: Long,
    val addedAt: Long,
)

object FileNames {
    fun displayName(title: String, extension: String): String {
        val cleaned = title
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
            .take(60)
            .ifBlank { "영상" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "$cleaned-$stamp.$extension"
    }
}

object MediaStoreSaver {
    const val RELATIVE_PATH = "Movies/SaveClip"

    fun save(context: Context, source: File, displayName: String, mime: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePending(context, source, displayName, mime)
        } else {
            saveLegacy(context, source, displayName)
        }
    }

    private fun savePending(context: Context, source: File, displayName: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)
            ?: error("영상을 보관함에 넣지 못했습니다")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("영상을 보관함에 넣지 못했습니다")
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, done, null, null)
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
        return uri
    }

    private fun saveLegacy(context: Context, source: File, displayName: String): Uri {
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val folder = File(movies, "SaveClip")
        if (!folder.exists()) folder.mkdirs()
        val dest = File(folder, displayName)
        source.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
        return Uri.fromFile(dest)
    }
}

class LibraryRepository(
    private val context: Context,
    private val dao: DownloadDao,
) {
    suspend fun list(): List<LibraryItem> = withContext(Dispatchers.IO) {
        val merged = linkedMapOf<String, LibraryItem>()
        queryStore().forEach { merged[it.uri.toString()] = it }
        dao.completed().forEach { entity ->
            val uri = entity.outputUri ?: return@forEach
            if (merged.containsKey(uri)) return@forEach
            merged[uri] = LibraryItem(
                uri = Uri.parse(uri),
                title = entity.title,
                sizeBytes = entity.bytesDone,
                addedAt = entity.createdAt,
            )
        }
        merged.values.sortedByDescending { it.addedAt }
    }

    suspend fun delete(item: LibraryItem, entity: DownloadEntity?) {
        withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.delete(item.uri, null, null) }
            if (item.uri.scheme == "file") {
                item.uri.path?.let { File(it).delete() }
            }
            entity?.let { dao.delete(it.id) }
        }
    }

    private fun queryStore(): List<LibraryItem> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) queryModern() else queryLegacy()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun queryModern(): List<LibraryItem> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val items = mutableListOf<LibraryItem>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("Movies/SaveClip%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items += LibraryItem(
                    uri = ContentUris.withAppendedId(collection, id),
                    title = cursor.getString(nameCol) ?: "영상",
                    sizeBytes = cursor.getLong(sizeCol),
                    addedAt = cursor.getLong(dateCol) * 1000L,
                )
            }
        }
        return items
    }

    private fun queryLegacy(): List<LibraryItem> {
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "SaveClip",
        )
        return folder.listFiles().orEmpty()
            .filter { it.isFile }
            .map {
                LibraryItem(
                    uri = Uri.fromFile(it),
                    title = it.name,
                    sizeBytes = it.length(),
                    addedAt = it.lastModified(),
                )
            }
    }
}
