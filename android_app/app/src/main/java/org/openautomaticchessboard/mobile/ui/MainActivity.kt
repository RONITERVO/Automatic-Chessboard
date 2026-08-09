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
    private var currentTab = 0
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
        game = GameController(engine, repository::sendCommand, ::onGameChanged) { reported, choose ->
            AlertDialog.Builder(this).setTitle("Promotion")
                .setItems(arrayOf("Queen", "Rook", "Bishop", "Knight")) { _, index ->
                    choose(charArrayOf('q', 'r', 'b', 'n')[index])
                }.setCancelable(false).show()
        }
        game.elo = prefs.getInt("elo", 2000)
        game.thinkMillis = prefs.getLong("think_ms", 800)
        gameState = game.snapshot
        game.chooseHumanSide(prefs.getBoolean("human_white", true))
        diagnosticsRunner = DiagnosticsRunner(this, repository, engine)
        buildShell()
        repository.addObserver(this)
        selectTab(0)
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
        listOf("Board", "Play", "Checks", "Camera", "Dev").forEachIndexed { index, label ->
            nav.addView(button(label, SURFACE) { selectTab(index) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                if (index > 0) marginStart = dp(3)
            })
        }
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        setContentView(root)
    }

    private fun selectTab(index: Int) {
        if (currentTab == 3 && index != 3) closeCamera()
        monitorUpdater = null
        playUpdater = null
        currentTab = index
        content.removeAllViews()
        val page = when (index) {
            0 -> buildMonitor()
            1 -> buildPlay()
            2 -> buildDiagnostics()
            3 -> buildCamera()
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
            board.trolley = state.telemetry?.let { it.trolleyX to it.trolleyY }
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
            trolley = monitorState.telemetry?.let { it.trolleyX to it.trolleyY }
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = text(gameState.status, if (landscape) 12f else 15f, Color.WHITE, true).apply { maxLines = 2 }
        controls.addView(status,
            if (landscape) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, .8f))
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
            board.trolley = monitorState.telemetry?.let { it.trolleyX to it.trolleyY }
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
    }

    private fun buildHistoryPager(update: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(button("◀ moves") { historyPage = (historyPage - 1).coerceAtLeast(0); update() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(button("moves ▶") { historyPage++; update() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(4) })
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
        monitorState = state
        connectionBadge.text = if (state.connected) "CONNECTED • ${state.health().first}" else "DISCONNECTED • ${state.connectionText}"
        connectionBadge.setTextColor(if (state.connected) GOOD else DANGER)
        if (currentTab == 0) monitorUpdater?.invoke()
        if (currentTab == 1) playUpdater?.invoke()
    }

    override fun onBoardEvent(event: BoardEvent) { game.handle(event) }

    override fun onTimelineChanged(entries: List<TimelineEntry>) {
        timeline = entries
        if (currentTab == 4) updateDeveloperLog()
    }

    private fun onGameChanged(snapshot: GameSnapshot) {
        gameState = snapshot
        repository.setExpectedSquares(snapshot.expectedSquares)
        if (currentTab == 1) playUpdater?.invoke()
        else if (currentTab == 0) monitorUpdater?.invoke()
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
        AlertDialog.Builder(this).setTitle("Calibration can move the carriage")
            .setMessage("Confirm the complete board is clear, both limits were tested locally, live state is current, and physical power cutoff is accessible.")
            .setPositiveButton("Start calibration") { _, _ ->
                val white = prefs.getBoolean("human_white", true)
                game.start(white).onFailure { alert("Could not start", it.message ?: it.toString()) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun runDiagnostics() {
        diagnosticsRunner.run { results -> diagnostics = results; if (currentTab == 2) selectTab(2) }
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
    private fun fileRank(x: Int, y: Int) = if (x in 0..7 && y in 0..7) "${('a'.code + x).toChar()}${y + 1}" else "unknown"
    private fun stamp(seconds: Boolean = false) = SimpleDateFormat(if (seconds) "yyyyMMdd-HHmmss" else "yyyyMMdd-HHmm", Locale.US).format(Date())
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun alert(title: String, message: String) = AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()

    private fun settingsSnapshot(): Map<String, Any?> = mapOf(
        "ble_name" to prefs.getString("ble_name", ""), "ble_address" to prefs.getString("ble_address", ""),
        "elo" to game.elo, "think_ms" to game.thinkMillis, "human_white" to gameState.humanWhite,
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
        repository.removeObserver(this); repository.close(); game.close()
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
        private const val REQ_BLE = 100
        private const val REQ_CAMERA = 101
        private const val REQ_PGN = 201
        private const val REQ_SUPPORT = 202
        private const val REQ_SNAPSHOT = 203
    }

    private data class SideSelector(val view: View, val white: Button, val black: Button)
}
