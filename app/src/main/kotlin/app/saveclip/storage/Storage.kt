package app.saveclip.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.saveclip.core.audio.AudioSave
import app.saveclip.core.library.LibraryOps
import app.saveclip.core.vault.PrivateFolder
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
    val pageUrl: String? = null,
    val downloadId: String? = null,
    val mime: String = "video/mp4",
    val appPrivate: Boolean = false,
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

object ImageStore {
    const val RELATIVE_PATH = "Pictures/SaveClip"

    fun save(context: Context, source: File, displayName: String, mime: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveCollection(
                context,
                source,
                displayName,
                mime,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                RELATIVE_PATH,
            )
        } else {
            savePublicFile(context, source, displayName, Environment.DIRECTORY_PICTURES, mime)
        }
    }

    fun mimeFor(url: String): String {
        val extension = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (extension) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
    }

    fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
}

object AudioStore {
    fun save(context: Context, source: File, displayName: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveCollection(
                context,
                source,
                displayName,
                AudioSave.MIME,
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                AudioSave.RELATIVE_PATH,
            )
        } else {
            savePublicFile(context, source, displayName, Environment.DIRECTORY_MUSIC, AudioSave.MIME)
        }
    }
}

private fun saveCollection(
    context: Context,
    source: File,
    displayName: String,
    mime: String,
    collection: Uri,
    relativePath: String,
): Uri {
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = context.contentResolver.insert(collection, values)
        ?: error("파일을 보관함에 넣지 못했습니다")
    try {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("파일을 보관함에 넣지 못했습니다")
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
    } catch (error: Exception) {
        context.contentResolver.delete(uri, null, null)
        throw error
    }
    return uri
}

private fun savePublicFile(
    context: Context,
    source: File,
    displayName: String,
    directory: String,
    mime: String,
): Uri {
    val root = Environment.getExternalStoragePublicDirectory(directory)
    val folder = File(root, "SaveClip")
    if (!folder.exists()) folder.mkdirs()
    val dest = File(folder, displayName)
    source.copyTo(dest, overwrite = true)
    MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
    return Uri.fromFile(dest)
}

class LibraryRepository(
    private val context: Context,
    private val dao: DownloadDao,
) {
    suspend fun list(includePrivate: Boolean = false): List<LibraryItem> = withContext(Dispatchers.IO) {
        val completed = dao.completed()
        val byUri = completed.mapNotNull { entity ->
            entity.outputUri?.let { it to entity }
        }.toMap()
        val merged = linkedMapOf<String, LibraryItem>()
        queryStore().forEach { item ->
            val match = byUri[item.uri.toString()]
            merged[item.uri.toString()] = if (match == null) {
                item
            } else {
                item.copy(pageUrl = match.pageUrl, downloadId = match.id, title = item.title.ifBlank { match.title })
            }
        }
        completed.forEach { entity ->
            val uri = entity.outputUri ?: return@forEach
            if (merged.containsKey(uri)) return@forEach
            if (uri.startsWith("file:") && uri.contains("/private/")) return@forEach
            merged[uri] = LibraryItem(
                uri = Uri.parse(uri),
                title = entity.title,
                sizeBytes = entity.bytesDone,
                addedAt = entity.createdAt,
                pageUrl = entity.pageUrl,
                downloadId = entity.id,
            )
        }
        if (includePrivate) {
            listPrivate().forEach { merged[it.uri.toString()] = it }
        }
        merged.values.sortedByDescending { it.addedAt }
    }

    suspend fun listPrivate(): List<LibraryItem> = withContext(Dispatchers.IO) {
        val root = PrivateFolder.directory(context.filesDir)
        if (!root.isDirectory) return@withContext emptyList()
        root.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".meta") }
            .map { file ->
                val page = runCatching { File(file.parentFile, file.name + ".meta").readText() }.getOrNull()
                LibraryItem(
                    uri = Uri.fromFile(file),
                    title = file.name,
                    sizeBytes = file.length(),
                    addedAt = file.lastModified(),
                    pageUrl = page?.takeIf { it.startsWith("http") },
                    mime = if (file.extension.equals("m4a", true)) AudioSave.MIME else "video/mp4",
                    appPrivate = true,
                )
            }
    }

    suspend fun delete(item: LibraryItem, entity: DownloadEntity?) {
        withContext(Dispatchers.IO) {
            deleteFiles(item)
            entity?.let { dao.delete(it.id) }
            item.downloadId?.let { dao.delete(it) }
        }
    }

    suspend fun deleteDirect(items: List<LibraryItem>) {
        withContext(Dispatchers.IO) {
            items.forEach { item ->
                deleteFiles(item)
                item.downloadId?.let { dao.delete(it) }
            }
        }
    }

    suspend fun forgetRows(items: List<LibraryItem>) {
        withContext(Dispatchers.IO) {
            items.forEach { item -> item.downloadId?.let { dao.delete(it) } }
        }
    }

    suspend fun rename(item: LibraryItem, raw: String): Boolean = withContext(Dispatchers.IO) {
        val extension = item.title.substringAfterLast('.', "mp4")
        val name = LibraryOps.sanitizeDisplayName(raw, extension)
        if (item.appPrivate) {
            val source = item.uri.path?.let { File(it) } ?: return@withContext false
            if (!PrivateFolder.isInside(context.filesDir, source)) return@withContext false
            val dest = PrivateFolder.resolve(context.filesDir, name)
            val meta = File(source.parentFile, source.name + ".meta")
            val moved = source.renameTo(dest)
            if (moved && meta.isFile) meta.renameTo(File(dest.parentFile, dest.name + ".meta"))
            moved
        } else {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
            context.contentResolver.update(item.uri, values, null, null) > 0
        }
    }

    suspend fun moveToPrivate(item: LibraryItem): File? = withContext(Dispatchers.IO) {
        if (item.appPrivate) return@withContext null
        val dest = PrivateFolder.resolve(context.filesDir, item.title)
        val copied = copyTo(item, dest)
        if (!copied) return@withContext null
        item.pageUrl?.let { page ->
            File(dest.parentFile, dest.name + ".meta").writeText(page)
        }
        dest
    }

    private fun deleteFiles(item: LibraryItem) {
        if (item.appPrivate) {
            item.uri.path?.let { path ->
                val file = File(path)
                if (PrivateFolder.isInside(context.filesDir, file)) {
                    file.delete()
                    File(file.parentFile, file.name + ".meta").delete()
                }
            }
            return
        }
        runCatching { context.contentResolver.delete(item.uri, null, null) }
        if (item.uri.scheme == "file") item.uri.path?.let { File(it).delete() }
    }

    private fun copyTo(item: LibraryItem, dest: File): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            val input = when {
                item.uri.scheme == "file" -> item.uri.path?.let { File(it).inputStream() }
                else -> context.contentResolver.openInputStream(item.uri)
            } ?: return false
            input.use { source -> dest.outputStream().use { source.copyTo(it) } }
            true
        } catch (_: Exception) {
            dest.delete()
            false
        }
    }

    private fun queryStore(): List<LibraryItem> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                queryModern(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Movies/SaveClip%", "video/mp4") +
                    queryModern(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Pictures/SaveClip%", "image/jpeg") +
                    queryModern(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Music/SaveClip%", AudioSave.MIME)
            } else {
                queryLegacy()
            }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun queryModern(collection: Uri, relativeLike: String, mime: String): List<LibraryItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val items = mutableListOf<LibraryItem>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(relativeLike),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items += LibraryItem(
                    uri = ContentUris.withAppendedId(collection, id),
                    title = cursor.getString(nameCol) ?: "영상",
                    sizeBytes = cursor.getLong(sizeCol),
                    addedAt = cursor.getLong(dateCol) * 1000L,
                    mime = cursor.getString(mimeCol) ?: mime,
                )
            }
        }
        return items
    }

    private fun queryLegacy(): List<LibraryItem> {
        val folders = listOf(
            Environment.DIRECTORY_MOVIES to "video/mp4",
            Environment.DIRECTORY_PICTURES to "image/jpeg",
            Environment.DIRECTORY_MUSIC to AudioSave.MIME,
        )
        return folders.flatMap { (directory, mime) ->
            val folder = File(Environment.getExternalStoragePublicDirectory(directory), "SaveClip")
            folder.listFiles().orEmpty()
                .filter { it.isFile }
                .map {
                    LibraryItem(
                        uri = Uri.fromFile(it),
                        title = it.name,
                        sizeBytes = it.length(),
                        addedAt = it.lastModified(),
                        mime = mime,
                    )
                }
        }
    }
}
