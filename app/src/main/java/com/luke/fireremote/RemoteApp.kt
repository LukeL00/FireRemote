package com.luke.fireremote

import android.app.Application
import android.content.Context

/** Holds the one shared Fire TV connection used by the app, notification and tile. */
class RemoteApp : Application() {
    val client: FireTvClient by lazy { FireTvClient(this) }

    val savedIp: String
        get() = getSharedPreferences("remote", Context.MODE_PRIVATE).getString("ip", "") ?: ""
}
