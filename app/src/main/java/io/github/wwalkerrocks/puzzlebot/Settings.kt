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

    /**
     * Which minigame the tap speed belongs to: each one is tuned separately, because the game may
     * handle taps on one puzzle faster than on another. "" = 15-Puzzle, [ARROW] = Arrow Puzzle.
     */
    var profile = ""

    /** How long each tap holds the finger down. */
    var tapMs: Int
        get() = int("${profile}tapMs", when (profile) { ARROW, TORUS -> 4; else -> 20 })
        set(v) = putInt("${profile}tapMs", v.coerceIn(MIN_TAP, 200))

    /** Pause between the end of one tap and the start of the next (auto-tuned if [autoSpeed]). */
    var gapMs: Int
        get() = int("${profile}gapMs", when (profile) { ARROW, TORUS -> 4; else -> 30 })
        set(v) = putInt("${profile}gapMs", v.coerceIn(MIN_GAP, MAX_GAP))

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

    // ---- statistics per puzzle and difficulty (see [BOARDS] for the keys)

    fun games(key: String) = int("games$key", 0)
    fun botMs(key: String) = p.getLong("ms$key", 0L)
    fun taps(key: String) = p.getLong("taps$key", 0L)
    fun stars(key: String) = p.getLong("stars$key", 0L)

    /** Records one finished board: [ms] = bot time spent on it, including claiming/starting. */
    fun recordGame(key: String, ms: Long, taps: Int, stars: Int) {
        p.edit()
            .putInt("games$key", games(key) + 1)
            .putLong("ms$key", botMs(key) + ms)
            .putLong("taps$key", taps(key) + taps)
            .putLong("stars$key", stars(key) + stars)
            .apply()
    }

    fun starsPerMinute(key: String): Double {
        val ms = botMs(key)
        return if (ms <= 0) 0.0 else stars(key) * 60_000.0 / ms
    }

    /** Stars per board typed in by the user; 0 = use what the game shows on its claim button. */
    fun manualStars(key: String) = int("manualStars$key", 0)
    fun setManualStars(key: String, v: Int) = putInt("manualStars$key", v.coerceIn(0, 1_000_000_000))

    /** Last reward read from the game's claim button (0 = never seen). */
    fun lastReward(key: String) = int("lastReward$key", 0)
    fun setLastReward(key: String, v: Int) { if (v != lastReward(key)) putInt("lastReward$key", v) }

    /** Stars counted for one board: the manual setting if set, else what the game showed, else a guess. */
    fun rewardFor(key: String, fromGame: Int?): Int {
        if (fromGame != null) setLastReward(key, fromGame)
        val manual = manualStars(key)
        return when {
            manual > 0 -> manual
            fromGame != null -> fromGame
            else -> BOARDS.firstOrNull { it.key == key }?.baseStars ?: 0
        }
    }

    fun resetStats() {
        val e = p.edit()
        for (b in BOARDS) e.remove("games${b.key}").remove("ms${b.key}").remove("moves${b.key}").remove("taps${b.key}").remove("stars${b.key}")
        e.apply()
    }

    /** A puzzle + difficulty the bot keeps stats for. */
    class Board(val key: String, val label: String, val baseStars: Int)

    companion object {
        const val MIN_GAP = 1 // 0 would make one tap start as the previous ends (a two-finger touch)
        const val MAX_GAP = 400
        const val MIN_TAP = 1
        const val MIN_BUTTON_WAIT = 150
        const val MIN_SETTLE = 50
        const val ARROW = "arrow."
        const val TORUS = "torus." // for the Torus Puzzle, "tap length" is how long each swipe takes

        val BOARDS = listOf(
            Board("3", "15-Puzzle Easy 3×3", Solvers.stars(3)),
            Board("4", "15-Puzzle Medium 4×4", Solvers.stars(4)),
            Board("5", "15-Puzzle Hard 5×5", Solvers.stars(5)),
            Board("a3", "Arrow Easy 3×3", 0),
            Board("a4", "Arrow Medium 4×4", 0),
            Board("h2", "Arrow Hard (hex, up/down)", 0),
            Board("h6", "Arrow Expert (hex, 6 ways)", 0),
            Board("t3", "Torus 3×3", 0),
            Board("t4", "Torus 4×4", 0),
            Board("t5", "Torus 5×5", 0),
            Board("t6", "Torus 6×6", 0),
        )
    }
}
