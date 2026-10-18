package io.github.wwalkerrocks.puzzlebot.core

/**
 * IDA* that only cares about placing [stageTiles] into their goal cells (all other tiles are
 * "don't care"), never entering [locked] cells. The heuristic is the max over exact distance
 * tables for subsets of the stage tiles, so the stage itself is solved optimally.
 */
class StageSearch(
    private val n: Int,
    private val stageTiles: IntArray,
    private val locked: BooleanArray,
    private val subsets: List<IntArray>,
    private val tables: List<ByteArray>,
) {
    private val cells = n * n
    private val order = Puzzle.straightFirst(n)
    private val m = tables.size
    private val blankMul = IntArray(m)
    private val tileMul = Array(m) { IntArray(cells) } // multiplier of tile t in table j, 0 if absent
    private val idx = IntArray(m)
    private val b = IntArray(cells)
    private val path = IntArray(512)
    private var nodes = 0L
    private var nodeLimit = 0L
    private var nextBound = 0
    private var gw = 1
    private var hw = 1

    init {
        val covered = HashSet<Int>()
        for (j in 0 until m) {
            val pw = PatternDb.powers(cells, subsets[j].size + 1)
            blankMul[j] = pw[subsets[j].size]
            for ((s, t) in subsets[j].withIndex()) { tileMul[j][t] = pw[s]; covered.add(t) }
        }
        require(covered.containsAll(stageTiles.toList())) { "subsets must cover every stage tile" }
    }

    companion object {
        /** Builds the subset tables (all subsets of size [k], or the whole stage if smaller). */
        fun create(n: Int, stageTiles: IntArray, locked: BooleanArray, k: Int): StageSearch {
            val subsets = if (stageTiles.size <= k) listOf(stageTiles) else combinations(stageTiles, k)
            val tables = subsets.map { PatternDb.buildStage(n, it, locked) }
            return StageSearch(n, stageTiles, locked, subsets, tables)
        }

        fun combinations(a: IntArray, k: Int): List<IntArray> {
            val out = ArrayList<IntArray>()
            fun rec(start: Int, acc: IntArray, d: Int) {
                if (d == k) { out.add(acc.copyOf()); return }
                for (i in start until a.size) { acc[d] = a[i]; rec(i + 1, acc, d + 1) }
            }
            rec(0, IntArray(k), 0)
            return out
        }

        private class NodeLimit : RuntimeException(null, null, false, false)
    }

    private fun h(): Int {
        var best = 0
        for (j in 0 until m) {
            val v = tables[j][idx[j]].toInt()
            if (v > best) best = v
        }
        return best
    }

    var lastNodes = 0L
        private set

    private var collected: ArrayList<IntArray>? = null
    private var collectCap = 0
    private var deadline = Long.MAX_VALUE

    private fun load(board: IntArray): Int {
        board.copyInto(b)
        val blank = b.indexOf(0)
        require(!locked[blank]) { "empty space is inside the locked area" }
        for (j in 0 until m) {
            var i = blank * blankMul[j]
            for (p in 0 until cells) { val t = b[p]; if (t != 0) i += p * tileMul[j][t] }
            idx[j] = i
        }
        return blank
    }

    /** Lower bound on the moves this stage needs from [board]. */
    fun estimate(board: IntArray): Int { load(board); return h() }

    /**
     * Up to [cap] different shortest solutions for this stage (they leave the other tiles and the
     * empty space in different places, which matters for the following stages).
     */
    fun solveMany(
        board: IntArray,
        cap: Int,
        maxNodes: Long = Long.MAX_VALUE,
        extra: Int = 0,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): List<IntArray> {
        val best = solve(board, maxNodes) ?: return emptyList()
        if (cap <= 1 || System.nanoTime() > deadlineNanos) return listOf(best)
        val blank = load(board)
        val out = ArrayList<IntArray>()
        collected = out; collectCap = cap; deadline = deadlineNanos
        nodes = 0; nodeLimit = maxNodes
        try {
            nextBound = Int.MAX_VALUE
            dfs(0, best.size + extra, blank, -1)
        } catch (e: NodeLimit) {
        } finally {
            collected = null
            deadline = Long.MAX_VALUE
        }
        return if (out.isEmpty()) listOf(best) else out
    }

    /** Moves (cells the empty space moves to) that place the stage tiles, or null on node limit. */
    fun solve(board: IntArray, maxNodes: Long = Long.MAX_VALUE, gNum: Int = 1, hNum: Int = 1): IntArray? {
        val blank = load(board)
        gw = gNum; hw = hNum
        nodes = 0; nodeLimit = maxNodes
        var bound = hw * h()
        try {
            while (true) {
                nextBound = Int.MAX_VALUE
                val len = dfs(0, bound, blank, -1)
                if (len >= 0) { lastNodes = nodes; return path.copyOf(len) }
                if (nextBound == Int.MAX_VALUE) return null
                bound = nextBound
            }
        } catch (e: NodeLimit) {
            lastNodes = nodes
            return null
        }
    }

    private fun dfs(g: Int, bound: Int, blank: Int, prev: Int): Int {
        val h = h()
        val f = gw * g + hw * h
        if (f > bound) {
            if (f < nextBound) nextBound = f
            return -1
        }
        if (h == 0) {
            val c = collected ?: return g
            c.add(path.copyOf(g))
            return if (c.size >= collectCap) g else -1
        }
        if (++nodes > nodeLimit) throw NodeLimit()
        if (nodes and 1023L == 0L && collected != null && System.nanoTime() > deadline) throw NodeLimit()
        for (nb in order[blank * (cells + 1) + prev + 1]) {
            if (locked[nb]) continue
            val t = b[nb]
            for (j in 0 until m) idx[j] += (nb - blank) * (blankMul[j] - tileMul[j][t])
            b[blank] = t; b[nb] = 0
            path[g] = nb
            val r = dfs(g + 1, bound, nb, blank)
            b[nb] = t; b[blank] = 0
            for (j in 0 until m) idx[j] -= (nb - blank) * (blankMul[j] - tileMul[j][t])
            if (r >= 0) return r
        }
        return -1
    }
}

/**
 * 5x5 solver: place the top row, then the left column (each stage optimal), then solve the
 * remaining 4x4 optimally. Among the many equally short row/column solutions it keeps the ones
 * that leave the easiest 4x4 behind, and it also tries the mirrored board (column first).
 */
class Solver5(
    private val row: StageSearch,
    private val col: StageSearch,
    private val rowFallback: List<StageSearch>,
    private val solver4: Solver4,
) {
    companion object {
        const val N = 5

        /**
         * [first] and [second] together must be the top row plus the left column. The fallback for
         * a slow first stage places its tiles three at a time.
         */
        fun create(
            solver4: Solver4,
            first: IntArray = intArrayOf(1, 2, 3, 4, 5),
            second: IntArray = intArrayOf(6, 11, 16, 21),
        ): Solver5 {
            val frame = (1..5) + listOf(6, 11, 16, 21)
            require((first + second).sorted() == frame.sorted()) { "stages must cover the top row and left column" }
            fun lockedFor(tiles: List<Int>) = BooleanArray(25) { cell -> tiles.any { it - 1 == cell } }
            val one = StageSearch.create(N, first, BooleanArray(25), 3)
            val two = StageSearch.create(N, second, lockedFor(first.toList()), 3)
            val fallback = first.toList().chunked(3).mapIndexed { i, chunk ->
                StageSearch.create(N, chunk.toIntArray(), lockedFor(first.take(i * 3)), 3)
            }
            return Solver5(one, two, fallback, solver4)
        }

        private fun mirrorTile(t: Int) = if (t == 0) 0 else ((t - 1) % N) * N + (t - 1) / N + 1
        private fun mirrorPos(p: Int) = (p % N) * N + p / N

        fun mirror(board: IntArray) = IntArray(25) { p -> mirrorTile(board[mirrorPos(p)]) }

        /** The bottom-right 4x4 of a 5x5 board, relabelled as a 4x4 puzzle. */
        fun subBoard(b: IntArray) = IntArray(16) { i ->
            val t = b[(i / 4 + 1) * N + (i % 4 + 1)]
            if (t == 0) 0 else ((t - 1) / N - 1) * 4 + ((t - 1) % N - 1) + 1
        }
    }

    /** Above this the top row is placed in two cheaper steps (1-3, then 4-5). */
    var rowNodeLimit = 25_000L
    /** Node budget for each attempt at the final 4x4 (optimal first, then inflated heuristics). */
    var solver4NodeLimit = 300_000L
    /** Time for looking at alternative row/column solutions, per orientation. */
    var planBudgetMs = 5L
    /** Also plan column-first (the mirrored board) and keep the better of the two. */
    var tryMirror = true
    /** Shortest top-row solutions considered / kept for a column search / column solutions considered. */
    var rowCandidates = 128
    var rowKeep = 8
    var colCandidates = 64
    /** Also consider stage solutions up to this many moves longer than the shortest (they may need fewer taps). */
    var extraMoves = 2
    /** Taps per move to expect from the unsolved 4x4 when comparing plans. */
    var tapsPerMove4 = 0.72

    /**
     * Top row and left column placed. [moves] are in the caller's coordinates; [score] is the
     * expected total number of taps.
     */
    class Plan internal constructor(val moves: IntArray, internal val board: IntArray, internal val mirrored: Boolean, val score: Double)

    fun solve(board: IntArray): IntArray {
        val p = plan(board)
        return p.moves + finish(p)
    }

    /** The quick part: picks row-first or column-first, whichever is expected to need fewer taps. */
    fun plan(board: IntArray): Plan {
        require(board.size == 25)
        val direct = planOriented(board, false, System.nanoTime() + planBudgetMs * 1_000_000)
        if (!tryMirror) return direct
        val mirrored = planOriented(mirror(board), true, System.nanoTime() + planBudgetMs * 1_000_000)
        return if (mirrored.score < direct.score) mirrored else direct
    }

    /** The slower part: the remaining 4x4. Returns the moves that follow [plan]'s moves. */
    fun finish(plan: Plan): IntArray {
        val sub = subBoard(plan.board)
        val m4 = solver4.solve(sub, solver4NodeLimit)
            ?: solver4.solve(sub, solver4NodeLimit, gNum = 4, hNum = 5)
            ?: solver4.solve(sub, gNum = 2, hNum = 3)!!
        return IntArray(m4.size) {
            val p = (m4[it] / 4 + 1) * N + (m4[it] % 4 + 1)
            if (plan.mirrored) mirrorPos(p) else p
        }
    }

    private fun planOriented(start: IntArray, mirrored: Boolean, deadline: Long): Plan {
        var rowSolutions = row.solveMany(start, rowCandidates, rowNodeLimit, extraMoves, deadline)
        if (rowSolutions.isEmpty()) {
            var b = start
            val m = ArrayList<Int>()
            for (s in rowFallback) { val x = s.solve(b)!!; b = Puzzle.applyMoves(b, N, x); m.addAll(x.asList()) }
            rowSolutions = listOf(m.toIntArray())
        }
        val startBlank = start.indexOf(0)
        val afterRow = rowSolutions.map { it to Puzzle.applyMoves(start, N, it) }
            .sortedBy { Taps.count(startBlank, it.first) + col.estimate(it.second) * 0.75 }
            .take(rowKeep)

        val blank = start.indexOf(0)
        var bestMoves: IntArray? = null
        var bestBoard: IntArray? = null
        var bestScore = Double.MAX_VALUE
        for ((m1, b1) in afterRow) {
            if (bestMoves != null && System.nanoTime() > deadline) break
            for (m2 in col.solveMany(b1, colCandidates, extra = extraMoves, deadlineNanos = deadline)) {
                val moves = Taps.simplify(blank, m1 + m2)
                val b2 = Puzzle.applyMoves(b1, N, m2)
                val score = Taps.count(blank, moves) + tapsPerMove4 * solver4.estimate(subBoard(b2))
                if (score < bestScore) {
                    bestScore = score
                    bestMoves = moves
                    bestBoard = b2
                }
            }
        }
        val moves = if (mirrored) bestMoves!!.map { mirrorPos(it) }.toIntArray() else bestMoves!!
        return Plan(moves, bestBoard!!, mirrored, bestScore)
    }
}
