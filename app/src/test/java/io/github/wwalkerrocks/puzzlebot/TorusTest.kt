package io.github.wwalkerrocks.puzzlebot

import io.github.wwalkerrocks.puzzlebot.core.TorusSolver
import io.github.wwalkerrocks.puzzlebot.core.TorusSolver.Move
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TorusTest {

    private fun scramble(n: Int, rnd: Random): IntArray {
        var b = IntArray(n * n) { it + 1 }
        repeat(300) { b = TorusSolver.apply(b, n, Move(rnd.nextBoolean(), rnd.nextInt(n), rnd.nextInt(1, n))) }
        return b
    }

    @Test
    fun movesMatchTheGame() {
        // Seen on the phone: start 4 8 9 / 1 2 6 / 7 5 3, top row one cell right, then left column one cell down.
        var b = intArrayOf(4, 8, 9, 1, 2, 6, 7, 5, 3)
        b = TorusSolver.apply(b, 3, Move(true, 0, 1))
        assertArrayEquals(intArrayOf(9, 4, 8, 1, 2, 6, 7, 5, 3), b)
        b = TorusSolver.apply(b, 3, Move(false, 0, 1))
        assertArrayEquals(intArrayOf(7, 4, 8, 9, 2, 6, 1, 5, 3), b)
    }

    @Test
    fun solvesTheRealEasyBoardOptimally() {
        // The board from the phone, solved in the game with these 5 swipes.
        val m = TorusSolver.solve(intArrayOf(7, 4, 8, 2, 6, 9, 1, 5, 3), 3)!!
        assertEquals(5, m.size)
        assertTrue(TorusSolver.isSolved(TorusSolver.applyAll(intArrayOf(7, 4, 8, 2, 6, 9, 1, 5, 3), 3, m)))
    }

    @Test
    fun solvesRandomBoardsOfEverySize() {
        val rnd = Random(3)
        for (n in 3..6) {
            var total = 0
            var worstMs = 0.0
            val runs = if (n == 3) 200 else 60
            repeat(runs) {
                val b = scramble(n, rnd)
                val t0 = System.nanoTime()
                val m = TorusSolver.solve(b, n)!!
                worstMs = maxOf(worstMs, (System.nanoTime() - t0) / 1e6)
                assertTrue("$n", TorusSolver.isSolved(TorusSolver.applyAll(b, n, m)))
                assertTrue(m.all { it.shift != 0 && kotlin.math.abs(it.shift) <= n / 2 })
                total += m.size
            }
            println("${n}x$n: avg %.1f swipes, worst solve %.1f ms".format(total.toDouble() / runs, worstMs))
        }
    }

    @Test
    fun rejectsBadBoards() {
        assertNull(TorusSolver.solve(intArrayOf(1, 1, 2, 3, 4, 5, 6, 7, 8), 3))
        // An odd permutation can't happen on a 3x3 torus.
        assertNull(TorusSolver.solve(intArrayOf(2, 1, 3, 4, 5, 6, 7, 8, 9), 3))
    }
}
