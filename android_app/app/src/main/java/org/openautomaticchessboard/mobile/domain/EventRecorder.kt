package org.openautomaticchessboard.mobile.domain

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class EventRecorder(context: Context) {
    val sessionId: String = UUID.randomUUID().toString().replace("-", "").take(12)
    val file: File
    private val lock = Any()

    init {
        val directory = File(context.filesDir, "logs").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        file = File(directory, "session-$stamp-$sessionId.jsonl")
        record("app", "session_started")
        directory.listFiles()?.sortedByDescending(File::lastModified)?.drop(20)?.forEach(File::delete)
    }

    fun record(category: String, message: String, fields: Map<String, Any?> = emptyMap()) {
        val row = JSONObject().apply {
            put("time", Instant.now().toString())
            put("session", sessionId)
            put("category", category)
            put("message", message)
            fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
        synchronized(lock) { file.appendText(row.toString() + "\n", Charsets.UTF_8) }
    }
}

data class TimelineEntry(
    val timeMs: Long,
    val direction: String,
    val event: String,
    val detail: String,
)
