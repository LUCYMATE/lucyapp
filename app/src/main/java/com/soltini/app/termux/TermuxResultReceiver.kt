package com.soltini.app.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * TermuxResultReceiver
 *
 * Receives execution results from the Termux RUN_COMMAND service.
 */
class TermuxResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TermuxResultReceiver"
        private const val ACTION_TERMUX_RESULT = "com.soltini.app.termux.RESULT"
        private val nextExecId = AtomicInteger(1000)
        private val pendingExecutions = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()

        data class TermuxResult(
            val exitCode: Int,
            val stdout: String,
            val stderr: String,
            val error: String?
        )

        suspend fun runTermuxCommand(
            context: Context,
            executable: String,
            args: Array<String> = emptyArray(),
            workDir: String? = null,
            inBackground: Boolean = true,
            timeoutSeconds: Long = 30
        ): TermuxResult {
            val execId = nextExecId.incrementAndGet()
            val deferred = CompletableDeferred<TermuxResult>()
            pendingExecutions[execId] = deferred

            try {
                val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
                    action = ACTION_TERMUX_RESULT
                    putExtra("soltini_exec_id", execId)
                }

                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pendingIntent = PendingIntent.getBroadcast(context, execId, callbackIntent, flags)

                val intent = Intent("com.termux.RUN_COMMAND").apply {
                    setClassName("com.termux", "com.termux.app.RunCommandService")
                    putExtra("com.termux.RUN_COMMAND_PATH", executable)
                    putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
                    putExtra("com.termux.RUN_COMMAND_WORKDIR", workDir ?: "/data/data/com.termux/files/home")
                    putExtra("com.termux.RUN_COMMAND_BACKGROUND", inBackground)
                    putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
                }

                context.startService(intent)
                Log.i(TAG, "Dispatched Termux command #$execId: $executable ${args.joinToString(" ")}")

                val result = withTimeoutOrNull(timeoutSeconds * 1000L) {
                    deferred.await()
                } ?: TermuxResult(-1, "", "", "Command execution timed out after ${timeoutSeconds}s")

                return result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run Termux command: ${e.message}", e)
                return TermuxResult(-1, "", "", e.message)
            } finally {
                pendingExecutions.remove(execId)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val execId = intent.getIntExtra("soltini_exec_id", -1)
        if (execId < 0) return

        val bundle = intent.getBundleExtra("result") ?: Bundle()
        val stdout = bundle.getString("stdout", "")
        val stderr = bundle.getString("stderr", "")
        val exitCode = bundle.getInt("exitCode", 0)
        val error = bundle.getString("err")

        Log.i(TAG, "Received Termux result for #$execId: exitCode=$exitCode")

        val deferred = pendingExecutions[execId]
        deferred?.complete(TermuxResult(exitCode, stdout, stderr, error))
    }
}
