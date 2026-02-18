package com.kyroos.app

import android.app.Application

class KyroosApp : Application() {
    
    companion object {
        lateinit var instance: KyroosApp
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        // HAPUS semua kode libsu - kita pakai Shizuku langsung
    }
}