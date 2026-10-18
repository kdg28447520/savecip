package app.saveclip.core.vault

import app.saveclip.core.library.LibraryOps
import java.io.File

object PrivateFolder {
    const val DIRECTORY = "private"

    fun directory(filesDir: File): File = File(filesDir, DIRECTORY)

    fun resolve(filesDir: File, displayName: String): File {
        val root = directory(filesDir).canonicalFile
        root.mkdirs()
        val safe = LibraryOps.sanitizeDisplayName(displayName, "mp4").replace("/", "").replace("\\", "")
        val target = File(root, safe).canonicalFile
        val rootPath = root.path + File.separator
        if (!target.path.startsWith(rootPath)) error("private path escaped")
        return target
    }

    fun isInside(filesDir: File, candidate: File): Boolean {
        val root = runCatching { directory(filesDir).canonicalFile }.getOrNull() ?: return false
        val path = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        return path.path.startsWith(root.path + File.separator)
    }
}
