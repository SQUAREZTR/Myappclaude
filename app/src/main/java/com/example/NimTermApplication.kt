package com.example

import android.app.Application
import com.example.service.TerminalRuntimeHost

class NimTermApplication : Application() {
    lateinit var runtimeHost: TerminalRuntimeHost
        private set

    override fun onCreate() {
        super.onCreate()
        runtimeHost = TerminalRuntimeHost(this)
    }
}
