package io.github.wwalkerrocks.puzzlebot.core

import java.io.File
import java.util.BitSet

/**
 * Lookup tables for the solvers, built by breadth-first search backwards from the goal.
 *
 * Positions of the tracked tiles are packed into one index: sum(pos_i * cells^i), and when the
 * empty space is part of the key it is the highest "digit": + blank * cells^k.
 */
object PatternDb {

    /** If set, built tables are saved here and loaded on the next start. */
    @Volatile
    var cacheDir: File? = null

    private fun cached(key: String, size: Int, build: () -> ByteArray): ByteArray {
        val dir = cacheDir ?: return build()
        val f = File(dir, "v1-$key.bin")
        try {
            if (f.length() == size.toLong()) return f.readBytes()
        } catch (_: Exception) {
        }
        val t = build()
        try {
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeBytes(t)
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
        return t
    }

    private fun key(kind: String, n: Int, tiles: IntArray, locked: BooleanArray?) =
        "$kind-n$n-${tiles.joinToString("_")}" + (locked?.let { l -> "-" + l.indices.filter { l[it] }.joinToString("_") } ?: "")

    private class IntList(cap: Int = 1 shl 16) {
        var a = IntArray(cap)
        var size = 0
        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(a.size * 2)
            a[size++] = v
        }
    }

    fun powers(cells: Int, count: Int) = IntArray(count) { var p = 1; repeat(it) { p *= cells }; p }

    /**
     * Additive (disjoint) pattern database: counts only moves of the tracked tiles, minimised over
     * every position of the empty space. Sums of these over disjoint tile groups stay admissible.
     * Indexed by sum(pos_i * cells^i) without the empty space.
     */
    fun buildAdditive(n: Int, tiles: IntArray): ByteArray =
        cached(key("add", n, tiles, null), powers(n * n, tiles.size + 1)[tiles.size]) { computeAdditive(n, tiles) }

    private fun computeAdditive(n: Int, tiles: IntArray): ByteArray {
        val cells = n * n
        val k = tiles.size
        val pw = powers(cells, k + 2)
        val full = pw[k + 1]
        val bk = pw[k]
        val pdb = ByteArray(bk) { -1 }
        val visited = BitSet(full)
        val inNext = BitSet(full)
        val adj = Puzzle.neighbors(n)
        var cur = IntList()
        var next = IntList()

        var goalKey = 0
        val occupied = BooleanArray(cells)
        for (i in 0 until k) {
            goalKey += (tiles[i] - 1) * pw[i]
            occupied[tiles[i] - 1] = true
        }
        for (blank in 0 until cells) if (!occupied[blank]) {
            val s = goalKey + blank * bk
            inNext.set(s); next.add(s)
        }

        val pos = IntArray(k)
        var cost = 0
        while (next.size > 0) {
            cur.size = 0
            for (i in 0 until next.size) {
                val s = next.a[i]
                inNext.clear(s)
                if (!visited.get(s)) { visited.set(s); cur.add(s) }
            }
            next.size = 0
            var i = 0
            while (i < cur.size) {
                val s = cur.a[i++]
                val pkey = s % bk
                val blank = s / bk
                if (pdb[pkey] < 0) pdb[pkey] = cost.toByte()
                var rest = s
                for (j in 0 until k) { pos[j] = rest % cells; rest /= cells }
                for (nb in adj[blank]) {
                    var slot = -1
                    for (j in 0 until k) if (pos[j] == nb) { slot = j; break }
                    if (slot < 0) {
                        val ns = s + (nb - blank) * bk
                        if (!visited.get(ns)) { visited.set(ns); cur.add(ns) }
                    } else {
                        val ns = s + (blank - nb) * pw[slot] + (nb - blank) * bk
                        if (!visited.get(ns) && !inNext.get(ns)) { inNext.set(ns); next.add(ns) }
                    }
                }
            }
            cost++
        }
        return pdb
    }

    /**
     * Exact distance table for "put these tiles in their goal cells", counting every move, in the
     * abstraction where all other tiles are interchangeable. Cells in [locked] are never entered.
     * Indexed by sum(pos_i * cells^i) + blank * cells^k. Unreachable entries stay -1.
     */
    fun buildStage(n: Int, tiles: IntArray, locked: BooleanArray): ByteArray =
        cached(key("stage", n, tiles, locked), powers(n * n, tiles.size + 2)[tiles.size + 1]) { computeStage(n, tiles, locked) }

    private fun computeStage(n: Int, tiles: IntArray, locked: BooleanArray): ByteArray {
        val cells = n * n
        val k = tiles.size
        val pw = powers(cells, k + 2)
        val bk = pw[k]
        val dist = ByteArray(pw[k + 1]) { -1 }
        val adj = Puzzle.neighbors(n)
        val queue = IntList()

        var goalKey = 0
        val occupied = BooleanArray(cells)
        for (i in 0 until k) {
            goalKey += (tiles[i] - 1) * pw[i]
            occupied[tiles[i] - 1] = true
        }
        for (blank in 0 until cells) if (!occupied[blank] && !locked[blank]) {
            val s = goalKey + blank * bk
            dist[s] = 0; queue.add(s)
        }
        val pos = IntArray(k)
        var i = 0
        while (i < queue.size) {
            val s = queue.a[i++]
            val d = dist[s] + 1
            val blank = s / bk
            var rest = s
            for (j in 0 until k) { pos[j] = rest % cells; rest /= cells }
            for (nb in adj[blank]) {
                if (locked[nb]) continue
                var slot = -1
                for (j in 0 until k) if (pos[j] == nb) { slot = j; break }
                val ns = if (slot < 0) s + (nb - blank) * bk else s + (blank - nb) * pw[slot] + (nb - blank) * bk
                if (dist[ns] < 0) { dist[ns] = d.toByte(); queue.add(ns) }
            }
        }
        return dist
    }
}
