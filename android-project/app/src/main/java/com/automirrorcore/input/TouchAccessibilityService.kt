package com.automirrorcore.input

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchA11yService"
        @Volatile
        var instance: TouchAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected — touch injection ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't process events; we only need dispatchGesture()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }
}
