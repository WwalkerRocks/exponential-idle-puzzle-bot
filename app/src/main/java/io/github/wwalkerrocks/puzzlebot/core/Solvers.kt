package io.github.wwalkerrocks.puzzlebot.core

/** Lazily built solvers for each board size. Building the 4x4/5x5 tables takes a moment once. */
object Solvers {
    @Volatile private var s3: Solver3? = null
    @Volatile private var s4: Solver4? = null
    @Volatile private var s5: Solver5? = null

    /** Stars the game pays per solved board. */
    fun stars(n: Int) = when (n) { 3 -> 1; 4 -> 3; 5 -> 6; else -> 0 }

    @Synchronized
    fun prepare(n: Int) {
        when (n) {
            3 -> if (s3 == null) s3 = Solver3()
            4 -> if (s4 == null) s4 = Solver4(Solver4.buildTables())
            5 -> {
                prepare(4)
                if (s5 == null) s5 = Solver5.create(s4!!)
            }
            else -> throw IllegalArgumentException("unsupported size $n")
        }
    }

    fun isReady(n: Int) = when (n) { 3 -> s3 != null; 4 -> s4 != null; 5 -> s5 != null; else -> false }

    /** Moves (single-tile, in order) that solve [board]. */
    @Synchronized
    fun solve(board: IntArray, n: Int): IntArray {
        prepare(n)
        return when (n) {
            3 -> s3!!.solve(board)
            4 -> s4!!.solve(board, 1_000_000L)
                ?: s4!!.solve(board, 1_000_000L, gNum = 4, hNum = 5)
                ?: s4!!.solve(board, gNum = 2, hNum = 3)!!
            else -> s5!!.solve(board)
        }
    }

    /** 5x5 in two parts, so tapping the first part can start while the second is computed. */
    @Synchronized
    fun plan5(board: IntArray): Solver5.Plan {
        prepare(5)
        return s5!!.plan(board)
    }

    @Synchronized
    fun finish5(plan: Solver5.Plan): IntArray = s5!!.finish(plan)
}
