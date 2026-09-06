package com.liban.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.liban.android.agent.AnalysisBus
import com.liban.android.capture.FloatingCaptureService
import com.liban.android.model.AnalysisState
import com.liban.android.ui.AppViewModel
import com.liban.android.ui.LibanApp

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()
    private var waitingForOverlay = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { launchOverlayOrProjection() }

    private val projectionPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            AnalysisBus.update(AnalysisState.Error("屏幕捕获授权被拒绝，可再次点击开启识屏模式"))
            return@registerForActivityResult
        }
        val intent = Intent(this, FloatingCaptureService::class.java).apply {
            action = FloatingCaptureService.ACTION_START
            putExtra(FloatingCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(FloatingCaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LibanApp(
                viewModel = viewModel,
                serviceConfiguration = (application as LibanApplication).graph.serviceConfiguration,
                onStartScreenMode = ::startScreenMode,
                onStopScreenMode = {
                    startService(Intent(this, FloatingCaptureService::class.java).apply {
                        action = FloatingCaptureService.ACTION_STOP
                    })
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlay && Settings.canDrawOverlays(this)) {
            waitingForOverlay = false
            launchProjection()
        } else if (waitingForOverlay) {
            AnalysisBus.update(AnalysisState.Error("尚未授予悬浮窗权限，可再次点击开启识屏模式"))
        }
    }

    private fun startScreenMode() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchOverlayOrProjection()
        }
    }

    private fun launchOverlayOrProjection() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlay = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        } else {
            launchProjection()
        }
    }

    private fun launchProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionPermission.launch(manager.createScreenCaptureIntent())
    }
}
