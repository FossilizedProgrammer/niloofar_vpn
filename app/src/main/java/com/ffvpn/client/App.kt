package com.ffvpn.client

import android.app.Application
import com.ffvpn.client.vpn.CaBundle

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Root certificates have to be unpacked before the engine or a probe runs.
        CaBundle.prepare(this)
    }
}
