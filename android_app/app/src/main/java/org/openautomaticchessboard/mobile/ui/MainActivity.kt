package org.openautomaticchessboard.mobile.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.github.bhlangonijr.chesslib.Piece
import org.openautomaticchessboard.mobile.BuildConfig
import org.openautomaticchessboard.mobile.camera.CameraController
import org.openautomaticchessboard.mobile.domain.BoardRepository
import org.openautomaticchessboard.mobile.domain.DiagnosticResult
import org.openautomaticchessboard.mobile.domain.DiagnosticsRunner
import org.openautomaticchessboard.mobile.domain.EventRecorder
import org.openautomaticchessboard.mobile.domain.GameController
import org.openautomaticchessboard.mobile.domain.GameSnapshot
import org.openautomaticchessboard.mobile.domain.HealthLevel
import org.openautomaticchessboard.mobile.domain.MonitorState
import org.openautomaticchessboard.mobile.domain.ManualMoveMode
import org.openautomaticchessboard.mobile.domain.ManualSelection
import org.openautomaticchessboard.mobile.domain.ManualVerification
import org.openautomaticchessboard.mobile.domain.StockfishEngine
import org.openautomaticchessboard.mobile.domain.SupportBundle
import org.openautomaticchessboard.mobile.domain.TimelineEntry
import org.openautomaticchessboard.mobile.protocol.BoardEvent
import org.openautomaticchessboard.mobile.protocol.CommandRisk
import org.openautomaticchessboard.mobile.protocol.Protocol
import org.openautomaticchessboard.mobile.transport.BleBoardTransport
import org.openautomaticchessboard.mobile.transport.BleDevice
import org.openautomaticchessboard.mobile.transport.SimulatorTransport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ManualPending { NONE, CALIBRATION, HEAD, PIECE }

class MainActivity : Activity(), BoardRepository.Observer {
    private lateinit var repository: BoardRepository
    private lateinit var recorder: EventRecorder
    private lateinit var engine: StockfishEngine
    private lateinit var game: GameController
    private lateinit var diagnosticsRunner: DiagnosticsRunner
    private lateinit var content: FrameLayout
    private lateinit var connectionBadge: TextView
    private var monitorState = MonitorState()
    private lateinit var gameState: GameSnapshot
    private var timeline: List<TimelineEntry> = emptyList()
    private var diagnostics: List<DiagnosticResult> = emptyList()
    private var currentTab = TAB_BOARD
    private var simulatorActive = false
    private var cameraController: CameraController? = null
    private var cameraSource = "0"
    private var pendingSnapshot: Bitmap? = null
    private var devCommand = "TELEM"
    private var devUnlock = false
    private var devPage = 0
    private var historyPage = 0
    private var devLog: TextView? = null
    private var devPageLabel: TextView? = null
    private var monitorUpdater: (() -> Unit)? = null
    private var playUpdater: (() -> Unit)? = null
    private var lastRouteFailureDialog = ""
    private var manualUpdater: (() -> Unit)? = null
    private var manualSelection = ManualSelection()
    private var manualStatus = "Calibrate from this page before moving the head."
    private var manualCalibrationVerified = false
    private var manualPending = ManualPending.NONE
    private var manualPendingSelection: ManualSelection? = null
    private var calibrationReportedSquare: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private val ageRefreshRunnable = object : Runnable {
        override fun run() {
            onBoardState(monitorState)
            ui.postDelayed(this, 1_000)
        }
    }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var stopScan: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = EventRecorder(this)
        repository = BoardRepository(recorder)
        engine = StockfishEngine(this)
        game = GameController(engine, repository, ::onGameChanged) { reported, choose ->
            AlertDialog.Builder(this).setTitle("Promotion")
                .setItems(arrayOf("Queen", "Rook", "Bishop", "Knight")) { _, index ->
                    choose(charArrayOf('q', 'r', 'b', 'n')[index])
                }.setCancelable(false).show()
        }
        game.elo = prefs.getInt("elo", 2000)
        game.thinkMillis = prefs.getLong("think_ms", 800)
        game.routeTimeMillis = prefs.getLong("route_ms", 8_000)
        game.routeMaxTemporaryPieces = prefs.getInt("route_temporary_pieces", 10)
        gameState = game.snapshot
        game.chooseHumanSide(prefs.getBoolean("human_white", true))
        diagnosticsRunner = DiagnosticsRunner(this, repository, engine)
        buildShell()
        repository.addObserver(this)
        selectTab(TAB_BOARD)
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NAVY)
            setOnApplyWindowInsetsListener { view, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                    top = bars.top
                    bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottom = insets.systemWindowInsetBottom
                }
                view.setPadding(0, top, 0, bottom)
                insets
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(5), dp(8), dp(5))
        }
        connectionBadge = text("DISCONNECTED", 12f, Color.WHITE, true).apply {
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showConnectionMenu() }
            setOnLongClickListener { showAbout(); true }
        }
        header.addView(connectionBadge, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("Connect") { showConnectionMenu() }, LinearLayout.LayoutParams(dp(92), dp(44)))
        header.addView(button("HALT", DANGER) { confirmEmergencyHalt() }, LinearLayout.LayoutParams(dp(78), dp(44)).apply {
            marginStart = dp(6)
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        content = FrameLayout(this).apply { setPadding(dp(8), dp(4), dp(8), dp(4)) }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(2), dp(4), dp(4)) }
        TAB_LABELS.forEach { (index, label) ->
            nav.addView(button(label, SURFACE) { selectTab(index) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                if (index != TAB_BOARD) marginStart = dp(3)
            })
        }
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        setContentView(root)
    }

    private fun selectTab(index: Int) {
        if (currentTab == TAB_CAMERA && index != TAB_CAMERA) closeCamera()
        monitorUpdater = null
        playUpdater = null
        manualUpdater = null
        currentTab = index
        content.removeAllViews()
        val page = when (index) {
            TAB_BOARD -> buildMonitor()
            TAB_PLAY -> buildPlay()
            TAB_MOVE -> buildManualControl()
            TAB_CHECKS -> buildDiagnostics()
            TAB_CAMERA -> buildCamera()
            else -> buildDeveloper()
        }
        content.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun buildMonitor(): View {
        val board = ChessboardView(this)
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val health = text("", 19f, Color.WHITE, true)
        val guidance = text("", 14f, Color.WHITE).apply { maxLines = 3 }
        val telemetry = text("", 13f, MUTED).apply { maxLines = 5 }
        details.addView(health, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, .55f))
        details.addView(guidance, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, .95f))
        details.addView(telemetry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2.85f))
        details.addView(button("Refresh safely") { repository.safeRefresh() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)))
        val root = adaptive(board, details, .54f)
        fun update() {
            val state = monitorState
            board.pieces = gameState.pieces
            board.sensors = state.sensorSquares
            board.flipped = !gameState.humanWhite
            board.trolley = trolleyPosition(state)
            val (label, level) = state.health()
            health.text = label
            health.setTextColor(when (level) { HealthLevel.GOOD -> GOOD; HealthLevel.WARN -> WARN; HealthLevel.BAD -> DANGER })
            guidance.text = state.guidance()
            val t = state.telemetry
            telemetry.text = if (t == null) "Telemetry unavailable\nSensor differences: ${state.missingSquares().size} missing • ${state.unexpectedSquares().size} unexpected"
            else "${state.sequenceName()}  •  ${if (t.homed) "Homed" else "Not homed"}\n" +
                "Carriage ${fileRank(t.trolleyX, t.trolleyY)}  •  Magnet ${if (t.magnetOn) "COMMANDED ON" else "off"}\n" +
                "A ${released(t.buttonAReleased)}  •  B ${released(t.buttonBReleased)}  •  A6 ${t.buttonBRaw}\n" +
                "Free RAM ${t.freeRam} B  •  Uptime ${t.uptimeSeconds}s\n" +
                "Sensors ${state.sensorSquares?.size ?: "—"}  •  missing ${state.missingSquares().size}  •  extra ${state.unexpectedSquares().size}"
        }
        monitorUpdater = { update() }
        update()
        return root
    }

    private fun buildPlay(): View {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val board = ChessboardView(this).apply {
            pieces = gameState.pieces; sensors = monitorState.sensorSquares; flipped = !gameState.humanWhite
            trolley = trolleyPosition()
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = text(gameState.status, if (landscape) 12f else 14f, Color.WHITE, true).apply { maxLines = 2 }
        controls.addView(status,
            if (landscape) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        val side = buildSideSelector()
        controls.addView(side.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape) 30 else 42)))
        controls.addView(buildPlaySliders(landscape), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape) 32 else 84),
        ))
        controls.addView(buildPlayActions(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape) 34 else 44),
        ))
        val history = text(historyPageText(), 13f, MUTED).apply { typeface = android.graphics.Typeface.MONOSPACE; maxLines = 4 }
        controls.addView(history, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, if (landscape) 1f else 1.3f))
        controls.addView(buildHistoryPager { playUpdater?.invoke() }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape) 25 else 38),
        ))
        playUpdater = {
            board.pieces = gameState.pieces
            board.sensors = monitorState.sensorSquares
            board.flipped = !gameState.humanWhite
            board.trolley = trolleyPosition()
            status.text = gameState.status
            side.white.background = rounded(if (gameState.humanWhite) ACCENT_DARK else SURFACE)
            side.black.background = rounded(if (!gameState.humanWhite) ACCENT_DARK else SURFACE)
            history.text = historyPageText()
        }
        playUpdater?.invoke()
        return adaptive(board, controls, .52f)
    }

    private fun buildSideSelector(): SideSelector {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val white = button("Human: White") {
            if (!gameState.active) {
                prefs.edit().putBoolean("human_white", true).apply()
                game.chooseHumanSide(true)
            }
        }
        val black = button("Human: Black") {
            if (!gameState.active) {
                prefs.edit().putBoolean("human_white", false).apply()
                game.chooseHumanSide(false)
            }
        }
        row.addView(white, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(black, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(4) })
        return SideSelector(row, white, black)
    }

    private fun buildPlaySliders(landscape: Boolean): View {
        val elo = sliderRow("Elo", 1320, 3190, game.elo) {
            game.elo = it
            prefs.edit().putInt("elo", it).apply()
        }
        val think = sliderRow("Think", 50, 5000, game.thinkMillis.toInt()) {
            game.thinkMillis = it.toLong()
            prefs.edit().putLong("think_ms", it.toLong()).apply()
        }
        return LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            addView(elo, LinearLayout.LayoutParams(
                if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 0, 1f,
            ))
            addView(think, LinearLayout.LayoutParams(
                if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 0, 1f,
            ))
        }
    }

    private fun buildPlayActions(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(button("Start + calibrate", ACCENT_DARK) { confirmStartGame() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f))
        addView(button("Stop") { game.stop() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .72f).apply { marginStart = dp(4) })
        addView(button("PGN") { createPgn() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .65f).apply { marginStart = dp(4) })
        addView(button("Route") { showRouteSettings() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .65f).apply { marginStart = dp(4) })
    }

    private fun showRouteSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(NAVY)
            addView(sliderRow("Search s", 1, 30, (game.routeTimeMillis / 1_000).toInt()) {
                game.routeTimeMillis = it * 1_000L
                prefs.edit().putLong("route_ms", game.routeTimeMillis).apply()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            addView(sliderRow("Temp pieces", 0, 30, game.routeMaxTemporaryPieces) {
                game.routeMaxTemporaryPieces = it
                prefs.edit().putInt("route_temporary_pieces", it).apply()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        AlertDialog.Builder(this)
            .setTitle("Collision-safe routing")
            .setMessage("Longer search and more temporary pieces solve harder positions but use more phone resources.")
            .setView(content)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun buildHistoryPager(update: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(button("◀ moves") { historyPage = (historyPage - 1).coerceAtLeast(0); update() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(button("moves ▶") { historyPage++; update() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(4) })
    }

    private fun buildManualControl(): View {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val board = ChessboardView(this).apply {
            pieces = gameState.pieces
            sensors = monitorState.sensorSquares
            flipped = !gameState.humanWhite
            trolley = trolleyPosition()
            selectedSquares = manualSelection.highlighted
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = text(manualStatus, if (landscape) 12f else 14f, Color.WHITE, true).apply { maxLines = 3 }
        val selectionText = text("", 13f, MUTED).apply { maxLines = 2 }
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val headMode = button("Head only") {
            if (manualPending != ManualPending.NONE) {
                manualStatus = "An operation is in progress; wait for verification."
                manualUpdater?.invoke()
                return@button
            }
            manualSelection = manualSelection.withMode(ManualMoveMode.HEAD_ONLY)
            manualStatus = "Tap one destination. The electromagnet will stay off."
            manualUpdater?.invoke()
        }
        val pieceMode = button("Move piece") {
            if (manualPending != ManualPending.NONE) {
                manualStatus = "An operation is in progress; wait for verification."
                manualUpdater?.invoke()
                return@button
            }
            manualSelection = manualSelection.withMode(ManualMoveMode.MOVE_PIECE)
            manualStatus = "Tap an occupied source, then an empty destination."
            manualUpdater?.invoke()
        }
        modeRow.addView(headMode, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        modeRow.addView(pieceMode, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(4) })
        controls.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        controls.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape) 34 else 44)))
        controls.addView(selectionText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, .75f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Calibrate", ACCENT_DARK) { confirmManualCalibration() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        actions.addView(button("Clear") {
            if (manualPending != ManualPending.NONE) {
                manualStatus = "An operation is in progress; wait for verification."
                manualUpdater?.invoke()
                return@button
            }
            manualSelection = ManualSelection(manualSelection.mode)
            manualStatus = if (manualCalibrationVerified) "Choose squares." else "Calibrate before moving."
            manualUpdater?.invoke()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .72f).apply { marginStart = dp(4) })
        actions.addView(button("Move", WARN) { confirmManualMove() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .82f).apply { marginStart = dp(4) })
        controls.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape) 36 else 46)))
        board.onSquareTapped = squareTap@ { square ->
            if (manualPending != ManualPending.NONE) {
                manualStatus = "An operation is in progress; wait for verification."
                manualUpdater?.invoke()
                return@squareTap
            }
            val result = manualSelection.choose(square, monitorState.sensorSquares)
            manualSelection = result.selection
            manualStatus = result.message
            manualUpdater?.invoke()
        }
        manualUpdater = {
            board.pieces = gameState.pieces
            board.sensors = monitorState.sensorSquares
            board.trolley = trolleyPosition()
            board.selectedSquares = manualSelection.highlighted
            status.text = manualStatus
            val calibration = if (manualCalibrationVerified) "Calibrated and verified" else "Calibration required"
            selectionText.text = "$calibration\n${manualSelection.command() ?: if (manualSelection.mode == ManualMoveMode.HEAD_ONLY) "Tap a target square" else "Tap source and destination"}"
            headMode.background = rounded(if (manualSelection.mode == ManualMoveMode.HEAD_ONLY) ACCENT_DARK else SURFACE)
            pieceMode.background = rounded(if (manualSelection.mode == ManualMoveMode.MOVE_PIECE) ACCENT_DARK else SURFACE)
        }
        manualUpdater?.invoke()
        return adaptive(board, controls, .60f)
    }

    private fun buildDiagnostics(): View {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(text("Safe, read-only checks — no calibration, magnet, or motion", 14f, Color.WHITE, true),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape) 25 else 38)))
        val values = if (diagnostics.isEmpty()) diagnosticsRunner.placeholders() else diagnostics
        fun resultRow(item: DiagnosticResult): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rounded(SURFACE) ; setPadding(dp(9), 0, dp(9), 0)
            addView(text(item.label, 13f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(text(item.result, 13f, if (item.good) GOOD else if (item.result in setOf("Fail", "Attention")) DANGER else WARN, true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(text(item.detail, 11f, MUTED).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.5f))
        }
        if (landscape) {
            val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(values.take(4), values.drop(4)).forEachIndexed { index, group ->
                val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                group.forEach { item -> column.addView(resultRow(item), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(2) }) }
                columns.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (index > 0) marginStart = dp(4) })
            }
            root.addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else values.forEach { item ->
            root.addView(resultRow(item), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(3) })
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Run checks", ACCENT_DARK) { runDiagnostics() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        actions.addView(button("Support ZIP") { createSupportBundle() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(4) })
        actions.addView(button("Copy") { copyDiagnostics() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, .65f).apply { marginStart = dp(4) })
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (landscape) 34 else 46)))
        return root
    }

    private fun buildCamera(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val source = EditText(this).apply {
            setText(cameraSource); setTextColor(Color.WHITE); setHintTextColor(MUTED); hint = "0, HTTPS, or local RTSP"; setSingleLine(true)
            background = rounded(SURFACE); setPadding(dp(8), 0, dp(8), 0)
        }
        row.addView(source, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(button("Start", ACCENT_DARK) { startCamera(source.text.toString()) }, LinearLayout.LayoutParams(dp(72), dp(44)).apply { marginStart = dp(4) })
        row.addView(button("Stop") { cameraController?.stop() }, LinearLayout.LayoutParams(dp(66), dp(44)).apply { marginStart = dp(4) })
        root.addView(row)
        val texture = TextureView(this).apply { isOpaque = true; contentDescription = "Live camera preview" }
        cameraController?.close()
        cameraController = null
        val preview = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(5); bottomMargin = dp(5) })
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val status = text("Camera is off. Frames stay local and are never logged.", 12f, MUTED).apply { maxLines = 2; tag = "camera_status" }
        bottom.addView(status, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottom.addView(button("Snapshot") { createSnapshot() }, LinearLayout.LayoutParams(dp(105), dp(44)))
        root.addView(bottom)
        cameraController = CameraController(this, texture) { message -> runOnUiThread { status.text = message } }
        return root
    }

    private fun buildDeveloper(): View {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val titleHeight = dp(if (landscape) 24 else 38)
        val pagerHeight = dp(if (landscape) 24 else 38)
        val commandHeight = dp(if (landscape) 32 else 45)
        val riskHeight = dp(if (landscape) 34 else 52)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(text("Structured protocol timeline", if (landscape) 12f else 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        titleRow.addView(button("Safety / About") { showAbout() }, LinearLayout.LayoutParams(dp(125), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, titleHeight))
        devLog = text("", 11f, MUTED).apply { typeface = android.graphics.Typeface.MONOSPACE; background = rounded(Color.rgb(8, 15, 25)); setPadding(dp(8), dp(5), dp(8), dp(5)); maxLines = 12 }
        root.addView(devLog, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val pager = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pager.addView(button("◀") { devPage = (devPage - 1).coerceAtLeast(0); updateDeveloperLog() }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT))
        devPageLabel = text("", 12f, MUTED).apply { gravity = Gravity.CENTER }
        pager.addView(devPageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        pager.addView(button("▶") { devPage++; updateDeveloperLog() }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pagerHeight))
        val risk = text(commandRiskText(), 12f, WARN).apply { maxLines = 2 }
        val commandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            setText(devCommand); setSingleLine(true); setTextColor(Color.WHITE); background = rounded(SURFACE); setPadding(dp(8), 0, dp(8), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    devCommand = s?.toString().orEmpty()
                    risk.text = commandRiskText()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        commandRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        commandRow.addView(button("Send", ACCENT_DARK) { sendDeveloperCommand() }, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) })
        root.addView(commandRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, commandHeight))
        val riskRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        riskRow.addView(risk, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        riskRow.addView(CheckBox(this).apply {
            text = "Unlock motion"; setTextColor(Color.WHITE); isChecked = devUnlock
            setOnCheckedChangeListener { _, checked -> devUnlock = checked }
        }, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(riskRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, riskHeight))
        updateDeveloperLog()
        return root
    }

    override fun onBoardState(state: MonitorState) {
        game.connectionChanged(state.connected)
        if (!state.connected && monitorState.connected) {
            invalidateManualCalibration("Connection lost; calibrate again after reconnecting.")
        } else if (manualCalibrationVerified && !ManualVerification.positionIsTrusted(state.telemetry)) {
            val reason = if (state.telemetry?.motionFault == true) {
                "Motion stopped with the carriage position unknown. Inspect locally, recover the fault, and recalibrate."
            } else {
                "Carriage position is no longer homed. Recalibrate before moving."
            }
            invalidateManualCalibration(reason)
        }
        if (state.connected && !monitorState.connected && !manualCalibrationVerified) {
            manualStatus = "Connected; calibrate from this page before moving."
        }
        monitorState = state
        connectionBadge.text = if (state.connected) "CONNECTED • ${state.health().first}" else "DISCONNECTED • ${state.connectionText}"
        connectionBadge.setTextColor(if (state.connected) GOOD else DANGER)
        if (currentTab == TAB_BOARD) monitorUpdater?.invoke()
        if (currentTab == TAB_PLAY) playUpdater?.invoke()
        if (currentTab == TAB_MOVE) manualUpdater?.invoke()
    }

    override fun onBoardEvent(event: BoardEvent) {
        game.handle(event)
        when (event.kind) {
            "CALIBRATING" -> manualStatus = "Calibrating; keep the mechanism clear."
            "CALIBRATED" -> {
                if (manualPending == ManualPending.CALIBRATION) {
                    calibrationReportedSquare = event.args.firstOrNull()
                    manualStatus = "Calibration ended at ${calibrationReportedSquare ?: "unknown"}; checking fresh telemetry."
                    repository.enqueueRequests("TELEM", "BOARD")
                }
            }
            "MOVING" -> if (event.args.firstOrNull() in setOf("HEAD", "PIECE")) {
                manualStatus = "${event.args.first()} movement in progress; keep hands clear."
            }
            "ESTOP" -> invalidateManualCalibration(
                "Remote halt stopped motion and invalidated the carriage position. Inspect locally and recalibrate."
            )
            "MOVED" -> handleManualMoved(event.args)
            "TELEM" -> handleManualTelemetry(event)
            "BOARD" -> handleManualBoard(event)
            "ERR" -> if (manualPending != ManualPending.NONE || event.args.joinToString(" ").contains("CALIBRATE")) {
                manualPending = ManualPending.NONE
                manualPendingSelection = null
                manualStatus = "Board rejected the operation: ${event.args.joinToString(" ")}"
            }
        }
        if (currentTab == TAB_MOVE) manualUpdater?.invoke()
    }

    private fun handleManualMoved(args: List<String>) {
        when (args.firstOrNull()) {
            "HEAD" -> if (manualPending == ManualPending.HEAD) {
                manualStatus = "Head stopped at ${args.getOrNull(1) ?: "unknown"}; verifying telemetry."
                repository.enqueueRequests("TELEM")
            }
            "PIECE" -> if (manualPending == ManualPending.PIECE) {
                manualStatus = "Piece movement finished; verifying both sensors and head position."
                repository.enqueueRequests("TELEM", "BOARD")
            }
        }
    }

    private fun handleManualTelemetry(event: BoardEvent) {
        val telemetry = runCatching { Protocol.parseTelemetry(event) }.getOrNull() ?: return
        when (manualPending) {
            ManualPending.CALIBRATION -> {
                manualCalibrationVerified = ManualVerification.calibrationMatches(calibrationReportedSquare, telemetry)
                manualStatus = if (manualCalibrationVerified) {
                    "Calibration verified: board and app agree the head is at e6, homed, with magnet off."
                } else "Calibration report disagrees with telemetry; do not move."
                manualPending = ManualPending.NONE
                manualPendingSelection = null
            }
            ManualPending.HEAD -> {
                val target = manualPendingSelection?.target
                if (target == null) {
                    manualStatus = "The requested head destination was lost; recalibrate before another move."
                    manualCalibrationVerified = false
                    manualPending = ManualPending.NONE
                    manualPendingSelection = null
                    return
                }
                val verified = ManualVerification.headMoveMatches(target, telemetry)
                manualStatus = if (verified) "Head position verified at ${ManualSelection.squareName(target)}; magnet remained off."
                else "Head position could not be verified; recalibrate before another move."
                if (!verified) manualCalibrationVerified = false
                manualPending = ManualPending.NONE
                manualPendingSelection = null
            }
            ManualPending.PIECE -> {
                val target = manualPendingSelection?.target
                if (target == null) {
                    manualStatus = "The requested piece destination was lost; inspect and recalibrate."
                    manualCalibrationVerified = false
                    manualPending = ManualPending.NONE
                    manualPendingSelection = null
                    return
                }
                if (!ManualVerification.headMoveMatches(target, telemetry)) {
                    manualStatus = "Head telemetry disagrees with the requested destination; inspect and recalibrate."
                    manualCalibrationVerified = false
                    manualPending = ManualPending.NONE
                    manualPendingSelection = null
                } else manualStatus = "Head is at ${ManualSelection.squareName(target)}; checking piece sensors."
            }
            else -> Unit
        }
    }

    private fun handleManualBoard(event: BoardEvent) {
        if (manualPending != ManualPending.PIECE || event.args.isEmpty()) return
        val sensors = runCatching { Protocol.parseBoardHex(event.args[0]) }.getOrNull() ?: return
        val pendingSelection = manualPendingSelection
        val source = pendingSelection?.source
        val target = pendingSelection?.target
        if (source == null || target == null) {
            manualStatus = "The requested piece squares were lost; inspect the board before continuing."
            manualCalibrationVerified = false
            manualPending = ManualPending.NONE
            manualPendingSelection = null
            return
        }
        val verified = ManualVerification.pieceMoveMatches(source, target, sensors)
        manualStatus = if (verified) "Piece verified at ${ManualSelection.squareName(target)}; source is clear."
        else "Sensors do not confirm the piece move; inspect the board before continuing."
        manualPending = ManualPending.NONE
        manualPendingSelection = null
        if (verified) manualSelection = ManualSelection(pendingSelection.mode)
    }

    private fun invalidateManualCalibration(message: String) {
        manualCalibrationVerified = false
        manualPending = ManualPending.NONE
        manualPendingSelection = null
        manualSelection = ManualSelection(manualSelection.mode)
        manualStatus = message
    }

    override fun onTimelineChanged(entries: List<TimelineEntry>) {
        timeline = entries
        if (currentTab == TAB_DEVELOPER) updateDeveloperLog()
    }

    private fun onGameChanged(snapshot: GameSnapshot) {
        gameState = snapshot
        repository.setExpectedSquares(snapshot.expectedSquares)
        if (snapshot.status.startsWith("Collision-safe route stopped:") &&
            snapshot.status != lastRouteFailureDialog
        ) {
            lastRouteFailureDialog = snapshot.status
            alert("Collision-safe route stopped", snapshot.status.removePrefix("Collision-safe route stopped: "))
        } else if (!snapshot.status.startsWith("Collision-safe route stopped:")) {
            lastRouteFailureDialog = ""
        }
        if (currentTab == TAB_PLAY) playUpdater?.invoke()
        else if (currentTab == TAB_BOARD) monitorUpdater?.invoke()
        else if (currentTab == TAB_MOVE) manualUpdater?.invoke()
    }

    private fun showConnectionMenu() {
        if (monitorState.connected) {
            AlertDialog.Builder(this).setTitle("Board connection").setMessage(monitorState.connectionText)
                .setPositiveButton("Disconnect") { _, _ -> repository.disconnect() }
                .setNegativeButton("Close", null).show()
            return
        }
        val savedAddress = prefs.getString("ble_address", null)
        val choices = buildList {
            add("Scan for Bluetooth board")
            if (savedAddress != null) add("Reconnect saved board")
            add("Use safe simulator")
        }
        AlertDialog.Builder(this).setTitle("Connect").setItems(choices.toTypedArray()) { _, which ->
            when (choices[which]) {
                "Scan for Bluetooth board" -> requestBleScan()
                "Reconnect saved board" -> connectBle(prefs.getString("ble_name", "HC-08")!!, savedAddress!!)
                else -> {
                    simulatorActive = true
                    repository.useTransport(SimulatorTransport(repository))
                }
            }
        }.show()
    }

    private fun requestBleScan() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            alert("Bluetooth unavailable", "This device has no Bluetooth Low Energy radio. Simulator mode remains available.")
            return
        }
        val needed = if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestPermissions(needed, REQ_BLE)
        else scanBle()
    }

    private fun scanBle() {
        val progress = AlertDialog.Builder(this)
            .setTitle("Bluetooth scan")
            .setMessage("Looking for HC-08 and other BLE devices…")
            .setView(ProgressBar(this).apply { isIndeterminate = true })
            .setNegativeButton("Cancel") { _, _ -> stopScan?.invoke() }
            .create()
        progress.show()
        stopScan = BleBoardTransport.scan(this) { result ->
            if (isFinishing || isDestroyed) return@scan
            progress.dismiss(); stopScan = null
            result.onSuccess(::showBleDevices).onFailure { alert("Bluetooth scan failed", it.message ?: it.toString()) }
        }
        progress.setOnCancelListener { stopScan?.invoke() }
    }

    private fun showBleDevices(devices: List<BleDevice>) {
        if (devices.isEmpty()) { alert("No BLE devices", "Move closer to the powered HC-08, check Bluetooth, then scan again."); return }
        val ranked = devices.sortedWith(compareByDescending<BleDevice> { it.name.contains("HC-08", true) || it.name.contains("HMSoft", true) }.thenByDescending { it.rssi })
        val visible = ranked.take(8)
        val labels = visible.map { "${it.name}   ${it.rssi} dBm\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose board (${devices.size} found)").setItems(labels) { _, index ->
            visible[index].let { connectBle(it.name, it.address) }
        }.setNegativeButton("Rescan") { _, _ -> scanBle() }.show()
    }

    private fun connectBle(name: String, address: String) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            alert("Bluetooth unavailable", "This device has no Bluetooth Low Energy radio. Simulator mode remains available.")
            return
        }
        simulatorActive = false
        prefs.edit().putString("ble_name", name).putString("ble_address", address).apply()
        repository.useTransport(BleBoardTransport(this, address, name, repository))
    }

    private fun confirmEmergencyHalt() {
        AlertDialog.Builder(this).setTitle("Send remote halt?")
            .setMessage("This best-effort radio command marks carriage position unknown. If motion continues, cut physical power.")
            .setPositiveButton("SEND HALT") { _, _ -> repository.emergencyHalt().onFailure { toast(it.message ?: "Halt failed") } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmStartGame() {
        if (!monitorState.connected) { toast("Connect to the board first"); return }
        if (!sensorFrameReady()) return
        AlertDialog.Builder(this).setTitle("Calibration can move the carriage")
            .setMessage("Confirm the complete board is clear, both limits were tested locally, live state is current, and physical power cutoff is accessible.")
            .setPositiveButton("Start calibration") { _, _ ->
                val white = prefs.getBoolean("human_white", true)
                game.start(white).onFailure { alert("Could not start", it.message ?: it.toString()) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun manualCapabilityReady(): Boolean {
        if (!monitorState.connected) { toast("Connect to the board first"); return false }
        val capabilities = monitorState.firmware?.capabilities.orEmpty()
        if (!capabilities.containsAll(setOf("MANUAL", "CALIBRATE"))) {
            alert("Firmware update required", "Install firmware 3.31 or newer to use in-app calibration and square movement.")
            return false
        }
        if (!sensorFrameReady()) return false
        if (gameState.active) { alert("Game active", "Stop the game session before manual head or piece movement."); return false }
        if (monitorState.telemetry?.motionFault == true) {
            alert("Motion fault", "A fault must be inspected and recovered locally before remote calibration or movement.")
            return false
        }
        return true
    }

    private fun sensorFrameReady(): Boolean {
        if ("SENSORFRAME" in monitorState.firmware?.capabilities.orEmpty()) return true
        alert("Firmware update required", "Install firmware 3.31 or newer so reed sensors and carriage coordinates agree.")
        return false
    }

    private fun confirmManualCalibration() {
        if (manualPending != ManualPending.NONE) {
            manualStatus = "An operation is already in progress; wait for verification."
            manualUpdater?.invoke()
            return
        }
        if (!manualCapabilityReady()) return
        AlertDialog.Builder(this).setTitle("Calibrate carriage from app?")
            .setMessage("Calibration moves the head to its limit references and parks at e6. Clear the mechanism, keep physical power cutoff accessible, and watch the board throughout.")
            .setPositiveButton("Calibrate") { _, _ ->
                manualCalibrationVerified = false
                manualPending = ManualPending.CALIBRATION
                manualPendingSelection = null
                calibrationReportedSquare = null
                manualStatus = "Calibration command sent; keep hands clear."
                repository.sendCommand("CALIBRATE").onFailure {
                    manualPending = ManualPending.NONE
                    manualPendingSelection = null
                    manualStatus = it.message ?: "Calibration send failed"
                }
                manualUpdater?.invoke()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmManualMove() {
        if (manualPending != ManualPending.NONE) {
            manualStatus = "An operation is already in progress; wait for verification."
            manualUpdater?.invoke()
            return
        }
        if (!manualCapabilityReady()) return
        if (!manualCalibrationVerified) { alert("Calibrate first", "Use Calibrate on this page and wait for the e6 telemetry check to pass."); return }
        val telemetry = monitorState.telemetry
        if (telemetry?.homed != true || telemetry.motionFault || telemetry.magnetOn) {
            manualCalibrationVerified = false
            alert("Board not ready", "Fresh telemetry no longer confirms a homed, fault-free carriage with the magnet off. Calibrate again.")
            return
        }
        val command = manualSelection.command()
        if (command == null) { toast("Select the required square or squares first"); return }
        val sensorAge = monitorState.sensorUpdatedMs?.let { System.currentTimeMillis() - it }
        if (manualSelection.mode == ManualMoveMode.MOVE_PIECE && (sensorAge == null || sensorAge > 5_000)) {
            repository.enqueueRequests("BOARD")
            toast("Refreshing piece sensors; verify the selection and tap Move again")
            return
        }
        val source = manualSelection.source
        val target = manualSelection.target
        if (manualSelection.mode == ManualMoveMode.MOVE_PIECE) {
            if (source == null || target == null) {
                toast("Select an occupied source and empty destination first")
                return
            }
            val occupied = monitorState.sensorSquares.orEmpty()
            if (source !in occupied || target in occupied) {
                alert("Sensor check changed", "The source must contain a piece and the destination must be empty. Refresh or choose again.")
                return
            }
        }
        val requestedSelection = manualSelection
        val description = if (requestedSelection.mode == ManualMoveMode.HEAD_ONLY) {
            val requestedTarget = target ?: run { toast("Select a destination first"); return }
            "Move the head to ${ManualSelection.squareName(requestedTarget)} with the electromagnet OFF?"
        } else {
            "Move the piece ${ManualSelection.squareName(checkNotNull(source))} to ${ManualSelection.squareName(checkNotNull(target))}? The magnet will energize only after the head reaches the occupied source."
        }
        AlertDialog.Builder(this).setTitle("Confirm physical movement").setMessage(description)
            .setPositiveButton("Move") { _, _ ->
                if (manualPending != ManualPending.NONE) return@setPositiveButton
                manualPending = if (requestedSelection.mode == ManualMoveMode.HEAD_ONLY) ManualPending.HEAD else ManualPending.PIECE
                manualPendingSelection = requestedSelection
                manualStatus = "Movement command sent; keep hands clear."
                repository.sendCommand(command).onFailure {
                    manualPending = ManualPending.NONE
                    manualPendingSelection = null
                    manualStatus = it.message ?: "Movement send failed"
                }
                manualUpdater?.invoke()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun runDiagnostics() {
        diagnosticsRunner.run { results -> diagnostics = results; if (currentTab == TAB_CHECKS) selectTab(TAB_CHECKS) }
    }

    private fun copyDiagnostics() {
        val body = buildString {
            appendLine("Automatic Chessboard Mobile ${BuildConfig.VERSION_NAME}")
            diagnostics.forEach { appendLine("${it.label}: ${it.result} — ${it.detail}") }
        }
        (getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("diagnostics", body))
        toast("Diagnostic summary copied")
    }

    private fun createPgn() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/x-chess-pgn"; putExtra(Intent.EXTRA_TITLE, "automatic-chessboard-${stamp()}.pgn"); addCategory(Intent.CATEGORY_OPENABLE)
        }, REQ_PGN)
    }

    private fun createSupportBundle() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/zip"; putExtra(Intent.EXTRA_TITLE, "chessboard-support-${stamp()}.zip"); addCategory(Intent.CATEGORY_OPENABLE)
        }, REQ_SUPPORT)
    }

    private fun startCamera(source: String) {
        cameraSource = source.trim().ifBlank { "0" }
        if (cameraSource.matches(Regex("\\d+")) && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA); return
        }
        if (cameraSource.matches(Regex("\\d+"))) prefs.edit().putString("camera_source", cameraSource).apply()
        cameraController?.start(cameraSource)
    }

    private fun createSnapshot() {
        val bitmap = cameraController?.snapshot()
        if (bitmap == null) { toast("Start the camera first"); return }
        pendingSnapshot = bitmap
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "image/jpeg"; putExtra(Intent.EXTRA_TITLE, "chessboard-${stamp(true)}.jpg"); addCategory(Intent.CATEGORY_OPENABLE)
        }, REQ_SNAPSHOT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            if (resultCode != RESULT_OK) return
            val uri = data?.data ?: return
            runCatching {
                when (requestCode) {
                    REQ_PGN -> contentResolver.openOutputStream(uri, "w")!!.use { it.write(game.pgn().toByteArray()) }
                    REQ_SUPPORT -> SupportBundle.write(this, uri, recorder, monitorState, settingsSnapshot(), diagnostics)
                    REQ_SNAPSHOT -> contentResolver.openOutputStream(uri, "w")!!.use {
                        checkNotNull(pendingSnapshot) { "Snapshot is no longer available" }
                            .compress(Bitmap.CompressFormat.JPEG, 94, it)
                    }
                }
            }.onSuccess { toast("Saved") }.onFailure { alert("Save failed", it.message ?: it.toString()) }
        } finally {
            if (requestCode == REQ_SNAPSHOT) pendingSnapshot = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (requestCode == REQ_BLE) scanBle() else if (requestCode == REQ_CAMERA) startCamera(cameraSource)
        } else {
            AlertDialog.Builder(this).setTitle("Permission required")
                .setMessage("This feature cannot work without the requested Android permission.")
                .setPositiveButton("App settings") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun sendDeveloperCommand() {
        val command = devCommand.trim()
        val risk = Protocol.classifyCommand(command)
        if (risk == CommandRisk.UNKNOWN && !(simulatorActive && command.uppercase().startsWith("SIMMOVE "))) {
            alert("Unknown command blocked", "Only commands documented by the firmware protocol may be sent."); return
        }
        if (risk == CommandRisk.EMERGENCY) {
            confirmEmergencyHalt()
        } else if (risk == CommandRisk.MOTION) {
            if (!devUnlock) { alert("Motion commands locked", "Enable Unlock motion first."); return }
            AlertDialog.Builder(this).setTitle("Send motion-capable command?").setMessage("Confirm the board is clear and physical power cutoff is accessible.")
                .setPositiveButton("Send") { _, _ -> repository.sendCommand(command).onFailure { toast(it.message ?: "Send failed") } }
                .setNegativeButton("Cancel", null).show()
        } else repository.sendCommand(command).onFailure { toast(it.message ?: "Send failed") }
    }

    private fun updateDeveloperLog() {
        val pageSize = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 10
        val pages = maxOf(1, (timeline.size + pageSize - 1) / pageSize)
        devPage = devPage.coerceIn(0, pages - 1)
        val fromEnd = devPage * pageSize
        val page = timeline.asReversed().drop(fromEnd).take(pageSize).reversed()
        val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        devLog?.text = page.joinToString("\n") { "${format.format(Date(it.timeMs))} ${it.direction.padEnd(4)} ${it.event.padEnd(10)} ${it.detail}" }
            .ifBlank { "No protocol events yet" }
        devPageLabel?.text = "Page ${devPage + 1}/$pages • newest first"
    }

    private fun commandRiskText(): String = when (Protocol.classifyCommand(devCommand)) {
        CommandRisk.READ_ONLY -> "Read-only: safe diagnostics"
        CommandRisk.CONTROL -> "Control: changes session state"
        CommandRisk.MOTION -> "MOTION RISK: calibration or carriage movement"
        CommandRisk.EMERGENCY -> "Emergency halt request"
        CommandRisk.UNKNOWN -> if (simulatorActive && devCommand.uppercase().startsWith("SIMMOVE ")) "Simulator-only human move" else "Unknown: blocked"
    }

    private fun historyPageText(): String {
        val pairs = gameState.history.chunked(2).mapIndexed { index, values -> "${index + 1}. ${values.joinToString("  ")}" }
        val pageSize = 5
        val pages = maxOf(1, (pairs.size + pageSize - 1) / pageSize)
        historyPage = historyPage.coerceIn(0, pages - 1)
        return pairs.drop(historyPage * pageSize).take(pageSize).joinToString("\n").ifBlank { "No moves yet" }
    }

    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("Automatic Chessboard ${BuildConfig.VERSION_NAME}")
            .setMessage("Bluetooth monitoring, Stockfish play, safe diagnostics, local/network camera, simulator, structured logs, support bundles, PGN export, and guarded developer controls.\n\nRadio halt and camera are not safety interlocks. Keep physical motor/magnet power isolation accessible. GPL-3.0-or-later; Stockfish 18 is GPLv3; chesslib 1.3.7 is Apache-2.0.")
            .setPositiveButton("Close", null)
            .setNeutralButton("License notices") { _, _ ->
                val notices = runCatching {
                    assets.open("THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() }
                }.getOrDefault("Third-party notices are unavailable in this build.")
                AlertDialog.Builder(this).setTitle("Third-party notices")
                    .setMessage(notices).setPositiveButton("Close", null).show()
            }.show()
    }

    private fun adaptive(primary: View, secondary: View, primaryWeight: Float): View {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            addView(primary, LinearLayout.LayoutParams(0.takeIf { landscape } ?: ViewGroup.LayoutParams.MATCH_PARENT,
                if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 0, primaryWeight))
            addView(secondary, LinearLayout.LayoutParams(if (landscape) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                if (landscape) ViewGroup.LayoutParams.MATCH_PARENT else 0, 1f - primaryWeight).apply {
                if (landscape) marginStart = dp(8) else topMargin = dp(6)
            })
        }
    }

    private fun sliderRow(label: String, min: Int, max: Int, initial: Int, changed: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val value = text("$label: $initial", 12f, Color.WHITE).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(value, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(SeekBar(this).apply {
            this.min = min; this.max = max; progress = initial.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { value.text = "$label: $progress"; if (fromUser) changed(progress) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return row
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; setTextColor(color); textSize = sizeSp; gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun button(label: String, color: Int = Color.rgb(39, 57, 76), click: () -> Unit) = Button(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 12f; isAllCaps = false; background = rounded(color)
        setPadding(dp(4), 0, dp(4), 0); minHeight = 0; minWidth = 0; setOnClickListener { click() }
    }

    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(9).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun released(value: Boolean) = if (value) "released" else "ACTIVE"
    private fun fileRank(x: Int, y: Int) = if (x in 1..8 && y in 1..8) "${('a'.code + x - 1).toChar()}$y" else "unknown"
    private fun trolleyPosition(state: MonitorState = monitorState): Pair<Int, Int>? =
        ManualVerification.trustedPosition(state.telemetry)
    private fun stamp(seconds: Boolean = false) = SimpleDateFormat(if (seconds) "yyyyMMdd-HHmmss" else "yyyyMMdd-HHmm", Locale.US).format(Date())
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun alert(title: String, message: String) = AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()

    private fun settingsSnapshot(): Map<String, Any?> = mapOf(
        "ble_name" to prefs.getString("ble_name", ""), "ble_address" to prefs.getString("ble_address", ""),
        "elo" to game.elo, "think_ms" to game.thinkMillis, "human_white" to gameState.humanWhite,
        "route_ms" to game.routeTimeMillis, "route_temporary_pieces" to game.routeMaxTemporaryPieces,
        "camera_source" to if (cameraSource.contains("://")) "<network-camera-url-redacted>" else cameraSource,
    )

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(ageRefreshRunnable)
        ui.post(ageRefreshRunnable)
    }

    override fun onPause() {
        ui.removeCallbacks(ageRefreshRunnable)
        super.onPause()
    }

    private fun closeCamera() { cameraController?.close(); cameraController = null }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        selectTab(currentTab)
    }

    override fun onDestroy() {
        stopScan?.invoke(); closeCamera(); ui.removeCallbacksAndMessages(null)
        diagnosticsRunner.close()
        repository.removeObserver(this); game.close(); repository.close()
        recorder.record("app", "session_closed")
        recorder.close()
        super.onDestroy()
    }

    companion object {
        private const val NAVY = 0xff0b1320.toInt()
        private const val SURFACE = 0xff18273a.toInt()
        private const val MUTED = 0xffa9b6c5.toInt()
        private const val GOOD = 0xff43c995.toInt()
        private const val WARN = 0xffffb84d.toInt()
        private const val DANGER = 0xffdb3e4d.toInt()
        private const val ACCENT_DARK = 0xff167c70.toInt()
        private const val TAB_BOARD = 0
        private const val TAB_PLAY = 1
        private const val TAB_MOVE = 2
        private const val TAB_CHECKS = 3
        private const val TAB_CAMERA = 4
        private const val TAB_DEVELOPER = 5
        private val TAB_LABELS = listOf(
            TAB_BOARD to "Board",
            TAB_PLAY to "Play",
            TAB_MOVE to "Move",
            TAB_CHECKS to "Checks",
            TAB_CAMERA to "Cam",
            TAB_DEVELOPER to "Dev",
        )
        private const val REQ_BLE = 100
        private const val REQ_CAMERA = 101
        private const val REQ_PGN = 201
        private const val REQ_SUPPORT = 202
        private const val REQ_SNAPSHOT = 203
    }

    private data class SideSelector(val view: View, val white: Button, val black: Button)
}
