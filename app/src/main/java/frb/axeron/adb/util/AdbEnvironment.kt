package frb.axeron.adb.util

import android.os.Build

object AdbEnvironment {
    fun getAdbTcpPort(): Int {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val getInt = c.getMethod("getInt", String::class.java, Int::class.java)
            var port = getInt.invoke(null, "service.adb.tcp.port", -1) as Int
            if (port <= 0) port = getInt.invoke(null, "persist.adb.tcp.port", -1) as Int
            port
        } catch (e: Exception) { -1 }
    }
}
