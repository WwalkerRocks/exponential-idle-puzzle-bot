package io.github.wwalkerrocks.puzzlebot.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads which way each arrow points from a screenshot. Each tile shows a chevron (like "^"). Seen
 * from its pointing direction, a chevron is narrow at the front (its tip) and wide at the back
 * (the two ends), so for each possible direction we compare the sideways spread of the front
 * half with the back half and keep the direction where the back is widest relative to the front.
 */
object ArrowReader {

    /** Directions (0 = up, then clockwise in 360/[dirs]° steps), or null if any arrow is unclear. */
    fun read(img: GrayImage, tiles: List<IntArray>, dirs: Int, minMargin: Float = 1.5f): IntArray? {
        val out = IntArray(tiles.size)
        for ((i, t) in tiles.withIndex()) out[i] = readOne(img, t, dirs, minMargin) ?: return null
        return out
    }

    /** One tile given as [left, top, right, bottom] in screen pixels. */
    fun readOne(img: GrayImage, t: IntArray, dirs: Int, minMargin: Float = 1.5f): Int? {
        val cx = (t[0] + t[2]) / 2f
        val cy = (t[1] + t[3]) / 2f
        val rad = (t[2] - t[0]) / 2f * 0.62f
        val x0 = (cx - rad).toInt().coerceAtLeast(0)
        val x1 = (cx + rad).toInt().coerceAtMost(img.width)
        val y0 = (cy - rad).toInt().coerceAtLeast(0)
        val y1 = (cy + rad).toInt().coerceAtMost(img.height)
        if (x1 - x0 < 10 || y1 - y0 < 10) return null

        // Background = most common grey level; the arrow is clearly brighter.
        val hist = IntArray(256)
        for (y in y0 until y1) for (x in x0 until x1) hist[img.at(x, y)]++
        var bg = 0
        for (v in 1 until 256) if (hist[v] > hist[bg]) bg = v
        val cap = (x1 - x0) * (y1 - y0)
        val xs = FloatArray(cap)
        val ys = FloatArray(cap)
        var count = 0
        var sx = 0f
        var sy = 0f
        for (y in y0 until y1) for (x in x0 until x1) {
            if (img.at(x, y) > bg + 60) {
                xs[count] = x - cx; ys[count] = y - cy
                sx += xs[count]; sy += ys[count]
                count++
            }
        }
        if (count < 20) return null
        val mx = sx / count
        val my = sy / count

        var best = -1
        var bestScore = -Float.MAX_VALUE
        var second = -Float.MAX_VALUE
        for (k in 0 until dirs) {
            val a = 2 * PI * k / dirs
            val dx = sin(a).toFloat()
            val dy = -cos(a).toFloat()
            var nf = 0; var sf = 0.0; var qf = 0.0
            var nb = 0; var sb = 0.0; var qb = 0.0
            for (p in 0 until count) {
                val px = xs[p] - mx
                val py = ys[p] - my
                val along = px * dx + py * dy
                val perp = (-px * dy + py * dx).toDouble()
                if (along > 0) { nf++; sf += perp; qf += perp * perp } else { nb++; sb += perp; qb += perp * perp }
            }
            if (nf < 5 || nb < 5) continue
            val front = sqrt((qf / nf - (sf / nf) * (sf / nf)).coerceAtLeast(0.0))
            val back = sqrt((qb / nb - (sb / nb) * (sb / nb)).coerceAtLeast(0.0))
            val score = (back - front).toFloat()
            if (score > bestScore) { second = bestScore; bestScore = score; best = k } else if (score > second) second = score
        }
        return if (best >= 0 && bestScore - second >= minMargin) best else null
    }
}
