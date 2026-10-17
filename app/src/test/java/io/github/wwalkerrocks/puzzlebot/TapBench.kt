package io.github.wwalkerrocks.puzzlebot

import io.github.wwalkerrocks.puzzlebot.core.Puzzle
import io.github.wwalkerrocks.puzzlebot.core.Solver3
import io.github.wwalkerrocks.puzzlebot.core.Solver5
import io.github.wwalkerrocks.puzzlebot.core.Taps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.random.Random

class TapBench {
    @Test
    fun tapRulesMatchTheGame() {
        // The user's examples, as one row of a 5x5 board.
        val row = intArrayOf(6, 4, 15, 0, 17) + IntArray(20) { if (it == 19) 0 else 100 + it }
        row[24] = 99
        assertArrayEquals(intArrayOf(6, 0, 4, 15, 17), Taps.apply(row, 5, intArrayOf(1)).copyOf(5))
        val row2 = intArrayOf(6, 4, 15, 17, 0) + IntArray(20) { 100 + it }
        assertArrayEquals(intArrayOf(0, 6, 4, 15, 17), Taps.apply(row2, 5, intArrayOf(0)).copyOf(5))
    }

    @Ignore("slow benchmark; run by hand when tuning Solver5")
    @Test
    fun compareCandidateSettings() {
        val s5 = Solver5.create(SolverTest.solver4)
        val boards = List(30) { Puzzle.randomSolvable(5, Random(300 + it)) }
        for (cfg in listOf(intArrayOf(64, 8, 32, 0), intArrayOf(64, 8, 32, 1), intArrayOf(128, 8, 64, 2), intArrayOf(256, 8, 64, 2), intArrayOf(512, 24, 128, 2), intArrayOf(512, 16, 128, 3), intArrayOf(1024, 24, 256, 4))) {
            s5.rowCandidates = cfg[0]; s5.rowKeep = cfg[1]; s5.colCandidates = cfg[2]; s5.extraMoves = cfg[3]
            var taps = 0; var worst = 0L; var planNs = 0L
            val t0 = System.nanoTime()
            for (b in boards) {
                val t1 = System.nanoTime()
                val p = s5.plan(b)
                planNs += System.nanoTime() - t1
                val rest = s5.finish(p)
                worst = maxOf(worst, System.nanoTime() - t1)
                val mid = Puzzle.applyMoves(b, 5, p.moves)
                taps += Taps.count(b.indexOf(0), p.moves) + Taps.count(mid.indexOf(0), rest)
            }
            println("cfg ${cfg.joinToString()}: %.1f taps, plan %.0f ms, total %.0f ms avg, worst %d ms".format(taps / 30.0, planNs / 3e7, (System.nanoTime() - t0) / 3e7, worst / 1_000_000))
        }
    }

    /** Worst cases matter: a slow plan delays the first tap. */
    @Test
    fun planTimeDistribution() {
        val s5 = Solver5.create(SolverTest.solver4)
        val rnd = Random(4242)
        val plan = ArrayList<Long>(); val fin = ArrayList<Long>(); var taps = 0
        val runs = 200
        repeat(runs) {
            val b = Puzzle.randomSolvable(5, rnd)
            val t0 = System.nanoTime()
            val p = s5.plan(b)
            val t1 = System.nanoTime()
            val rest = s5.finish(p)
            fin.add((System.nanoTime() - t1) / 1_000_000); plan.add((t1 - t0) / 1_000_000)
            val mid = Puzzle.applyMoves(b, 5, p.moves)
            val t = Taps.merge(b.indexOf(0), p.moves) + Taps.merge(mid.indexOf(0), rest)
            assertTrue(Puzzle.isSolved(Taps.apply(b, 5, t)))
            taps += t.size
        }
        plan.sort(); fin.sort()
        fun q(l: List<Long>, f: Double) = l[((l.size - 1) * f).toInt()]
        println("5x5 x$runs: %.1f taps | plan p50 %d p90 %d p99 %d max %d ms | finish p50 %d p90 %d max %d ms".format(
            taps.toDouble() / runs, q(plan, .5), q(plan, .9), q(plan, .99), plan.last(), q(fin, .5), q(fin, .9), fin.last()))
    }

    @Test
    fun fiveByFivePhases() {
        val s5 = Solver5.create(SolverTest.solver4)
        val rnd = Random(77)
        var planMs = 0L; var finishMs = 0L; var worstPlan = 0L; var worstFinish = 0L; var taps = 0
        val runs = 40
        repeat(runs) {
            val b = Puzzle.randomSolvable(5, rnd)
            val t0 = System.nanoTime()
            val p = s5.plan(b)
            val t1 = System.nanoTime()
            val rest = s5.finish(p)
            val t2 = System.nanoTime()
            planMs += t1 - t0; finishMs += t2 - t1
            worstPlan = maxOf(worstPlan, t1 - t0); worstFinish = maxOf(worstFinish, t2 - t1)
            val blank = b.indexOf(0)
            val mid = Puzzle.applyMoves(b, 5, p.moves)
            val t = Taps.merge(blank, p.moves) + Taps.merge(mid.indexOf(0), rest)
            assertTrue(Puzzle.isSolved(Taps.apply(b, 5, t)))
            taps += t.size
        }
        println("5x5 pipelined: %.1f taps, plan %.0f ms (worst %d), finish %.0f ms (worst %d)".format(
            taps.toDouble() / runs, planMs / 1e6 / runs, worstPlan / 1_000_000, finishMs / 1e6 / runs, worstFinish / 1_000_000))
    }

    @Test
    fun mergedTapsSolve() {
        val rnd = Random(9)
        val s5 = Solver5.create(SolverTest.solver4)
        for ((n, solve) in listOf<Pair<Int, (IntArray) -> IntArray>>(
            3 to { b -> Solver3().solve(b) },
            4 to { b -> SolverTest.solver4.solve(b)!! },
            5 to { b -> s5.solve(b) },
        )) {
            var moves = 0; var taps = 0; var ms = 0L
            val runs = if (n == 5) 30 else 60
            repeat(runs) {
                val b = Puzzle.randomSolvable(n, rnd)
                val t0 = System.nanoTime()
                val m = solve(b)
                ms += System.nanoTime() - t0
                val blank = b.indexOf(0)
                val simple = Taps.simplify(blank, m)
                val t = Taps.merge(blank, simple)
                assertTrue(Puzzle.isSolved(Taps.apply(b, n, t)))
                moves += m.size; taps += t.size
            }
            println("${n}x$n: %.1f moves -> %.1f taps (%.0f%%), %.0f ms".format(
                moves.toDouble() / runs, taps.toDouble() / runs, 100.0 * taps / moves, ms / 1e6 / runs))
        }
    }
}
