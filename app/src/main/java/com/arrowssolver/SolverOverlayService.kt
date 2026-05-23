package com.arrowssolver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import com.arrowssolver.overlay.OverlayView
import com.arrowssolver.solver.*
import java.util.concurrent.atomic.AtomicBoolean

class SolverOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "solver_overlay_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.arrowssolver.ACTION_START"
        const val ACTION_STOP = "com.arrowssolver.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        var isRunning = AtomicBoolean(false)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: OverlayView
    private var overlayParams: WindowManager.LayoutParams? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var resultCode: Int = 0
    private var data: Intent? = null

    private var detectedBoard: BoardDetectionResult? = null
    private var currentSolution: Solution? = null

    private val handler = Handler(Looper.getMainLooper())
    private val captureHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                data = intent.getParcelableExtra(EXTRA_DATA)

                startForeground(NOTIFICATION_ID, createNotification())
                showOverlay()
                isRunning.set(true)

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
        hideOverlay()
        releaseMediaProjection()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Arrows Solver Overlay",
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
        overlayView = OverlayView(this)
        overlayView.setOnBoardTap { x, y ->
            handleTap(x, y)
        }

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
            overlayParams?.let { params ->
                windowManager.addView(overlayView, params)
                overlayView.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to show overlay: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideOverlay() {
        try {
            overlayView.visibility = View.GONE
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            // view may not be attached
        }
    }

    private fun setupMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, data!!)
        startCapture()
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isCapturing.get()) {
                captureHandler.post { captureFrame(reader) }
            }
        }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SolverCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private val isCapturing = AtomicBoolean(false)

    private fun captureFrame(reader: ImageReader) {
        if (isCapturing.getAndSet(true)) return

        try {
            val image = reader.acquireLatestImage() ?: run {
                isCapturing.set(false)
                return
            }

            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val pixels = IntArray(image.width * image.height)
            buffer.rewind()
            for (row in 0 until image.height) {
                for (col in 0 until image.width) {
                    val pos = row * rowStride + col * pixelStride
                    val r = buffer.get(pos).toInt() and 0xFF
                    val g = buffer.get(pos + 1).toInt() and 0xFF
                    val b = buffer.get(pos + 2).toInt() and 0xFF
                    val a = buffer.get(pos + 3).toInt() and 0xFF
                    pixels[row * image.width + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            val bitmap = android.graphics.Bitmap.createBitmap(
                image.width, image.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)

            image.close()

            analyzeBoard(croppedBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isCapturing.set(false)
        }
    }

    private fun analyzeBoard(bitmap: android.graphics.Bitmap) {
        val result = ArrowDetector.detectBoard(bitmap)
        if (result != null && result.arrows.isNotEmpty()) {
            detectedBoard = result
            val board = BoardState(result.gridRows, result.gridCols, result.arrows)
            val solution = EscapeSolver.solve(board)
            currentSolution = solution

            updateOverlay(result, solution)
            sendBoardInfo(result, solution)
        }
    }

    private fun updateOverlay(result: BoardDetectionResult, solution: Solution) {
        handler.post {
            val safeArrows = if (solution.steps.isNotEmpty()) {
                val firstStep = solution.steps.first().safeArrows
                firstStep.map { it.row * result.gridCols + it.col }
            } else emptyList()

            val cellToSeq = mutableMapOf<Int, Int>()
            var seqNum = 1
            for (step in solution.steps) {
                for (arrow in step.safeArrows) {
                    val idx = arrow.row * result.gridCols + arrow.col
                    cellToSeq[idx] = seqNum++
                }
            }

            val boardRect = result.boardBounds
            val displayRect = if (boardRect != null) {
                val metrics = DisplayMetrics()
                windowManager.defaultDisplay.getRealMetrics(metrics)
                Rect(
                    boardRect.left,
                    boardRect.top,
                    boardRect.right.coerceAtMost(metrics.widthPixels),
                    boardRect.bottom.coerceAtMost(metrics.heightPixels)
                )
            } else {
                null
            }

            overlayView.apply {
                boardRect = displayRect
                gridRows = result.gridRows
                gridCols = result.gridCols
                safeArrows = safeArrows
                allArrows = result.arrows
                arrowSequence = cellToSeq
                invalidate()
            }
        }
    }

    private fun sendBoardInfo(result: BoardDetectionResult, solution: Solution) {
        val intent = Intent("com.arrowssolver.BOARD_UPDATE")
        intent.putExtra("rows", result.gridRows)
        intent.putExtra("cols", result.gridCols)
        intent.putExtra("arrows", result.arrows.size)
        intent.putExtra("safe", solution.steps.firstOrNull()?.safeArrows?.size ?: 0)
        intent.putExtra("complete", solution.isComplete)
        sendBroadcast(intent)
    }

    private fun handleTap(x: Float, y: Float) {
        val rect = overlayView.boardRect ?: return
        if (x < rect.left || x > rect.right || y < rect.top || y > rect.bottom) return

        val col = ((x - rect.left) / rect.width() * overlayView.gridCols).toInt()
        val row = ((y - rect.top) / rect.height() * overlayView.gridRows).toInt()

        if (row in 0 until overlayView.gridRows && col in 0 until overlayView.gridCols) {
            Toast.makeText(
                this,
                "Tapped cell: ($row, $col)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun requestCapture() {
        if (!isCapturing.getAndSet(true)) {
            imageReader?.let { reader ->
                captureFrame(reader)
            }
        }
    }

    private fun releaseMediaProjection() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
