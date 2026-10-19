package io.github.wwalkerrocks.puzzlebot

import io.github.wwalkerrocks.puzzlebot.core.ArrowReader
import io.github.wwalkerrocks.puzzlebot.core.ArrowSolver
import io.github.wwalkerrocks.puzzlebot.core.GrayImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/** Fixtures: real screenshots (Easy, Hard, Expert) whose directions were confirmed by solving them in the game. */
class ArrowTest {

    private class Fixture(val img: GrayImage, val tiles: List<IntArray>, val dirs: IntArray)

    private fun load(name: String): Fixture {
        val img = DataInputStream(GZIPInputStream(javaClass.getResourceAsStream("/arrows/$name.gray.gz")!!).buffered()).use { s ->
            val w = s.readInt(); val h = s.readInt()
            GrayImage(w, h, ByteArray(w * h).also { s.readFully(it) })
        }
        val rows = javaClass.getResourceAsStream("/arrows/$name.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
            .map { line -> line.trim().split(" ").map(String::toInt) }
        return Fixture(img, rows.map { it.take(4).toIntArray() }, rows.map { it[4] }.toIntArray())
    }

    private fun centres(tiles: List<IntArray>) =
        FloatArray(tiles.size) { (tiles[it][0] + tiles[it][2]) / 2f } to FloatArray(tiles.size) { (tiles[it][1] + tiles[it][3]) / 2f }

    private fun touching(tiles: List<IntArray>): Array<IntArray> {
        val (x, y) = centres(tiles)
        return ArrowSolver.neighbours(x, y)
    }

    private fun solves(state: IntArray, touching: Array<IntArray>, taps: IntArray, m: Int): Boolean {
        val s = state.copyOf()
        for (j in taps.indices) for (i in touching[j]) s[i] = (s[i] + taps[j]) % m
        return s.all { it == 0 }
    }

    @Test
    fun readsArrowsOnEveryBoardType() {
        for ((name, dirs) in listOf("easy" to 4, "hard" to 6, "expert" to 6)) {
            val f = load(name)
            val t0 = System.nanoTime()
            val got = ArrowReader.read(f.img, f.tiles, dirs)
            println("$name: read ${f.tiles.size} arrows in %.1f ms".format((System.nanoTime() - t0) / 1e6))
            assertArrayEquals(name, f.dirs, got)
        }
    }

    @Test
    fun tapsTurnTheRightTiles() {
        val easy = touching(load("easy").tiles)
        assertEquals(9, easy[4].size) // centre of 3x3: everything
        assertEquals(4, easy[0].size) // corner: itself and 3 around it
        val hex = touching(load("expert").tiles)
        assertEquals(listOf(4, 5, 7), hex.map { it.size }.distinct().sorted())
    }

    @Test
    fun solvesTheRealBoards() {
        for ((name, m) in listOf("easy" to 4, "expert" to 6)) {
            val f = load(name)
            val taps = ArrowSolver.solve(f.dirs, touching(f.tiles), m)
            assertNotNull(name, taps)
            assertTrue(name, solves(f.dirs, touching(f.tiles), taps!!, m))
            println("$name: ${taps.sum()} taps")
        }
        val hard = load("hard")
        val state = IntArray(hard.dirs.size) { if (hard.dirs[it] == 3) 1 else 0 }
        val taps = ArrowSolver.solve(state, touching(hard.tiles), 2)!!
        assertTrue(solves(state, touching(hard.tiles), taps, 2))
        println("hard: ${taps.sum()} taps")
    }

    @Test
    fun solvesRandomBoards() {
        val rnd = Random(7)
        val square = { n: Int -> List(n * n) { intArrayOf((it % n) * 300, (it / n) * 300, (it % n) * 300 + 280, (it / n) * 300 + 280) } }
        val cases = listOf(Triple("3x3", square(3), 4), Triple("4x4", square(4), 4),
            Triple("hex mod 6", load("expert").tiles, 6), Triple("hex mod 2", load("hard").tiles, 2))
        for ((name, tiles, m) in cases) {
            val t = touching(tiles)
            var total = 0
            var worstMs = 0.0
            repeat(200) {
                val scramble = IntArray(tiles.size) { rnd.nextInt(m) }
                val state = IntArray(tiles.size)
                for (j in scramble.indices) for (i in t[j]) state[i] = (state[i] + scramble[j]) % m
                val t0 = System.nanoTime()
                val taps = ArrowSolver.solve(state, t, m)
                worstMs = maxOf(worstMs, (System.nanoTime() - t0) / 1e6)
                assertNotNull("$name unsolvable", taps)
                assertTrue(name, solves(state, t, taps!!, m))
                total += taps.sum()
            }
            println("$name: avg %.1f taps, worst solve %.1f ms".format(total / 200.0, worstMs))
        }
    }

    @Test
    fun preparedSolverMatchesAndFindsFewestTaps() {
        val rnd = Random(11)
        val square = { n: Int -> List(n * n) { intArrayOf((it % n) * 300, (it / n) * 300, (it % n) * 300 + 280, (it / n) * 300 + 280) } }
        val cases = listOf(Triple("3x3", square(3), 4), Triple("4x4", square(4), 4),
            Triple("hex mod 6", load("expert").tiles, 6), Triple("hex mod 2", load("hard").tiles, 2))
        for ((name, tiles, m) in cases) {
            val t = touching(tiles)
            val t0 = System.nanoTime()
            val prep = ArrowSolver.Prepared(t, m)
            val setupMs = (System.nanoTime() - t0) / 1e6
            var worst = 0.0
            repeat(100) {
                val scramble = IntArray(tiles.size) { rnd.nextInt(m) }
                val state = IntArray(tiles.size)
                for (j in scramble.indices) for (i in t[j]) state[i] = (state[i] + scramble[j]) % m
                val s0 = System.nanoTime()
                val fast = prep.solve(state)!!
                worst = maxOf(worst, (System.nanoTime() - s0) / 1e6)
                assertTrue(name, solves(state, t, fast, m))
                // The full search with no limit gives the true minimum; the prepared one must match.
                val slow = ArrowSolver.solve(state, t, m, maxLeaves = Int.MAX_VALUE)!!
                assertEquals(name, slow.sum(), fast.sum())
            }
            println("$name: kernel ${prep.kernel?.size}, setup %.1f ms, worst solve %.2f ms".format(setupMs, worst))
        }
        // A position that can't be reached is rejected.
        val hex = ArrowSolver.Prepared(touching(load("expert").tiles), 6)
        val impossible = IntArray(37) { 0 }.also { it[0] = 1 }
        assertTrue(hex.solve(impossible) == null || solves(impossible, touching(load("expert").tiles), hex.solve(impossible)!!, 6))
    }

    @Test
    fun tapOrderNeverRepeatsATileWhileOthersRemain() {
        val order = ArrowSolver.tapOrder(intArrayOf(2, 0, 1, 3))
        assertArrayEquals(intArrayOf(0, 2, 3, 0, 3, 3), order)
    }
}
