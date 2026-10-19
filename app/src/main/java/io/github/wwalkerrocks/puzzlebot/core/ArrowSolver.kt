package io.github.wwalkerrocks.puzzlebot.core

import kotlin.math.hypot

/**
 * Arrow Puzzle: tapping a tile turns it and every touching tile one step clockwise (90° on the
 * square boards, 60° on Expert, 180° on Hard). Directions are numbers mod m (0 = up), so the taps
 * needed form a linear system: state[i] + sum(taps[j] for j touching i) = 0 (mod m).
 */
object ArrowSolver {

    /** Tiles turned by a tap on each tile: itself and every tile whose centre is within 1.5 tile pitches. */
    fun neighbours(x: FloatArray, y: FloatArray): Array<IntArray> {
        val n = x.size
        var pitch = Float.MAX_VALUE
        for (i in 0 until n) for (j in i + 1 until n) pitch = minOf(pitch, hypot(x[i] - x[j], y[i] - y[j]))
        return Array(n) { i -> (0 until n).filter { j -> hypot(x[i] - x[j], y[i] - y[j]) < 1.5f * pitch }.toIntArray() }
    }

    /**
     * Taps per tile (0..m-1) that make every arrow point up, using as few taps as it can find, or
     * null if the position can't be solved (which means it was misread).
     */
    fun solve(state: IntArray, touching: Array<IntArray>, m: Int, maxLeaves: Int = 20_000): IntArray? {
        val n = state.size
        val a = Array(n) { IntArray(n + 1) }
        for (j in 0 until n) for (i in touching[j]) a[i][j] = 1
        for (i in 0 until n) a[i][n] = Math.floorMod(-state[i], m)

        // Row reduction over Z_m using Euclid between rows (only invertible row operations, so
        // the set of solutions is unchanged even though m need not be prime).
        val pivotRow = ArrayList<Int>()
        val pivotCol = ArrayList<Int>()
        var r = 0
        for (c in 0 until n) {
            if (r >= n) break
            for (i in r + 1 until n) {
                while (a[i][c] != 0) {
                    if (a[r][c] == 0) { val t = a[r]; a[r] = a[i]; a[i] = t; continue }
                    val q = a[r][c] / a[i][c]
                    if (q != 0) for (k in c..n) a[r][k] = Math.floorMod(a[r][k] - q * a[i][k], m)
                    val t = a[r]; a[r] = a[i]; a[i] = t
                }
            }
            if (a[r][c] != 0) { pivotRow.add(r); pivotCol.add(c); r++ }
        }
        for (i in r until n) if (a[i][n] != 0) return null

        val isPivot = BooleanArray(n)
        for (c in pivotCol) isPivot[c] = true
        val free = (0 until n).filter { !isPivot[it] }
        val x = IntArray(n)
        var best: IntArray? = null
        var bestTotal = Int.MAX_VALUE
        var leaves = 0

        fun back(k: Int, total: Int) {
            if (leaves >= maxLeaves || total >= bestTotal) return
            if (k < 0) {
                best = x.copyOf(); bestTotal = total; leaves++
                return
            }
            val row = pivotRow[k]
            val col = pivotCol[k]
            val g = a[row][col]
            var rhs = a[row][n]
            for (j in col + 1 until n) rhs -= a[row][j] * x[j]
            rhs = Math.floorMod(rhs, m)
            val d = gcd(g, m)
            if (rhs % d != 0) return
            val mm = m / d
            val x0 = if (mm == 1) 0 else Math.floorMod((rhs / d) * inverse(g / d % mm, mm), mm)
            val options = (0 until d).map { (x0 + it * mm) % m }.sorted()
            for (v in options) {
                x[col] = v
                back(k - 1, total + v)
            }
            x[col] = 0
        }

        // Free tiles (if any) can take any value; try them all when there are few.
        var combos = 1L
        for (f in free) { combos *= m; if (combos > 50_000) break }
        if (combos <= 50_000) {
            val digits = IntArray(free.size)
            while (true) {
                for ((i, f) in free.withIndex()) x[f] = digits[i]
                back(pivotRow.size - 1, digits.sum())
                var i = 0
                while (i < digits.size && ++digits[i] == m) { digits[i] = 0; i++ }
                if (i == digits.size) break
            }
        } else {
            back(pivotRow.size - 1, 0)
        }
        return best
    }

    /**
     * The same solver with the board shape's work done once: the row reduction, and every
     * combination of taps that changes nothing (the "kernel"; 3,888 of them on the Expert board).
     * Each board then needs one back-substitution plus a scan of those combinations for the
     * fewest taps.
     */
    class Prepared(touching: Array<IntArray>, private val m: Int) {
        private val n = touching.size
        private val e: Array<IntArray> // reduced matrix: e = t * a (mod m)
        private val t: Array<IntArray> // the row operations used
        private val pivotRow = ArrayList<Int>()
        private val pivotCol = ArrayList<Int>()
        private val rank: Int
        val kernel: List<IntArray>? // null if there are too many to list (then [solve] searches instead)
        private val touching = touching

        init {
            val a = Array(n) { IntArray(2 * n) }
            for (j in 0 until n) for (i in touching[j]) a[i][j] = 1
            for (i in 0 until n) a[i][n + i] = 1
            var r = 0
            for (c in 0 until n) {
                if (r >= n) break
                for (i in r + 1 until n) {
                    while (a[i][c] != 0) {
                        if (a[r][c] == 0) { val tmp = a[r]; a[r] = a[i]; a[i] = tmp; continue }
                        val q = a[r][c] / a[i][c]
                        if (q != 0) for (k in 0 until 2 * n) a[r][k] = Math.floorMod(a[r][k] - q * a[i][k], m)
                        val tmp = a[r]; a[r] = a[i]; a[i] = tmp
                    }
                }
                if (a[r][c] != 0) { pivotRow.add(r); pivotCol.add(c); r++ }
            }
            rank = r
            e = Array(n) { a[it].copyOf(n) }
            t = Array(n) { a[it].copyOfRange(n, 2 * n) }
            kernel = allSolutions(IntArray(n), 100_000)
        }

        /**
         * Solutions of e x = b' (b' already transformed). More than [cap]: null if
         * [tooManyIsError], else just the first [cap].
         */
        private fun allSolutions(bp: IntArray, cap: Int, tooManyIsError: Boolean = true): List<IntArray>? {
            val isPivot = BooleanArray(n)
            for (c in pivotCol) isPivot[c] = true
            val free = (0 until n).filter { !isPivot[it] }
            val out = ArrayList<IntArray>()
            val x = IntArray(n)
            var overflow = false
            fun back(k: Int) {
                if (overflow) return
                if (k < 0) {
                    if (out.size >= cap) { overflow = true; return }
                    out.add(x.copyOf())
                    if (!tooManyIsError && out.size >= cap) overflow = true
                    return
                }
                val row = pivotRow[k]
                val col = pivotCol[k]
                val g = e[row][col]
                var rhs = bp[row]
                for (j in col + 1 until n) rhs -= e[row][j] * x[j]
                rhs = Math.floorMod(rhs, m)
                val d = gcd(g, m)
                if (rhs % d != 0) return
                val mm = m / d
                val x0 = if (mm == 1) 0 else Math.floorMod((rhs / d) * inverse(g / d % mm, mm), mm)
                for (opt in 0 until d) {
                    x[col] = (x0 + opt * mm) % m
                    back(k - 1)
                }
                x[col] = 0
            }
            val digits = IntArray(free.size)
            while (true) {
                for ((i, f) in free.withIndex()) x[f] = digits[i]
                back(pivotRow.size - 1)
                if (overflow) return if (tooManyIsError) null else out
                var i = 0
                while (i < digits.size && ++digits[i] == m) { digits[i] = 0; i++ }
                if (i == digits.size) break
            }
            return out
        }

        /** Same contract as [ArrowSolver.solve]. */
        fun solve(state: IntArray): IntArray? {
            val k = kernel ?: return ArrowSolver.solve(state, touching, m)
            val b = IntArray(n) { Math.floorMod(-state[it], m) }
            val bp = IntArray(n)
            for (i in 0 until n) {
                var v = 0
                val row = t[i]
                for (j in 0 until n) v += row[j] * b[j]
                bp[i] = Math.floorMod(v, m)
            }
            for (i in rank until n) if (bp[i] != 0) return null
            val x = allSolutions(bp, 1, tooManyIsError = false)?.firstOrNull() ?: return null
            var best = x
            var bestTotal = x.sum()
            val y = IntArray(n)
            for (z in k) {
                var total = 0
                for (i in 0 until n) { val v = (x[i] + z[i]) % m; y[i] = v; total += v }
                if (total < bestTotal) { bestTotal = total; best = y.copyOf() }
            }
            return best
        }
    }

    /** Taps in an order that never hits the same tile twice in a row (keeps fast taps distinct). */
    fun tapOrder(taps: IntArray): IntArray {
        val left = taps.copyOf()
        val out = ArrayList<Int>(taps.sum())
        while (true) {
            var any = false
            for (i in left.indices) if (left[i] > 0) { out.add(i); left[i]--; any = true }
            if (!any) break
        }
        return out.toIntArray()
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun inverse(a: Int, m: Int): Int {
        for (v in 1 until m) if (a * v % m == 1) return v
        throw ArithmeticException("$a has no inverse mod $m")
    }
}
