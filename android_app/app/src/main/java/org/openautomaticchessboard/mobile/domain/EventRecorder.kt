package org.openautomaticchessboard.mobile.domain

import android.content.Context
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EventRecorder(context: Context) : Closeable {
    val sessionId: String = UUID.randomUUID().toString().replace("-", "").take(12)
    private val directory = File(context.filesDir, "logs").apply { mkdirs() }
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneOffset.UTC).format(Instant.now())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "event-recorder").apply { isDaemon = true }
    }
    @Volatile private var accepting = true
    @Volatile var file: File = sessionFile(0)
        private set
    private var part = 0
    private var writer: BufferedWriter = openWriter(file)
    private var writtenBytes = file.length()

    init {
        record("app", "session_started")
        pruneSessions()
    }

    fun record(category: String, message: String, fields: Map<String, Any?> = emptyMap()) {
        if (!accepting) return
        val encoded = (JSONObject().apply {
            put("time", Instant.now().toString())
            put("session", sessionId)
            put("category", category)
            put("message", message)
            fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }.toString() + "\n").toByteArray(Charsets.UTF_8)
        runCatching {
            worker.execute {
                if (writtenBytes > 0 && writtenBytes + encoded.size > MAX_SESSION_BYTES) rollOver()
                writer.write(encoded.toString(Charsets.UTF_8))
                writer.flush()
                writtenBytes += encoded.size
            }
        }
    }

    /** Flushes queued writes and returns only the bounded tail used by support bundles. */
    fun readTail(maximumBytes: Int = MAX_SUPPORT_LOG_BYTES): ByteArray {
        val task = runCatching {
            worker.submit<ByteArray> {
                writer.flush()
                readFileTail(file, maximumBytes)
            }
        }.getOrNull()
        return if (task != null) runCatching { task.get(2, TimeUnit.SECONDS) }.getOrDefault(byteArrayOf())
        else readFileTail(file, maximumBytes)
    }

    private fun rollOver() {
        runCatching { writer.close() }
        part++
        file = sessionFile(part)
        writer = openWriter(file)
        writtenBytes = 0
        pruneSessions()
    }

    private fun sessionFile(index: Int): File {
        val suffix = if (index == 0) "" else "-part%03d".format(index)
        return File(directory, "session-$stamp-$sessionId$suffix.jsonl")
    }

    private fun openWriter(target: File): BufferedWriter =
        FileOutputStream(target, true).bufferedWriter(Charsets.UTF_8)

    private fun pruneSessions() {
        directory.listFiles { value -> value.name.startsWith("session-") && value.extension == "jsonl" }
            ?.filter { candidate -> candidate != file }
            ?.sortedByDescending(File::lastModified)
            ?.drop(MAX_LOG_FILES - 1)
            ?.forEach(File::delete)
    }

    override fun close() {
        if (!accepting) return
        accepting = false
        runCatching { worker.execute { runCatching { writer.close() } } }
        worker.shutdown()
        try {
            worker.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val MAX_SESSION_BYTES = 512 * 1024
        const val MAX_SUPPORT_LOG_BYTES = 256 * 1024
        private const val MAX_LOG_FILES = 20
        private const val CLOSE_TIMEOUT_SECONDS = 2L

        private fun readFileTail(source: File, maximumBytes: Int): ByteArray {
            if (!source.isFile || maximumBytes <= 0) return byteArrayOf()
            return runCatching {
                RandomAccessFile(source, "r").use { input ->
                    val length = input.length()
                    val start = (length - maximumBytes).coerceAtLeast(0)
                    input.seek(start)
                    val bytes = ByteArray((length - start).toInt())
                    input.readFully(bytes)
                    if (start == 0L) bytes else {
                        val newline = bytes.indexOf('\n'.code.toByte())
                        if (newline < 0 || newline + 1 >= bytes.size) byteArrayOf()
                        else bytes.copyOfRange(newline + 1, bytes.size)
                    }
                }
            }.getOrDefault(byteArrayOf())
        }
    }
}

data class TimelineEntry(
    val timeMs: Long,
    val direction: String,
    val event: String,
    val detail: String,
)
