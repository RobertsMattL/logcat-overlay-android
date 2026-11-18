package com.devtechdesign.android.logcat

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Streams `logcat` into an in-memory ring buffer and hands new lines to listeners on the
 * main thread, in batches.
 *
 * By default Android only lets an app read its own process's logs. To see every log on the
 * device (same as `adb logcat`), grant the permission once from a computer:
 *   adb shell pm grant <your.app.package> android.permission.READ_LOGS
 * then restart the app.
 */
object LogcatReader {
    private const val TAG = "LogcatReader"
    private const val MAX_LINES = 20_000
    private const val BACKLOG_LINES = "2000"
    private const val BATCH_DELAY_MS = 150L

    interface Listener {
        /** [added] were appended; [dropped] (oldest first) were removed from the front of [lines]. */
        fun onLines(added: List<LogLine>, dropped: List<LogLine>)

        /** The buffer was emptied. */
        fun onCleared()
    }

    private val main = Handler(Looper.getMainLooper())
    private val buffer = ArrayDeque<LogLine>()          // main thread only
    private val listeners = mutableSetOf<Listener>()    // main thread only

    private val incoming = ArrayList<LogLine>()         // guarded by itself
    private var flushScheduled = false                  // guarded by incoming
    private val flushRunnable = Runnable { flush() }

    @Volatile private var process: Process? = null
    private var thread: Thread? = null

    val lines: List<LogLine> get() = buffer

    val isRunning: Boolean get() = process != null

    fun start() {
        if (process != null) return
        val p = try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime", "-T", BACKLOG_LINES))
        } catch (e: Exception) {
            Log.e(TAG, "Could not start logcat", e)
            return
        }
        process = p
        thread = Thread({ readLoop(p) }, "logcat-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        process?.destroy()
        process = null
        thread = null
    }

    fun clear() {
        buffer.clear()
        listeners.forEach { it.onCleared() }
    }

    fun addListener(listener: Listener) { listeners += listener }

    fun removeListener(listener: Listener) { listeners -= listener }

    private fun readLoop(p: Process) {
        try {
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("---------")) continue  // "--------- beginning of main"
                    synchronized(incoming) {
                        incoming += LogLine.parse(line)
                        if (!flushScheduled) {
                            flushScheduled = true
                            main.postDelayed(flushRunnable, BATCH_DELAY_MS)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (process === p) Log.w(TAG, "logcat stream ended: ${e.message}")
        } finally {
            if (process === p) process = null
        }
    }

    private fun flush() {
        val added: List<LogLine>
        synchronized(incoming) {
            added = ArrayList(incoming)
            incoming.clear()
            flushScheduled = false
        }
        if (added.isEmpty()) return
        buffer.addAll(added)
        val dropped = ArrayList<LogLine>()
        while (buffer.size > MAX_LINES) {
            dropped += buffer.removeFirst()
        }
        listeners.forEach { it.onLines(added, dropped) }
    }
}
