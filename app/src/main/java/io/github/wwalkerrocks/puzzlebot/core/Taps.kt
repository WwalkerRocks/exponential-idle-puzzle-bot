package io.github.wwalkerrocks.puzzlebot.core

/**
 * Turns single-tile moves into taps. The game slides every tile between the tapped tile and the
 * empty space when they share a row or column, so a straight run of moves is one tap.
 */
object Taps {
    /** Removes moves that are immediately undone (can appear where two solver stages meet). */
    fun simplify(startBlank: Int, moves: IntArray): IntArray {
        val out = IntArray(moves.size)
        val before = IntArray(moves.size) // blank position before each kept move
        var size = 0
        var blank = startBlank
        for (m in moves) {
            if (size > 0 && before[size - 1] == m) {
                size--
            } else {
                before[size] = blank
                out[size++] = m
            }
            blank = m
        }
        return out.copyOf(size)
    }

    /** Merges straight runs: each element is the cell to tap (the tile farthest from the empty space). */
    fun merge(startBlank: Int, moves: IntArray): IntArray {
        val out = ArrayList<Int>(moves.size)
        var blank = startBlank
        var dir = 0
        for (m in moves) {
            val d = m - blank
            if (out.isNotEmpty() && d == dir) out[out.size - 1] = m else { out.add(m); dir = d }
            blank = m
        }
        return out.toIntArray()
    }

    fun count(startBlank: Int, moves: IntArray) = merge(startBlank, moves).size

    /** Applies taps with the game's rules (multi-tile slides along a row or column). */
    fun apply(board: IntArray, n: Int, taps: IntArray): IntArray {
        val b = board.copyOf()
        var blank = b.indexOf(0)
        for (t in taps) {
            val step = when {
                t / n == blank / n && t != blank -> if (t > blank) 1 else -1
                t % n == blank % n && t != blank -> if (t > blank) n else -n
                else -> throw IllegalArgumentException("tap $t not in line with empty space $blank")
            }
            while (blank != t) { b[blank] = b[blank + step]; blank += step }
            b[blank] = 0
        }
        return b
    }
}
