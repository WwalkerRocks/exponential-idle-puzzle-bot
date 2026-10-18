package io.github.wwalkerrocks.puzzlebot

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.wwalkerrocks.puzzlebot.core.Puzzle
import io.github.wwalkerrocks.puzzlebot.core.Solvers
import io.github.wwalkerrocks.puzzlebot.core.Taps
import kotlin.random.Random

/** Setup screen: enable the service, tune speed, see stats. Built in code (no layout XML). */
class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var serviceState: TextView
    private lateinit var statsText: TextView
    private lateinit var benchText: TextView
    private val main = Handler(Looper.getMainLooper())

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(24f), dp(20f), dp(32f))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(text("Puzzle Bot for Exponential Idle", 22f, bold = true))
        col.addView(text("Solves the 15-Puzzle minigame for you: it reads the board from the screen, " +
            "finds a short solution and taps the tiles, then claims the stars and starts the next board.", 14f))

        col.addView(header("1. Turn on the service"))
        serviceState = text("", 15f, bold = true)
        col.addView(serviceState)
        col.addView(text("Android 13+: if the switch is greyed out (\"Restricted setting\"), first open App info, " +
            "tap ⋮ (top right) → \"Allow restricted settings\", then come back here.", 13f))
        col.addView(button("Open Accessibility settings") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("Open App info (allow restricted settings)") {
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        col.addView(header("2. Use it"))
        col.addView(text("• Open Exponential Idle → Stars → Minigames → 15-Puzzle and pick a difficulty.\n" +
            "• A small bar appears near the top. Press ▶ to start, ■ to stop. Drag the text to move it; " +
            "long-press the text to come back here.\n" +
            "• The bot works on Easy, Medium and Hard. Hard (6★) is usually the most stars per minute " +
            "once the speed has tuned itself; compare your own numbers in the stats below.\n" +
            "• It stops by itself if you leave the game or it can't see the puzzle.\n" +
            "• Tip: turn on Do Not Disturb so notifications don't cover the board.", 14f))

        col.addView(header("Speed"))
        col.addView(intField("Tap length (ms)", settings.tapMs) { settings.tapMs = it })
        col.addView(intField("Pause between taps (ms)", settings.gapMs) { settings.gapMs = it })
        col.addView(switch("Auto-tune speed (pause, tap length and waits get shorter until the game drops a tap)", settings.autoSpeed) { settings.autoSpeed = it })
        col.addView(switch("Slide several tiles per tap (one tap on a tile in the empty space's row/column)", settings.multiSlide) { settings.multiSlide = it })
        col.addView(intField("Taps per gesture (1–20; use 1 if taps get lost)", settings.tapsPerGesture) { settings.tapsPerGesture = it })
        col.addView(intField("Wait after claim/start button (ms)", settings.buttonWaitMs) { settings.buttonWaitMs = it })
        col.addView(intField("Wait after last tile tap (ms)", settings.settleMs) { settings.settleMs = it })

        col.addView(header("Stars per board"))
        col.addView(text("The game raises its star rewards as you progress. The bot reads the reward from the " +
            "\"Claim N stars\" button by itself. Type a number only to override it (0 = automatic).", 13f))
        for ((n, name) in listOf(3 to "Easy 3×3", 4 to "Medium 4×4", 5 to "Hard 5×5")) {
            val seen = settings.lastReward(n)
            val label = name + if (seen > 0) " (game says $seen★)" else ""
            col.addView(intField(label, settings.manualStars(n)) { settings.setManualStars(n, it) })
        }

        col.addView(header("Other"))
        col.addView(switch("Only show the bar over Exponential Idle", settings.onlyOverGame) {
            settings.onlyOverGame = it
            BotService.instance?.let { s -> if (it) s.hideIsland() else s.showIsland() }
        })
        col.addView(switch("Save a screenshot when the bot can't read the board", settings.saveDebug) { settings.saveDebug = it })
        col.addView(text("Debug screenshots go to Android/data/$packageName/files/debug (visible over USB).", 12f))
        col.addView(button("Reset learned digit shapes") { BotService.instance?.resetLearnedDigits() })

        col.addView(header("Stats"))
        statsText = text("", 14f)
        col.addView(statsText)
        col.addView(button("Reset stats") { settings.resetStats(); refresh() })

        col.addView(header("Solver benchmark (this phone)"))
        benchText = text("Solves random boards of each size to show solution length and thinking time.", 14f)
        col.addView(benchText)
        col.addView(button("Run benchmark") { runBenchmark() })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun serviceEnabled(): Boolean {
        val enabled = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, BotService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun refresh() {
        val on = serviceEnabled()
        serviceState.text = if (on) "✓ Service is ON" else "✗ Service is OFF — enable \"Puzzle Bot\" under Accessibility → Installed apps"
        serviceState.setTextColor(if (on) 0xFF43A047.toInt() else 0xFFE53935.toInt())
        statsText.text = buildString {
            for (n in 3..5) {
                val g = settings.games(n)
                append("${n}×$n: ")
                if (g == 0) append("no games yet\n") else append(
                    "$g games, ${settings.stars(n)}★, %.2f s/game, %.0f taps/game, %.1f ★/min\n".format(
                        settings.botMs(n) / 1000.0 / g, settings.taps(n).toDouble() / g, settings.starsPerMinute(n),
                    ),
                )
            }
            append("Current tap ${settings.tapMs} ms + pause ${settings.gapMs} ms, settle ${settings.settleMs} ms, button wait ${settings.buttonWaitMs} ms")
        }
    }

    private fun runBenchmark() {
        benchText.text = "Running… (the first run builds the solver tables)"
        Thread {
            val sb = StringBuilder()
            val rnd = Random(SystemClock.uptimeMillis())
            val tau = settings.tapMs + settings.gapMs + 0.0
            for (n in 3..5) {
                val t0 = SystemClock.uptimeMillis()
                Solvers.prepare(n)
                val prep = SystemClock.uptimeMillis() - t0
                val runs = if (n == 5) 10 else 20
                var moves = 0L
                var ms = 0L
                var worst = 0L
                repeat(runs) {
                    val b = Puzzle.randomSolvable(n, rnd)
                    val s = SystemClock.uptimeMillis()
                    val m = Taps.simplify(b.indexOf(0), Solvers.solve(b, n))
                    moves += if (settings.multiSlide) Taps.count(b.indexOf(0), m) else m.size
                    val d = SystemClock.uptimeMillis() - s
                    ms += d
                    worst = maxOf(worst, d)
                }
                val avgMoves = moves.toDouble() / runs
                val avgMs = ms.toDouble() / runs
                // Rough cycle: taps + thinking + claim/start buttons + settling + a few screenshots.
                // Rough cycle: taps + thinking + claiming and starting the next board (~0.2 s).
                val cycle = avgMoves * tau + avgMs + 200
                val stars = settings.manualStars(n).takeIf { it > 0 } ?: settings.lastReward(n).takeIf { it > 0 } ?: Solvers.stars(n)
                sb.append("${n}×$n: %.1f taps, %.0f ms avg (worst %d ms), setup %d ms → est. %.1f ★/min at %d ms/tap\n"
                    .format(avgMoves, avgMs, worst, prep, stars * 60000.0 / cycle, tau))
                main.post { benchText.text = sb.toString() + "…" }
            }
            main.post { benchText.text = sb.toString().trimEnd() }
        }.start()
    }

    // ---------------------------------------------------------------- tiny view helpers

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4f), 0, dp(4f))
    }

    private fun header(s: String) = text(s, 17f, bold = true).apply { setPadding(0, dp(20f), 0, dp(4f)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 14f
        isChecked = value
        setPadding(0, dp(6f), 0, dp(6f))
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun intField(label: String, value: Int, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(text(label, 14f), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            minEms = 3
            setSelectAllOnFocus(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    s?.toString()?.toIntOrNull()?.let { onChange(it); refresh() }
                }
            })
        }
        row.addView(edit)
        return row
    }
}
