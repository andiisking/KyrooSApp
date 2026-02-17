package com.kyroos.app

import android.content.Context
import android.content.Intent
import android.os.Build
import frb.axeron.adb.AdbClient
import frb.axeron.adb.AdbKey
import frb.axeron.adb.AdbPairingService

object AdbManager {
    private var client: AdbClient? = null

    // Fungsi untuk memicu Notifikasi Pairing (Panggil AdbPairingService.kt)
    fun startPairingService(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(context, AdbPairingService::class.java)
            context.startForegroundService(intent)
        }
    }

    suspend fun connect(context: Context, port: Int = 5555): Boolean {
        return try {
            val key = AdbKey(context)
            client = AdbClient(key, port)
            client?.shell("echo ready")?.contains("ready") == true
        } catch (e: Exception) { false }
    }

    suspend fun shell(cmd: String) = client?.shell(cmd) ?: ""
}
