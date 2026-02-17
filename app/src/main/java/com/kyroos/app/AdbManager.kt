package com.kyroos.app

import android.content.Context
import frb.axeron.adb.AdbClient
import frb.axeron.adb.AdbKey
import frb.axeron.adb.AdbPairingClient
import frb.axeron.adb.util.WifiReadyGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AdbManager {
    private var client: AdbClient? = null

    suspend fun pair(context: Context, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
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
