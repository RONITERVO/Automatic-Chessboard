package org.openautomaticchessboard.mobile.domain

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SupportBundle {
    fun write(
        context: Context,
        uri: Uri,
        recorder: EventRecorder,
        state: MonitorState,
        settings: Map<String, Any?>,
        diagnostics: List<DiagnosticResult>,
    ) {
        context.contentResolver.openOutputStream(uri, "w")!!.use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.text("system.json", JSONObject().apply {
                    put("manufacturer", Build.MANUFACTURER)
                    put("model", Build.MODEL)
                    put("android", Build.VERSION.RELEASE)
                    put("sdk", Build.VERSION.SDK_INT)
                    put("app", "Open Automatic Chessboard Mobile")
                }.toString(2))
                zip.text("settings-sanitized.json", JSONObject(settings.mapValues { (key, value) ->
                    when {
                        key.contains("address", true) -> "<bluetooth-address-redacted>"
                        key.contains("camera", true) && value.toString().contains("://") -> "<network-camera-url-redacted>"
                        else -> value
                    }
                }).toString(2))
                zip.text("board-snapshot.json", JSONObject().apply {
                    put("connection", state.connectionText.replace(Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"), "<address>"))
                    put("firmware", state.firmware?.firmware)
                    put("protocol", state.firmware?.protocol)
                    put("capabilities", JSONArray(state.firmware?.capabilities?.toList().orEmpty()))
                    put("telemetry", state.telemetry?.let { JSONObject(it.toStringMap()) })
                    put("sensor_hex", state.sensorHex)
                    put("sensor_occupied", state.sensorSquares?.size)
                    put("health", state.health().first)
                    put("last_error", state.lastError)
                    put("diagnostics", JSONArray(diagnostics.map { JSONObject().apply {
                        put("check", it.label); put("result", it.result); put("detail", it.detail)
                    } }))
                }.toString(2))
                if (recorder.file.isFile) {
                    val redacted = recorder.file.readText().replace(
                        Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"), "<bluetooth-address-redacted>"
                    ).replace(Regex("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@"), "$1<credentials-redacted>@")
                    zip.text("session.jsonl", redacted)
                }
                zip.text("README.txt", "Support bundle contains logs and controller state only. It excludes camera frames, PGNs, and credentials.\n")
            }
        }
    }

    private fun ZipOutputStream.text(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun org.openautomaticchessboard.mobile.protocol.Telemetry.toStringMap() = mapOf(
        "sequence" to sequence, "homed" to homed, "remote_mode" to remoteMode,
        "motion_fault" to motionFault, "magnet_on" to magnetOn, "x" to trolleyX, "y" to trolleyY,
        "a_released" to buttonAReleased, "b_released" to buttonBReleased, "b_raw" to buttonBRaw,
        "free_ram" to freeRam, "uptime_seconds" to uptimeSeconds,
    )
}
