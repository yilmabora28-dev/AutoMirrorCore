package com.automirrorcore.input

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Placeholder service for Shizuku binder registration.
 * The actual Shizuku communication happens in ShizukuManager.
 */
class ShizukuInputService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
