package com.wislvault

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TransferItem(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val direction: Direction,
    val status: Status,
    val bytesTransferred: Long = 0L,
    val speed: Double = 0.0,
    val completedAt: Long? = null
) {
    enum class Direction { UPLOAD, DOWNLOAD }
    enum class Status { ACTIVE, COMPLETED, CANCELLED, FAILED }
}

object TransferManager {

    private const val NOTIF_CHANNEL_ID = "wisl_downloads"
    private const val PREFS_NAME = "transfer_history"
    private const val KEY_ITEMS = "items"
    private val notifIdCounter = AtomicInteger(1000)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        loadHistory()
    }

    private fun loadHistory() {
        val ctx = appContext ?: return
        val json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null) ?: return
        val items = deserialize(json).map { item ->
            if (item.status == TransferItem.Status.ACTIVE)
                item.copy(status = TransferItem.Status.FAILED, speed = 0.0)
            else item
        }
        _transfers.value = items
    }

    private fun saveHistory() {
        val ctx = appContext ?: return
        scope.launch(Dispatchers.IO) {
            val json = serialize(_transfers.value)
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, json).apply()
        }
    }

    private fun serialize(items: List<TransferItem>): String {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("fileName", item.fileName)
                put("fileSize", item.fileSize)
                put("mimeType", item.mimeType)
                put("direction", item.direction.name)
                put("status", item.status.name)
                put("bytesTransferred", item.bytesTransferred)
                if (item.completedAt != null) put("completedAt", item.completedAt)
            })
        }
        return arr.toString()
    }

    private fun deserialize(json: String): List<TransferItem> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TransferItem(
                    id = obj.getString("id"),
                    fileName = obj.getString("fileName"),
                    fileSize = obj.getLong("fileSize"),
                    mimeType = obj.getString("mimeType"),
                    direction = TransferItem.Direction.valueOf(obj.getString("direction")),
                    status = TransferItem.Status.valueOf(obj.getString("status")),
                    bytesTransferred = obj.optLong("bytesTransferred", 0L),
                    speed = 0.0,
                    completedAt = if (obj.has("completedAt")) obj.getLong("completedAt") else null
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun startUpload(uri: Uri, serverUrl: String, context: Context): String {
        val id = UUID.randomUUID().toString()
        val fileName = getFileName(uri, context) ?: "arquivo"
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L

        addTransfer(TransferItem(id, fileName, fileSize, mimeType, TransferItem.Direction.UPLOAD, TransferItem.Status.ACTIVE))
        launchUpload(id, uri, fileName, mimeType, fileSize, serverUrl, context.applicationContext)
        return id
    }

    fun startDownload(file: VaultActivity.FileInfo, serverUrl: String, context: Context): String {
        val id = UUID.randomUUID().toString()
        val mimeType = "application/octet-stream"
        addTransfer(TransferItem(id, file.name, file.size, mimeType, TransferItem.Direction.DOWNLOAD, TransferItem.Status.ACTIVE))
        launchDownload(id, file, serverUrl, context.applicationContext)
        return id
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        updateTransfer(id) { copy(status = TransferItem.Status.CANCELLED, speed = 0.0) }
        saveHistory()
    }

    fun remove(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        _transfers.update { list -> list.filter { it.id != id } }
        saveHistory()
    }

    private fun launchUpload(
        id: String,
        uri: Uri,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        serverUrl: String,
        context: Context
    ) {
        val notifId = notifIdCounter.getAndIncrement()
        val nm = context.getSystemService(NotificationManager::class.java)

        val job = scope.launch {
            val thisJob = checkNotNull(coroutineContext[kotlinx.coroutines.Job])
            val startTime = System.currentTimeMillis()
            var lastUpdate = 0L

            val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_arrow_upload)
                .setContentTitle(fileName)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)

            try {
                val fileId = UUID.randomUUID().toString()
                val boundary = "WiSLBoundary${fileId.replace("-", "")}"

                val conn = URL("$serverUrl/upload").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setChunkedStreamingMode(256 * 1024)
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                conn.connectTimeout = 10_000
                conn.readTimeout = 3_600_000

                conn.outputStream.use { out ->
                    val tracked = object : OutputStream() {
                        var written = 0L

                        override fun write(b: Int) { out.write(b); tick(++written) }

                        override fun write(b: ByteArray, off: Int, len: Int) {
                            out.write(b, off, len)
                            written += len
                            tick(written)
                        }

                        override fun flush() = out.flush()
                        override fun close() = out.close()

                        fun tick(loaded: Long) {
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate < 150) return
                            lastUpdate = now
                            val elapsed = (now - startTime) / 1000.0
                            val speed = if (elapsed > 0.5 && loaded > 0) loaded / elapsed else 0.0
                            val pct = if (fileSize > 0) ((loaded.toDouble() / fileSize) * 100).toInt() else 0
                            updateTransfer(id) { copy(bytesTransferred = loaded, speed = speed) }
                            val text = buildString {
                                append(formatSize(loaded))
                                if (fileSize > 0) append(" / ${formatSize(fileSize)}")
                                if (speed > 0) append(" • ${formatSize(speed.toLong())}/s")
                            }
                            builder.setContentText(text).setProgress(100, pct, fileSize <= 0)
                            nm.notify(notifId, builder.build())
                        }
                    }
                    writeMultipart(tracked, boundary, uri, fileName, mimeType, fileId, context) { thisJob.isActive }
                }

                val code = conn.responseCode
                if (code !in 200..299) throw Exception("HTTP $code")
                updateTransfer(id) { copy(status = TransferItem.Status.COMPLETED, bytesTransferred = fileSize, speed = 0.0, completedAt = System.currentTimeMillis()) }
                saveHistory()
                nm.notify(notifId, NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_arrow_upload)
                    .setContentTitle(fileName)
                    .setContentText(context.getString(R.string.upload_complete))
                    .setAutoCancel(true)
                    .build())
            } catch (e: Exception) {
                nm.cancel(notifId)
                updateTransfer(id) {
                    if (status == TransferItem.Status.CANCELLED) copy(speed = 0.0)
                    else copy(status = TransferItem.Status.FAILED, speed = 0.0)
                }
                saveHistory()
            } finally {
                jobs.remove(id)
            }
        }
        jobs[id] = job
    }

    private fun launchDownload(id: String, file: VaultActivity.FileInfo, serverUrl: String, context: Context) {
        val notifId = notifIdCounter.getAndIncrement()
        val nm = context.getSystemService(NotificationManager::class.java)

        val job = scope.launch {
            val thisJob = checkNotNull(coroutineContext[kotlinx.coroutines.Job])
            var mediaUri: Uri? = null
            try {
                val encoded = URLEncoder.encode(file.name, "UTF-8")
                val conn = URL("$serverUrl/files/$encoded").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 3_600_000
                conn.connect()

                if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")

                val totalBytes = conn.contentLengthLong
                val mimeType = conn.contentType ?: "application/octet-stream"

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                mediaUri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw Exception("Cannot create file in Downloads")

                val startTime = System.currentTimeMillis()
                var downloaded = 0L
                var lastUpdate = 0L

                val builder = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(file.name)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)

                conn.inputStream.use { input ->
                    context.contentResolver.openOutputStream(mediaUri!!)!!.use { output ->
                        val buf = ByteArray(256 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            if (!thisJob.isActive) throw IOException("Transfer cancelled")
                            output.write(buf, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 200) {
                                lastUpdate = now
                                val elapsed = (now - startTime) / 1000.0
                                val speed = if (elapsed > 0.5 && downloaded > 0) downloaded / elapsed else 0.0
                                val pct = if (totalBytes > 0) ((downloaded.toDouble() / totalBytes) * 100).toInt() else 0

                                updateTransfer(id) { copy(bytesTransferred = downloaded, speed = speed) }

                                val text = buildString {
                                    append(formatSize(downloaded))
                                    if (totalBytes > 0) append(" / ${formatSize(totalBytes)}")
                                    if (speed > 0) append(" • ${formatSize(speed.toLong())}/s")
                                }
                                builder.setContentText(text).setProgress(100, pct, totalBytes <= 0)
                                nm.notify(notifId, builder.build())
                            }
                        }
                    }
                }

                val doneValues = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                context.contentResolver.update(mediaUri!!, doneValues, null, null)

                updateTransfer(id) { copy(status = TransferItem.Status.COMPLETED, bytesTransferred = if (totalBytes > 0) totalBytes else downloaded, speed = 0.0, completedAt = System.currentTimeMillis()) }
                saveHistory()

                nm.notify(notifId, NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_download)
                    .setContentTitle(file.name)
                    .setContentText(context.getString(R.string.download_complete))
                    .setAutoCancel(true)
                    .build())

            } catch (e: Exception) {
                mediaUri?.let { context.contentResolver.delete(it, null, null) }
                nm.cancel(notifId)
                updateTransfer(id) {
                    if (status == TransferItem.Status.CANCELLED) copy(speed = 0.0)
                    else copy(status = TransferItem.Status.FAILED, speed = 0.0)
                }
                saveHistory()
            } finally {
                jobs.remove(id)
            }
        }
        jobs[id] = job
    }

    private fun addTransfer(item: TransferItem) {
        _transfers.update { list -> list + item }
        saveHistory()
    }

    private fun updateTransfer(id: String, block: TransferItem.() -> TransferItem) {
        _transfers.update { list -> list.map { if (it.id == id) it.block() else it } }
    }

    private fun writeMultipart(
        out: OutputStream,
        boundary: String,
        uri: Uri,
        fileName: String,
        mimeType: String,
        fileId: String,
        context: Context,
        isActive: () -> Boolean
    ) {
        fun field(name: String, value: String) {
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            out.write("$value\r\n".toByteArray())
        }

        field("file_id", fileId)
        field("chunk_index", "0")
        field("total_chunks", "1")

        out.write("--$boundary\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n".toByteArray())
        out.write("Content-Type: $mimeType\r\n\r\n".toByteArray())

        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(256 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                if (!isActive()) throw IOException("Transfer cancelled")
                out.write(buf, 0, read)
            }
        }

        out.write("\r\n--$boundary--\r\n".toByteArray())
        out.flush()
    }

    private fun getFileName(uri: Uri, context: Context): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && idx >= 0) it.getString(idx) else null
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val i = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt().coerceIn(0, units.size - 1)
        return "%.1f %s".format(bytes / Math.pow(1024.0, i.toDouble()), units[i])
    }
}
