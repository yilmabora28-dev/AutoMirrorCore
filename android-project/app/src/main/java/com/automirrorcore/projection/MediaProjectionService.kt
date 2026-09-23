package com.automirrorcore.projection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.automirrorcore.app.R
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MediaProjectionService : Service() {

    companion object {
        private const val TAG = "MediaProjectionService"
        private const val CHANNEL_ID = "projection_channel"
        private const val NOTIF_ID = 7701

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_SURFACE = "target_surface"
        const val EXTRA_WIDTH = "target_width"
        const val EXTRA_HEIGHT = "target_height"
        const val EXTRA_DPI = "target_dpi"
        const val EXTRA_DIRECT_SURFACE = "direct_surface"

        val projectionState: StateFlow<ProjectionState> get() = _projectionState
        private val _projectionState = MutableStateFlow<ProjectionState>(ProjectionState.Idle)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var targetSurface: Surface? = null

    private var videoWidth = 1920
    private var videoHeight = 1080
    private var videoDpi = 320
    private var directSurfaceMode = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        intent?.let {
            val resultCode = it.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val resultData: Intent? = it.getParcelableExtra(EXTRA_RESULT_DATA)
            videoWidth = it.getIntExtra(EXTRA_WIDTH, 1920)
            videoHeight = it.getIntExtra(EXTRA_HEIGHT, 1080)
            videoDpi = it.getIntExtra(EXTRA_DPI, 320)
            directSurfaceMode = it.getBooleanExtra(EXTRA_DIRECT_SURFACE, true)
            @Suppress("DEPRECATION")
            targetSurface = it.getParcelableExtra(EXTRA_TARGET_SURFACE)

            if (resultCode != 0 && resultData != null) {
                startProjection(resultCode, resultData)
            } else {
                Log.e(TAG, "Missing projection consent — stopping")
                stopSelf()
            }
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startProjection(resultCode: Int, data: Intent) {
        _projectionState.value = ProjectionState.Starting

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        projection = manager.getMediaProjection(resultCode, data).also { mp ->
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system")
                    _projectionState.value = ProjectionState.Stopped
                    teardown()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
        }

        if (directSurfaceMode && targetSurface != null) {
            createVirtualDisplay(targetSurface!!)
            _projectionState.value = ProjectionState.Streaming(videoWidth, videoHeight, 60)
        } else {
            startEncodedPipeline()
        }
    }

    private fun createVirtualDisplay(surface: Surface): VirtualDisplay {
        return projection!!.createVirtualDisplay(
            "AutoMirror_VD",
            videoWidth, videoHeight, videoDpi,
            VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                VIRTUAL_DISPLAY_FLAG_PUBLIC or
                VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            surface, null, null
        ).also { virtualDisplay = it }
    }

    private fun startEncodedPipeline() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, computeBitrate(videoWidth, videoHeight))
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderSurface = createInputSurface()
            createVirtualDisplay(encoderSurface)
            start()
            scope.launch { drainEncodedStream(this@apply) }
        }

        _projectionState.value = ProjectionState.Streaming(videoWidth, videoHeight, 60)
    }

    private fun computeBitrate(w: Int, h: Int): Int = (w * h * 60 * 0.15).toInt()

    private suspend fun drainEncodedStream(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (scope.isActive) {
            val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                index >= 0 -> {
                    codec.getOutputBuffer(index)?.let { outputBuffer ->
                        if (bufferInfo.size > 0 &&
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val bytes = ByteArray(bufferInfo.size)
                            outputBuffer.get(bytes)
                            Log.d(TAG, "Encoded frame: ${bytes.size} bytes")
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "Encoder format: ${codec.outputFormat}")
                }
            }
        }
    }

    private fun teardown() {
        virtualDisplay?.release()
        virtualDisplay = null
        encoder?.let { it.stop(); it.release() }
        encoder = null
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        teardown()
        wakeLock?.release()
        scope.cancel()
        _projectionState.value = ProjectionState.Idle
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Projection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mirroring screen to Android Auto"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.projection_notif_title))
            .setContentText(getString(R.string.projection_notif_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "AutoMirrorCore::ProjectionWakeLock"
        ).also { it.acquire(60 * 60 * 1000L) }
    }

    sealed interface ProjectionState {
        data object Idle : ProjectionState
        data object Starting : ProjectionState
        data class Streaming(val width: Int, val height: Int, val fps: Int) : ProjectionState
        data object Stopped : ProjectionState
    }
}
