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
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    fun run(onUpdate: (List<DiagnosticResult>) -> Unit) {
        val running = labels.map { (key, label) -> DiagnosticResult(key, label, "Running", "Waiting…", false) }.toMutableList()
        onUpdate(running)
        repository.safeRefresh()
        worker.execute {
            val engineResult = runCatching { engine.identity() }
            main.postDelayed({
                val state = repository.state
                val telemetry = state.telemetry
                val cameraCount = runCatching {
                    context.getSystemService(CameraManager::class.java).cameraIdList.size
                }.getOrDefault(0)
                val controlsGood = telemetry?.let { it.buttonAReleased && it.buttonBReleased && it.buttonBRaw >= 700 } == true
                onUpdate(listOf(
                    DiagnosticResult("connection", "Board connection", if (state.connected) "Pass" else "Fail", state.connectionText, state.connected),
                    DiagnosticResult("firmware", "Firmware identity", if (state.firmware != null) "Pass" else "Fail",
                        state.firmware?.let { "Firmware ${it.firmware}, ${it.protocol}" } ?: "No INFO response", state.firmware != null),
                    DiagnosticResult("telemetry", "Live telemetry", if (telemetry != null) "Pass" else "Fail",
                        telemetry?.let { state.sequenceName() } ?: "No TELEM response", telemetry != null),
                    DiagnosticResult("sensors", "64-square sensors", if (state.sensorSquares != null) "Pass" else "Fail",
                        state.sensorSquares?.let { "Read all 64 squares; ${it.size} occupied" } ?: "No BOARD response", state.sensorSquares != null),
                    DiagnosticResult("controls", "Buttons / limits", if (controlsGood) "Pass" else "Attention",
                        telemetry?.let { "A released=${it.buttonAReleased}; B released=${it.buttonBReleased}; A6=${it.buttonBRaw}" }
                            ?: "Telemetry unavailable", controlsGood),
                    DiagnosticResult("engine", "Stockfish engine", if (engineResult.isSuccess) "Pass" else "Fail",
                        engineResult.getOrElse { it.message ?: "Engine unavailable" }.toString(), engineResult.isSuccess),
                    DiagnosticResult("camera", "Phone camera", if (cameraCount > 0) "Pass" else "Optional",
                        if (cameraCount > 0) "$cameraCount local camera(s) available" else "No local camera", cameraCount > 0),
                ))
            }, 2_500)
        }
    }

    companion object {
        private val labels = listOf(
            "connection" to "Board connection", "firmware" to "Firmware identity",
            "telemetry" to "Live telemetry", "sensors" to "64-square sensors",
            "controls" to "Buttons / limits", "engine" to "Stockfish engine", "camera" to "Phone camera",
        )
    }
}
