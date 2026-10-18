package io.github.wwalkerrocks.puzzlebot

import io.github.wwalkerrocks.puzzlebot.core.Puzzle
import io.github.wwalkerrocks.puzzlebot.core.TreeBoard
import io.github.wwalkerrocks.puzzlebot.core.TreeTile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses tile bounds exactly as the game's accessibility tree reported them on a 1080x2340 phone. */
class TreeBoardTest {
    private val dump = listOf(
        10 to intArrayOf(66, 1463, 256, 1653), 11 to intArrayOf(66, 1653, 256, 1842),
        12 to intArrayOf(446, 1463, 636, 1653), 13 to intArrayOf(66, 894, 256, 1084),
        14 to intArrayOf(635, 1463, 825, 1653), 15 to intArrayOf(256, 1653, 446, 1842),
        16 to intArrayOf(256, 1274, 446, 1464), 17 to intArrayOf(825, 894, 1014, 1084),
        18 to intArrayOf(825, 1274, 1014, 1464), 19 to intArrayOf(66, 1274, 256, 1464),
        20 to intArrayOf(635, 1653, 825, 1842), 21 to intArrayOf(446, 1653, 636, 1842),
        22 to intArrayOf(256, 894, 446, 1084), 23 to intArrayOf(825, 1084, 1014, 1274),
        24 to intArrayOf(825, 1463, 1014, 1653), 1 to intArrayOf(635, 894, 825, 1084),
        2 to intArrayOf(256, 1463, 446, 1653), 3 to intArrayOf(825, 1653, 1014, 1842),
        4 to intArrayOf(446, 894, 636, 1084), 5 to intArrayOf(635, 1274, 825, 1464),
        6 to intArrayOf(446, 1084, 636, 1274), 7 to intArrayOf(256, 1084, 446, 1274),
        8 to intArrayOf(66, 1084, 256, 1274), 9 to intArrayOf(446, 1274, 636, 1464),
    )

    private fun tiles(shift: Map<Int, Int> = emptyMap()) =
        dump.map { (v, r) -> val dx = shift[v] ?: 0; TreeTile(r[0] + dx, r[1], r[2] + dx, r[3], v) }

    @Test
    fun readsTheBoardFromTileBounds() {
        val b = TreeBoard.from(66, 894, 1014, 1842, tiles())!!
        assertEquals(5, b.n)
        assertTrue(b.aligned)
        assertArrayEquals(intArrayOf(
            13, 22, 4, 1, 17,
            8, 7, 6, 0, 23,
            19, 16, 9, 5, 18,
            10, 2, 12, 14, 24,
            11, 15, 21, 20, 3,
        ), b.board)
        assertTrue(Puzzle.isSolvable(b.board, 5))
        assertFalse(b.solved)
    }

    @Test
    fun aTileHalfwayBetweenCellsMeansStillMoving() {
        // Tile 6 sliding right towards the empty cell.
        assertFalse(TreeBoard.from(66, 894, 1014, 1842, tiles(mapOf(6 to 95)))!!.aligned)
        // A few pixels off is still fine.
        assertTrue(TreeBoard.from(66, 894, 1014, 1842, tiles(mapOf(6 to 6)))!!.aligned)
    }

    @Test
    fun recognisesSolvedBoards() {
        val cell = (1014 - 66) / 5f
        val solved = (1..24).map { v ->
            val c = (v - 1) % 5; val r = (v - 1) / 5
            TreeTile((66 + c * cell).toInt(), (894 + r * cell).toInt(), (66 + (c + 1) * cell).toInt(), (894 + (r + 1) * cell).toInt(), v)
        }
        assertTrue(TreeBoard.from(66, 894, 1014, 1842, solved)!!.solved)
    }

    @Test
    fun rejectsThingsThatAreNotABoard() {
        assertNull(TreeBoard.from(66, 894, 1014, 1842, tiles().take(5)))
        assertNull(TreeBoard.from(66, 894, 1014, 1500, tiles()))
    }

    @Test
    fun readsTheReward() {
        assertEquals(22, TreeBoard.reward("Great! Claim 22 stars."))
        assertEquals(1, TreeBoard.reward("Great! Claim 1 star."))
        assertEquals(1250, TreeBoard.reward("Super ! Récupérez 1 250 étoiles."))
        assertNull(TreeBoard.reward("I Give Up!"))
    }
}
