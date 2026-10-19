package io.github.wwalkerrocks.puzzlebot.core

/**
 * Torus Puzzle (also known as Loopover): an n×n board where a swipe shifts a whole row or column
 * by any number of cells, wrapping around. A [Move] is one swipe.
 *
 * - 3×3: optimal (fewest swipes), from a table of all 181,440 reachable positions.
 * - Larger: rows 0..n-3 tile by tile, then the last two rows column by column. Each tile is put
 *   in place with a commutator (A B A⁻¹ B⁻¹ of a row move and a column move), which cycles exactly
 *   three tiles and leaves everything else, including finished rows, untouched. Early tiles try a
 *   1–2 swipe shortcut first.
 *
 * Board: IntArray of n*n, row-major, tile t belongs at index t-1.
 */
object TorusSolver {

    /** Shift row [index] right (or column [index] down) by [shift] cells; negative = left/up. */
    data class Move(val row: Boolean, val index: Int, val shift: Int)

    fun apply(b: IntArray, n: Int, m: Move): IntArray {
        val out = b.copyOf()
        val k = Math.floorMod(m.shift, n)
        if (k == 0) return out
        for (j in 0 until n) {
            if (m.row) out[m.index * n + (j + k) % n] = b[m.index * n + j]
            else out[((j + k) % n) * n + m.index] = b[j * n + m.index]
        }
        return out
    }

    fun applyAll(b: IntArray, n: Int, moves: List<Move>): IntArray = moves.fold(b) { acc, m -> apply(acc, n, m) }

    fun isSolved(b: IntArray) = b.withIndex().all { it.value == it.index + 1 }

    /** Swipes that solve [board], or null if it isn't a valid torus position. */
    fun solve(board: IntArray, n: Int): List<Move>? {
        if (board.size != n * n || !Puzzle.isPermutation(IntArray(n * n) { board[it] - 1 })) return null
        return if (n == 3) Table3.solve(board) else Constructive(board, n).run()
    }

    /** Shortest signed shift for k cells. */
    fun shortest(k: Int, n: Int): Int {
        val m = Math.floorMod(k, n)
        return if (m > n / 2) m - n else m
    }

    /** Joins consecutive swipes on the same line (and drops ones that cancel out). */
    fun merge(moves: List<Move>, n: Int): List<Move> {
        val out = ArrayList<Move>()
        for (m in moves) {
            val last = out.lastOrNull()
            if (last != null && last.row == m.row && last.index == m.index) {
                out.removeAt(out.size - 1)
                val k = shortest(last.shift + m.shift, n)
                if (k != 0) out.add(Move(m.row, m.index, k))
            } else if (shortest(m.shift, n) != 0) {
                out.add(Move(m.row, m.index, shortest(m.shift, n)))
            }
        }
        return out
    }

    private class Constructive(start: IntArray, private val n: Int) {
        private var b = start.copyOf()
        private val moves = ArrayList<Move>()
        private val solved = ArrayList<Int>()
        private val all = buildList {
            for (row in listOf(true, false)) for (i in 0 until n) for (k in 1 until n) add(Move(row, i, shortest(k, n)))
        }

        private fun row(i: Int, k: Int) = Move(true, i, k)
        private fun col(i: Int, k: Int) = Move(false, i, k)
        private fun inv(m: Move) = Move(m.row, m.index, -m.shift)
        private fun comm(a: Move, c: Move) = listOf(a, c, inv(a), inv(c))

        private fun doMoves(seq: List<Move>) {
            for (m in seq) {
                if (Math.floorMod(m.shift, n) == 0) continue
                b = apply(b, n, m)
                moves.add(m)
            }
        }

        private fun pos(tile: Int) = b.indexOf(tile)

        private fun keeps(t: IntArray) = solved.all { t[it] == b[it] }

        /** 1 or 2 swipes that put [tile] at [cell] without disturbing solved cells. */
        private fun shortcut(cell: Int, tile: Int): List<Move>? {
            for (m in all) { val t = apply(b, n, m); if (t[cell] == tile && keeps(t)) return listOf(m) }
            for (m1 in all) {
                val t1 = apply(b, n, m1)
                for (m2 in all) {
                    if (m1.row == m2.row && m1.index == m2.index) continue
                    val t2 = apply(t1, n, m2)
                    if (t2[cell] == tile && keeps(t2)) return listOf(m1, m2)
                }
            }
            return null
        }

        fun run(): List<Move> {
            // Rows 0..n-3, tile by tile.
            for (r in 0 until n - 2) for (c in 0 until n) {
                val cell = r * n + c
                val tile = cell + 1
                if (b[cell] != tile) {
                    val quick = if (solved.size < 2 * n) shortcut(cell, tile) else null
                    if (quick != null) doMoves(quick) else {
                        var p = pos(tile)
                        var tr = p / n
                        var tc = p % n
                        if (tr > r) {
                            if (tc == c) { doMoves(listOf(row(tr, 1))); p = pos(tile); tr = p / n; tc = p % n }
                            // [C(c, tr-r), R(tr, c-tc)] cycles (tr, tc) -> (r, c).
                            doMoves(comm(col(c, tr - r), row(tr, c - tc)))
                        } else {
                            // Same row, further right: [R(r, c-tc), C(c, r-(n-1))] cycles (r, tc) -> (r, c).
                            doMoves(comm(row(r, c - tc), col(c, r - (n - 1))))
                        }
                    }
                    check(b[cell] == tile) { "row phase failed at $r,$c" }
                }
                solved.add(cell)
            }
            // Even sizes: a single row shift is an odd permutation. Make the total even now, so
            // the last two cells (only ever fixed by commutators, which are even) come out right.
            if (n % 2 == 0) {
                var inv = 0
                for (i in b.indices) for (j in i + 1 until b.size) if (b[i] > b[j]) inv++
                if (inv % 2 == 1) doMoves(listOf(row(n - 1, 1)))
            }
            // Last two rows, column by column; the last column falls into place.
            for (c in 0 until n - 1) {
                for (y in listOf(n - 2, n - 1)) {
                    val cell = y * n + c
                    val tile = cell + 1
                    if (b[cell] == tile) continue
                    var p = pos(tile)
                    if (p % n == c) {
                        // Other row of the same column: move it out to the last column first.
                        doMoves(comm(row(p / n, n - 1 - c), col(n - 1, 1)))
                        p = pos(tile)
                    }
                    val ty = p / n
                    val tx = p % n
                    val a = tx - c
                    val options = if (ty == y) {
                        listOf(comm(row(y, a), col(tx, 1)), comm(row(y, a), col(tx, -1)), comm(col(tx, 1), row(y, a)), comm(col(tx, -1), row(y, a)))
                    } else {
                        listOf(comm(row(y, a), col(tx, y - ty)), comm(col(tx, y - ty), row(y, a)))
                    }
                    val seq = options.firstOrNull { s -> val t = applyAll(b, n, s); t[cell] == tile && keeps(t) }
                        ?: error("last-rows phase failed at $y,$c")
                    doMoves(seq)
                }
                solved.add((n - 2) * n + c)
                solved.add((n - 1) * n + c)
            }
            check(isSolved(b)) { "not solved" }
            return merge(moves, n)
        }
    }

    /** 3×3: distance of every reachable position, then walk downhill. */
    private object Table3 {
        private const val N = 3
        private val fact = IntArray(10).also { it[0] = 1; for (i in 1..9) it[i] = it[i - 1] * i }
        private val moves = buildList {
            for (row in listOf(true, false)) for (i in 0 until N) for (k in listOf(1, -1)) add(Move(row, i, k))
        }
        private val dist: ByteArray by lazy { build() }

        private fun rank(b: IntArray): Int {
            var r = 0
            for (i in 0 until 9) {
                var smaller = 0
                for (j in i + 1 until 9) if (b[j] < b[i]) smaller++
                r += smaller * fact[8 - i]
            }
            return r
        }

        private fun unrank(rk: Int): IntArray {
            var r = rk
            val left = (1..9).toMutableList()
            return IntArray(9) { i -> val d = r / fact[8 - i]; r %= fact[8 - i]; left.removeAt(d) }
        }

        private fun build(): ByteArray {
            val d = ByteArray(fact[9]) { -1 }
            val queue = IntArray(fact[9])
            var head = 0
            var tail = 0
            val g = rank(IntArray(9) { it + 1 })
            d[g] = 0; queue[tail++] = g
            while (head < tail) {
                val s = queue[head++]
                val b = unrank(s)
                for (m in moves) {
                    val t = rank(apply(b, N, m))
                    if (d[t] < 0) { d[t] = (d[s] + 1).toByte(); queue[tail++] = t }
                }
            }
            return d
        }

        fun solve(start: IntArray): List<Move>? {
            var b = start
            if (dist[rank(b)] < 0) return null
            val out = ArrayList<Move>()
            while (!isSolved(b)) {
                val here = dist[rank(b)]
                val m = moves.first { dist[rank(apply(b, N, it))] == (here - 1).toByte() }
                b = apply(b, N, m)
                out.add(m)
            }
            return out
        }
    }
}
