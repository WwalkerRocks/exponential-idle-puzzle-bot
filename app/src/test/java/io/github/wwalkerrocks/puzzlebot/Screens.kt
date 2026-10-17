package io.github.wwalkerrocks.puzzlebot

import io.github.wwalkerrocks.puzzlebot.core.GrayImage
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/** The user's screenshots (923x2000, downscaled from the phone) and what is on them. */
object Screens {
    val boards = mapOf(
        "1.png" to intArrayOf(6, 1, 5, 8, 2, 4, 7, 3, 0),
        "2.png" to intArrayOf(4, 9, 11, 5, 2, 0, 3, 6, 13, 12, 7, 14, 8, 1, 10, 15),
        "3.png" to intArrayOf(
            7, 15, 0, 8, 2,
            17, 10, 16, 5, 21,
            6, 4, 1, 14, 23,
            3, 22, 12, 13, 19,
            9, 18, 24, 20, 11,
        ),
    )

    /** Loads a fixture made by tools/make_test_screens.py; width 923 = original, 1080 = phone size. */
    fun load(name: String, width: Int = 923): GrayImage {
        val file = "/screens/${name.removeSuffix(".png")}_$width.gray.gz"
        DataInputStream(GZIPInputStream(Screens::class.java.getResourceAsStream(file)!!).buffered()).use { s ->
            val w = s.readInt()
            val h = s.readInt()
            val px = ByteArray(w * h)
            s.readFully(px)
            return GrayImage(w, h, px)
        }
    }
}
