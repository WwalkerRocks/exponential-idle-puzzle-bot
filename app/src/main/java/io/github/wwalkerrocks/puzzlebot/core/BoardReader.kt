package io.github.wwalkerrocks.puzzlebot.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 8-bit luminance image. */
class GrayImage(val width: Int, val height: Int, val px: ByteArray) {
    fun at(x: Int, y: Int) = px[y * width + x].toInt() and 0xFF

    companion object {
        fun fromArgb(argb: IntArray, w: Int, h: Int, reuse: ByteArray? = null): GrayImage {
            val out = if (reuse != null && reuse.size == w * h) reuse else ByteArray(w * h)
            for (i in 0 until w * h) {
                val c = argb[i]
                out[i] = ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
            }
            return GrayImage(w, h, out)
        }
    }
}

/** Board position on screen. Cells are numbered row-major like the board array. */
data class Grid(val n: Int, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cellW get() = (right - left) / n
    val cellH get() = (bottom - top) / n
    fun centerX(cell: Int) = left + (cell % n + 0.5f) * cellW
    fun centerY(cell: Int) = top + (cell / n + 0.5f) * cellH

    /** The wide button under the board ("I Give Up!" / "Great! Claim N stars." / start). */
    fun buttonX() = left + 0.433f * (right - left)
    fun buttonY() = bottom + 0.192f * (right - left)
}

/** Shape of one digit: coverage on a GW x GH grid plus width/height ratio. */
class Glyph(val cells: FloatArray, val aspect: Float, val height: Int)

sealed class Reading {
    /** [glyphs] per cell, so verified readings can refine the digit templates. */
    class Ok(val grid: Grid, val board: IntArray, val worstCost: Float, val glyphs: List<List<Glyph>>) : Reading() {
        val solved get() = Puzzle.isSolved(board) || (board.none { it == 0 } && board.withIndex().all { it.value == it.index + 1 })
    }
    class Failed(val reason: String, val grid: Grid? = null) : Reading()
}

object BoardReader {
    const val GW = 8
    const val GH = 12

    // ---------------------------------------------------------------- grid detection

    private const val LINE_DIFF = 20

    /** Finds the square tile grid by looking for long thin lines (the tile borders). */
    fun detectGrid(img: GrayImage, debug: StringBuilder? = null): Pair<Grid?, String> {
        val w = img.width
        val h = img.height
        val px = img.px
        val k = max(2, w / 220)
        val minRun = (w * 0.07f).toInt()
        val maxGap = 2

        // Vertical lines: per-column runs of "brighter (or darker) than both horizontal neighbours".
        val runStart = IntArray(w) { -1 }
        val runEnd = IntArray(w)
        val colSum = IntArray(w)
        val colTop = IntArray(w) { Int.MAX_VALUE }
        val colBot = IntArray(w) { -1 }
        val rowSum = IntArray(h)

        fun closeCol(x: Int) {
            val len = runEnd[x] - runStart[x] + 1
            if (len >= minRun) {
                colSum[x] += len
                if (runStart[x] < colTop[x]) colTop[x] = runStart[x]
                if (runEnd[x] > colBot[x]) colBot[x] = runEnd[x]
            }
            runStart[x] = -1
        }

        // Pass 1: mark thin vertical (bit 1) and horizontal (bit 2) line pixels.
        val flags = ByteArray(w * h)
        for (y in k until h - k) {
            val o = y * w
            for (x in k until w - k) {
                var f = 0
                if (isLine(px, o + x, 1, k)) f = 1
                if (isLine(px, o + x, w, k)) f = f or 2
                flags[o + x] = f.toByte()
            }
        }
        // Pass 2: long runs. Each pixel also looks one pixel to either side, so borders of tiles
        // in the same column (row) still add up if they are drawn a pixel apart.
        for (y in 1 until h - 1) {
            val o = y * w
            for (x in 1 until w - 1) {
                if ((flags[o + x - 1].toInt() or flags[o + x].toInt() or flags[o + x + 1].toInt()) and 1 != 0) {
                    if (runStart[x] < 0) runStart[x] = y
                    runEnd[x] = y
                } else if (runStart[x] >= 0 && y - runEnd[x] > maxGap) {
                    closeCol(x)
                }
            }
            var rs = -1
            var re = 0
            for (x in 0 until w) {
                if ((flags[o - w + x].toInt() or flags[o + x].toInt() or flags[o + w + x].toInt()) and 2 != 0) {
                    if (rs < 0) rs = x
                    re = x
                } else if (rs >= 0 && x - re > maxGap) {
                    if (re - rs + 1 >= minRun) rowSum[y] += re - rs + 1
                    rs = -1
                }
            }
            if (rs >= 0 && re - rs + 1 >= minRun) rowSum[y] += re - rs + 1
        }
        for (x in 0 until w) if (runStart[x] >= 0) closeCol(x)

        val strong = (w * 0.3f).toInt()
        val cols = clusters(colSum, strong)
        val rows = clusters(rowSum, strong)
        debug?.append("cols=${cols.map { "${it.first}-${it.last}:${colSum[it.first]}/${colTop[it.first]}-${colBot[it.first]}" }}")
            ?.append(" rows=${rows.map { "${it.first}-${it.last}" }}\n")
        if (cols.size < 3) return null to "no tile grid on screen"

        // Choose outer edges and size so that every inner boundary has lines and nothing else does.
        var best: Grid? = null
        for (i in cols.indices) for (j in cols.indices.reversed()) {
            if (j <= i) break
            val left = cols[i].first
            val right = cols[j].last
            val d = (right - left).toFloat()
            if (d < w * 0.45f) continue
            if (best != null && d <= best.right - best.left) continue
            for (n in 3..5) {
                val tol = d * 0.03f
                val bounds = FloatArray(n - 1) { left + d * (it + 1) / n }
                val inner = cols.subList(i + 1, j)
                if (!bounds.all { bx -> inner.any { abs(center(it) - bx) <= tol } }) continue
                if (!inner.all { c -> bounds.any { abs(center(c) - it) <= tol } }) continue

                var top = Int.MAX_VALUE
                var bot = -1
                for (c in i..j) for (x in cols[c]) {
                    if (colSum[x] >= strong) { top = min(top, colTop[x]); bot = max(bot, colBot[x]) }
                }
                if (bot < 0) continue
                // Column runs start after the rounded corner; snap to the real top/bottom lines.
                val t = rows.filter { it.first in (top - d * 0.06f).toInt()..(top + d * 0.01f).toInt() }
                    .minByOrNull { abs(it.first - top) }?.first ?: top
                val b = rows.filter { it.last in (bot - d * 0.01f).toInt()..(bot + d * 0.06f).toInt() }
                    .minByOrNull { abs(it.last - bot) }?.last ?: bot
                if (abs((b - t) - d) > d * 0.04f) continue
                val hb = FloatArray(n - 1) { t + (b - t).toFloat() * (it + 1) / n }
                if (!hb.all { by -> rows.any { abs(center(it) - by) <= tol } }) continue
                best = Grid(n, left.toFloat(), t.toFloat(), right.toFloat(), b.toFloat())
                break
            }
        }
        return if (best == null) null to "found lines but no 3x3/4x4/5x5 grid" else best to "ok"
    }

    /**
     * True if pixel [i] is a thin line: clearly brighter (or darker) than something on both sides
     * within 2..k pixels along [step]. Looking at a window rather than one pixel copes with two
     * tile borders that are only a few pixels apart.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun isLine(px: ByteArray, i: Int, step: Int, k: Int): Boolean {
        val g = px[i].toInt() and 0xFF
        var loA = 255; var hiA = 0; var loB = 255; var hiB = 0
        for (d in 2..k) {
            val a = px[i - d * step].toInt() and 0xFF
            val b = px[i + d * step].toInt() and 0xFF
            if (a < loA) loA = a
            if (a > hiA) hiA = a
            if (b < loB) loB = b
            if (b > hiB) hiB = b
        }
        return (g - loA >= LINE_DIFF && g - loB >= LINE_DIFF) || (hiA - g >= LINE_DIFF && hiB - g >= LINE_DIFF)
    }

    private fun center(r: IntRange) = (r.first + r.last) / 2f

    private fun clusters(sum: IntArray, threshold: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var s = -1
        var e = -1
        for (i in sum.indices) {
            if (sum[i] >= threshold) {
                if (s >= 0 && i - e <= 3) e = i else {
                    if (s >= 0) out.add(s..e)
                    s = i; e = i
                }
            }
        }
        if (s >= 0) out.add(s..e)
        return out
    }

    // ---------------------------------------------------------------- cells and glyphs

    /** Glyphs in a cell (left to right). Empty list = empty space; null = unreadable. */
    fun readCell(img: GrayImage, grid: Grid, cell: Int): List<Glyph>? {
        val cw = grid.cellW
        val ch = grid.cellH
        val x0 = (grid.left + (cell % grid.n) * cw + cw * 0.12f).roundToInt()
        val x1 = (grid.left + (cell % grid.n + 1) * cw - cw * 0.12f).roundToInt()
        val y0 = (grid.top + (cell / grid.n) * ch + ch * 0.12f).roundToInt()
        val y1 = (grid.top + (cell / grid.n + 1) * ch - ch * 0.12f).roundToInt()
        if (x0 < 0 || y0 < 0 || x1 > img.width || y1 > img.height || x1 - x0 < 8 || y1 - y0 < 8) return null

        val hist = IntArray(256)
        for (y in y0 until y1) for (x in x0 until x1) hist[img.at(x, y)]++
        var bg = 0
        for (v in 1 until 256) if (hist[v] > hist[bg]) bg = v
        var contrast = 0
        for (v in 0 until 256) if (hist[v] > 0) contrast = max(contrast, abs(v - bg))
        if (contrast < 60) return emptyList()

        val thr = max(35, contrast / 2)
        val rw = x1 - x0
        val colInk = IntArray(rw)
        for (y in y0 until y1) for (x in x0 until x1) if (abs(img.at(x, y) - bg) >= thr) colInk[x - x0]++

        // Split into glyphs at empty columns; ignore specks.
        val runs = ArrayList<IntArray>() // start, end, mass
        var s = -1
        var mass = 0
        for (x in 0..rw) {
            val v = if (x < rw) colInk[x] else 0
            if (v > 0) { if (s < 0) { s = x; mass = 0 }; mass += v } else if (s >= 0) { runs.add(intArrayOf(s, x - 1, mass)); s = -1 }
        }
        val maxMass = runs.maxOfOrNull { it[2] } ?: return emptyList()
        if (maxMass < 20) return emptyList()
        val glyphRuns = runs.filter { it[2] >= maxMass * 0.08f }
        if (glyphRuns.size > 2) return null

        val lo = contrast * 0.2f
        val span = contrast * 0.6f
        return glyphRuns.map { r ->
            val gx0 = x0 + r[0]
            val gx1 = x0 + r[1]
            var gy0 = Int.MAX_VALUE
            var gy1 = -1
            for (y in y0 until y1) for (x in gx0..gx1) if (abs(img.at(x, y) - bg) >= thr) {
                if (y < gy0) gy0 = y
                if (y > gy1) gy1 = y
            }
            val gw = gx1 - gx0 + 1
            val gh = gy1 - gy0 + 1
            val sum = FloatArray(GW * GH)
            val cnt = IntArray(GW * GH)
            for (y in gy0..gy1) for (x in gx0..gx1) {
                val i = ((y - gy0) * GH / gh) * GW + (x - gx0) * GW / gw
                sum[i] += ((abs(img.at(x, y) - bg) - lo) / span).coerceIn(0f, 1f)
                cnt[i]++
            }
            for (i in sum.indices) if (cnt[i] > 0) sum[i] /= cnt[i]
            Glyph(sum, gw.toFloat() / gh, gh)
        }
    }

    // ---------------------------------------------------------------- labelling

    fun glyphDistance(g: Glyph, digit: Int, templates: DigitTemplates): Float {
        val t = templates.cells[digit]
        var d = 0f
        for (i in t.indices) { val e = g.cells[i] - t[i]; d += e * e }
        val a = g.aspect - templates.aspect[digit]
        return d / t.size + 2f * a * a
    }

    private fun digitsOf(v: Int) = if (v >= 10) intArrayOf(v / 10, v % 10) else intArrayOf(v)

    fun read(img: GrayImage, templates: DigitTemplates = DigitTemplates.DEFAULT, maxCost: Float = 0.08f): Reading {
        val (grid, why) = detectGrid(img)
        if (grid == null) return Reading.Failed(why)
        val n = grid.n
        val cells = n * n
        val glyphs = arrayOfNulls<List<Glyph>>(cells)
        val empties = ArrayList<Int>()
        for (c in 0 until cells) {
            val g = readCell(img, grid, c) ?: return Reading.Failed("cell ${c + 1} is unreadable", grid)
            glyphs[c] = g
            if (g.isEmpty()) empties.add(c)
        }
        if (empties.size > 1) return Reading.Failed("${empties.size} empty cells (board still moving?)", grid)

        val heights = glyphs.flatMap { it!! }.map { it.height }.sorted()
        val med = heights[heights.size / 2]
        if (heights.first() < med * 0.8f || heights.last() > med * 1.2f) return Reading.Failed("digit sizes differ", grid)

        // Every number appears once, so pick the cheapest one-to-one assignment.
        val tiles = (0 until cells).filter { it !in empties }
        val numbers = if (empties.isEmpty()) (1..cells).toList() else (1 until cells).toList()
        val cost = Array(tiles.size) { ti ->
            val g = glyphs[tiles[ti]]!!
            DoubleArray(numbers.size) { ni ->
                val ds = digitsOf(numbers[ni])
                if (ds.size != g.size) 1e3 else ds.indices.sumOf { glyphDistance(g[it], ds[it], templates).toDouble() }
            }
        }
        val assign = Hungarian.solve(cost)
        val board = IntArray(cells)
        var worst = 0f
        for (ti in tiles.indices) {
            board[tiles[ti]] = numbers[assign[ti]]
            worst = max(worst, cost[ti][assign[ti]].toFloat() / glyphs[tiles[ti]]!!.size)
        }
        if (worst > maxCost) return Reading.Failed("digits don't match the templates (%.3f)".format(worst), grid)
        val ok = Reading.Ok(grid, board, worst, glyphs.map { it!! })
        if (empties.isEmpty()) return if (ok.solved) ok else Reading.Failed("no empty cell", grid)
        if (!Puzzle.isSolvable(board, n)) return Reading.Failed("read an impossible position", grid)
        return ok
    }
}

/** Minimum-cost perfect matching (rows to columns) for small square matrices. */
object Hungarian {
    fun solve(a: Array<DoubleArray>): IntArray {
        val n = a.size
        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(n + 1) { Double.MAX_VALUE }
            val used = BooleanArray(n + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.MAX_VALUE
                var j1 = 0
                for (j in 1..n) if (!used[j]) {
                    val cur = a[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..n) if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val ans = IntArray(n)
        for (j in 1..n) if (p[j] != 0) ans[p[j] - 1] = j - 1
        return ans
    }
}
