package com.wisperlow.mobile.stt

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DownloadState {
    data object NotStarted : DownloadState
    data class Downloading(val progressPct: Int) : DownloadState
    data object Extracting : DownloadState
    data class Completed(val modelDir: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

@Singleton
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val modelsRoot: File
        get() = File(context.filesDir, "models")

    private val downloadsDir: File?
        get() = context.getExternalFilesDir(null)?.let { File(it, "downloads") }

    fun installedModels(): List<File> {
        val root = modelsRoot
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory && File(f, MARKER_FILE).isFile }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun installedDirFor(modelId: String): File? {
        val model = ModelCatalog.byId(modelId) ?: return null
        val dir = File(modelsRoot, model.dirName)
        return if (File(dir, MARKER_FILE).isFile) dir else null
    }

    fun enqueue(model: SttModel): Result<Long> = runCatching {
        val destDir = downloadsDir ?: throw IOException("External storage unavailable")
        if (!destDir.isDirectory && !destDir.mkdirs()) {
            throw IOException("Cannot create download directory")
        }
        val request = DownloadManager.Request(Uri.parse(model.downloadUrl)).apply {
            setTitle(model.archiveName)
            setDescription(model.displayName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE,
            )
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setDestinationUri(Uri.fromFile(File(destDir, model.archiveName)))
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }

    suspend fun awaitAndExtract(downloadId: Long, model: SttModel): File =
        withContext(Dispatchers.IO) {
            val archive = awaitDownload(downloadId)
            try {
                extract(archive, model)
                val dir = File(modelsRoot, model.dirName)
                File(dir, MARKER_FILE).writeText(model.id)
                dir
            } finally {
                if (!archive.delete()) {
                    Log.w(TAG, "Failed to delete archive ${archive.name}")
                }
            }
        }

    fun deleteModel(modelId: String) {
        val model = ModelCatalog.byId(modelId) ?: return
        File(modelsRoot, model.dirName).deleteRecursively()
    }

    private suspend fun awaitDownload(downloadId: Long): File {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            pollOnce(dm, downloadId)?.let { file -> return file }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun pollOnce(dm: DownloadManager, downloadId: Long): File? {
        dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw IOException("Download $downloadId not found")
            }
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> return localFile(dm, downloadId)
                DownloadManager.STATUS_FAILED -> {
                    val reason =
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    throw IOException("Download failed: reason=$reason")
                }
            }
        }
        return null
    }

    private fun localFile(dm: DownloadManager, downloadId: Long): File {
        dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val uri = if (idx >= 0) cursor.getString(idx) else null
                if (uri != null) {
                    val path = Uri.parse(uri).path
                    if (path != null && File(path).isFile) {
                        return File(path)
                    }
                }
            }
        }
        throw IOException("Could not locate downloaded file for $downloadId")
    }

    private fun extract(archive: File, model: SttModel) {
        val dest = File(modelsRoot, model.dirName)
        if (!modelsRoot.isDirectory && !modelsRoot.mkdirs()) {
            throw IOException("Cannot create models directory")
        }
        if (dest.exists() && !dest.deleteRecursively()) {
            throw IOException("Cannot clear existing model directory")
        }
        if (!dest.mkdirs()) {
            throw IOException("Cannot create model directory ${dest.name}")
        }
        val destCanonical = dest.canonicalPath + File.separator
        val stripPrefix = detectRootFolder(archive)

        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val relative = entry.name.removePrefix(stripPrefix)
                    if (relative.isEmpty()) continue
                    writeEntry(entry, relative, tar, dest, destCanonical)
                }
            }
        }
        if (dest.listFiles().isNullOrEmpty()) {
            throw IOException("Extraction produced no files for ${model.dirName}")
        }
    }

    private fun detectRootFolder(archive: File): String {
        val roots = mutableSetOf<String>()
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (!entry.name.isBlank()) {
                        roots.add(entry.name.trimStart('/').substringBefore('/'))
                        if (roots.size > 1) return ""
                    }
                }
            }
        }
        return if (roots.size == 1) "${roots.single()}/" else ""
    }

    private fun writeEntry(
        entry: TarArchiveEntry,
        relativeName: String,
        tar: TarArchiveInputStream,
        dest: File,
        destCanonical: String,
    ) {
        if (relativeName.contains("..")) {
            Log.w(TAG, "Skipping suspicious tar entry: ${entry.name}")
            return
        }
        val outFile = File(dest, relativeName)
        if (!outFile.canonicalPath.startsWith(destCanonical)) {
            throw IOException("Zip-slip detected in entry ${entry.name}")
        }
        if (entry.isSymbolicLink || entry.isLink) {
            Log.w(TAG, "Skipping link entry: ${entry.name}")
            return
        }
        if (entry.isDirectory) {
            if (!outFile.isDirectory && !outFile.mkdirs()) {
                throw IOException("Cannot create directory ${outFile.name}")
            }
            return
        }
        outFile.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Cannot create directory ${parent.name}")
            }
        }
        FileOutputStream(outFile).use { out ->
            tar.copyTo(out, BUFFER_SIZE)
        }
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val MARKER_FILE = ".installed"
        private const val POLL_INTERVAL_MS = 500L
        private const val BUFFER_SIZE = 64 * 1024
    }
}
