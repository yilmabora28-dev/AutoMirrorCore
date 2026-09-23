package com.automirrorcore.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import kotlin.math.roundToInt

interface TouchInjector {
    val isAvailable: Boolean
    fun tap(x: Float, y: Float): Boolean
    fun swipe(points: List<Pair<Float, Float>>, durationMs: Long): Boolean
    fun injectMotionEvent(event: MotionEvent): Boolean {
        throw UnsupportedOperationException("Raw injection not supported by ${javaClass.simpleName}")
    }
}

object CoordinateTransformer {

    data class Transform(
        val phoneWidth: Int,
        val phoneHeight: Int,
        val carWidth: Int,
        val carHeight: Int,
        val orientation: Orientation
    ) {
        enum class Orientation { PORTRAIT_TO_LANDSCAPE, LANDSCAPE_TO_LANDSCAPE }
    }

    fun mapCarToPhone(carX: Float, carY: Float, t: Transform): Pair<Float, Float>? {
        val (contentX, contentY, contentW, contentH) = computeContentRect(t)

        if (carX < contentX || carX > contentX + contentW ||
            carY < contentY || carY > contentY + contentH
        ) return null

        val normX = (carX - contentX) / contentW
        val normY = (carY - contentY) / contentH

        val phoneX = (normX * t.phoneWidth).roundToInt().toFloat()
        val phoneY = (normY * t.phoneHeight).roundToInt().toFloat()

        return phoneX to phoneY
    }

    private fun computeContentRect(t: Transform): FloatArray {
        val phoneAspect = t.phoneWidth.toFloat() / t.phoneHeight
        val carAspect = t.carWidth.toFloat() / t.carHeight

        return if (phoneAspect > carAspect) {
            val scaledH = t.carWidth / phoneAspect
            val yOffset = (t.carHeight - scaledH) / 2f
            floatArrayOf(0f, yOffset, t.carWidth.toFloat(), scaledH)
        } else {
            val scaledW = t.carHeight * phoneAspect
            val xOffset = (t.carWidth - scaledW) / 2f
            floatArrayOf(xOffset, 0f, scaledW, t.carHeight.toFloat())
        }
    }
}

class AccessibilityTouchInjector(
    private val service: AccessibilityService
) : TouchInjector {

    override val isAvailable: Boolean = true
    private val handler = Handler(Looper.getMainLooper())

    override fun tap(x: Float, y: Float): Boolean {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {}
            override fun onCancelled(g: GestureDescription?) {
                Log.w("ATI", "Gesture cancelled at ($x, $y)")
            }
        }, handler)
    }

    override fun swipe(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.size < 2) return false

        val path = android.graphics.Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                lineTo(points[i].first, points[i].second)
            }
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                Log.d("ATI", "Swipe completed (${points.size} pts)")
            }
            override fun onCancelled(g: GestureDescription?) {
                Log.w("ATI", "Swipe cancelled")
            }
        }, handler)
    }
}

class ShizukuTouchInjector : TouchInjector {

    override val isAvailable: Boolean
        get() = ShizukuManager.isServiceRunning()

    override fun tap(x: Float, y: Float): Boolean {
        return try {
            injectMotionEvent(buildEvent(MotionEvent.ACTION_DOWN, x, y))
            injectMotionEvent(buildEvent(MotionEvent.ACTION_UP, x, y))
            true
        } catch (e: Exception) {
            Log.e("ShizukuTI", "Tap failed: ${e.message}")
            false
        }
    }

    override fun swipe(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.size < 2) return false
        val stepMs = durationMs / (points.size - 1)
        val downTime = android.os.SystemClock.uptimeMillis()

        try {
            injectMotionEvent(buildEvent(MotionEvent.ACTION_DOWN,
                points[0].first, points[0].second, downTime))

            for (i in 1 until points.size) {
                Thread.sleep(stepMs)
                injectMotionEvent(buildEvent(MotionEvent.ACTION_MOVE,
                    points[i].first, points[i].second, downTime))
            }

            injectMotionEvent(buildEvent(MotionEvent.ACTION_UP,
                points.last().first, points.last().second, downTime))
            return true
        } catch (e: Exception) {
            Log.e("ShizukuTI", "Swipe failed: ${e.message}")
            return false
        }
    }

    override fun injectMotionEvent(event: MotionEvent): Boolean {
        return ShizukuManager.injectEvent(event)
    }

    private fun buildEvent(
        action: Int, x: Float, y: Float,
        downTime: Long = android.os.SystemClock.uptimeMillis()
    ): MotionEvent {
        val eventTime = android.os.SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            downTime, eventTime, action, x, y, 0, 1f, 1f, 0, 0, 0, 0
        )
    }
}

object TouchInjectorFactory {
    fun create(accessibilityService: AccessibilityService?): TouchInjector {
        val shizuku = ShizukuTouchInjector()
        if (shizuku.isAvailable) return shizuku
        if (accessibilityService != null) {
            return AccessibilityTouchInjector(accessibilityService)
        }
        throw IllegalStateException("No touch injector available — grant Accessibility or start Shizuku")
    }
}
