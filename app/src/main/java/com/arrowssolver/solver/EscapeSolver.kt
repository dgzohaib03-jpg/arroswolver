package com.arrowssolver.solver

data class SolvedStep(
    val stepNumber: Int,
    val safeArrows: List<Arrow>,
    val remainingCount: Int
)

data class Solution(
    val initialBoard: BoardState,
    val steps: List<SolvedStep>,
    val isComplete: Boolean,
    val finalRemainingCount: Int
)

object EscapeSolver {

    fun solve(board: BoardState): Solution {
        val steps = mutableListOf<SolvedStep>()
        val remaining = mutableListOf<Arrow>()
        remaining.addAll(board.arrows)

        val currentGrid = Array(board.rows) { Array(board.cols) { false } }
        for (a in remaining) currentGrid[a.row][a.col] = true

        var stepNum = 0
        var changed = true

        while (remaining.isNotEmpty() && changed) {
            changed = false
            stepNum++

            val safeNow = remaining.filter { (r, c) ->
                val dir = remaining.first { it.row == r && it.col == c }.direction
                var hasBlock = false
                var rr = r + dir.dy
                var cc = c + dir.dx
                while (rr in 0 until board.rows && cc in 0 until board.cols) {
                    if (currentGrid[rr][cc]) {
                        hasBlock = true
                        break
                    }
                    rr += dir.dy
                    cc += dir.dx
                }
                !hasBlock
            }

            if (safeNow.isNotEmpty()) {
                steps.add(
                    SolvedStep(
                        stepNumber = stepNum,
                        safeArrows = safeNow,
                        remainingCount = remaining.size - safeNow.size
                    )
                )
                remaining.removeAll(safeNow)
                for (a in safeNow) currentGrid[a.row][a.col] = false
                changed = true
            }
        }

        if (remaining.isNotEmpty()) {
            steps.add(
                SolvedStep(
                    stepNumber = stepNum + 1,
                    safeArrows = remaining.toList(),
                    remainingCount = 0
                )
            )
        }

        return Solution(
            initialBoard = board,
            steps = steps,
            isComplete = remaining.isEmpty(),
            finalRemainingCount = remaining.size
        )
    }

    fun findSafeArrows(board: BoardState): List<Arrow> {
        return board.arrows.filter { (r, c, dir) ->
            var hasBlock = false
            var rr = r + dir.dy
            var cc = c + dir.dx
            while (rr in 0 until board.rows && cc in 0 until board.cols) {
                if (board.getArrow(rr, cc) != null) {
                    hasBlock = true
                    break
                }
                rr += dir.dy
                cc += dir.dx
            }
            !hasBlock
        }
    }
}
