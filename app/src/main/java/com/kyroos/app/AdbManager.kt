package com.kyroos.app

import android.content.Context
import android.content.Intent
import android.os.Build
import frb.axeron.adb.AdbClient
import frb.axeron.adb.AdbKey
import frb.axeron.adb.AdbPairingClient
import frb.axeron.adb.AdbPairingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AdbManager {
    private var client: AdbClient? = null

    fun startPairingService(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(context, AdbPairingService::class.java)
            context.startForegroundService(intent)
        }
    }

    suspend fun pair(port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val pairingClient = AdbPairingClient("127.0.0.1", port, code)
            val success = pairingClient.pair()
            pairingClient.close()
            success
        } catch (e: Exception) { false }
    }

    suspend fun connect(context: Context, port: Int = 5555): Boolean = withContext(Dispatchers.IO) {
        try {
            val key = AdbKey(context)
            client = AdbClient(key, port, "127.0.0.1")
            client?.shell("echo ready")?.contains("ready") == true
        } catch (e: Exception) { false }
    }

    suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) {
        try { client?.shell(cmd) ?: "" } catch (e: Exception) { "" }
    }
}
