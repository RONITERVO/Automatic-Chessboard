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
    private val stateLock = Object()
    private val observers = linkedSetOf<Observer>()
    private val requestQueue = ArrayDeque<String>()
    private val timeline = ArrayDeque<TimelineEntry>()
    private val responseCounts = mutableMapOf<String, Int>()
    @Volatile private var transport: BoardTransport? = null
    private var pending: PendingRequest? = null
    private var pollFuture: ScheduledFuture<*>? = null
    private var pollBoardNext = false
    private var lastPeriodicPollMs = 0L
    private var motionStartedMs: Long? = null
    @Volatile var state = MonitorState()
        private set

    fun addObserver(observer: Observer) {
        val timelineSnapshot = synchronized(stateLock) {
            observers += observer
            timeline.toList()
        }
        observer.onBoardState(state)
        observer.onTimelineChanged(timelineSnapshot)
    }

    fun removeObserver(observer: Observer) = synchronized(stateLock) { observers -= observer }

    fun useTransport(next: BoardTransport) {
        transport?.close()
        synchronized(stateLock) {
            transport = next
            pending = null
            requestQueue.clear()
            responseCounts.clear()
            motionStartedMs = null
            state = state.copy(connected = false, connectionText = "Connectingâ€¦", lastError = "")
            stateLock.notifyAll()
            if (pollFuture == null) {
                pollFuture = scheduler.scheduleWithFixedDelay(::pollTick, 500, 500, TimeUnit.MILLISECONDS)
            }
        }
        publishState()
        next.start()
    }

    fun disconnect() {
        transport?.close()
        synchronized(stateLock) {
            transport = null
            pending = null
            requestQueue.clear()
            motionStartedMs = null
            state = state.copy(connected = false, connectionText = "Disconnected", motionExpected = false)
            stateLock.notifyAll()
        }
        publishState()
    }

    fun safeRefresh() = enqueueRequests(*SAFE_REFRESH_COMMANDS)

    /** Blocks a worker thread until this refresh batch completes or its deadline expires. */
    fun safeRefreshAndWait(timeoutMs: Long = SAFE_REFRESH_TIMEOUT_MS): Boolean {
        val expected = SAFE_REFRESH_COMMANDS.map(::expectedResponse).toSet()
        val baseline = synchronized(stateLock) {
            if (!state.connected) return false
            expected.associateWith { responseCounts[it] ?: 0 }
        }
        enqueueRequests(*SAFE_REFRESH_COMMANDS)
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(stateLock) {
            while (baseline.any { (kind, count) -> (responseCounts[kind] ?: 0) <= count }) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                try {
                    stateLock.wait(remaining.coerceAtMost(250))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    fun enqueueRequests(vararg commands: String) {
        synchronized(stateLock) {
            commands.map(String::trim).filter(String::isNotEmpty).forEach { command ->
                if (Protocol.classifyCommand(command) == CommandRisk.READ_ONLY &&
                    command != pending?.command && command !in requestQueue
                ) requestQueue.add(command)
            }
        }
        pollTick()
    }

    fun setExpectedSquares(squares: Set<Int>) {
        synchronized(stateLock) { state = state.copy(expectedSquares = squares) }
        publishState()
    }

    fun sendCommand(command: String): Result<Unit> = runCatching {
        val active = transport ?: error("Connect to the board first")
        check(active.isConnected) { "Board connection is not ready" }
        val risk = Protocol.classifyCommand(command)
        if (risk == CommandRisk.READ_ONLY) {
            enqueueRequests(command)
            return@runCatching
        }
        active.send(command)
        recorder.record("protocol_tx", command)
        addTimeline("TX", command.substringBefore(' ').uppercase(), command.substringAfter(' ', ""))
        if (risk == CommandRisk.MOTION) {
            synchronized(stateLock) {
                motionStartedMs = System.currentTimeMillis()
                state = state.copy(motionExpected = true)
            }
            publishState()
        }
    }.onFailure { error ->
        synchronized(stateLock) {
            state = state.copy(lastError = error.message ?: error.javaClass.simpleName)
        }
        recorder.record("error", "send_failed", mapOf("command" to command, "error" to error.toString()))
        publishState()
    }

    fun emergencyHalt(): Result<Unit> {
        val result = sendCommand("!")
        result.onSuccess {
            synchronized(stateLock) {
                motionStartedMs = null
                state = state.copy(motionExpected = false)
            }
            recorder.record("safety", "remote_halt_sent")
            publishState()
        }
        return result
    }

    private fun pollTick() {
        var timedOutRequest: PendingRequest? = null
        var motionTimedOut = false
        var command = ""
        try {
            val active = synchronized(stateLock) {
                val current = transport ?: return
                if (!current.isConnected) return
                val now = System.currentTimeMillis()
                if (state.motionExpected) {
                    val started = motionStartedMs ?: now.also { motionStartedMs = it }
                    if (now - started <= MAX_MOTION_DURATION_MS) return
                    motionStartedMs = null
                    motionTimedOut = true
                    state = state.copy(
                        motionExpected = false,
                        lastError = "Motion status timed out; live polling resumed",
                    )
                }
                pending?.let { entry ->
                    if (now - entry.startedMs < REQUEST_TIMEOUT_MS) return
                    timedOutRequest = entry
                    pending = null
                    stateLock.notifyAll()
                }
                if (pending != null) return
                val queued = if (requestQueue.isEmpty()) null else requestQueue.removeFirst()
                if (queued == null && now - lastPeriodicPollMs < PERIODIC_POLL_INTERVAL_MS) {
                    if (motionTimedOut) publishState()
                    return
                }
                val capabilities = state.firmware?.capabilities.orEmpty()
                command = queued ?: when {
                    capabilities.isEmpty() -> "INFO"
                    pollBoardNext && "BOARD" in capabilities -> "BOARD"
                    "TELEM" in capabilities -> "TELEM"
                    "BOARD" in capabilities -> "BOARD"
                    else -> "STATUS"
                }
                pollBoardNext = !pollBoardNext
                current.send(command)
                pending = PendingRequest(expectedResponse(command), command, now)
                lastPeriodicPollMs = now
                current
            }
            if (motionTimedOut) {
                recorder.record("monitor", "motion_timeout")
                publishState()
            }
            timedOutRequest?.let { entry ->
                recorder.record("monitor", "request_timeout", mapOf(
                    "command" to entry.command, "expected" to entry.expected,
                ))
            }
            recorder.record("protocol_tx", command)
            addTimeline("TX", command, "safe poll via ${active.label}")
        } catch (error: Exception) {
            synchronized(stateLock) {
                pending = null
                state = state.copy(lastError = error.message ?: "Poll failed")
                stateLock.notifyAll()
            }
            runCatching {
                recorder.record("error", "poll_failed", mapOf("command" to command, "error" to error.toString()))
            }
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
            synchronized(stateLock) {
                if (!connected) {
                    pending = null
                    requestQueue.clear()
                    motionStartedMs = null
                }
                state = state.copy(connected = connected, connectionText = status)
                stateLock.notifyAll()
            }
            recorder.record("transport", status)
            addTimeline("LINK", if (connected) "Connected" else "Status", status)
            if (connected) enqueueRequests(*SAFE_REFRESH_COMMANDS)
            publishState()
        }
    }

    override fun onLine(line: String) {
        main.post {
            recorder.record("protocol_rx", line)
            val event = Protocol.parseEvent(line)
            addTimeline("RX", event.kind, event.args.joinToString(" "))
            synchronized(stateLock) {
                val now = System.currentTimeMillis()
                responseCounts[event.kind] = (responseCounts[event.kind] ?: 0) + 1
                if (pending?.expected == event.kind) pending = null
                var next = state.copy(connected = true, lastSeenMs = now)
                try {
                    next = when (event.kind) {
                        "INFO" -> next.copy(firmware = Protocol.parseInfo(event), lastError = "")
                        "TELEM" -> {
                            val telemetry = Protocol.parseTelemetry(event)
                            val moving = telemetry.sequence in setOf(3, 8, 19)
                            motionStartedMs = if (moving) motionStartedMs ?: now else null
                            next.copy(telemetry = telemetry, motionExpected = moving, lastError = "")
                        }
                        "BOARD" -> if (event.args.isNotEmpty()) next.copy(
                            sensorHex = event.args[0],
                            sensorSquares = Protocol.parseBoardHex(event.args[0]),
                            sensorUpdatedMs = now,
                            lastError = "",
                        ) else next
                        "READY", "PONG" -> next.copy(connectionText = "Board connected and responding")
                        "SETUP", "DONE", "ESTOP", "ERR", "STOPPED" -> {
                            motionStartedMs = null
                            next.copy(motionExpected = false)
                        }
                        "MOVING" -> {
                            motionStartedMs = motionStartedMs ?: now
                            next.copy(motionExpected = true)
                        }
                        else -> next
                    }
                } catch (error: Exception) {
                    next = next.copy(lastError = error.message ?: "Malformed board response")
                    recorder.record("protocol", "parse_error", mapOf("line" to line, "error" to error.toString()))
                }
                if (event.kind == "ERR") next = next.copy(lastError = event.args.joinToString(" "))
                state = next
                stateLock.notifyAll()
            }
            publishState()
            observerSnapshot().forEach { it.onBoardEvent(event) }
            pollTick()
        }
    }

    private fun addTimeline(direction: String, event: String, detail: String) {
        val snapshot = synchronized(stateLock) {
            timeline.addLast(TimelineEntry(System.currentTimeMillis(), direction, event, detail))
            while (timeline.size > 500) timeline.removeFirst()
            timeline.toList()
        }
        main.post { observerSnapshot().forEach { it.onTimelineChanged(snapshot) } }
    }

    private fun observerSnapshot(): List<Observer> = synchronized(stateLock) { observers.toList() }

    private fun publishState() = main.post {
        val snapshot = state
        observerSnapshot().forEach { it.onBoardState(snapshot) }
    }

    override fun close() {
        disconnect()
        pollFuture?.cancel(true)
        scheduler.shutdownNow()
        synchronized(stateLock) {
            observers.clear()
            stateLock.notifyAll()
        }
    }

    companion object {
        const val PERIODIC_POLL_INTERVAL_MS = 2_000L
        const val REQUEST_TIMEOUT_MS = 4_000L
        const val SAFE_REFRESH_TIMEOUT_MS = 18_000L
        const val MAX_MOTION_DURATION_MS = 10 * 60_000L
        private val SAFE_REFRESH_COMMANDS = arrayOf("PING", "INFO", "TELEM", "BOARD")
    }
}
