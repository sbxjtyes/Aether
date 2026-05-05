package com.zhousl.aether.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.zhousl.aether.BuildConfig

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val executionId = intent.getIntExtra(TermuxContract.ExecutionIdExtra, -1)
        if (executionId < 0) {
            logTermuxReceiver("missing execution id action=${intent.action.orEmpty()} extras=${intent.extras?.keySet().orEmpty()}")
            return
        }

        val resultBundle = intent.extras?.getBundle(TermuxContract.ResultBundleExtra)
            ?: intent.extras?.findFirstBundle()

        val result = TermuxCommandResult(
            stdout = resultBundle?.getString(TermuxContract.ResultStdoutExtra).orEmpty(),
            stderr = resultBundle?.getString(TermuxContract.ResultStderrExtra).orEmpty(),
            exitCode = resultBundle?.getInt(TermuxContract.ResultExitCodeExtra, -1) ?: -1,
            err = resultBundle?.getInt(TermuxContract.ResultErrExtra, -1) ?: -1,
            errmsg = resultBundle?.getString(TermuxContract.ResultErrmsgExtra).orEmpty(),
        )
        logTermuxReceiver(
            "received execution_id=$executionId exit_code=${result.exitCode} err=${result.err} " +
                "stdout_bytes=${result.stdout.toByteArray(Charsets.UTF_8).size} " +
                "stderr_bytes=${result.stderr.toByteArray(Charsets.UTF_8).size} " +
                "extra_keys=${intent.extras?.keySet().orEmpty()}",
        )

        TermuxPendingResults.complete(executionId, result)
    }
}

private fun Bundle.findFirstBundle(): Bundle? =
    keySet().firstNotNullOfOrNull(::getBundle)

private fun logTermuxReceiver(message: String) {
    if (BuildConfig.DEBUG) {
        Log.d("AetherTermux", "receiver $message")
    }
}
