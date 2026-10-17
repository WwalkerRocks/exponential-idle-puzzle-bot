package io.github.wwalkerrocks.puzzlebot.core

import kotlin.random.Random

/**
 * Board convention used everywhere: IntArray of size n*n in row-major order.
 * Value = tile number (1..n*n-1), 0 = empty space. Goal: tile t at index t-1, empty space last.
 * A move is written as the cell index the empty space moves to, which is also the tile you tap.
 */
object Puzzle {
    fun goal(n: Int) = IntArray(n * n) { if (it == n * n - 1) 0 else it + 1 }

    fun isSolved(b: IntArray): Boolean {
        for (i in 0 until b.size - 1) if (b[i] != i + 1) return false
        return b[b.size - 1] == 0
    }

    fun isPermutation(b: IntArray): Boolean {
        val seen = BooleanArray(b.size)
        for (v in b) {
            if (v < 0 || v >= b.size || seen[v]) return false
            seen[v] = true
        }
        return true
    }

    fun isSolvable(b: IntArray, n: Int): Boolean {
        var inv = 0
        for (i in b.indices) {
            if (b[i] == 0) continue
            for (j in i + 1 until b.size) if (b[j] != 0 && b[i] > b[j]) inv++
        }
        if (n % 2 == 1) return inv % 2 == 0
        val blankRowFromBottom = n - b.indexOf(0) / n // 1-based
        return (inv + blankRowFromBottom) % 2 == 1
    }

    fun neighbors(n: Int): Array<IntArray> = Array(n * n) { p ->
        val r = p / n
        val c = p % n
        val out = ArrayList<Int>(4)
        if (r > 0) out.add(p - n)
        if (r < n - 1) out.add(p + n)
        if (c > 0) out.add(p - 1)
        if (c < n - 1) out.add(p + 1)
        out.toIntArray()
    }

    /**
     * Neighbour lists for every (empty cell, previous empty cell) pair, without the cell we just
     * came from and with "keep going straight" first. Straight runs become single taps.
     * Index: blank * (n*n + 1) + prev + 1 (prev = -1 at the start).
     */
    fun straightFirst(n: Int): Array<IntArray> {
        val adj = neighbors(n)
        val cells = n * n
        return Array(cells * (cells + 1)) { i ->
            val blank = i / (cells + 1)
            val prev = i % (cells + 1) - 1
            val straight = if (prev >= 0) 2 * blank - prev else -1
            val list = adj[blank].filter { it != prev }
            (list.filter { it == straight } + list.filter { it != straight }).toIntArray()
        }
    }

    /** Applies moves (cells the empty space moves to). Throws if a move is not adjacent. */
    fun applyMoves(board: IntArray, n: Int, moves: IntArray): IntArray {
        val b = board.copyOf()
        var blank = b.indexOf(0)
        for (m in moves) {
            val dr = Math.abs(m / n - blank / n)
            val dc = Math.abs(m % n - blank % n)
            require(dr + dc == 1) { "illegal move $m from blank $blank" }
            b[blank] = b[m]
            b[m] = 0
            blank = m
        }
        return b
    }

    fun randomSolvable(n: Int, rnd: Random): IntArray {
        while (true) {
            val b = IntArray(n * n) { it }
            for (i in b.size - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = b[i]; b[i] = b[j]; b[j] = t
            }
            if (isSolvable(b, n)) return b
        }
    }

    fun format(b: IntArray, n: Int): String = buildString {
        for (r in 0 until n) {
            for (c in 0 until n) {
                val v = b[r * n + c]
                append(if (v == 0) "  ." else "%3d".format(v))
            }
            if (r < n - 1) append('\n')
        }
    }
}
