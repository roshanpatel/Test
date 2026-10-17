package app.jolt

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * The user's own inspiration clips: GIFs, photos and short videos copied into the app's
 * private storage, so they keep working if the originals are moved or deleted.
 */
class Clips(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "clips").apply { mkdirs() }

    fun list(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    fun random(): File? = list().randomOrNull()

    /** Copies [uri] in. Returns false if it's not an image or video, too large, or unreadable. */
    fun add(uri: Uri): Boolean {
        val resolver = app.contentResolver
        val type = resolver.getType(uri).orEmpty()
        val ext = when {
            type == "image/gif" -> GIF
            type.startsWith("image/") -> IMAGE
            type.startsWith("video/") -> VIDEO
            else -> return false
        }
        val target = File(dir, "${System.currentTimeMillis()}-${(0..9999).random()}.$ext")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw TooLarge()
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return false
            true
        } catch (e: Exception) {
            target.delete()
            false
        }
    }

    fun remove(file: File) {
        if (file.parentFile == dir) file.delete()
    }

    private class TooLarge : Exception()

    companion object {
        const val GIF = "gif"
        const val IMAGE = "img"
        const val VIDEO = "vid"
        const val MAX_BYTES = 50L * 1024 * 1024

        fun isVideo(file: File) = file.extension == VIDEO
    }
}
