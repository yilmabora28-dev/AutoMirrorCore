package com.automirrorcore.aa

import android.content.Intent
import android.util.Log
import android.view.Surface
import androidx.car.app.CarAppService
import androidx.car.app.SessionInfo
import androidx.car.app.Screen
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.SurfaceCallback
import com.automirrorcore.input.CoordinateTransformer
import com.automirrorcore.input.TouchInjectorFactory
import com.automirrorcore.input.TouchAccessibilityService
import com.automirrorcore.projection.MediaProjectionService

class AutoCarAppService : CarAppService() {

    companion object {
        private const val TAG = "AutoCarAppService"
    }

    override fun onCarConfigurationChanged() {
        Log.i(TAG, "Car configuration changed")
    }

    override fun onCreateSession(sessionInfo: SessionInfo): androidx.car.app.Session {
        Log.i(TAG, "onCreateSession")
        return MirrorSession()
    }

    private inner class MirrorSession : androidx.car.app.Session() {

        private var touchInjector = TouchInjectorFactory.create(
            TouchAccessibilityService.instance
        )

        private var transform: CoordinateTransformer.Transform? = null

        private val surfaceCallback = object : SurfaceCallback {

            override fun onSurfaceAvailable(surface: Surface) {
                Log.i(TAG, "Car Surface available")
                startProjectionToCar(surface)
            }

            override fun onSurfaceDestroyed() {
                Log.i(TAG, "Car Surface destroyed")
                carContext.stopService(
                    Intent(carContext, MediaProjectionService::class.java)
                )
            }

            override fun onClick(x: Float, y: Float) {
                val t = transform ?: return
                val mapped = CoordinateTransformer.mapCarToPhone(x, y, t) ?: return
                touchInjector.tap(mapped.first, mapped.second)
            }

            override fun onScroll(distanceX: Float, distanceY: Float) {
                Log.d(TAG, "Scroll: dx=$distanceX dy=$distanceY")
            }

            override fun onFling(velocityX: Float, velocityY: Float) {
                val t = transform ?: return
                val cx = t.carWidth / 2f
                val cy = t.carHeight / 2f
                val endX = (cx - velocityX * 0.3f).coerceIn(0f, t.phoneWidth.toFloat())
                val endY = (cy - velocityY * 0.3f).coerceIn(0f, t.phoneHeight.toFloat())
                touchInjector.swipe(listOf(cx to cy, endX to endY), 200L)
            }
        }

        override fun onCreateScreen(screenIntent: Intent): Screen {
            touchInjector = TouchInjectorFactory.create(
                TouchAccessibilityService.instance
            )
            return MirrorScreen(carContext, surfaceCallback)
        }

        private fun startProjectionToCar(surface: Surface) {
            val phoneW = carContext.resources.displayMetrics.widthPixels
            val phoneH = carContext.resources.displayMetrics.heightPixels

            transform = CoordinateTransformer.Transform(
                phoneWidth = phoneW,
                phoneHeight = phoneH,
                carWidth = 1920,
                carHeight = 1080,
                orientation = CoordinateTransformer.Transform
                    .Orientation.PORTRAIT_TO_LANDSCAPE
            )

            val intent = Intent(carContext, MediaProjectionService::class.java).apply {
                putExtra(MediaProjectionService.EXTRA_TARGET_SURFACE, surface)
                putExtra(MediaProjectionService.EXTRA_DIRECT_SURFACE, true)
                putExtra(MediaProjectionService.EXTRA_WIDTH, 1920)
                putExtra(MediaProjectionService.EXTRA_HEIGHT, 1080)
                putExtra(MediaProjectionService.EXTRA_DPI, 320)
            }
            carContext.startForegroundService(intent)
        }
    }

    private class MirrorScreen(
        context: androidx.car.app.CarContext,
        private val surfaceCallback: SurfaceCallback
    ) : Screen(context) {

        override fun onGetTemplate(): Template {
            return NavigationTemplate.Builder()
                .setSurfaceCallback(surfaceCallback)
                .setMapActionStrip(
                    androidx.car.app.model.ActionStrip.Builder()
                        .addAction(androidx.car.app.model.Action.PAN)
                        .build()
                )
                .build()
        }
    }
}
