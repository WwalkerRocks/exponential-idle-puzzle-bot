package io.github.wwalkerrocks.puzzlebot.core

/**
 * 4x4 solver: IDA* with a disjoint 5-5-5 additive pattern database, plus the same lookup on the
 * board mirrored across the main diagonal (the tile groups are chosen so the mirror maps each
 * group onto itself). With weight 1 the result is optimal.
 */
class Solver4(private val pdb: Array<ByteArray>, private val patterns: Array<IntArray> = PATTERNS) {

    companion object {
        /** Tile groups; the mirror image of every group is also a group (here: 1st<->2nd, 3rd itself). */
        val PATTERNS = arrayOf(
            intArrayOf(3, 4, 7, 8, 12),
            intArrayOf(9, 13, 10, 14, 15),
            intArrayOf(1, 2, 5, 6, 11),
        )

        fun buildTables(patterns: Array<IntArray> = PATTERNS): Array<ByteArray> =
            Array(patterns.size) { PatternDb.buildAdditive(4, patterns[it]) }

        private val ORDER = Puzzle.straightFirst(4)
    }

    private val patOf = IntArray(16) { -1 }
    private val shiftOf = IntArray(16)   // 4 * slot of the tile in its pattern
    private val patT = IntArray(16)      // group of this tile's mirror image
    private val shiftT = IntArray(16)    // shift of this tile's mirror image in the mirrored index
    private val trp = IntArray(16) { (it % 4) * 4 + it / 4 } // mirrored cell

    init {
        for (p in patterns.indices) for ((s, t) in patterns[p].withIndex()) {
            patOf[t] = p; shiftOf[t] = 4 * s
        }
        for (t in 1..15) {
            val mirror = trp[t - 1] + 1
            patT[t] = patOf[mirror]
            shiftT[t] = shiftOf[mirror]
        }
        for (p in patterns.indices) {
            val m = patterns[p].map { trp[it - 1] + 1 }.toSet()
            require(patterns.any { it.toSet() == m }) { "the mirror image of every group must be a group" }
        }
    }

    private val b = IntArray(16)
    private val idx = IntArray(3)
    private val idxT = IntArray(3)
    private val path = IntArray(512)
    private var hA = 0
    private var hT = 0
    private var nodes = 0L
    private var nodeLimit = 0L
    private var nextBound = 0
    private var gw = 1
    private var hw = 1

    var lastNodes = 0L
        private set

    private fun lookup(p: Int, i: Int) = pdb[p][i].toInt()

    /** Heuristic estimate (moves) for an arbitrary 4x4 board. */
    fun estimate(board: IntArray): Int {
        load(board)
        return maxOf(hA, hT)
    }

    private fun load(board: IntArray) {
        board.copyInto(b)
        idx.fill(0); idxT.fill(0)
        for (pos in 0 until 16) {
            val t = b[pos]
            if (t == 0) continue
            idx[patOf[t]] += pos shl shiftOf[t]
            idxT[patT[t]] += trp[pos] shl shiftT[t]
        }
        hA = 0; hT = 0
        for (p in 0 until 3) { hA += lookup(p, idx[p]); hT += lookup(p, idxT[p]) }
    }

    /**
     * Returns the moves (cells the empty space moves to), or null if [maxNodes] was exceeded.
     * weight = hNum/gNum inflates the heuristic for a faster, slightly longer solution.
     */
    fun solve(board: IntArray, maxNodes: Long = Long.MAX_VALUE, gNum: Int = 1, hNum: Int = 1): IntArray? {
        require(board.size == 16)
        load(board)
        gw = gNum; hw = hNum
        nodes = 0; nodeLimit = maxNodes
        val blank = b.indexOf(0)
        var bound = hw * maxOf(hA, hT)
        try {
            while (true) {
                nextBound = Int.MAX_VALUE
                val len = dfs(0, bound, blank, -1)
                if (len >= 0) {
                    lastNodes = nodes
                    return path.copyOf(len)
                }
                if (nextBound == Int.MAX_VALUE) return null
                bound = nextBound
            }
        } catch (e: NodeLimit) {
            lastNodes = nodes
            return null
        }
    }

    private class NodeLimit : RuntimeException(null, null, false, false)

    private fun dfs(g: Int, bound: Int, blank: Int, prev: Int): Int {
        val h = if (hA > hT) hA else hT
        val f = gw * g + hw * h
        if (f > bound) {
            if (f < nextBound) nextBound = f
            return -1
        }
        if (h == 0) return g
        if (++nodes > nodeLimit) throw NodeLimit()
        for (nb in ORDER[blank * 17 + prev + 1]) {
            val t = b[nb]
            val p = patOf[t]
            val q = patT[t]
            val oldI = idx[p]
            val oldIT = idxT[q]
            val newI = oldI + ((blank - nb) shl shiftOf[t])
            val newIT = oldIT + ((trp[blank] - trp[nb]) shl shiftT[t])
            val dA = lookup(p, newI) - lookup(p, oldI)
            val dT = lookup(q, newIT) - lookup(q, oldIT)
            idx[p] = newI; idxT[q] = newIT; hA += dA; hT += dT
            b[blank] = t; b[nb] = 0
            path[g] = nb
            val r = dfs(g + 1, bound, nb, blank)
            b[nb] = t; b[blank] = 0
            idx[p] = oldI; idxT[q] = oldIT; hA -= dA; hT -= dT
            if (r >= 0) return r
        }
        return -1
    }
}
