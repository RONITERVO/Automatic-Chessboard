package org.openautomaticchessboard.mobile.domain

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

data class DiagnosticResult(val key: String, val label: String, val result: String, val detail: String, val good: Boolean)

class DiagnosticsRunner(
    private val context: Context,
    private val repository: BoardRepository,
    private val engine: StockfishEngine,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostics").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    fun placeholders(result: String = "Not run", detail: String = ""): List<DiagnosticResult> =
        labels.map { (key, label) -> DiagnosticResult(key, label, result, detail, false) }

    fun run(onUpdate: (List<DiagnosticResult>) -> Unit) {
        onUpdate(placeholders("Running", "Waiting for current responses\u2026"))
        worker.execute {
            val responsesComplete = repository.safeRefreshAndWait()
            val engineResult = runCatching { engine.identity() }
            val state = repository.state
            val telemetry = state.telemetry
            val cameraCount = runCatching {
                context.getSystemService(CameraManager::class.java).cameraIdList.size
            }.getOrDefault(0)
            val controlsGood = telemetry?.let {
                it.buttonAReleased && it.buttonBReleased && it.buttonBRaw >= 700
            } == true
            val results = listOf(
                connectionResult(state),
                firmwareResult(state, responsesComplete),
                telemetryResult(state, responsesComplete),
                sensorsResult(state, responsesComplete),
                controlsResult(state, controlsGood),
                engineResult(engineResult),
                cameraResult(cameraCount),
            )
            main.post { if (!closed) onUpdate(results) }
        }
    }

    private fun connectionResult(state: MonitorState) = DiagnosticResult(
        "connection", "Board connection", if (state.connected) "Pass" else "Fail",
        state.connectionText, state.connected,
    )

    private fun firmwareResult(state: MonitorState, responsesComplete: Boolean) = DiagnosticResult(
        "firmware", "Firmware identity", if (state.firmware != null) "Pass" else "Fail",
        state.firmware?.let { "Firmware ${it.firmware}, ${it.protocol}" }
            ?: if (responsesComplete) "No INFO response" else "INFO request timed out",
        state.firmware != null,
    )

    private fun telemetryResult(state: MonitorState, responsesComplete: Boolean) = DiagnosticResult(
        "telemetry", "Live telemetry", if (state.telemetry != null) "Pass" else "Fail",
        state.telemetry?.let { state.sequenceName() }
            ?: if (responsesComplete) "No TELEM response" else "TELEM request timed out",
        state.telemetry != null,
    )

    private fun sensorsResult(state: MonitorState, responsesComplete: Boolean) = DiagnosticResult(
        "sensors", "64-square sensors", if (state.sensorSquares != null) "Pass" else "Fail",
        state.sensorSquares?.let { "Read all 64 squares; ${it.size} occupied" }
            ?: if (responsesComplete) "No BOARD response" else "BOARD request timed out",
        state.sensorSquares != null,
    )

    private fun controlsResult(state: MonitorState, good: Boolean) = DiagnosticResult(
        "controls", "Buttons / limits", if (good) "Pass" else "Attention",
        state.telemetry?.let {
            "A released=${it.buttonAReleased}; B released=${it.buttonBReleased}; A6=${it.buttonBRaw}"
        } ?: "Telemetry unavailable",
        good,
    )

    private fun engineResult(result: Result<String>) = DiagnosticResult(
        "engine", "Stockfish engine", if (result.isSuccess) "Pass" else "Fail",
        result.getOrElse { it.message ?: "Engine unavailable" }, result.isSuccess,
    )

    private fun cameraResult(count: Int) = DiagnosticResult(
        "camera", "Phone camera", if (count > 0) "Pass" else "Optional",
        if (count > 0) "$count local camera(s) available" else "No local camera", count > 0,
    )

    override fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }

    companion object {
        val labels = listOf(
            "connection" to "Board connection", "firmware" to "Firmware identity",
            "telemetry" to "Live telemetry", "sensors" to "64-square sensors",
            "controls" to "Buttons / limits", "engine" to "Stockfish engine",
            "camera" to "Phone camera",
        )
    }
}
