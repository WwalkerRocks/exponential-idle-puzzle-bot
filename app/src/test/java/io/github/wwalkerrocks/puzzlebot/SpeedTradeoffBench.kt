package io.github.wwalkerrocks.puzzlebot

import io.github.wwalkerrocks.puzzlebot.core.Puzzle
import io.github.wwalkerrocks.puzzlebot.core.Solver5
import io.github.wwalkerrocks.puzzlebot.core.Taps
import org.junit.Ignore
import org.junit.Test
import kotlin.random.Random

/** Thinking time vs taps for 5x5. Phone ~3x slower than PC; a tap costs ~2 ms on the test phone. */

@Ignore("benchmark; run by hand when tuning Solver5")
class SpeedTradeoffBench {
    @Test
    fun compare() {
        val boards = List(150) { Puzzle.randomSolvable(5, Random(900 + it)) }
        data class Cfg(val name: String, val cand: Int, val keep: Int, val col: Int, val extra: Int, val budget: Long, val mirror: Boolean, val limit4: Long, val rowLimit: Long = 400_000)
        val cfgs = listOf(
            Cfg("row 25k", 128, 8, 64, 2, 5, true, 300_000, 25_000),
            Cfg("row 10k", 128, 8, 64, 2, 5, true, 300_000, 10_000),
            Cfg("row always split", 128, 8, 64, 2, 5, true, 300_000, 1),
            Cfg("row 10k, budget 10ms", 128, 8, 64, 2, 10, true, 300_000, 10_000),
        )
        for (c in cfgs) {
            val s = Solver5.create(SolverTest.solver4)
            s.rowCandidates = c.cand; s.rowKeep = c.keep; s.colCandidates = c.col; s.extraMoves = c.extra
            s.planBudgetMs = c.budget; s.tryMirror = c.mirror; s.solver4NodeLimit = c.limit4; s.rowNodeLimit = c.rowLimit
            repeat(5) { s.solve(boards[it]) } // warm up
            val plan = ArrayList<Double>(); val fin = ArrayList<Double>(); var taps = 0; var phone = 0.0
            for (b in boards) {
                val t0 = System.nanoTime(); val p = s.plan(b); val t1 = System.nanoTime(); val rest = s.finish(p); val t2 = System.nanoTime()
                val mid = Puzzle.applyMoves(b, 5, p.moves)
                val t1n = Taps.count(b.indexOf(0), p.moves); val t2n = Taps.count(mid.indexOf(0), rest)
                taps += t1n + t2n
                val planMs = (t1 - t0) / 1e6; val finMs = (t2 - t1) / 1e6
                plan.add(planMs); fin.add(finMs)
                // Phone estimate: plan, then tap part 1 while the 4x4 solves, then part 2.
                phone += 3 * planMs + maxOf(2.0 * t1n, 3 * finMs) + 2.0 * t2n
            }
            plan.sort(); fin.sort()
            println("%-22s taps %.1f | plan p50 %.1f p90 %.1f p99 %.1f ms | finish p50 %.1f p90 %.1f ms | est. phone %.0f ms/board".format(
                c.name, taps / 150.0, plan[75], plan[135], plan[148], fin[75], fin[135], phone / 150))
        }
    }
}
