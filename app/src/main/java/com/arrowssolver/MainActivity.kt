package com.arrowssolver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var captureButton: Button
    private lateinit var boardInfoText: TextView
    private lateinit var safeArrowsText: TextView
    private lateinit var sequenceText: TextView

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startSolverService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_LONG).show()
        }
    }

    private val boardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val rows = intent.getIntExtra("rows", 0)
            val cols = intent.getIntExtra("cols", 0)
            val arrows = intent.getIntExtra("arrows", 0)
            val safe = intent.getIntExtra("safe", 0)
            val complete = intent.getBooleanExtra("complete", false)

            boardInfoText.text = "Board: ${rows}x${cols}, $arrows arrows"
            safeArrowsText.text = "Safe now: $safe | Complete: $complete"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        captureButton = findViewById(R.id.captureButton)
        boardInfoText = findViewById(R.id.boardInfoText)
        safeArrowsText = findViewById(R.id.safeArrowsText)
        sequenceText = findViewById(R.id.sequenceText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(
                boardReceiver,
                IntentFilter("com.arrowssolver.BOARD_UPDATE"),
                Context.RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(
                boardReceiver,
                IntentFilter("com.arrowssolver.BOARD_UPDATE")
            )
        }

        startButton.setOnClickListener {
            if (SolverOverlayService.isRunning.get()) {
                stopSolverService()
            } else {
                requestOverlayPermission()
            }
        }

        captureButton.setOnClickListener {
            if (SolverOverlayService.isRunning.get()) {
                requestScreenCapture()
            } else {
                Toast.makeText(this, "Start overlay first", Toast.LENGTH_SHORT).show()
            }
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(boardReceiver)
        } catch (e: Exception) { }
    }

    private fun updateUI() {
        val running = SolverOverlayService.isRunning.get()
        statusText.text = "Status: ${if (running) "Running" else "Idle"}"
        startButton.text = if (running) "Stop Overlay" else "Start Overlay"
        captureButton.isEnabled = running
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Overlay Permission Required")
                    .setMessage("This app needs overlay permission to display the solver on top of Arrows GO!")
                    .setPositiveButton("Grant") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        overlayPermissionLauncher.launch(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                requestNotificationPermission()
            }
        } else {
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startSolverService(resultCode: Int, data: Intent) {
        val intent = Intent(this, SolverOverlayService::class.java).apply {
            action = SolverOverlayService.ACTION_START
            putExtra(SolverOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SolverOverlayService.EXTRA_DATA, data)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "Solver overlay started", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopSolverService() {
        val intent = Intent(this, SolverOverlayService::class.java).apply {
            action = SolverOverlayService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "Solver overlay stopped", Toast.LENGTH_SHORT).show()
        updateUI()
    }
}
