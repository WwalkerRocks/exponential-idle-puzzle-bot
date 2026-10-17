package io.github.wwalkerrocks.puzzlebot

import io.github.wwalkerrocks.puzzlebot.core.BoardReader
import io.github.wwalkerrocks.puzzlebot.core.DigitTemplates
import io.github.wwalkerrocks.puzzlebot.core.GrayImage
import io.github.wwalkerrocks.puzzlebot.core.Grid
import io.github.wwalkerrocks.puzzlebot.core.Puzzle
import io.github.wwalkerrocks.puzzlebot.core.Reading
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

class ReaderTest {

    @Test
    fun readsEveryScreenshotAtBothSizes() {
        for ((name, expected) in Screens.boards) for (w in listOf(923, 1080)) {
            val t0 = System.nanoTime()
            val r = BoardReader.read(Screens.load(name, w))
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue("$name@$w: ${(r as? Reading.Failed)?.reason}", r is Reading.Ok)
            r as Reading.Ok
            println("$name@$w: worst cost %.4f, %.0f ms\n%s".format(r.worstCost, ms, Puzzle.format(r.board, r.grid.n)))
            assertArrayEquals(expected, r.board)
        }
    }

    /** Margin between the right digit and the closest wrong one, per digit (bigger is safer). */
    @Test
    fun digitMargins() {
        val t = DigitTemplates.DEFAULT
        val worst = FloatArray(10) { Float.MAX_VALUE }
        val confusedWith = IntArray(10)
        for ((name, board) in Screens.boards) for (w in listOf(923, 1080)) {
            val img = Screens.load(name, w)
            val grid = BoardReader.detectGrid(img).first!!
            for (cell in board.indices) {
                val v = board[cell]
                if (v == 0) continue
                val digits = if (v >= 10) listOf(v / 10, v % 10) else listOf(v)
                for ((g, d) in BoardReader.readCell(img, grid, cell)!!.zip(digits)) {
                    val right = BoardReader.glyphDistance(g, d, t)
                    for (o in 0..9) if (o != d) {
                        val m = BoardReader.glyphDistance(g, o, t) - right
                        if (m < worst[d]) { worst[d] = m; confusedWith[d] = o }
                    }
                }
            }
        }
        for (d in 0..9) println("digit $d: margin %.4f (closest wrong: ${confusedWith[d]})".format(worst[d]))
        assertTrue(worst.all { it > 0.01f })
    }

    @Test
    fun buttonPointIsOnTheButton() {
        // Button spans x 57..758, y 1668..1788 on the 923-wide screenshots.
        for (name in Screens.boards.keys) {
            val g = BoardReader.detectGrid(Screens.load(name)).first!!
            assertTrue(g.buttonX() in 120f..700f && g.buttonY() in 1690f..1766f)
        }
    }

    /** Copies tile images around to build any arrangement of the tiles in a real screenshot. */
    private fun rearrange(img: GrayImage, grid: Grid, original: IntArray, wanted: IntArray): GrayImage {
        val out = GrayImage(img.width, img.height, img.px.copyOf())
        fun rect(cell: Int): IntArray {
            val c = cell % grid.n
            val r = cell / grid.n
            return intArrayOf(
                (grid.left + c * grid.cellW).roundToInt(), (grid.top + r * grid.cellH).roundToInt(),
                (grid.left + (c + 1) * grid.cellW).roundToInt(), (grid.top + (r + 1) * grid.cellH).roundToInt(),
            )
        }
        for (dst in wanted.indices) {
            val src = original.indexOf(wanted[dst])
            val a = rect(src)
            val b = rect(dst)
            val w = minOf(a[2] - a[0], b[2] - b[0])
            val h = minOf(a[3] - a[1], b[3] - b[1])
            for (y in 0 until h) System.arraycopy(img.px, (a[1] + y) * img.width + a[0], out.px, (b[1] + y) * img.width + b[0], w)
        }
        return out
    }

    @Test
    fun readsRandomArrangementsAndSolvedBoards() {
        for (name in listOf("1.png", "2.png", "3.png")) for (w in listOf(923, 1080)) {
            val img = Screens.load(name, w)
            val original = Screens.boards.getValue(name)
            val grid = BoardReader.detectGrid(img).first!!
            val n = grid.n
            val rnd = Random(w + n)
            repeat(25) {
                val want = Puzzle.randomSolvable(n, rnd)
                val syn = rearrange(img, grid, original, want)
                val r = BoardReader.read(syn)
                if (r !is Reading.Ok) {
                    val sb = StringBuilder()
                    BoardReader.detectGrid(syn, sb)
                    println("FAIL $name@$w")
                    println(Puzzle.format(want, n))
                    println(sb)
                }
                assertTrue("$name@$w: ${(r as? Reading.Failed)?.reason}", r is Reading.Ok)
                assertArrayEquals(want, (r as Reading.Ok).board)
            }
            val solved = BoardReader.read(rearrange(img, grid, original, Puzzle.goal(n)))
            assertTrue(solved is Reading.Ok && solved.solved)
        }
    }

    @Test
    fun torusPuzzleIsNotAFifteenPuzzleInProgress() {
        // Solved torus board: all nine numbers, no empty cell. Reads as "solved" (only the claim button is tapped).
        val r = BoardReader.read(Screens.load("4", 1080))
        assertTrue(r !is Reading.Ok || r.solved)
    }
}
