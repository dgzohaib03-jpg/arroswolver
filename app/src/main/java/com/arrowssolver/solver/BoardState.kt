package com.arrowssolver.solver

enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1),
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0);

    companion object {
        fun fromAngle(angleDeg: Double): Direction = when {
            angleDeg >= 315 || angleDeg < 45 -> RIGHT
            angleDeg >= 45 && angleDeg < 135 -> DOWN
            angleDeg >= 135 && angleDeg < 225 -> LEFT
            else -> UP
        }

        fun fromDelta(dx: Int, dy: Int): Direction = when {
            dx > 0 -> RIGHT
            dx < 0 -> LEFT
            dy > 0 -> DOWN
            dy < 0 -> UP
            else -> RIGHT
        }
    }
}

data class Arrow(
    val row: Int,
    val col: Int,
    val direction: Direction
)

data class BoardState(
    val rows: Int,
    val cols: Int,
    val arrows: List<Arrow>
) {
    private val grid: Array<Array<Direction?>> = Array(rows) { Array(cols) { null } }

    init {
        for (arrow in arrows) {
            grid[arrow.row][arrow.col] = arrow.direction
        }
    }

    fun getArrow(row: Int, col: Int): Direction? {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return null
        return grid[row][col]
    }

    fun hasArrowInDirection(row: Int, col: Int, dir: Direction): Boolean {
        var r = row + dir.dy
        var c = col + dir.dx
        while (r in 0 until rows && c in 0 until cols) {
            if (grid[r][c] != null) return true
            r += dir.dy
            c += dir.dx
        }
        return false
    }

    fun isSafeArrow(row: Int, col: Int): Boolean {
        val dir = grid[row][col] ?: return false
        return !hasArrowInDirection(row, col, dir)
    }

    fun findSafeArrows(): List<Arrow> {
        return arrows.filter { isSafeArrow(it.row, it.col) }
    }

    fun solve(): List<List<Arrow>> {
        val steps = mutableListOf<List<Arrow>>()
        val remaining = arrows.toMutableList()
        val currentGrid = Array(rows) { Array(cols) { false } }
        for (a in remaining) currentGrid[a.row][a.col] = true

        var changed = true
        while (remaining.isNotEmpty() && changed) {
            changed = false
            val safeNow = remaining.filter { (r, c, _) ->
                val dir = arrows.first { it.row == r && it.col == c }.direction
                var hasBlock = false
                var rr = r + dir.dy
                var cc = c + dir.dx
                while (rr in 0 until rows && cc in 0 until cols) {
                    if (currentGrid[rr][cc]) { hasBlock = true; break }
                    rr += dir.dy
                    cc += dir.dx
                }
                !hasBlock
            }
            if (safeNow.isNotEmpty()) {
                steps.add(safeNow)
                remaining.removeAll(safeNow)
                for (a in safeNow) currentGrid[a.row][a.col] = false
                changed = true
            }
        }

        if (remaining.isNotEmpty()) {
            steps.add(remaining.toList())
        }

        return steps
    }
}
