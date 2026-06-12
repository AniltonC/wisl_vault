package com.wislvault

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder

class WiSLVaultApp : Application() {

    var transferService: TransferService? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            transferService = (binder as TransferService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            transferService = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        val intent = Intent(this, TransferService::class.java)
        startService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }
}
