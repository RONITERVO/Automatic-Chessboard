package org.openautomaticchessboard.mobile.domain

import android.os.Handler
import android.os.Looper
import org.openautomaticchessboard.mobile.protocol.BoardEvent
import org.openautomaticchessboard.mobile.protocol.CommandRisk
import org.openautomaticchessboard.mobile.protocol.Protocol
import org.openautomaticchessboard.mobile.transport.BoardTransport
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Single source of truth for connection, protocol, polling, and safety state. */
class BoardRepository(private val recorder: EventRecorder) : BoardTransport.Listener, AutoCloseable {
    private data class PendingRequest(val expected: String, val command: String, val startedMs: Long)

    interface Observer {
        fun onBoardState(state: MonitorState)
        fun onBoardEvent(event: BoardEvent) {}
        fun onTimelineChanged(entries: List<TimelineEntry>) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "board-poller").apply { isDaemon = true }
    }
    private val observers = linkedSetOf<Observer>()
    private val requestQueue = ArrayDeque<String>()
    private val timeline = ArrayDeque<TimelineEntry>()
    @Volatile private var transport: BoardTransport? = null
    @Volatile private var pending: PendingRequest? = null
    private var pollFuture: ScheduledFuture<*>? = null
    private var pollBoardNext = false
    private var lastPeriodicPollMs = 0L
    @Volatile var state = MonitorState()
        private set

    fun addObserver(observer: Observer) {
        observers += observer
        observer.onBoardState(state)
        observer.onTimelineChanged(timeline.toList())
    }

    fun removeObserver(observer: Observer) { observers -= observer }

    @Synchronized
    fun useTransport(next: BoardTransport) {
        transport?.close()
        transport = next
        pending = null
        requestQueue.clear()
        state = state.copy(connected = false, connectionText = "Connecting…", lastError = "")
        publishState()
        next.start()
        if (pollFuture == null) {
            pollFuture = scheduler.scheduleWithFixedDelay(::pollTick, 500, 500, TimeUnit.MILLISECONDS)
        }
    }

    fun disconnect() {
        transport?.close()
        transport = null
        pending = null
        state = state.copy(connected = false, connectionText = "Disconnected", motionExpected = false)
        publishState()
    }

    fun safeRefresh() = enqueueRequests("PING", "INFO", "TELEM", "BOARD")

    @Synchronized
    fun enqueueRequests(vararg commands: String) {
        commands.map(String::trim).filter(String::isNotEmpty).forEach { command ->
            if (Protocol.classifyCommand(command) == CommandRisk.READ_ONLY &&
                command != pending?.command && command !in requestQueue
            ) requestQueue.add(command)
        }
        pollTick()
    }

    fun setExpectedSquares(squares: Set<Int>) {
        state = state.copy(expectedSquares = squares)
        publishState()
    }

    fun sendCommand(command: String): Result<Unit> = runCatching {
        val active = transport ?: error("Connect to the board first")
        check(active.isConnected) { "Board connection is not ready" }
        if (Protocol.classifyCommand(command) == CommandRisk.READ_ONLY) {
            enqueueRequests(command)
            return@runCatching
        }
        active.send(command)
        recorder.record("protocol_tx", command)
        addTimeline("TX", command.substringBefore(' ').uppercase(), command.substringAfter(' ', ""))
        if (Protocol.classifyCommand(command) == CommandRisk.MOTION) {
            state = state.copy(motionExpected = true)
            publishState()
        }
    }.onFailure { error ->
        state = state.copy(lastError = error.message ?: error.javaClass.simpleName)
        recorder.record("error", "send_failed", mapOf("command" to command, "error" to error.toString()))
        publishState()
    }

    fun emergencyHalt(): Result<Unit> {
        val result = sendCommand("!")
        state = state.copy(motionExpected = false)
        recorder.record("safety", "remote_halt_sent")
        publishState()
        return result
    }

    @Synchronized
    private fun pollTick() {
        val active = transport ?: return
        if (!active.isConnected || state.motionExpected) return
        pending?.let { entry ->
            if (System.currentTimeMillis() - entry.startedMs < 4_000) return
            recorder.record("monitor", "poll_timeout", mapOf(
                "command" to entry.command, "expected" to entry.expected,
            ))
            pending = null
        }
        if (pending != null) return
        val queued = if (requestQueue.isEmpty()) null else requestQueue.removeFirst()
        val now = System.currentTimeMillis()
        if (queued == null && now - lastPeriodicPollMs < PERIODIC_POLL_INTERVAL_MS) return
        val capabilities = state.firmware?.capabilities.orEmpty()
        val command = queued ?: when {
            capabilities.isEmpty() -> "INFO"
            pollBoardNext && "BOARD" in capabilities -> "BOARD"
            "TELEM" in capabilities -> "TELEM"
            "BOARD" in capabilities -> "BOARD"
            else -> "STATUS"
        }
        pollBoardNext = !pollBoardNext
        try {
            active.send(command)
            pending = PendingRequest(expectedResponse(command), command, System.currentTimeMillis())
            lastPeriodicPollMs = now
            recorder.record("protocol_tx", command)
            addTimeline("TX", command, "safe poll")
        } catch (error: Exception) {
            state = state.copy(lastError = error.message ?: "Poll send failed")
            publishState()
        }
    }

    private fun expectedResponse(command: String): String = when (command) {
        "PING", "HELLO" -> "PONG"
        "BTTEST" -> "BT"
        else -> command.substringBefore(' ').uppercase()
    }

    override fun onStatus(status: String, connected: Boolean) {
        main.post {
            if (!connected) {
                pending = null
                requestQueue.clear()
            }
            state = state.copy(connected = connected, connectionText = status)
            recorder.record("transport", status)
            addTimeline("LINK", if (connected) "Connected" else "Status", status)
            if (connected) enqueueRequests("PING", "INFO", "TELEM", "BOARD")
            publishState()
        }
    }

    override fun onLine(line: String) {
        main.post {
            recorder.record("protocol_rx", line)
            val event = Protocol.parseEvent(line)
            addTimeline("RX", event.kind, event.args.joinToString(" "))
            if (pending?.expected == event.kind) pending = null
            var next = state.copy(connected = true, lastSeenMs = System.currentTimeMillis())
            try {
                next = when (event.kind) {
                "INFO" -> next.copy(firmware = Protocol.parseInfo(event))
                "TELEM" -> {
                    val telemetry = Protocol.parseTelemetry(event)
                    next.copy(telemetry = telemetry, motionExpected = telemetry.sequence in setOf(3, 8, 19))
                }
                "BOARD" -> if (event.args.isNotEmpty()) next.copy(
                    sensorHex = event.args[0],
                    sensorSquares = Protocol.parseBoardHex(event.args[0]),
                    sensorUpdatedMs = System.currentTimeMillis(),
                ) else next
                "READY", "PONG" -> next.copy(connectionText = "Board connected and responding")
                "SETUP", "DONE", "ESTOP", "ERR", "STOPPED" -> next.copy(motionExpected = false)
                "MOVING" -> next.copy(motionExpected = true)
                else -> next
                }
            } catch (error: Exception) {
                next = next.copy(lastError = error.message ?: "Malformed board response")
                recorder.record("protocol", "parse_error", mapOf("line" to line, "error" to error.toString()))
            }
            if (event.kind == "ERR") next = next.copy(lastError = event.args.joinToString(" "))
            state = next
            publishState()
            observers.toList().forEach { it.onBoardEvent(event) }
            pollTick()
        }
    }

    private fun addTimeline(direction: String, event: String, detail: String) {
        timeline.addLast(TimelineEntry(System.currentTimeMillis(), direction, event, detail))
        while (timeline.size > 500) timeline.removeFirst()
        val snapshot = timeline.toList()
        main.post { observers.toList().forEach { it.onTimelineChanged(snapshot) } }
    }

    private fun publishState() = main.post {
        val snapshot = state
        observers.toList().forEach { it.onBoardState(snapshot) }
    }

    override fun close() {
        disconnect()
        pollFuture?.cancel(true)
        scheduler.shutdownNow()
        observers.clear()
    }

    companion object {
        const val PERIODIC_POLL_INTERVAL_MS = 2_000L
    }
}
