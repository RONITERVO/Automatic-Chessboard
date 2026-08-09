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
        onUpdate(placeholders("Running", "Waiting for current responsesâ€¦"))
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
                DiagnosticResult(
                    "connection", "Board connection", if (state.connected) "Pass" else "Fail",
                    state.connectionText, state.connected,
                ),
                DiagnosticResult(
                    "firmware", "Firmware identity", if (state.firmware != null) "Pass" else "Fail",
                    state.firmware?.let { "Firmware ${it.firmware}, ${it.protocol}" }
                        ?: if (responsesComplete) "No INFO response" else "INFO request timed out",
                    state.firmware != null,
                ),
                DiagnosticResult(
                    "telemetry", "Live telemetry", if (telemetry != null) "Pass" else "Fail",
                    telemetry?.let { state.sequenceName() }
                        ?: if (responsesComplete) "No TELEM response" else "TELEM request timed out",
                    telemetry != null,
                ),
                DiagnosticResult(
                    "sensors", "64-square sensors", if (state.sensorSquares != null) "Pass" else "Fail",
                    state.sensorSquares?.let { "Read all 64 squares; ${it.size} occupied" }
                        ?: if (responsesComplete) "No BOARD response" else "BOARD request timed out",
                    state.sensorSquares != null,
                ),
                DiagnosticResult(
                    "controls", "Buttons / limits", if (controlsGood) "Pass" else "Attention",
                    telemetry?.let {
                        "A released=${it.buttonAReleased}; B released=${it.buttonBReleased}; A6=${it.buttonBRaw}"
                    } ?: "Telemetry unavailable",
                    controlsGood,
                ),
                DiagnosticResult(
                    "engine", "Stockfish engine", if (engineResult.isSuccess) "Pass" else "Fail",
                    engineResult.getOrElse { it.message ?: "Engine unavailable" }.toString(),
                    engineResult.isSuccess,
                ),
                DiagnosticResult(
                    "camera", "Phone camera", if (cameraCount > 0) "Pass" else "Optional",
                    if (cameraCount > 0) "$cameraCount local camera(s) available" else "No local camera",
                    cameraCount > 0,
                ),
            )
            main.post { if (!closed) onUpdate(results) }
        }
    }

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
