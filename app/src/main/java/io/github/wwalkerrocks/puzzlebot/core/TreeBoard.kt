package io.github.wwalkerrocks.puzzlebot.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** A tile as the game's accessibility tree reports it: screen bounds and its number. */
class TreeTile(val left: Int, val top: Int, val right: Int, val bottom: Int, val value: Int)

/**
 * Turns the tile views of the game's board into a board array. [aligned] is false while tiles are
 * still sliding (a tile is between cells), in which case [board] must not be used.
 */
class TreeBoard(val n: Int, val board: IntArray, val grid: Grid, val aligned: Boolean) {
    val solved get() = aligned && (Puzzle.isSolved(board) || board.withIndex().all { it.value == it.index + 1 })

    companion object {
        /** Null if this doesn't look like a 3x3/4x4/5x5 board. */
        fun from(left: Int, top: Int, right: Int, bottom: Int, tiles: List<TreeTile>): TreeBoard? {
            if (tiles.size < 8) return null
            val size = (right - left).toFloat()
            if (size <= 0 || abs((bottom - top) - size) > size * 0.05f) return null
            val tileW = tiles.map { it.right - it.left }.sorted()[tiles.size / 2]
            if (tileW <= 0) return null
            val n = (size / tileW).roundToInt()
            if (n !in 3..5 || tiles.size !in n * n - 1..n * n) return null
            val cell = size / n
            val board = IntArray(n * n)
            var aligned = true
            for (t in tiles) {
                if (t.value !in 1..n * n) return null
                val fx = ((t.left + t.right) / 2f - left) / cell
                val fy = ((t.top + t.bottom) / 2f - top) / cell
                val c = fx.toInt().coerceIn(0, n - 1)
                val r = fy.toInt().coerceIn(0, n - 1)
                if (abs(fx - c - 0.5f) > 0.12f || abs(fy - r - 0.5f) > 0.12f) aligned = false
                val i = r * n + c
                if (board[i] != 0) aligned = false else board[i] = t.value
            }
            if (aligned && !Puzzle.isPermutation(board) && tiles.size == n * n - 1) aligned = false
            val grid = Grid(n, left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            return TreeBoard(n, board, grid, aligned)
        }

        /** The star reward in a button text such as "Great! Claim 22 stars." (any language). */
        fun reward(buttonText: CharSequence?): Int? =
            buttonText?.let { Regex("\\d[\\d,.  ]*").find(it)?.value?.filter(Char::isDigit)?.toIntOrNull() }
    }
}
