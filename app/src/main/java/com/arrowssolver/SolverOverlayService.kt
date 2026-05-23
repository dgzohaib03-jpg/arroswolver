package com.arrowssolver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.Toast
import com.arrowssolver.overlay.OverlayView
import com.arrowssolver.solver.*
import java.util.concurrent.atomic.AtomicBoolean

class SolverOverlayService : Service() {

    companion object {
        private const val TAG = "SolverOverlay"
        const val CHANNEL_ID = "solver_overlay_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.arrowssolver.ACTION_START"
        const val ACTION_STOP = "com.arrowssolver.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        val isRunning = AtomicBoolean(false)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: OverlayView
    private var overlayParams: WindowManager.LayoutParams? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0

    private var resultCode: Int = 0
    private var data: Intent? = null

    private val handler = Handler(Looper.getMainLooper())
    private val captureHandler = Handler(Looper.getMainLooper())
    private val isCapturing = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                startForeground(NOTIFICATION_ID, createNotification())
                isRunning.set(true)

                createOverlay()
                showOverlay()

                if (resultCode != 0 && data != null) {
                    setupMediaProjection()
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning.set(false)
        releaseMediaProjection()
        hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Arrows Solver Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows solver overlay over Arrows GO!"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Arrows GO! Solver")
            .setContentText("Overlay is active")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setForegroundServiceBehavior(
                Notification.FOREGROUND_SERVICE_IMMEDIATE
            )
        }
        return builder.build()
    }

    private fun createOverlay() {
        if (::overlayView.isInitialized) return
        overlayView = OverlayView(this)
        overlayView.setOnBoardTap { x, y -> handleTap(x, y) }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
    }

    private fun showOverlay() {
        try {
            if (!::overlayView.isInitialized) return
            overlayParams?.let { params ->
                if (overlayView.isAttachedToWindow) return
                windowManager.addView(overlayView, params)
                overlayView.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }

    private fun hideOverlay() {
        try {
            if (!::overlayView.isInitialized) return
            if (!overlayView.isAttachedToWindow) return
            overlayView.visibility = View.GONE
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide overlay", e)
        }
    }

    private fun setupMediaProjection() {
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data!!)
            startCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup MediaProjection", e)
        }
    }

    private fun startCapture() {
        try {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            captureWidth = metrics.widthPixels
            captureHeight = metrics.heightPixels

            imageReader = ImageReader.newInstance(
                captureWidth, captureHeight,
                PixelFormat.RGBA_8888, 2
            )
            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isCapturing.get()) {
                    captureHandler.post { captureFrame(reader) }
                }
            }, captureHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SolverCapture",
                captureWidth, captureHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            Log.d(TAG, "Capture started: ${captureWidth}x${captureHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture", e)
        }
    }

    private fun captureFrame(reader: ImageReader) {
        if (isCapturing.getAndSet(true)) return

        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: run {
                isCapturing.set(false)
                return
            }

            val bitmap = imageToBitmap(image)
            image.close()
            image = null

            if (bitmap != null) {
                analyzeBoard(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture frame error", e)
        } finally {
            image?.close()
            isCapturing.set(false)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val pixelCount = width * height
        val pixels = IntArray(pixelCount)

        val rowBytes = ByteArray(rowStride)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(rowBytes, 0, rowStride)
            for (col in 0 until width) {
                val offset = col * pixelStride
                val r = rowBytes[offset].toInt() and 0xFF
                val g = rowBytes[offset + 1].toInt() and 0xFF
                val b = rowBytes[offset + 2].toInt() and 0xFF
                val a = rowBytes[offset + 3].toInt() and 0xFF
                pixels[row * width + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun analyzeBoard(bitmap: Bitmap) {
        try {
            val result = ArrowDetector.detectBoard(bitmap)
            if (result != null && result.arrows.isNotEmpty()) {
                val board = BoardState(result.gridRows, result.gridCols, result.arrows)
                val solution = EscapeSolver.solve(board)
                updateOverlay(result, solution)
                sendBoardInfo(result, solution)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Board analysis error", e)
        }
    }

    private fun updateOverlay(result: BoardDetectionResult, solution: Solution) {
        handler.post {
            try {
                if (!::overlayView.isInitialized) return@post

                val safeList = if (solution.steps.isNotEmpty()) {
                    solution.steps.first().safeArrows
                        .map { it.row * result.gridCols + it.col }
                } else emptyList()

                val seqMap = mutableMapOf<Int, Int>()
                var num = 1
                for (step in solution.steps) {
                    for (arrow in step.safeArrows) {
                        seqMap[arrow.row * result.gridCols + arrow.col] = num++
                    }
                }

                val displayRect = result.boardBounds?.let { bounds ->
                    val m = DisplayMetrics()
                    windowManager.defaultDisplay.getRealMetrics(m)
                    Rect(
                        bounds.left.coerceAtLeast(0),
                        bounds.top.coerceAtLeast(0),
                        bounds.right.coerceAtMost(m.widthPixels),
                        bounds.bottom.coerceAtMost(m.heightPixels)
                    )
                }

                with(overlayView) {
                    this.boardRect = displayRect
                    this.gridRows = result.gridRows
                    this.gridCols = result.gridCols
                    this.safeArrows = safeList
                    this.allArrows = result.arrows
                    this.arrowSequence = seqMap
                    invalidate()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Overlay update error", e)
            }
        }
    }

    private fun sendBoardInfo(result: BoardDetectionResult, solution: Solution) {
        try {
            val i = Intent("com.arrowssolver.BOARD_UPDATE").apply {
                putExtra("rows", result.gridRows)
                putExtra("cols", result.gridCols)
                putExtra("arrows", result.arrows.size)
                putExtra("safe", solution.steps.firstOrNull()?.safeArrows?.size ?: 0)
                putExtra("complete", solution.isComplete)
            }
            sendBroadcast(i)
        } catch (e: Exception) {
            Log.e(TAG, "sendBoardInfo error", e)
        }
    }

    private fun handleTap(x: Float, y: Float) {
        try {
            if (!::overlayView.isInitialized) return
            val rect = overlayView.boardRect ?: return
            if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) return

            val col = ((x - rect.left) / rect.width() * overlayView.gridCols).toInt()
            val row = ((y - rect.top) / rect.height() * overlayView.gridRows).toInt()
            Toast.makeText(this, "Cell: ($row, $col)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Tap error", e)
        }
    }

    fun requestCapture() {
        if (!isCapturing.getAndSet(true)) {
            try {
                imageReader?.let { captureFrame(it) }
            } catch (e: Exception) {
                isCapturing.set(false)
            }
        }
    }

    private fun releaseMediaProjection() {
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "release error", e)
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
