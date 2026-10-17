package io.github.wwalkerrocks.puzzlebot.core

/** 3x3 solver: IDA* with Manhattan distance + linear conflicts. Optimal, and instant for 3x3. */
class Solver3 {
    private val n = 3
    private val order = Puzzle.straightFirst(n)
    private val b = IntArray(9)
    private val path = IntArray(64)
    private var nextBound = 0

    private fun h(): Int {
        var md = 0
        for (p in 0 until 9) {
            val t = b[p]
            if (t == 0) continue
            md += Math.abs(p / n - (t - 1) / n) + Math.abs(p % n - (t - 1) % n)
        }
        // Linear conflicts: tiles already in their goal row (column) but out of order. Each tile
        // that has to leave the line to let the others pass costs 2 extra moves.
        val line = IntArray(n)
        for (r in 0 until n) {
            var cnt = 0
            for (c in 0 until n) { val t = b[r * n + c]; if (t != 0 && (t - 1) / n == r) line[cnt++] = t }
            md += 2 * (cnt - lis(line, cnt))
        }
        for (c in 0 until n) {
            var cnt = 0
            for (r in 0 until n) { val t = b[r * n + c]; if (t != 0 && (t - 1) % n == c) line[cnt++] = t }
            md += 2 * (cnt - lis(line, cnt))
        }
        return md
    }

    private fun lis(a: IntArray, len: Int): Int {
        var best = 0
        val l = IntArray(len)
        for (i in 0 until len) {
            l[i] = 1
            for (j in 0 until i) if (a[j] < a[i] && l[j] + 1 > l[i]) l[i] = l[j] + 1
            if (l[i] > best) best = l[i]
        }
        return best
    }

    fun solve(board: IntArray): IntArray {
        require(board.size == 9)
        board.copyInto(b)
        var bound = h()
        val blank = b.indexOf(0)
        while (true) {
            nextBound = Int.MAX_VALUE
            val len = dfs(0, bound, blank, -1)
            if (len >= 0) return path.copyOf(len)
            bound = nextBound
        }
    }

    private fun dfs(g: Int, bound: Int, blank: Int, prev: Int): Int {
        val h = h()
        if (g + h > bound) {
            if (g + h < nextBound) nextBound = g + h
            return -1
        }
        if (h == 0) return g
        for (nb in order[blank * 10 + prev + 1]) {
            b[blank] = b[nb]; b[nb] = 0
            path[g] = nb
            val r = dfs(g + 1, bound, nb, blank)
            b[nb] = b[blank]; b[blank] = 0
            if (r >= 0) return r
        }
        return -1
    }
}
