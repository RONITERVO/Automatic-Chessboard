package org.openautomaticchessboard.mobile

import android.app.Application
import java.io.File
import java.time.Instant

class ChessboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "last-crash.txt").writeText(
                    "${Instant.now()} ${thread.name}\n${error.stackTraceToString()}"
                )
            }
            prior?.uncaughtException(thread, error)
        }
    }
}
