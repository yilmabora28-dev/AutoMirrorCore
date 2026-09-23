package com.automirrorcore.app

import android.app.Application
import android.util.Log

class AutoMirrorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AutoMirrorCore initialized")
    }

    companion object {
        private const val TAG = "AutoMirrorApp"
    }
}
