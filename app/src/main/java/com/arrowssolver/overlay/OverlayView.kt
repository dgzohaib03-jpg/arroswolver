package com.arrowssolver.overlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.arrowssolver.solver.Arrow
import com.arrowssolver.solver.Direction
import kotlin.math.cos
import kotlin.math.sin

class OverlayView(context: Context) : View(context) {

    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val paintSafe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 76, 175, 80)
        style = Paint.Style.FILL
    }

    private val paintSafeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 76, 175, 80)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val paintArrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val paintBlocked = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 244, 67, 54)
        style = Paint.Style.FILL
    }

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val paintSeqNumber = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 235, 59)
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    var boardRect: Rect? = null
    var gridRows: Int = 0
    var gridCols: Int = 0
    var safeArrows: List<Int> = emptyList()
    var allArrows: List<Arrow> = emptyList()
    var arrowSequence: Map<Int, Int> = emptyMap()
    var arrowColors: Map<Int, Int> = emptyMap()

    private var cellWidth: Float = 0f
    private var cellHeight: Float = 0f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    private var tapCallback: ((Float, Float) -> Unit)? = null

    fun setOnBoardTap(callback: (Float, Float) -> Unit) {
        tapCallback = callback
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = boardRect ?: return

        canvas.drawColor(Color.argb(60, 0, 0, 0))

        cellWidth = rect.width().toFloat() / gridCols
        cellHeight = rect.height().toFloat() / gridRows
        offsetX = rect.left.toFloat()
        offsetY = rect.top.toFloat()

        drawGrid(canvas)

        val cellToSeq = arrowSequence

        for (arrow in allArrows) {
            val cx = offsetX + arrow.col * cellWidth + cellWidth / 2
            val cy = offsetY + arrow.row * cellHeight + cellHeight / 2
            val idx = arrow.row * gridCols + arrow.col

            val isSafe = safeArrows.contains(idx)
            val seqNum = cellToSeq[idx]

            if (isSafe) {
                canvas.drawCircle(cx, cy, minOf(cellWidth, cellHeight) * 0.40f, paintSafe)
                canvas.drawCircle(cx, cy, minOf(cellWidth, cellHeight) * 0.40f, paintSafeBorder)

                val glowPaint = Paint(paintSafeBorder).apply {
                    color = Color.argb(80, 76, 175, 80)
                    strokeWidth = 2f
                }
                canvas.drawCircle(cx, cy, minOf(cellWidth, cellHeight) * 0.48f, glowPaint)
            } else {
                canvas.drawCircle(cx, cy, minOf(cellWidth, cellHeight) * 0.35f, paintBlocked)
            }

            drawArrowShape(canvas, cx, cy, arrow.direction, isSafe)

            if (isSafe) {
                val checkPaint = Paint(paintText).apply {
                    color = Color.argb(255, 255, 255, 255)
                    textSize = minOf(cellWidth, cellHeight) * 0.35f
                    isFakeBoldText = true
                }
                canvas.drawText("\u2713", cx, cy + checkPaint.textSize * 0.35f, checkPaint)
            }

            if (seqNum != null) {
                val numPaint = Paint(paintSeqNumber).apply {
                    textSize = minOf(cellWidth, cellHeight) * 0.25f
                    color = Color.argb(220, 255, 255, 255)
                }
                canvas.drawText(
                    "#$seqNum",
                    cx + cellWidth * 0.3f,
                    cy - cellHeight * 0.3f,
                    numPaint
                )
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        for (r in 0..gridRows) {
            val y = offsetY + r * cellHeight
            canvas.drawLine(offsetX, y, offsetX + gridCols * cellWidth, y, paintGrid)
        }
        for (c in 0..gridCols) {
            val x = offsetX + c * cellWidth
            canvas.drawLine(x, offsetY, x, offsetY + gridRows * cellHeight, paintGrid)
        }
    }

    private fun drawArrowShape(canvas: Canvas, cx: Float, cy: Float, dir: Direction, isSafe: Boolean) {
        val size = minOf(cellWidth, cellHeight) * 0.15f
        val arrowPaint = Paint(paintArrow).apply {
            color = if (isSafe) Color.argb(255, 255, 255, 255)
            else Color.argb(120, 200, 200, 200)
            strokeWidth = size * 0.4f
            strokeCap = Paint.Cap.ROUND
        }

        val endX = cx + dir.dx * size * 1.5f
        val endY = cy + dir.dy * size * 1.5f

        canvas.drawLine(cx, cy, endX, endY, arrowPaint)

        val angle = Math.toRadians(
            when (dir) {
                Direction.UP -> -90.0
                Direction.DOWN -> 90.0
                Direction.LEFT -> 180.0
                Direction.RIGHT -> 0.0
            }
        )

        val tipLen = size * 0.8f
        val tipAngle = Math.toRadians(30.0)

        val ax1 = endX - tipLen * cos(angle - tipAngle).toFloat()
        val ay1 = endY - tipLen * sin(angle - tipAngle).toFloat()
        val ax2 = endX - tipLen * cos(angle + tipAngle).toFloat()
        val ay2 = endY - tipLen * sin(angle + tipAngle).toFloat()

        canvas.drawLine(endX, endY, ax1, ay1, arrowPaint)
        canvas.drawLine(endX, endY, ax2, ay2, arrowPaint)

        arrowPaint.style = Paint.Style.FILL
        val headPath = Path().apply {
            moveTo(endX, endY)
            lineTo(ax1, ay1)
            lineTo(ax2, ay2)
            close()
        }
        canvas.drawPath(headPath, arrowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            tapCallback?.invoke(event.x, event.y)
            return true
        }
        return super.onTouchEvent(event)
    }
}
