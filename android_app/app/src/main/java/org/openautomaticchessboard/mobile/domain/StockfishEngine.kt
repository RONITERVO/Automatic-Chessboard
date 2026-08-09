package org.openautomaticchessboard.mobile.domain

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Minimal, lifecycle-safe UCI client for the bundled official Stockfish build. */
class StockfishEngine(context: Context) : Closeable {
    private val executable = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var readerThread: Thread? = null
    private var engineName = "Stockfish"
    private val output = LinkedBlockingQueue<String>()
    private val lock = Any()

    val isInstalled: Boolean get() = executable.isFile

    fun identity(): String = synchronized(lock) {
        ensureStarted()
        engineName
    }

    fun bestMove(fen: String, elo: Int, thinkMillis: Long): String = synchronized(lock) {
        ensureStarted()
        drainOutput()
        send("setoption name UCI_LimitStrength value true")
        send("setoption name UCI_Elo value ${elo.coerceIn(1320, 3190)}")
        send("isready")
        readUntil("readyok", 5_000)
        send("position fen $fen")
        send("go movetime ${thinkMillis.coerceIn(50, 300_000)}")
        val line = readUntil("bestmove ", thinkMillis.coerceAtMost(300_000) + 10_000)
            .lastOrNull { it.startsWith("bestmove ") }
            ?: error("Stockfish did not return a move")
        val move = line.split(' ').getOrNull(1).orEmpty()
        require(move.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) { "Invalid engine move: $line" }
        move
    }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        check(isInstalled) { "Bundled Stockfish engine is missing for this CPU" }
        output.clear()
        process = ProcessBuilder(executable.absolutePath)
            .redirectErrorStream(true)
            .start()
        input = BufferedWriter(OutputStreamWriter(process!!.outputStream, Charsets.UTF_8))
        readerThread = Thread({
            BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach(output::offer)
            }
        }, "stockfish-output").apply { isDaemon = true; start() }
        send("uci")
        val lines = readUntil("uciok", 8_000)
        engineName = lines.firstOrNull { it.startsWith("id name ") }?.removePrefix("id name ") ?: "Stockfish"
    }

    private fun send(command: String) {
        input?.apply { write(command); newLine(); flush() } ?: error("Stockfish is not running")
    }

    private fun readUntil(prefix: String, timeoutMs: Long): List<String> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val lines = mutableListOf<String>()
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val line = output.poll(remaining.coerceAtLeast(1), TimeUnit.NANOSECONDS) ?: break
            lines += line
            if (line.startsWith(prefix)) return lines
        }
        error("Timed out waiting for Stockfish: $prefix")
    }

    private fun drainOutput() { while (output.poll() != null) Unit }

    override fun close() = synchronized(lock) {
        try { send("quit") } catch (_: Exception) {}
        try { process?.waitFor(500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        process?.destroy()
        process = null
        input = null
        output.clear()
    }
}
