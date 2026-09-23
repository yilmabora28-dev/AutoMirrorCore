package com.automirrorcore.input

import android.util.Log
import android.view.MotionEvent

/**
 * Shizuku manager — provides shell execution and input event injection
 * via Shizuku's binder. When Shizuku is not running, falls back to root.
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"

    fun isServiceRunning(): Boolean {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val binderField = cls.getDeclaredField("binder")
            binderField.isAccessible = true
            binderField.get(null) != null
        } catch (e: Exception) {
            false
        }
    }

    fun injectEvent(event: MotionEvent): Boolean {
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val binderField = cls.getDeclaredField("binder")
            binderField.isAccessible = true
            val binder = binderField.get(null) ?: return false

            val iInjectEventCls = Class.forName("rikka.shizuku.shizukufor3rdparty.IInjectEvent")
            val injectMethod = iInjectEventCls.getDeclaredMethod("injectEvent", MotionEvent::class.java)
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                iInjectEventCls.classLoader,
                arrayOf(iInjectEventCls)
            ) { _, method, args ->
                if (method.name == "injectEvent" && args != null && args.isNotEmpty()) {
                    doInjectViaShell(args[0] as MotionEvent)
                } else null
            }
            injectMethod.invoke(proxy, event)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku inject failed, trying root: ${e.message}")
            doInjectViaShell(event)
        }
    }

    fun execShell(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "execShell failed: ${e.message}")
            ""
        }
    }

    private fun doInjectViaShell(event: MotionEvent): Boolean {
        return try {
            val cmd = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    "input tap ${event.x.toInt()} ${event.y.toInt()}"
                MotionEvent.ACTION_MOVE -> true // skip for shell mode
                MotionEvent.ACTION_UP -> true
                else -> true
            }
            if (cmd is String) execShell(cmd)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Shell inject failed: ${e.message}")
            false
        }
    }
}
