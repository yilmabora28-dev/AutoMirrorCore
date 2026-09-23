package com.automirrorcore.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DisplayOptimizer(private val context: Context) {

    companion object {
        private const val TAG = "DisplayOptimizer"
        private var originalSize: String? = null
        private var originalDensity: String? = null
    }

    suspend fun applyCarDisplay(
        carWidth: Int,
        carHeight: Int,
        targetDpi: Int = 320
    ): Boolean = withContext(Dispatchers.IO) {

        if (originalSize == null) {
            originalSize = exec("wm size")
                .substringAfter("Physical size: ")
                .trim()
        }
        if (originalDensity == null) {
            originalDensity = exec("wm density")
                .substringAfter("Physical density: ")
                .trim()
        }

        Log.i(TAG, "Original: $originalSize @ ${originalDensity}dpi")
        Log.i(TAG, "Applying: ${carWidth}x$carHeight @ ${targetDpi}dpi")

        val sizeResult = exec("wm size ${carWidth}x${carHeight}")
        val densityResult = exec("wm density ${targetDpi}")

        val ok = !sizeResult.contains("error", ignoreCase = true) &&
                 !densityResult.contains("error", ignoreCase = true)

        if (ok) Log.i(TAG, "Display override applied")
        else Log.e(TAG, "Failed: size=[$sizeResult] density=[$densityResult]")

        ok
    }

    suspend fun restoreOriginal(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Restoring original display settings")
        exec("wm size reset")
        exec("wm density reset")
        true
    }

    private fun exec(command: String): String {
        return try {
            if (com.automirrorcore.input.ShizukuManager.isServiceRunning()) {
                com.automirrorcore.input.ShizukuManager.execShell(command)
            } else {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                output
            }
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command → ${e.message}")
            ""
        }
    }
}
