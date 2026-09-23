package com.automirrorcore.app.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.automirrorcore.app.R
import com.automirrorcore.input.TouchAccessibilityService
import com.automirrorcore.projection.MediaProjectionService
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: MaterialButton
    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnOverlay: MaterialButton
    private lateinit var tvStatus: TextView

    companion object {
        private const val REQ_MEDIA_PROJECTION = 1001
        private const val REQ_OVERLAY = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStartProjection)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnOverlay = findViewById(R.id.btnOverlay)
        tvStatus = findViewById(R.id.tvStatus)

        updateStatus()

        btnStart.setOnClickListener {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQ_OVERLAY)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, MediaProjectionService::class.java).apply {
                putExtra(MediaProjectionService.EXTRA_RESULT_CODE, resultCode)
                putExtra(MediaProjectionService.EXTRA_RESULT_DATA, data)
                putExtra(MediaProjectionService.EXTRA_WIDTH, 1920)
                putExtra(MediaProjectionService.EXTRA_HEIGHT, 1080)
                putExtra(MediaProjectionService.EXTRA_DPI, 320)
            }
            startForegroundService(intent)
            updateStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val a11yEnabled = isAccessibilityEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)
        val parts = mutableListOf<String>()
        parts.add(if (a11yEnabled) "Accessibility: ON" else "Accessibility: OFF")
        parts.add(if (overlayEnabled) "Overlay: ON" else "Overlay: OFF")
        tvStatus.text = "Status: ${parts.joinToString(" | ")}"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        if (enabled == 1) {
            val list = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return list.contains("$packageName/${TouchAccessibilityService::class.java.name}")
        }
        return false
    }
}
