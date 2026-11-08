package dev.couchpad

import android.app.Application

class CouchpadApp : Application() {
    lateinit var hub: ControllerHub
        private set

    override fun onCreate() {
        super.onCreate()
        hub = ControllerHub(this).also { it.start() }
    }
}
