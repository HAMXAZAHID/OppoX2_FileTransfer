package dev.hamza.watchfiles.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.hamza.watchfiles.smb.SmbPaths
import java.io.File

/** Files kept on the watch, in the app's own "Transfers" folder. */
class LocalFiles(private val context: Context) {
    val dir: File
        get() = (context.getExternalFilesDir("Transfers") ?: File(context.filesDir, "Transfers")).apply { mkdirs() }

    fun list(): List<File> =
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(PARTIAL) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun freeBytes(): Long = dir.usableSpace

    /** A file name that doesn't clash with what's already on the watch. */
    fun newFile(name: String): File {
        val clean = SmbPaths.sanitizeName(name)
        var f = File(dir, clean)
        var n = 1
        while (f.exists() || File(dir, f.name + PARTIAL).exists()) f = File(dir, SmbPaths.numbered(clean, n++))
        return f
    }

    fun partialFor(target: File) = File(target.parentFile, target.name + PARTIAL)

    fun cleanPartials() {
        dir.listFiles()?.filter { it.name.endsWith(PARTIAL) }?.forEach { it.delete() }
    }

    fun viewIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        const val PARTIAL = ".part"
    }
}
