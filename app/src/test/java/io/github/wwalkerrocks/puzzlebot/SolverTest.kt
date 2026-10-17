package io.github.wwalkerrocks.puzzlebot

import io.github.wwalkerrocks.puzzlebot.core.Puzzle
import io.github.wwalkerrocks.puzzlebot.core.Solver3
import io.github.wwalkerrocks.puzzlebot.core.Solver4
import io.github.wwalkerrocks.puzzlebot.core.Solver5
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SolverTest {

    companion object {
        val solver4: Solver4 by lazy {
            val t0 = System.nanoTime()
            val s = Solver4(Solver4.buildTables())
            println("4x4 tables built in ${(System.nanoTime() - t0) / 1_000_000} ms")
            s
        }
        val solver5: Solver5 by lazy {
            val t0 = System.nanoTime()
            val s = Solver5.create(solver4)
            println("5x5 tables built in ${(System.nanoTime() - t0) / 1_000_000} ms")
            s
        }
    }

    @Test
    fun solvabilityMatchesRandomWalks() {
        val rnd = Random(1)
        for (n in 3..5) {
            val adj = Puzzle.neighbors(n)
            repeat(200) {
                val b = Puzzle.goal(n)
                var blank = n * n - 1
                repeat(rnd.nextInt(1, 300)) {
                    val nb = adj[blank][rnd.nextInt(adj[blank].size)]
                    b[blank] = b[nb]; b[nb] = 0; blank = nb
                }
                assertTrue(Puzzle.isSolvable(b, n))
                val swapped = b.copyOf()
                val (i, j) = (0 until n * n).filter { swapped[it] != 0 }.take(2)
                val t = swapped[i]; swapped[i] = swapped[j]; swapped[j] = t
                assertFalse(Puzzle.isSolvable(swapped, n))
            }
        }
    }

    @Test
    fun solves3x3Optimally() {
        val rnd = Random(3)
        val s = Solver3()
        var total = 0
        val runs = 300
        val t0 = System.nanoTime()
        repeat(runs) {
            val b = Puzzle.randomSolvable(3, rnd)
            val m = s.solve(b)
            assertTrue(Puzzle.isSolved(Puzzle.applyMoves(b, 3, m)))
            total += m.size
        }
        println("3x3: avg %.1f moves, %.2f ms/solve".format(total.toDouble() / runs, (System.nanoTime() - t0) / 1e6 / runs))
        // Known hardest 3x3 position needs exactly 31 moves.
        assertEquals(31, s.solve(intArrayOf(8, 6, 7, 2, 5, 4, 3, 0, 1)).size)
    }

    @Test
    fun solves4x4() {
        val rnd = Random(4)
        var total = 0L
        var nodes = 0L
        var worst = 0L
        val runs = 60
        val t0 = System.nanoTime()
        repeat(runs) {
            val b = Puzzle.randomSolvable(4, rnd)
            val s0 = System.nanoTime()
            val m = solver4.solve(b)!!
            worst = maxOf(worst, System.nanoTime() - s0)
            assertTrue(Puzzle.isSolved(Puzzle.applyMoves(b, 4, m)))
            total += m.size
            nodes += solver4.lastNodes
        }
        println("4x4 optimal: avg %.1f moves, avg %.1f ms, worst %d ms, avg %d nodes".format(
            total.toDouble() / runs, (System.nanoTime() - t0) / 1e6 / runs, worst / 1_000_000, nodes / runs))
    }

    @Test
    fun solves5x5() {
        solver4
        val rnd = Random(5)
        var total = 0L
        var worst = 0L
        val runs = 40
        val t0 = System.nanoTime()
        repeat(runs) {
            val b = Puzzle.randomSolvable(5, rnd)
            val s0 = System.nanoTime()
            val m = solver5.solve(b)
            worst = maxOf(worst, System.nanoTime() - s0)
            assertTrue(Puzzle.isSolved(Puzzle.applyMoves(b, 5, m)))
            total += m.size
        }
        println("5x5: avg %.1f moves, avg %.1f ms, worst %d ms".format(
            total.toDouble() / runs, (System.nanoTime() - t0) / 1e6 / runs, worst / 1_000_000))
    }
}
