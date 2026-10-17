package io.github.wwalkerrocks.puzzlebot

import android.content.Context
import android.content.SharedPreferences
import io.github.wwalkerrocks.puzzlebot.core.Solvers

/** User settings and running statistics, stored in SharedPreferences. */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = p.getInt(key, def)
    private fun putInt(key: String, v: Int) = p.edit().putInt(key, v).apply()
    private fun bool(key: String, def: Boolean) = p.getBoolean(key, def)
    private fun putBool(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()

    /** How long each tap holds the finger down. */
    var tapMs: Int
        get() = int("tapMs", 20)
        set(v) = putInt("tapMs", v.coerceIn(MIN_TAP, 200))

    /** Pause between the end of one tap and the start of the next (auto-tuned if [autoSpeed]). */
    var gapMs: Int
        get() = int("gapMs", 30)
        set(v) = putInt("gapMs", v.coerceIn(MIN_GAP, MAX_GAP))

    /** Taps sent to Android in one gesture (1 = one at a time; higher is smoother and faster). */
    var tapsPerGesture: Int
        get() = int("tapsPerGesture", 20)
        set(v) = putInt("tapsPerGesture", v.coerceIn(1, 20))

    /** Auto-tune tap pause, tap length and button wait. */
    var autoSpeed: Boolean
        get() = bool("autoSpeed", true)
        set(v) = putBool("autoSpeed", v)

    /** One tap slides every tile between it and the empty space (the game supports this). */
    var multiSlide: Boolean
        get() = bool("multiSlide", true)
        set(v) = putBool("multiSlide", v)

    /** Wait after tapping the claim/start button before looking at the board again. */
    var buttonWaitMs: Int
        get() = int("buttonWaitMs", 700)
        set(v) = putInt("buttonWaitMs", v.coerceIn(MIN_BUTTON_WAIT, 5000))

    /** Wait after the last tile tap so the final slide animation finishes before checking. */
    var settleMs: Int
        get() = int("settleMs", 250)
        set(v) = putInt("settleMs", v.coerceIn(MIN_SETTLE, 3000))

    var onlyOverGame: Boolean
        get() = bool("onlyOverGame", true)
        set(v) = putBool("onlyOverGame", v)

    var saveDebug: Boolean
        get() = bool("saveDebug", true)
        set(v) = putBool("saveDebug", v)

    var islandX: Int
        get() = int("islandX", 0)
        set(v) = putInt("islandX", v)

    var islandY: Int
        get() = int("islandY", -1)
        set(v) = putInt("islandY", v)

    // ---- statistics per board size

    fun games(n: Int) = int("games$n", 0)
    fun botMs(n: Int) = p.getLong("ms$n", 0L)
    fun taps(n: Int) = p.getLong("taps$n", 0L)

    /** Records one finished board: [ms] = bot time spent on it, including claiming/starting. */
    fun recordGame(n: Int, ms: Long, taps: Int) {
        p.edit()
            .putInt("games$n", games(n) + 1)
            .putLong("ms$n", botMs(n) + ms)
            .putLong("taps$n", taps(n) + taps)
            .apply()
    }

    fun starsPerMinute(n: Int): Double {
        val ms = botMs(n)
        return if (ms <= 0) 0.0 else games(n) * Solvers.stars(n) * 60_000.0 / ms
    }

    fun resetStats() {
        val e = p.edit()
        for (n in 3..5) e.remove("games$n").remove("ms$n").remove("moves$n").remove("taps$n")
        e.apply()
    }

    companion object {
        const val MIN_GAP = 0
        const val MAX_GAP = 400
        const val MIN_TAP = 4
        const val MIN_BUTTON_WAIT = 150
        const val MIN_SETTLE = 50
    }
}
