package com.wisperlow.mobile.stt

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DownloadState {
    data object NotStarted : DownloadState
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        /** DownloadManager is holding the transfer until Wi-Fi (or any network) returns. */
        val waitingForNetwork: Boolean = false,
    ) : DownloadState {
        val progressPct: Int
            get() = if (totalBytes > 0L) {
                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
    }
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

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<File>>()
    // Keep only one archive verification/extraction in memory at a time.
    private val modelInstallMutex = Mutex()
    private val _states = MutableStateFlow<Map<String, DownloadState>>(
        ModelCatalog.all.associate { it.id to DownloadState.NotStarted },
    )

    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    /** Reconcile disk and DownloadManager state after process/activity recreation. */
    fun refresh() {
        ModelCatalog.all.forEach { model ->
            val installedDir = installedDirFor(model.id)
            if (installedDir != null) {
                clearPersistedDownload(model.id)
                setState(model.id, DownloadState.Completed(installedDir))
                return@forEach
            }

            val downloadId = persistedDownloadId(model.id)
            val snapshot = downloadId?.let(::querySnapshot)
            if (snapshot != null &&
                (snapshot.status == DownloadManager.STATUS_SUCCESSFUL || snapshot.isActive)
            ) {
                // A live operation may already be extracting. Preserve its
                // Extracting state when refresh is called from the UI.
                if (inFlight[model.id] == null) {
                    setState(model.id, snapshot.toState())
                    ensureOperation(model, allowMobileData = false)
                }
            } else {
                clearPersistedDownload(model.id)
                setState(model.id, DownloadState.NotStarted)
            }
        }
    }

    fun installedModels(): List<File> {
        return ModelCatalog.all.mapNotNull { installedDirFor(it.id) }.sortedBy { it.name }
    }

    fun installedDirFor(modelId: String): File? {
        val model = ModelCatalog.byId(modelId) ?: return null
        val dir = File(modelsRoot, model.dirName)
        return dir.takeIf { candidate ->
            isValidModelDirectory(candidate) &&
                runCatching { File(candidate, MARKER_FILE).readText().trim() == model.id }
                    .getOrDefault(false)
        }
    }

    /**
     * Starts or attaches to the one operation for this model. Without
     * [allowMobileData] the transfer waits for an unmetered network.
     */
    suspend fun download(model: SttModel, allowMobileData: Boolean = false): File {
        installedDirFor(model.id)?.let { installed ->
            setState(model.id, DownloadState.Completed(installed))
            return installed
        }
        if (allowMobileData && persistedMobileData(model.id) == false) {
            // A queued Wi-Fi-only request cannot be changed; replace it.
            cancelDownload(model.id)
        }
        return ensureOperation(model, allowMobileData).await()
    }

    /** Stops an in-progress download and discards its partial archive. */
    fun cancelDownload(modelId: String) {
        inFlight.remove(modelId)?.cancel()
        persistedDownloadId(modelId)?.let { id ->
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            runCatching { downloadManager.remove(id) }
        }
        clearPersistedDownload(modelId)
        if (installedDirFor(modelId) == null) setState(modelId, DownloadState.NotStarted)
    }

    /** Deletes an installed model. Release any loaded recognizer for it first. */
    fun deleteModel(modelId: String) {
        val model = ModelCatalog.byId(modelId) ?: return
        cancelDownload(modelId)
        File(modelsRoot, model.dirName).deleteRecursively()
        setState(modelId, DownloadState.NotStarted)
    }

    private fun ensureOperation(model: SttModel, allowMobileData: Boolean): Deferred<File> {
        inFlight[model.id]?.let { return it }

        lateinit var operation: Deferred<File>
        operation = downloadScope.async(start = CoroutineStart.LAZY) {
            performDownload(model, allowMobileData)
        }
        operation.invokeOnCompletion {
            inFlight.remove(model.id, operation)
        }

        val existing = inFlight.putIfAbsent(model.id, operation)
        if (existing != null) {
            operation.cancel()
            return existing
        }
        operation.start()
        return operation
    }

    private suspend fun performDownload(model: SttModel, allowMobileData: Boolean): File {
        var archive: File? = null
        var preserveDownload = false
        try {
            val downloadId = findOrEnqueue(model, allowMobileData)
            archive = awaitDownload(downloadId, model)
            if (archive.length() != model.archiveSizeBytes) {
                throw IOException(
                    "Downloaded model has the wrong size (${archive.length()} of ${model.archiveSizeBytes} bytes)",
                )
            }
            val installedDir = modelInstallMutex.withLock {
                verifyChecksum(archive, model)
                setState(model.id, DownloadState.Extracting)
                extract(archive, model)
            }
            setState(model.id, DownloadState.Completed(installedDir))
            discardDownloadEntry(model.id)
            return installedDir
        } catch (cancelled: CancellationException) {
            // DownloadManager continues independently of this coroutine. Keep
            // its id and partial archive so a recreated service can resume it;
            // deleting the destination here races with DownloadManager.
            preserveDownload = true
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Installing ${model.id} failed", error)
            setState(
                model.id,
                DownloadState.Failed(error.message ?: "Model download failed"),
            )
            discardDownloadEntry(model.id)
            throw error
        } finally {
            if (!preserveDownload) {
                archive?.let { file ->
                    if (file.exists() && !file.delete()) {
                        Log.w(TAG, "Failed to delete downloaded archive ${file.name}")
                    }
                }
            }
        }
    }

    private fun findOrEnqueue(model: SttModel, allowMobileData: Boolean): Long {
        val persistedId = persistedDownloadId(model.id)
        if (persistedId != null) {
            val snapshot = querySnapshot(persistedId)
            if (snapshot != null &&
                (snapshot.isActive || snapshot.status == DownloadManager.STATUS_SUCCESSFUL)
            ) {
                return persistedId
            }
            clearPersistedDownload(model.id)
        }

        val destinationDir = downloadsDir ?: throw IOException("External storage unavailable")
        if (!destinationDir.isDirectory && !destinationDir.mkdirs()) {
            throw IOException("Cannot create download directory")
        }
        val requiredBytes = model.archiveSizeBytes * 5L / 2L
        val internalFreeBytes = modelsRoot.parentFile?.usableSpace ?: 0L
        if (destinationDir.usableSpace < requiredBytes || internalFreeBytes < requiredBytes) {
            throw IOException(
                "Not enough free space. Keep at least ${requiredBytes / (1024L * 1024L)} MB available",
            )
        }
        val destination = File(destinationDir, model.archiveName)
        if (destination.exists() && !destination.delete()) {
            throw IOException("Cannot replace an incomplete model download")
        }

        val request = DownloadManager.Request(Uri.parse(model.downloadUrl)).apply {
            setTitle(model.archiveName)
            setDescription(model.displayName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // The archive is hundreds of megabytes: never spend mobile data
            // or roaming allowance unless the user explicitly chose to.
            setAllowedOverMetered(allowMobileData)
            setAllowedOverRoaming(false)
            setDestinationUri(Uri.fromFile(destination))
        }
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request).also { id ->
            preferences.edit()
                .putLong(downloadKey(model.id), id)
                .putBoolean(mobileDataKey(model.id), allowMobileData)
                .apply()
        }
    }

    private suspend fun awaitDownload(downloadId: Long, model: SttModel): File {
        while (true) {
            val snapshot = querySnapshot(downloadId)
                ?: throw IOException("Download $downloadId is no longer available")
            when {
                snapshot.status == DownloadManager.STATUS_SUCCESSFUL -> {
                    return snapshot.localFile
                        ?: throw IOException("Downloaded model file is unavailable")
                }
                snapshot.status == DownloadManager.STATUS_FAILED -> {
                    throw IOException("Download failed: reason=${snapshot.reason}")
                }
                snapshot.isActive -> {
                    setState(model.id, snapshot.toState())
                    delay(POLL_INTERVAL_MS)
                }
                else -> throw IOException("Download stopped unexpectedly")
            }
        }
    }

    private fun querySnapshot(downloadId: Long): DownloadSnapshot? {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloadedBytes = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
            )
            val totalBytes = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
            )
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val localUri = localUriIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getString)
            val localFile = localUri?.let { uri ->
                Uri.parse(uri).path?.let(::File)?.takeIf(File::isFile)
            }
            return DownloadSnapshot(
                status = status,
                downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                totalBytes = totalBytes.coerceAtLeast(0L),
                reason = reason,
                localFile = localFile,
            )
        }
    }

    private fun extract(archive: File, model: SttModel): File {
        if (!modelsRoot.isDirectory && !modelsRoot.mkdirs()) {
            throw IOException("Cannot create models directory")
        }
        val destination = File(modelsRoot, model.dirName)
        val temporary = File(modelsRoot, ".${model.dirName}.partial")
        if (temporary.exists() && !temporary.deleteRecursively()) {
            throw IOException("Cannot clear incomplete model extraction")
        }
        if (!temporary.mkdirs()) {
            throw IOException("Cannot create temporary model directory")
        }

        try {
            val destinationCanonical = temporary.canonicalPath + File.separator
            var stripPrefix: String? = null
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val normalizedName = entry.name.trimStart('/')
                        if (normalizedName.split('/').any { it == ".." }) {
                            Log.w(TAG, "Skipping suspicious tar entry: ${entry.name}")
                            continue
                        }
                        if (stripPrefix == null && normalizedName.isNotEmpty()) {
                            // Discover a conventional single archive root while
                            // streaming, avoiding a second 480 MB decompression.
                            stripPrefix = if ('/' in normalizedName) {
                                normalizedName.substringBefore('/') + "/"
                            } else if (entry.isDirectory) {
                                "$normalizedName/"
                            } else {
                                ""
                            }
                        }
                        val relative = normalizedName.removePrefix(stripPrefix.orEmpty())
                        if (relative.isEmpty()) continue
                        writeEntry(entry, relative, tar, temporary, destinationCanonical)
                    }
                }
            }
            if (!isValidModelDirectory(temporary)) {
                throw IOException("Downloaded archive does not contain a valid speech model")
            }
            // Write the marker while the extraction is still private. A
            // process death after the rename can then never expose a model
            // directory that looks complete but cannot be trusted.
            File(temporary, MARKER_FILE).writeText(model.id)
            if (destination.exists() && !destination.deleteRecursively()) {
                throw IOException("Cannot replace existing model directory")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Cannot finalize model extraction")
            }
            return destination
        } catch (error: Throwable) {
            temporary.deleteRecursively()
            throw error
        }
    }

    private fun verifyChecksum(archive: File, model: SttModel) {
        if (model.archiveSha256.isEmpty()) return
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(archive)).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (!actual.equals(model.archiveSha256, ignoreCase = true)) {
            throw IOException("Downloaded model failed its integrity check")
        }
    }

    private fun writeEntry(
        entry: TarArchiveEntry,
        relativeName: String,
        tar: TarArchiveInputStream,
        destination: File,
        destinationCanonical: String,
    ) {
        if (relativeName.split('/').any { it == ".." }) {
            Log.w(TAG, "Skipping suspicious tar entry: ${entry.name}")
            return
        }
        val outputFile = File(destination, relativeName)
        if (!outputFile.canonicalPath.startsWith(destinationCanonical)) {
            throw IOException("Archive path escapes model directory")
        }
        if (entry.isSymbolicLink || entry.isLink) {
            Log.w(TAG, "Skipping link entry: ${entry.name}")
            return
        }
        if (entry.isDirectory) {
            if (!outputFile.isDirectory && !outputFile.mkdirs()) {
                throw IOException("Cannot create model directory ${outputFile.name}")
            }
            return
        }
        outputFile.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Cannot create directory ${parent.name}")
            }
        }
        FileOutputStream(outputFile).use { output ->
            tar.copyTo(output, BUFFER_SIZE)
        }
    }

    private fun isValidModelDirectory(directory: File): Boolean {
        if (!directory.isDirectory) return false
        val files = directory.listFiles { file -> file.isFile && file.length() > 0L } ?: return false
        val hasTokens = files.any { it.name == "tokens.txt" }
        val hasTransducer = files.any { it.name.startsWith("encoder") && it.name.endsWith(".onnx") } &&
            files.any { it.name.startsWith("decoder") && it.name.endsWith(".onnx") } &&
            files.any { it.name.startsWith("joiner") && it.name.endsWith(".onnx") }
        val hasSingleNemoModel = files.count { it.name.endsWith(".onnx") } == 1
        return hasTokens && (hasTransducer || hasSingleNemoModel)
    }

    /** Removes the system download record too, so finished or failed transfers don't linger in Downloads. */
    private fun discardDownloadEntry(modelId: String) {
        persistedDownloadId(modelId)?.let { id ->
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            runCatching { downloadManager.remove(id) }
        }
        clearPersistedDownload(modelId)
    }

    private fun setState(modelId: String, state: DownloadState) {
        _states.update { states -> states + (modelId to state) }
    }

    private fun persistedDownloadId(modelId: String): Long? =
        preferences.getLong(downloadKey(modelId), -1L).takeIf { it > 0L }

    private fun persistedMobileData(modelId: String): Boolean? =
        if (preferences.contains(mobileDataKey(modelId))) preferences.getBoolean(mobileDataKey(modelId), false) else null

    private fun clearPersistedDownload(modelId: String) {
        preferences.edit().remove(downloadKey(modelId)).remove(mobileDataKey(modelId)).apply()
    }

    private fun downloadKey(modelId: String): String = "download_id_$modelId"
    private fun mobileDataKey(modelId: String): String = "download_mobile_$modelId"

    private data class DownloadSnapshot(
        val status: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val reason: Int,
        val localFile: File?,
    ) {
        val isActive: Boolean
            get() = status == DownloadManager.STATUS_PENDING ||
                status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_PAUSED

        fun toState(): DownloadState.Downloading = DownloadState.Downloading(
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            waitingForNetwork = status == DownloadManager.STATUS_PAUSED &&
                reason != DownloadManager.PAUSED_UNKNOWN,
        )
    }

    companion object {
        private const val TAG = "ModelDownloader"
        private const val PREFERENCES_NAME = "model_downloads"
        private const val MARKER_FILE = ".installed"
        private const val POLL_INTERVAL_MS = 500L
        private const val BUFFER_SIZE = 64 * 1024
    }
}
