package com.arrowssolver.solver

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

data class BoardDetectionResult(
    val boardBounds: android.graphics.Rect?,
    val gridRows: Int,
    val gridCols: Int,
    val arrows: List<Arrow>,
    val confidence: Float
)

object ArrowDetector {

    private const val MIN_CELL_SIZE = 30
    private const val MAX_CELL_SIZE = 200
    private const val GRID_LINE_THRESHOLD = 50
    private const val ARROW_PIXEL_RATIO = 0.05f

    fun detectBoard(bitmap: Bitmap): BoardDetectionResult? {
        val width = bitmap.width
        val height = bitmap.height

        val gridLinesH = findHorizontalGridLines(bitmap)
        val gridLinesV = findVerticalGridLines(bitmap)

        if (gridLinesH.size < 3 || gridLinesV.size < 3) {
            return detectByRegionAnalysis(bitmap)
        }

        val rows = gridLinesH.size - 1
        val cols = gridLinesV.size - 1

        val cellWidth = (gridLinesV[1] - gridLinesV[0])
        val cellHeight = (gridLinesH[1] - gridLinesH[0])

        if (cellWidth < MIN_CELL_SIZE || cellHeight < MIN_CELL_SIZE ||
            cellWidth > MAX_CELL_SIZE || cellHeight > MAX_CELL_SIZE
        ) {
            return detectByRegionAnalysis(bitmap)
        }

        val boardLeft = gridLinesV.first()
        val boardTop = gridLinesH.first()
        val boardRight = gridLinesV.last()
        val boardBottom = gridLinesH.last()

        val arrows = mutableListOf<Arrow>()
        var detectedCells = 0

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cellLeft = gridLinesV[c]
                val cellTop = gridLinesH[r]
                val cellRight = gridLinesV[c + 1]
                val cellBottom = gridLinesH[r + 1]

                val dir = detectArrowInCell(bitmap, cellLeft, cellTop, cellRight, cellBottom)
                if (dir != null) {
                    arrows.add(Arrow(r, c, dir))
                    detectedCells++
                }
            }
        }

        val totalCells = rows * cols
        val confidence = if (totalCells > 0) detectedCells.toFloat() / totalCells else 0f

        if (arrows.isEmpty() || confidence < 0.1f) {
            return detectByRegionAnalysis(bitmap)
        }

        return BoardDetectionResult(
            boardBounds = android.graphics.Rect(boardLeft, boardTop, boardRight, boardBottom),
            gridRows = rows,
            gridCols = cols,
            arrows = arrows,
            confidence = confidence
        )
    }

    fun detectByRegionAnalysis(bitmap: Bitmap): BoardDetectionResult? {
        val width = bitmap.width
        val height = bitmap.height

        val sampleRate = 4
        val sampleW = width / sampleRate
        val sampleH = height / sampleRate

        val pixelMap = Array(sampleH) { IntArray(sampleW) }
        for (y in 0 until sampleH) {
            for (x in 0 until sampleW) {
                pixelMap[y][x] = bitmap.getPixel(x * sampleRate, y * sampleRate)
            }
        }

        val bgColor = findBackgroundColor(pixelMap)

        val occupied = Array(sampleH) { BooleanArray(sampleW) }
        for (y in 0 until sampleH) {
            for (x in 0 until sampleW) {
                val pixel = pixelMap[y][x]
                occupied[y][x] = !isSimilarColor(pixel, bgColor, 40)
            }
        }

        val regions = findConnectedRegions(occupied)
        if (regions.isEmpty()) return null

        val mainRegion = regions.maxByOrNull { it.pixels.size } ?: return null
        val bounds = mainRegion.bounds

        val pad = 4
        val bLeft = (bounds.left * sampleRate).coerceAtLeast(0)
        val bTop = (bounds.top * sampleRate).coerceAtLeast(0)
        val bRight = ((bounds.right + 1) * sampleRate).coerceAtMost(width)
        val bBottom = ((bounds.bottom + 1) * sampleRate).coerceAtMost(height)

        val bw = bRight - bLeft
        val bh = bBottom - bTop
        if (bw <= 0 || bh <= 0) return null
        val boardBitmap = Bitmap.createBitmap(bitmap, bLeft, bTop, bw, bh)
        return detectGridFromBoard(boardBitmap, bLeft, bTop)
    }

    private fun detectGridFromBoard(boardBitmap: Bitmap, offsetX: Int, offsetY: Int): BoardDetectionResult? {
        val w = boardBitmap.width
        val h = boardBitmap.height

        if (w < 100 || h < 100) return null

        val sampleRate = 2
        val gray = IntArray((w / sampleRate) * (h / sampleRate))

        for (y in 0 until h / sampleRate) {
            for (x in 0 until w / sampleRate) {
                val p = boardBitmap.getPixel(x * sampleRate, y * sampleRate)
                gray[y * (w / sampleRate) + x] = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
            }
        }

        val cols = estimateGridDimension(gray, w / sampleRate, h / sampleRate, isHorizontal = false)
        val rows = estimateGridDimension(gray, w / sampleRate, h / sampleRate, isHorizontal = true)

        if (rows < 2 || cols < 2 || rows > 20 || cols > 20) return null

        val cellW = w / cols
        val cellH = h / rows

        val arrows = mutableListOf<Arrow>()

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = c * cellW
                val top = r * cellH
                val right = (c + 1) * cellW
                val bottom = (r + 1) * cellH

                val dir = detectArrowInCell(boardBitmap, left, top, right, bottom)
                if (dir != null) {
                    arrows.add(Arrow(r, c, dir))
                }
            }
        }

        return BoardDetectionResult(
            boardBounds = android.graphics.Rect(offsetX, offsetY, offsetX + w, offsetY + h),
            gridRows = rows,
            gridCols = cols,
            arrows = arrows,
            confidence = if (arrows.isNotEmpty()) 0.5f else 0f
        )
    }

    fun detectArrowInCell(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Direction? {
        val cellW = right - left
        val cellH = bottom - top
        if (cellW < 8 || cellH < 8) return null

        val cx = left + cellW / 2
        val cy = top + cellH / 2

        val sampleRadius = minOf(cellW, cellH) / 4
        val samplePixels = mutableListOf<Int>()

        for (y in (cy - sampleRadius) until (cy + sampleRadius)) {
            for (x in (cx - sampleRadius) until (cx + sampleRadius)) {
                if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                    samplePixels.add(bitmap.getPixel(x, y))
                }
            }
        }

        if (samplePixels.isEmpty()) return null

        val avgColor = samplePixels.map { Triple(Color.red(it), Color.green(it), Color.blue(it)) }
            .let { list ->
                Triple(
                    list.map { it.first }.average().toInt(),
                    list.map { it.second }.average().toInt(),
                    list.map { it.third }.average().toInt()
                )
            }

        val bgColor = Triple(
            avgColor.first,
            avgColor.second,
            avgColor.third
        )

        val edgeLen = minOf(cellW, cellH) / 3
        val dirScores = IntArray(4)

        for (i in 1..edgeLen) {
            val px = listOf(
                cx + i, cy,
                cx - i, cy,
                cx, cy + i,
                cx, cy - i
            )

            for (d in 0 until 4) {
                val x = px[d * 2]
                val y = px[d * 2 + 1]
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val p = bitmap.getPixel(x, y)
                    val diff = abs(Color.red(p) - bgColor.first) +
                            abs(Color.green(p) - bgColor.second) +
                            abs(Color.blue(p) - bgColor.third)
                    if (diff > 120) dirScores[d]++
                }
            }
        }

        val totalScore = dirScores.sum()
        val bestDir = dirScores.indices.maxByOrNull { dirScores[it] } ?: return null
        val bestScore = dirScores[bestDir]

        if (totalScore < 3 || bestScore < 2) return null
        if (bestScore.toFloat() / totalScore.toFloat() < 0.35f) return null

        return when (bestDir) {
            0 -> Direction.RIGHT
            1 -> Direction.LEFT
            2 -> Direction.DOWN
            3 -> Direction.UP
            else -> null
        }
    }

    private fun findHorizontalGridLines(bitmap: Bitmap): List<Int> {
        val w = bitmap.width
        val h = bitmap.height
        val lines = mutableListOf<Int>()
        val step = 2

        for (y in 0 until h step step) {
            var darkCount = 0
            for (x in 0 until w step 2) {
                val p = bitmap.getPixel(x, y)
                val l = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                if (l < GRID_LINE_THRESHOLD) darkCount++
            }
            if (darkCount > w / 4) {
                lines.add(y)
            }
        }

        return mergeCloseLines(lines, 6)
    }

    private fun findVerticalGridLines(bitmap: Bitmap): List<Int> {
        val w = bitmap.width
        val h = bitmap.height
        val lines = mutableListOf<Int>()
        val step = 2

        for (x in 0 until w step step) {
            var darkCount = 0
            for (y in 0 until h step 2) {
                val p = bitmap.getPixel(x, y)
                val l = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                if (l < GRID_LINE_THRESHOLD) darkCount++
            }
            if (darkCount > h / 4) {
                lines.add(x)
            }
        }

        return mergeCloseLines(lines, 6)
    }

    private fun mergeCloseLines(lines: List<Int>, threshold: Int): List<Int> {
        if (lines.isEmpty()) return lines

        val merged = mutableListOf<Int>()
        val sorted = lines.sorted()
        var current = sorted[0]
        var count = 1

        for (i in 1 until sorted.size) {
            if (sorted[i] - current <= threshold) {
                current += sorted[i]
                count++
            } else {
                merged.add(current / count)
                current = sorted[i]
                count = 1
            }
        }
        merged.add(current / count)

        if (merged.size < 3) return emptyList()

        val filtered = mutableListOf(merged.first())
        for (i in 1 until merged.size) {
            val gap = merged[i] - merged[i - 1]
            if (gap >= MIN_CELL_SIZE * 0.6) {
                filtered.add(merged[i])
            }
        }

        return if (filtered.size >= 3) filtered else emptyList()
    }

    private fun estimateGridDimension(gray: IntArray, w: Int, h: Int, isHorizontal: Boolean): Int {
        if (isHorizontal) {
            val projections = IntArray(h) { y ->
                var sum = 0
                for (x in 0 until w) sum += gray[y * w + x]
                sum / w
            }
            return estimateDimensionFromProjection(projections)
        } else {
            val projections = IntArray(w) { x ->
                var sum = 0
                for (y in 0 until h) sum += gray[y * w + x]
                sum / h
            }
            return estimateDimensionFromProjection(projections)
        }
    }

    private fun estimateDimensionFromProjection(projection: IntArray): Int {
        val len = projection.size
        var minVal = projection.min()
        var maxVal = projection.max()
        val range = maxVal - minVal
        if (range < 20) return 0

        val threshold = minVal + range / 3
        val segments = mutableListOf<Int>()

        var inSegment = false
        for (i in 0 until len) {
            if (projection[i] < threshold && !inSegment) {
                segments.add(i)
                inSegment = true
            } else if (projection[i] >= threshold && inSegment) {
                inSegment = false
            }
        }

        return segments.size + 1
    }

    data class Region(
        val pixels: Set<Pair<Int, Int>>,
        val bounds: android.graphics.Rect
    )

    private fun findConnectedRegions(occupied: Array<BooleanArray>): List<Region> {
        val h = occupied.size
        if (h == 0) return emptyList()
        val w = occupied[0].size
        val visited = Array(h) { BooleanArray(w) }
        val regions = mutableListOf<Region>()

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (occupied[y][x] && !visited[y][x]) {
                    val pixels = mutableSetOf<Pair<Int, Int>>()
                    val queue = ArrayDeque<Pair<Int, Int>>()
                    queue.add(Pair(x, y))
                    visited[y][x] = true

                    while (queue.isNotEmpty()) {
                        val (cx, cy) = queue.removeFirst()
                        pixels.add(Pair(cx, cy))

                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                if (dx == 0 && dy == 0) continue
                                val nx = cx + dx
                                val ny = cy + dy
                                if (nx in 0 until w && ny in 0 until h &&
                                    occupied[ny][nx] && !visited[ny][nx]
                                ) {
                                    visited[ny][nx] = true
                                    queue.add(Pair(nx, ny))
                                }
                            }
                        }
                    }

                    if (pixels.size > 50) {
                        val minX = pixels.minOf { it.first }
                        val maxX = pixels.maxOf { it.first }
                        val minY = pixels.minOf { it.second }
                        val maxY = pixels.maxOf { it.second }
                        regions.add(
                            Region(
                                pixels,
                                android.graphics.Rect(minX, minY, maxX, maxY)
                            )
                        )
                    }
                }
            }
        }

        return regions
    }

    private fun findBackgroundColor(pixelMap: Array<IntArray>): Triple<Int, Int, Int> {
        val h = pixelMap.size
        val w = pixelMap[0].size
        val corners = listOf(
            pixelMap[0][0], pixelMap[0][w - 1],
            pixelMap[h - 1][0], pixelMap[h - 1][w - 1]
        )
        val avgR = corners.map { Color.red(it) }.average().toInt()
        val avgG = corners.map { Color.green(it) }.average().toInt()
        val avgB = corners.map { Color.blue(it) }.average().toInt()
        return Triple(avgR, avgG, avgB)
    }

    private fun isSimilarColor(c1: Int, c2: Triple<Int, Int, Int>, threshold: Int): Boolean {
        val diff = abs(Color.red(c1) - c2.first) +
                abs(Color.green(c1) - c2.second) +
                abs(Color.blue(c1) - c2.third)
        return diff < threshold
    }
}
