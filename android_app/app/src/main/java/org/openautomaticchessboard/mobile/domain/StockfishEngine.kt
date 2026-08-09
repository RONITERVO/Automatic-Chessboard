package org.openautomaticchessboard.mobile.domain

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Minimal, lifecycle-safe UCI client for the bundled official Stockfish build. */
class StockfishEngine(context: Context) : Closeable {
    private data class Session(
        val process: Process,
        val input: BufferedWriter,
        val output: LinkedBlockingQueue<String>,
        val reader: Thread,
    )

    private val executable = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
    private val lock = Any()
    @Volatile private var session: Session? = null
    @Volatile private var closed = false
    private var engineName = "Stockfish"

    val isInstalled: Boolean get() = executable.isFile

    fun identity(): String = synchronized(lock) {
        ensureStarted()
        engineName
    }

    /** Returns null only for the valid UCI `bestmove (none)` response. */
    fun bestMove(fen: String, elo: Int, thinkMillis: Long): String? = synchronized(lock) {
        val active = ensureStarted()
        drainOutput(active)
        send(active, "setoption name UCI_LimitStrength value true")
        send(active, "setoption name UCI_Elo value ${elo.coerceIn(1320, 3190)}")
        send(active, "isready")
        readUntil(active, "readyok", 5_000)
        send(active, "position fen $fen")
        send(active, "go movetime ${thinkMillis.coerceIn(50, 300_000)}")
        val line = readUntil(active, "bestmove ", thinkMillis.coerceAtMost(300_000) + 10_000)
            .lastOrNull { it.startsWith("bestmove ") }
            ?: error("Stockfish did not return a move")
        val move = line.split(' ').getOrNull(1).orEmpty()
        if (move == "(none)") return@synchronized null
        require(move.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) { "Invalid engine move: $line" }
        move
    }

    private fun ensureStarted(): Session {
        check(!closed) { "Stockfish engine is closed" }
        session?.takeIf { it.process.isAlive }?.let { return it }
        check(isInstalled) { "Bundled Stockfish engine is missing for this CPU" }
        session?.let(::shutdownSession)
        val process = ProcessBuilder(executable.absolutePath).redirectErrorStream(true).start()
        val output = LinkedBlockingQueue<String>()
        val input = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach(output::offer)
                }
            } catch (_: IOException) {
                // Expected when close() destroys the process or closes its stream.
            } finally {
                output.offer(END_OF_STREAM)
            }
        }, "stockfish-output").apply { isDaemon = true; start() }
        val active = Session(process, input, output, reader)
        session = active
        send(active, "uci")
        val lines = readUntil(active, "uciok", 8_000)
        engineName = lines.firstOrNull { it.startsWith("id name ") }
            ?.removePrefix("id name ") ?: "Stockfish"
        return active
    }

    private fun send(active: Session, command: String) {
        check(session === active && active.process.isAlive) { "Stockfish process changed" }
        active.input.apply { write(command); newLine(); flush() }
    }

    private fun readUntil(active: Session, prefix: String, timeoutMs: Long): List<String> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val lines = mutableListOf<String>()
        while (System.nanoTime() < deadline) {
            check(session === active) { "Stockfish process changed" }
            val remaining = deadline - System.nanoTime()
            val line = active.output.poll(remaining.coerceAtLeast(1), TimeUnit.NANOSECONDS) ?: break
            if (line == END_OF_STREAM) error("Stockfish process ended while waiting for $prefix")
            lines += line
            if (line.startsWith(prefix)) return lines
        }
        error("Timed out waiting for Stockfish: $prefix")
    }

    private fun drainOutput(active: Session) {
        while (active.output.poll()?.takeUnless { it == END_OF_STREAM } != null) Unit
    }

    private fun shutdownSession(active: Session) {
        runCatching { active.input.write("quit\n"); active.input.flush() }
        runCatching { active.input.close() }
        runCatching { active.process.destroy() }
        runCatching {
            if (!active.process.waitFor(500, TimeUnit.MILLISECONDS)) active.process.destroyForcibly()
        }
        active.output.clear()
        active.output.offer(END_OF_STREAM)
        if (session === active) session = null
    }

    /** Never waits for an in-progress engine operation on the caller/main thread. */
    override fun close() {
        closed = true
        val active = session ?: return
        runCatching { active.process.destroy() }
        active.output.offer(END_OF_STREAM)
        Thread({
            synchronized(lock) { if (session === active) shutdownSession(active) }
        }, "stockfish-shutdown").apply { isDaemon = true; start() }
    }

    companion object {
        private const val END_OF_STREAM = "\u0000ENGINE_EOF\u0000"
    }
}
