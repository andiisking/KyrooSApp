package com.kyroos.app

import android.app.Application
import android.content.Context
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.Shell.Initializer

class KyroosApp : Application() {
    
    companion object {
        lateinit var instance: KyroosApp
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        // Setup Shell to use Shizuku
        Shell.enableVerboseLogging = false
        
        // Use direct value 2 (FLAG_USE_SHIZUKU)
        Shell.Builder.create()
            .setFlags(2)  // 2 = FLAG_USE_SHIZUKU
            .setInitializers(KyroosInitializer::class.java)
            .build()
    }
    
    class KyroosInitializer : Initializer() {
        override fun onInit(context: Context, shell: Shell): Boolean {
            // Initialize environment variables if needed
            shell.newJob()
                .add("export PATH=\$PATH:/system/bin:/system/xbin")
                .add("export LD_LIBRARY_PATH=\$LD_LIBRARY_PATH:/system/lib64:/system/lib")
                .exec()
            return true
        }
    }
}