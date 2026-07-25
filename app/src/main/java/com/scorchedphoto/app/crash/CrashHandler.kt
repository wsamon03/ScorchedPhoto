package com.scorchedphoto.app.crash

import android.content.Context
import android.content.Intent
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Replaces the system "App keeps stopping" dialog with [CrashActivity], which shows the
 * full stack trace on screen with a copy/share button - this is temporary debug tooling
 * for testing on a device with no computer/adb access, not something to ship long-term.
 */
class CrashHandler(private val appContext: Context) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        try {
            val intent = Intent(appContext, CrashActivity::class.java).apply {
                putExtra(CrashActivity.EXTRA_STACK_TRACE, stackTrace)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            appContext.startActivity(intent)
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(1)
        }
    }
}
