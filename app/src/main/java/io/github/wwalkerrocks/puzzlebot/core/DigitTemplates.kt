package io.github.wwalkerrocks.puzzlebot.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Reference shapes for digits 0-9 (see [BoardReader.glyphDistance]). */
class DigitTemplates(val cells: Array<FloatArray>, val aspect: FloatArray) {

    fun copy() = DigitTemplates(Array(10) { cells[it].copyOf() }, aspect.copyOf())

    /**
     * Nudges the templates towards how digits look on this phone, using a reading whose labels
     * were confirmed (the solution worked, so every number was read correctly).
     */
    fun learn(board: IntArray, glyphs: List<List<Glyph>>, rate: Float = 0.15f) {
        for ((cell, v) in board.withIndex()) {
            if (v == 0) continue
            val digits = if (v >= 10) intArrayOf(v / 10, v % 10) else intArrayOf(v)
            val g = glyphs[cell]
            if (g.size != digits.size) continue
            for ((i, d) in digits.withIndex()) {
                val t = cells[d]
                for (j in t.indices) t[j] += rate * (g[i].cells[j] - t[j])
                aspect[d] += rate * (g[i].aspect - aspect[d])
            }
        }
    }

    fun save(f: File) {
        DataOutputStream(f.outputStream().buffered()).use { o ->
            o.writeInt(cells[0].size)
            for (d in 0..9) { for (v in cells[d]) o.writeFloat(v); o.writeFloat(aspect[d]) }
        }
    }

    companion object {
        /** Measured from the game's own screenshots by TemplateGenerator (unit test). */
        val DEFAULT = DigitTemplates(DigitTemplateData.CELLS, DigitTemplateData.ASPECT)

        fun loadOrDefault(f: File): DigitTemplates = try {
            DataInputStream(f.inputStream().buffered()).use { s ->
                val n = s.readInt()
                require(n == DEFAULT.cells[0].size)
                val c = Array(10) { FloatArray(n) }
                val a = FloatArray(10)
                for (d in 0..9) { for (j in 0 until n) c[d][j] = s.readFloat(); a[d] = s.readFloat() }
                DigitTemplates(c, a)
            }
        } catch (e: Exception) {
            DEFAULT.copy()
        }
    }
}
