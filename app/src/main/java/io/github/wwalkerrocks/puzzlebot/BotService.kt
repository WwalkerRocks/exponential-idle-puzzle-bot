package io.github.wwalkerrocks.puzzlebot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import io.github.wwalkerrocks.puzzlebot.core.BoardReader
import io.github.wwalkerrocks.puzzlebot.core.DigitTemplates
import io.github.wwalkerrocks.puzzlebot.core.GrayImage
import io.github.wwalkerrocks.puzzlebot.core.Grid
import io.github.wwalkerrocks.puzzlebot.core.PatternDb
import io.github.wwalkerrocks.puzzlebot.core.Puzzle
import io.github.wwalkerrocks.puzzlebot.core.Reading
import io.github.wwalkerrocks.puzzlebot.core.Solvers
import io.github.wwalkerrocks.puzzlebot.core.Taps
import io.github.wwalkerrocks.puzzlebot.core.TreeBoard
import io.github.wwalkerrocks.puzzlebot.core.TreeTile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * Accessibility service that shows the floating "island" over Exponential Idle and, while
 * running, loops: screenshot -> read board -> solve -> tap tiles -> claim/start -> repeat.
 */
class BotService : AccessibilityService() {

    companion object {
        private const val TAG = "PuzzleBot"
        private const val MAX_READ_FAILS = 8
        private const val SCREENSHOT_INTERVAL_MS = 340L // Android allows ~3 screenshots per second
        private const val PAUSE_LIMIT_MS = 3 * 60_000L // give up after being paused this long
        private const val CLEAR_FOR_MS = 600L // screen must stay clear this long before resuming
        private const val MAX_BATCH_MS = 150L // window check at least this often while tapping
        private const val STABLE_MS = 150L // board unchanged this long after the taps = some were dropped
        private const val NEW_GAME_WAIT_MS = 1500L // after the button, wait this long for a new board
        private const val BOARD_CHECK_MS = 300L // while waiting for "Claim", look at the board this often (a full read is slow)

        @Volatile
        var instance: BotService? = null
            private set

        fun isGame(pkg: String) = pkg.contains("exponential", true) || pkg.contains("conicgames", true)
    }

    private lateinit var settings: Settings
    private lateinit var wm: WindowManager
    @Volatile
    private var templates = DigitTemplates.DEFAULT.copy()
    private val templateFile get() = File(filesDir, "digits.bin")
    private val main = Handler(Looper.getMainLooper())
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private val solveExec = Executors.newSingleThreadExecutor()
    private var island: Island? = null

    @Volatile
    private var running = false
    @Volatile
    private var generation = 0 // bumped on every start so an old loop can't keep going
    private var worker: Thread? = null

    // Session statistics (shown on the island).
    private var sessionStart = 0L
    private var sessionGames = 0
    private var sessionStars = 0
    private var sessionMissed = 0

    override fun onServiceConnected() {
        instance = this
        settings = Settings(this)
        wm = getSystemService(WindowManager::class.java)
        templates = DigitTemplates.loadOrDefault(templateFile)
        PatternDb.cacheDir = File(filesDir, "tables").apply { mkdirs() }
        Thread({
            for (n in 3..5) try { Solvers.prepare(n) } catch (e: Throwable) { Log.e(TAG, "prepare $n", e) }
        }, "solver-warmup").apply { priority = Thread.MIN_PRIORITY; start() }
        if (!settings.onlyOverGame) showIsland()
        main.post(refreshIsland)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && t != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // Decide from the actual window list, not from who sent the event: popups such as the
        // Google Play Games greeting come and go without the game sending a new event.
        main.removeCallbacks(refreshIsland)
        main.postDelayed(refreshIsland, 120)
        guardInFlight()
    }

    // Points of the gesture being performed right now, and whether we cancelled it on purpose.
    @Volatile
    private var inFlight: List<PointF>? = null
    @Volatile
    private var safetyCancel = false

    /**
     * A window just appeared or changed. If it covers any tap that is still on its way, cancel the
     * rest of the gesture: Android cancels a gesture in progress when a new one is sent, so send a
     * single harmless tap to our own island (it is the topmost window and ignores short taps).
     */
    private fun guardInFlight() {
        val pts = inFlight ?: return
        val why = blocked(pts) ?: return
        val at = island?.safeTapPoint() ?: return
        safetyCancel = true
        Log.i(TAG, "cancelling taps in progress: $why")
        val path = Path().apply { moveTo(at.x, at.y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 1)).build(), null, null)
    }

    private val refreshIsland = Runnable {
        val (pkg, _) = foreground(appWindows())
        when {
            pkg == null -> {}
            isGame(pkg) -> showIsland()
            settings.onlyOverGame -> hideIsland()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        hideIsland()
        shotExecutor.shutdownNow()
        solveExec.shutdownNow()
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ island (overlay)

    fun showIsland() = main.post {
        if (island == null) island = Island().also { it.attach() }
    }

    fun hideIsland() = main.post {
        island?.detach()
        island = null
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private inner class Island {
        private val text = TextView(this@BotService)
        private val button = TextView(this@BotService)
        private val root = LinearLayout(this@BotService)
        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = settings.islandX
            y = if (settings.islandY >= 0) settings.islandY else dp(44f)
        }

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(Color.argb(235, 24, 26, 30))
                setStroke(dp(1f), Color.argb(90, 255, 255, 255))
            }
            root.setPadding(dp(14f), dp(5f), dp(5f), dp(5f))

            text.setTextColor(Color.WHITE)
            text.textSize = 12.5f
            text.maxWidth = dp(250f)
            text.maxLines = 2
            text.text = if (Solvers.isReady(5)) "Puzzle Bot ready — open a 15-Puzzle" else "Puzzle Bot — preparing solver…"
            root.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            button.gravity = Gravity.CENTER
            button.textSize = 16f
            button.setTextColor(Color.BLACK)
            val size = dp(36f)
            root.addView(button, LinearLayout.LayoutParams(size, size).apply { leftMargin = dp(10f) })
            button.setOnClickListener { if (running) stopBot("Stopped") else startBot() }
            setRunning(false)
            setupDrag()
        }

        fun setRunning(r: Boolean) {
            button.text = if (r) "■" else "▶"
            button.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (r) Color.rgb(239, 120, 110) else Color.rgb(245, 184, 65))
            }
        }

        fun setText(s: String) { text.text = s }

        fun attach() = try { wm.addView(root, params) } catch (e: Exception) { Log.e(TAG, "addView", e) }

        fun detach() = try { wm.removeView(root) } catch (_: Exception) {}

        fun screenRect(): Rect {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            return Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
        }

        /** Centre of the status text: tapping it briefly does nothing. Null if not laid out. */
        fun safeTapPoint(): PointF? {
            if (text.width == 0 || !root.isAttachedToWindow) return null
            val loc = IntArray(2)
            text.getLocationOnScreen(loc)
            return PointF(loc[0] + text.width / 2f, loc[1] + text.height / 2f)
        }

        fun moveToTop() {
            params.x = 0
            params.y = dp(44f)
            try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun setupDrag() {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var downTime = 0L
            var moved = false
            text.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                        downTime = SystemClock.uptimeMillis(); moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (abs(dx) + abs(dy) > dp(6f)) moved = true
                        if (moved) {
                            params.x = startX + dx.toInt()
                            params.y = (startY + dy.toInt()).coerceAtLeast(0)
                            try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            settings.islandX = params.x
                            settings.islandY = params.y
                        } else if (SystemClock.uptimeMillis() - downTime > 600) {
                            stopBot("Stopped")
                            startActivity(Intent(this@BotService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                true
            }
        }
    }

    private fun status(s: String) {
        Log.i(TAG, s)
        main.post { island?.setText(s) }
    }

    // ------------------------------------------------------------------ what's on screen

    private enum class TapResult { DONE, PAUSED, FAILED }

    private fun appWindows(): List<AccessibilityWindowInfo> = try { windows } catch (_: Exception) { emptyList() }

    // ------------------------------------------------------------------ reading the game directly

    /** The board and the button below it, read from the game's own views. */
    private class GameView(val board: TreeBoard, val button: PointF?, val buttonText: CharSequence?)

    private var boardPath: IntArray? = null // child indexes from the root to the board's parent, then the board
    // Nodes found by the last full read, so later reads need a single request.
    private var treeParent: AccessibilityNodeInfo? = null
    private var treeIndex = -1
    private var treeBoardNode: AccessibilityNodeInfo? = null
    private var treeButtonAt: PointF? = null
    private var treeButtonIndex = -1
    private var treeBoardBottom = 0
    private var treeBoardHeight = 0
    private var buttonSignals = 0 // times "Claim" was spotted on the button (vs. on the board)
    private var treeReads = 0L
    private var treeNanos = 0L

    private fun looksLikeBoard(node: AccessibilityNodeInfo, r: Rect): Boolean {
        val c = node.childCount
        if (c < 8 || c > 25) return false
        node.getBoundsInScreen(r)
        val w = r.width()
        return w >= resources.displayMetrics.widthPixels * 0.4f && kotlin.math.abs(r.height() - w) <= w * 0.05f
    }

    /**
     * Reads the puzzle from Exponential Idle's views (it exposes every tile as text with its screen
     * position). Much faster than screenshots and exact. Null if the board isn't on screen.
     */
    private fun readTree(): GameView? {
        if (Build.VERSION.SDK_INT < 33) return null
        val t0 = System.nanoTime()
        try {
            clearCache() // never use stale positions
            val w = appWindows().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer } ?: return null
            val root = w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST) ?: return null
            if (root.packageName?.toString()?.let(::isGame) != true) return null
            val r = Rect()

            // Find the board's parent and index: try the remembered path, else search 3 levels.
            fun follow(path: IntArray): Pair<AccessibilityNodeInfo, Int>? {
                var node = root
                for (i in 0 until path.size - 1) node = node.getChild(path[i]) ?: return null
                val c = node.getChild(path.last()) ?: return null
                return if (looksLikeBoard(c, r)) node to path.last() else null
            }
            var found = boardPath?.let { follow(it) }
            if (found == null) {
                val queue = ArrayDeque<Pair<AccessibilityNodeInfo, IntArray>>()
                queue.add(root to IntArray(0))
                search@ while (queue.isNotEmpty()) {
                    val (node, path) = queue.removeFirst()
                    for (i in 0 until node.childCount) {
                        val c = node.getChild(i) ?: continue
                        if (looksLikeBoard(c, r)) { found = node to i; boardPath = path + i; break@search }
                        if (path.size < 2) queue.add(c to path + i)
                    }
                }
            }
            val (parent, index) = found ?: return null

            // Fetch the board again with all its tiles in one request.
            parent.getChild(index)?.let { clearCachedSubtree(it) }
            val boardNode = parent.getChild(index, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST) ?: return null
            val board = parseBoard(boardNode) ?: return null
            boardNode.getBoundsInScreen(r)

            // The wide button right under the board ("I Give Up!" / "Great! Claim N stars.").
            val bottom = r.bottom
            val height = r.height()
            var button: PointF? = null
            var text: CharSequence? = null
            for (i in 0 until parent.childCount) {
                val c = parent.getChild(i) ?: continue
                if (c.className?.toString()?.endsWith("Button") != true) continue
                c.getBoundsInScreen(r)
                if (r.top < bottom - 5 || r.top > bottom + height * 0.4f) continue
                button = PointF(r.exactCenterX(), r.exactCenterY())
                text = c.text
                treeButtonIndex = i
                break
            }
            if (button == null) treeButtonIndex = -1
            treeParent = parent; treeIndex = index; treeBoardNode = boardNode; treeButtonAt = button
            treeBoardBottom = bottom; treeBoardHeight = height
            return GameView(board, button, text)
        } catch (e: Exception) {
            Log.w(TAG, "readTree", e)
            return null
        } finally {
            treeNanos += System.nanoTime() - t0
            treeReads++
        }
    }

    // Which app owns each window. Asking a window for its root node is a round trip to that app
    // (slow while the game is busy), and a window never changes owner, so remember the answer.
    private val windowOwners = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun ownerOf(w: AccessibilityWindowInfo): String? {
        windowOwners[w.id]?.let { return it }
        val pkg = w.root?.packageName?.toString() ?: return null
        if (windowOwners.size > 200) windowOwners.clear()
        windowOwners[w.id] = pkg
        return pkg
    }

    private fun parseBoard(boardNode: AccessibilityNodeInfo): TreeBoard? {
        val r = Rect()
        val tiles = ArrayList<TreeTile>(boardNode.childCount)
        for (i in 0 until boardNode.childCount) {
            val t = boardNode.getChild(i) ?: continue
            val text = t.text ?: (if (t.childCount > 0) t.getChild(0)?.text else null)
            val v = text?.toString()?.trim()?.toIntOrNull() ?: continue
            t.getBoundsInScreen(r)
            tiles.add(TreeTile(r.left, r.top, r.right, r.bottom, v))
        }
        boardNode.getBoundsInScreen(r)
        return TreeBoard.from(r.left, r.top, r.right, r.bottom, tiles)
    }

    /** Re-reads only the board (one request to the game), falling back to a full read. */
    private fun readBoardFast(): GameView? {
        val parent = treeParent
        val old = treeBoardNode
        if (Build.VERSION.SDK_INT < 33 || parent == null || old == null) return readTree()
        val t0 = System.nanoTime()
        try {
            clearCachedSubtree(old)
            val node = parent.getChild(treeIndex, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST)
            val board = if (node != null && looksLikeBoard(node, Rect())) parseBoard(node) else null
            if (board != null) {
                treeBoardNode = node
                return GameView(board, treeButtonAt, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readBoardFast", e)
        } finally {
            treeNanos += System.nanoTime() - t0
            treeReads++
        }
        treeParent = null
        return readTree()
    }

    /** Fresh text of the button under the board (one small request), or null. */
    private fun buttonText(): CharSequence? {
        // The game swaps the "I Give Up!" and "Claim" buttons rather than changing one button's
        // text, so a remembered button node goes stale. Refresh the parent's child list and take
        // whatever button now sits in the same place (two small requests).
        val parent = treeParent ?: return null
        val i = treeButtonIndex
        if (i < 0 || Build.VERSION.SDK_INT < 33) return null
        return try {
            if (!parent.refresh() || i >= parent.childCount) return null
            val c = parent.getChild(i) ?: return null
            if (!c.refresh()) return null
            if (c.className?.toString()?.endsWith("Button") != true) return null
            val r = Rect()
            c.getBoundsInScreen(r)
            if (r.top < treeBoardBottom - 5 || r.top > treeBoardBottom + treeBoardHeight * 0.4f) return null
            c.text
        } catch (_: Exception) {
            null
        }
    }

    /** Package and layer of the topmost app window that fills most of the screen. */
    private fun foreground(ws: List<AccessibilityWindowInfo>): Pair<String?, Int> {
        val dm = resources.displayMetrics
        val r = Rect()
        for (w in ws.sortedByDescending { it.layer }) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            w.getBoundsInScreen(r)
            if (r.width() < dm.widthPixels * 0.6f || r.height() < dm.heightPixels * 0.5f) continue
            return ownerOf(w) to w.layer
        }
        return null to Int.MIN_VALUE
    }

    /**
     * Why it isn't safe to tap [points] right now, or null if it is. Exponential Idle must be the
     * app in front and no other window (call screen, dialog, notification shade, keyboard, popup…)
     * may be under any of the points. Only window positions are looked at, never their contents.
     */
    private fun blocked(points: List<PointF>): String? {
        val ws = appWindows()
        if (ws.isEmpty()) return null // no window list: the screenshot checks still apply
        val (pkg, layer) = foreground(ws)
        if (pkg == "com.android.systemui") return "the notification shade is open"
        if (pkg != null && !isGame(pkg)) return "Exponential Idle isn't in front"
        if (points.isEmpty() || pkg == null) return null
        val region = Region()
        val r = Rect()
        for (w in ws) {
            if (w.layer <= layer) continue
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && ownerOf(w) == packageName) continue
            if (Build.VERSION.SDK_INT >= 33) w.getRegionInScreen(region) else { w.getBoundsInScreen(r); region.set(r) }
            if (points.any { region.contains(it.x.toInt(), it.y.toInt()) }) {
                return "${w.title ?: ownerOf(w) ?: "a popup"} is covering the puzzle"
            }
        }
        return null
    }

    /** Every point the bot may tap on this board: all tiles and the button. */
    private fun guardPoints(grid: Grid) =
        (0 until grid.n * grid.n).map { PointF(grid.centerX(it), grid.centerY(it)) } + PointF(grid.buttonX(), grid.buttonY())

    /** Waits until it is safe to tap again. False if stopped or paused for too long. */
    private fun waitUntilClear(points: List<PointF>, alive: () -> Boolean): Boolean {
        val start = SystemClock.uptimeMillis()
        var clearSince = -1L
        var shown = ""
        while (alive()) {
            val why = blocked(points)
            val now = SystemClock.uptimeMillis()
            if (why == null) {
                if (clearSince < 0) clearSince = now
                if (now - clearSince >= CLEAR_FOR_MS) return true
            } else {
                clearSince = -1
                if (why != shown) { shown = why; status("Paused: $why") }
                if (now - start > PAUSE_LIMIT_MS) return false
            }
            sleepMs(200)
        }
        return false
    }

    // ------------------------------------------------------------------ bot control

    fun startBot() {
        if (running) return
        running = true
        val gen = ++generation
        sessionStart = SystemClock.uptimeMillis()
        sessionGames = 0; sessionStars = 0; sessionMissed = 0
        main.post { island?.setRunning(true) }
        worker = Thread({
            try {
                if (Build.VERSION.SDK_INT >= 33) treeLoop(gen) else screenshotLoop(gen)
            } catch (e: Throwable) {
                Log.e(TAG, "bot loop", e)
                if (generation == gen) stopBot("Error: ${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }, "puzzle-bot").apply { start() }
    }

    fun stopBot(reason: String) {
        val was = running
        running = false
        main.post { island?.setRunning(false) }
        if (was) status(reason + sessionSummary())
    }

    private fun sessionSummary(): String {
        if (sessionGames == 0) return ""
        val min = (SystemClock.uptimeMillis() - sessionStart) / 60000.0
        return " · $sessionGames games, $sessionStars★ (%.1f★/min)".format(sessionStars / min)
    }

    private fun sleepMs(ms: Long) {
        if (ms > 0) try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    /** Cells to tap for [moves], starting with the empty space at [blank]. */
    private fun toTaps(blank: Int, moves: IntArray): IntArray {
        val simple = Taps.simplify(blank, moves)
        return if (settings.multiSlide) Taps.merge(blank, simple) else simple
    }

    // Speed tuning: after a few clean games make one knob faster; if the game then drops a tap,
    // undo that step and don't go below it again this session.
    private var successStreak = 0
    private var lastTunedTap = false
    private var gapFloor = Settings.MIN_GAP
    private var tapFloor = Settings.MIN_TAP
    private var settleFloor = Settings.MIN_SETTLE

    /** The board was checked before the last slide finished: wait longer, and stay there. */
    private fun settleTooShort() {
        if (!settings.autoSpeed) return
        settings.settleMs += 40
        settleFloor = settings.settleMs
    }

    private fun tuneFaster() {
        if (!settings.autoSpeed || ++successStreak < 3) return
        val gap = settings.gapMs
        val tap = settings.tapMs
        if (gap > gapFloor) {
            settings.gapMs = maxOf(gapFloor, (gap * 0.85).toInt())
            lastTunedTap = false
        } else if (tap > tapFloor) {
            settings.tapMs = maxOf(tapFloor, tap - maxOf(1, tap / 8))
            lastTunedTap = true
        }
    }

    private fun tuneSlower() {
        successStreak = 0
        if (!settings.autoSpeed) return
        if (lastTunedTap) {
            settings.tapMs = settings.tapMs + maxOf(2, settings.tapMs / 3)
            tapFloor = settings.tapMs
        } else {
            settings.gapMs = (settings.gapMs * 1.4 + 5).toInt()
            gapFloor = settings.gapMs
        }
    }

    /** Fallback loop: reads the board from screenshots (if the game's views can't be read). */
    private fun screenshotLoop(gen: Int) {
        fun alive() = running && generation == gen
        var fails = 0
        var buttonTaps = 0
        var solvedSinceTap = 0
        var justTappedButton = false
        var executed: Reading.Ok? = null // last board we ran a full solution for
        var firstLookAfterTaps = false
        var lookedAgain = false
        var gameTaps = 0
        var lastBoard: IntArray? = null
        var noEffect = 0
        var pendingMs = 0L
        var pendingN = 0
        var lastTick = SystemClock.uptimeMillis()
        var checkedIsland = false
        resetTuning()
        var lastGrid: Grid? = null

        status("Starting…")
        while (alive()) {
            // Failsafe: never tap while something else is on top (call, dialog, shade, other app).
            val guard = lastGrid?.let { guardPoints(it) } ?: emptyList()
            if (blocked(guard) != null) {
                if (!waitUntilClear(guard, ::alive)) {
                    if (alive()) stopBot("Stopped: paused for more than ${PAUSE_LIMIT_MS / 60_000} minutes")
                    break
                }
                status("Resuming…")
                // Start over from a fresh look at the board.
                executed = null; lastBoard = null; fails = 0
                firstLookAfterTaps = false; lookedAgain = false; justTappedButton = false
            }
            val img = capture()
            if (!alive()) break
            if (img == null) {
                if (++fails >= MAX_READ_FAILS) { stopBot("Stopped: screenshots failed (error $shotError)"); break }
                sleepMs(400)
                continue
            }
            val r = BoardReader.read(img, templates)
            if (r !is Reading.Ok) {
                r as Reading.Failed
                if (justTappedButton && settings.autoSpeed) settings.buttonWaitMs += 100 // looked too early
                if (firstLookAfterTaps) settleTooShort()
                justTappedButton = false
                firstLookAfterTaps = false
                fails++
                status("Looking for the puzzle… (${r.reason})")
                if (fails >= MAX_READ_FAILS) {
                    saveDebug(img, r.reason)
                    stopBot("Stopped: ${r.reason}")
                    break
                }
                sleepMs(250)
                continue
            }
            fails = 0
            val grid = r.grid
            val n = grid.n
            lastGrid = grid
            if (!checkedIsland) { checkedIsland = true; keepIslandClear(grid) }

            val now = SystemClock.uptimeMillis()
            if (n != pendingN) { pendingN = n; pendingMs = 0 }
            pendingMs += now - lastTick
            lastTick = now

            if (firstLookAfterTaps && settings.autoSpeed && r.solved && !lookedAgain) {
                settings.settleMs = maxOf(settleFloor, (settings.settleMs * 0.9).toInt())
            }
            val wasFirstLook = firstLookAfterTaps
            firstLookAfterTaps = false

            if (r.solved) {
                lookedAgain = false
                executed?.let { prev ->
                    // The solution worked, so every digit was read correctly and every tap landed.
                    if (prev.worstCost < 0.04f) {
                        templates.learn(prev.board, prev.glyphs)
                        try { templates.save(templateFile) } catch (_: Exception) {}
                    }
                    val reward = rewardFor(n, null)
                    settings.recordGame(n, pendingMs, gameTaps, reward)
                    pendingMs = 0
                    sessionGames++
                    sessionStars += reward
                    tuneFaster()
                }
                executed = null
                gameTaps = 0
                if (justTappedButton) {
                    // Still solved right after tapping the button: most likely we looked too soon.
                    // Look once more before tapping again, so a fresh game's "I Give Up!" is never hit.
                    justTappedButton = false
                    if (++solvedSinceTap < 2) {
                        if (settings.autoSpeed) settings.buttonWaitMs += 100
                        sleepMs(300)
                        continue
                    }
                }
                if (buttonTaps >= 3) { stopBot("Stopped: the button didn't start a new game"); break }
                status("${n}×$n solved — tapping button" + sessionSummary())
                if (blocked(listOf(PointF(grid.buttonX(), grid.buttonY()))) != null) continue // pause at the top
                if (!tap(grid.buttonX(), grid.buttonY())) {
                    if (safetyCancel) { safetyCancel = false; continue }
                    stopBot("Stopped: Android rejected the tap")
                    break
                }
                buttonTaps++
                solvedSinceTap = 0
                justTappedButton = true
                sleepMs(settings.buttonWaitMs.toLong())
                continue
            }

            if (justTappedButton && settings.autoSpeed) settings.buttonWaitMs = (settings.buttonWaitMs * 0.9).toInt()
            justTappedButton = false
            buttonTaps = 0
            if (executed != null && wasFirstLook && !lookedAgain) {
                // One tap short of solved: most likely the last slide was still animating. Look again.
                val oneMore = try { Taps.apply(r.board, n, intArrayOf(lastTap)) } catch (_: IllegalArgumentException) { null }
                if (oneMore != null && Puzzle.isSolved(oneMore)) {
                    settleTooShort()
                    lookedAgain = true
                    firstLookAfterTaps = true
                    continue
                }
            }
            lookedAgain = false
            if (executed != null) {
                // We sent a whole solution but the board isn't solved: the game dropped some taps.
                sessionMissed++
                tuneSlower()
            }
            if (lastBoard != null && lastBoard.contentEquals(r.board)) {
                if (++noEffect >= 2) { stopBot("Stopped: taps don't move the tiles"); break }
            } else noEffect = 0
            lastBoard = r.board

            val (result, taps) = solveAndTap(r.board, n, grid, ::alive)
            if (result == TapResult.PAUSED) {
                // Something appeared on screen mid-solve. Re-read and re-solve once it's gone.
                executed = null
                lastBoard = null
                continue
            }
            if (result == TapResult.FAILED) {
                if (alive()) stopBot("Stopped: Android cancelled the taps")
                break
            }
            executed = r
            gameTaps += taps
            firstLookAfterTaps = true
            sleepMs(settings.settleMs.toLong())
        }
        if (sessionMissed > 0) Log.i(TAG, "solutions with dropped taps: $sessionMissed")
    }

    private var lastTap = -1 // final tap of the last solution

    /** Solves [board] and taps the solution. 5x5: starts tapping while the last 4x4 is solving. */
    private fun solveAndTap(board: IntArray, n: Int, grid: Grid, alive: () -> Boolean): Pair<TapResult, Int> {
        if (!Solvers.isReady(n)) status("Preparing the ${n}×$n solver (first run only)…")
        val t0 = SystemClock.uptimeMillis()
        val first: IntArray
        val rest: Future<IntArray>?
        if (n == 5) {
            val plan = Solvers.plan5(board)
            first = plan.moves
            rest = solveExec.submit(Callable { Solvers.finish5(plan) })
        } else {
            first = Solvers.solve(board, n)
            rest = null
        }
        val planMs = SystemClock.uptimeMillis() - t0
        if (!alive()) return TapResult.FAILED to 0
        var blank = board.indexOf(0)
        val taps1 = toTaps(blank, first)
        status("${n}×$n: solving (${planMs}ms), tap ${settings.tapMs}+${settings.gapMs}ms" + sessionSummary())
        var result = tapCells(grid, taps1, alive)
        var taps = taps1.size
        lastTap = taps1.lastOrNull() ?: -1
        if (result == TapResult.DONE && rest != null) {
            if (first.isNotEmpty()) blank = first.last()
            val taps2 = toTaps(blank, rest.get())
            result = tapCells(grid, taps2, alive)
            taps += taps2.size
            if (taps2.isNotEmpty()) lastTap = taps2.last()
        }
        val reads = if (treeReads > 0) " read %.1fms".format(treeNanos / 1e6 / treeReads) else ""
        Log.i(TAG, "${n}x$n: $taps taps, first part after ${planMs}ms, total ${SystemClock.uptimeMillis() - t0}ms, " +
            "tap ${settings.tapMs}ms gap ${settings.gapMs}ms$reads")
        return result to taps
    }

    /** Stars for one board: the manual setting if set, else what the game said, else the base value. */
    private fun rewardFor(n: Int, fromGame: Int?): Int {
        val manual = settings.manualStars(n)
        val r = if (manual > 0) manual else fromGame ?: Solvers.stars(n)
        if (fromGame != null) settings.setLastReward(n, fromGame)
        return r
    }

    private fun resetTuning() {
        successStreak = 0
        gapFloor = Settings.MIN_GAP
        tapFloor = Settings.MIN_TAP
        settleFloor = Settings.MIN_SETTLE
    }

    /**
     * Main loop when the game's views can be read directly: no screenshots and no fixed waits.
     * After the taps it watches the button until it says "Claim N stars" (cheap), looking at the
     * board now and then in case some taps were lost; after claiming it waits for the button to
     * change back, then reads the new board.
     */
    private fun treeLoop(gen: Int) {
        fun alive() = running && generation == gen
        val fresh = 0
        val waitSolved = 1
        val waitNew = 2
        var phase = fresh
        var phaseStart = 0L
        var prevBoard: IntArray? = null
        var lastChange = 0L
        var lastBoardCheck = 0L
        var buttonTaps = 0
        var gameTaps = 0
        var gameN = 0
        var lastRecord = SystemClock.uptimeMillis()
        var missingSince = -1L
        var lastGuard = 0L
        var lastGrid: Grid? = null
        var lastSolveFrom: IntArray? = null
        var noEffect = 0
        var checkedIsland = false
        var tapsDoneAt = 0L
        var claimedAt = 0L
        var buttonPolls = 0
        resetTuning()
        treeReads = 0; treeNanos = 0; treeParent = null; buttonSignals = 0

        fun finishGame(reward: Int?) {
            val now = SystemClock.uptimeMillis()
            Log.i(TAG, "solved seen ${now - tapsDoneAt}ms after the last tap ($buttonPolls button checks)")
            claimedAt = now
            val stars = rewardFor(gameN, reward)
            settings.recordGame(gameN, now - lastRecord, gameTaps, stars)
            lastRecord = now
            sessionGames++
            sessionStars += stars
            tuneFaster()
        }

        /** Taps the claim/start button. False = stop the loop. */
        fun pressButton(grid: Grid): Boolean {
            val at = treeButtonAt ?: PointF(grid.buttonX(), grid.buttonY())
            if (blocked(listOf(at)) != null) { lastGuard = 0; phase = fresh; return true }
            if (buttonTaps >= 3) { stopBot("Stopped: the button didn't start a new game"); return false }
            if (!tap(at.x, at.y)) {
                if (safetyCancel) { safetyCancel = false; phase = fresh; return true }
                stopBot("Stopped: Android rejected the tap")
                return false
            }
            buttonTaps++
            phase = waitNew
            phaseStart = SystemClock.uptimeMillis()
            buttonPolls = 0
            return true
        }

        status("Starting (reading the game directly)…")
        while (alive()) {
            // Failsafe: pause while anything else is on top (checked at least every 250 ms, and
            // before every group of taps).
            if (SystemClock.uptimeMillis() - lastGuard > 250) {
                lastGuard = SystemClock.uptimeMillis()
                val guard = lastGrid?.let { guardPoints(it) } ?: emptyList()
                if (blocked(guard) != null) {
                    if (!waitUntilClear(guard, ::alive)) {
                        if (alive()) stopBot("Stopped: paused for more than ${PAUSE_LIMIT_MS / 60_000} minutes")
                        break
                    }
                    status("Resuming…")
                    phase = fresh; prevBoard = null; missingSince = -1; treeParent = null
                    continue
                }
            }
            var now = SystemClock.uptimeMillis()

            // Cheap signals from the button text.
            if (phase == waitSolved || phase == waitNew) buttonPolls++
            if (phase == waitSolved) {
                val reward = TreeBoard.reward(buttonText())
                if (reward != null) {
                    buttonSignals++
                    finishGame(reward)
                    status("${gameN}×$gameN solved — claiming $reward★" + sessionSummary())
                    if (!pressButton(lastGrid!!)) break
                    continue
                }
                if (now - lastBoardCheck < BOARD_CHECK_MS) { sleepMs(2); continue }
                lastBoardCheck = now
            } else if (phase == waitNew && now - phaseStart < NEW_GAME_WAIT_MS) {
                val text = buttonText()
                if (text != null && TreeBoard.reward(text) != null) { sleepMs(2); continue } // still "Claim"
            }

            val v = readBoardFast()
            now = SystemClock.uptimeMillis()
            if (v == null) {
                if (missingSince < 0) missingSince = now
                if (now - missingSince > 1500) {
                    Log.i(TAG, "board not found in the game's views; switching to screenshots")
                    screenshotLoop(gen)
                    return
                }
                sleepMs(30)
                continue
            }
            missingSince = -1
            val b = v.board
            if (!b.aligned) { sleepMs(3); continue } // tiles still sliding
            val n = b.n
            lastGrid = b.grid
            if (!checkedIsland) { checkedIsland = true; keepIslandClear(b.grid) }

            if (b.solved) {
                if (phase == waitSolved) finishGame(TreeBoard.reward(buttonText()))
                if (phase == waitNew && now - phaseStart < NEW_GAME_WAIT_MS) { sleepMs(5); continue }
                if (!pressButton(b.grid)) break
                continue
            }

            if (phase == waitSolved) {
                // The game may still be working through queued taps: wait until the board settles.
                if (prevBoard == null || !prevBoard.contentEquals(b.board)) { prevBoard = b.board; lastChange = now; continue }
                if (now - lastChange < STABLE_MS) continue
                sessionMissed++
                Log.i(TAG, "board not solved after the taps; re-solving")
                tuneSlower()
            }
            if (phase == waitNew) gameTaps = 0 // a new board
            buttonTaps = 0
            if (lastSolveFrom != null && lastSolveFrom.contentEquals(b.board)) {
                if (++noEffect >= 3) { stopBot("Stopped: taps don't move the tiles"); break }
            } else noEffect = 0
            lastSolveFrom = b.board

            if (phase == waitNew) Log.i(TAG, "new board read ${now - claimedAt}ms after claiming ($buttonPolls button checks)")
            gameN = n
            val (result, taps) = solveAndTap(b.board, n, b.grid, ::alive)
            when (result) {
                TapResult.PAUSED -> { phase = fresh; prevBoard = null; lastSolveFrom = null; lastGuard = 0; continue }
                TapResult.FAILED -> { if (alive()) stopBot("Stopped: Android cancelled the taps"); break }
                TapResult.DONE -> {}
            }
            gameTaps += taps
            phase = waitSolved
            prevBoard = null
            buttonPolls = 0
            tapsDoneAt = SystemClock.uptimeMillis()
            lastBoardCheck = tapsDoneAt
        }
        if (sessionMissed > 0) Log.i(TAG, "solutions with dropped taps: $sessionMissed")
        Log.i(TAG, "solved noticed on the button $buttonSignals of $sessionGames times")
    }

    /** If the island sits over the board or the button, move it to the top so taps don't hit it. */
    private fun keepIslandClear(grid: Grid) {
        main.post {
            val isl = island ?: return@post
            val r = isl.screenRect()
            val board = Rect(grid.left.toInt(), grid.top.toInt(), grid.right.toInt(), (grid.buttonY() + grid.cellH).toInt())
            if (Rect.intersects(r, board)) isl.moveToTop()
        }
    }

    // ------------------------------------------------------------------ taps

    private fun tap(x: Float, y: Float) = dispatch(listOf(PointF(x, y)), settings.tapMs.toLong(), 0L)

    private fun tapCells(grid: Grid, moves: IntArray, alive: () -> Boolean): TapResult {
        val tapMs = settings.tapMs.toLong()
        val gap = settings.gapMs.toLong()
        // Short gestures, so the window check runs often even at slow speeds.
        val byTime = maxOf(1L, MAX_BATCH_MS / maxOf(1L, tapMs + gap)).toInt()
        val per = minOf(settings.tapsPerGesture, GestureDescription.getMaxStrokeCount(), byTime)
        var i = 0
        while (i < moves.size) {
            if (!alive()) return TapResult.FAILED
            val end = min(moves.size, i + per)
            val pts = (i until end).map { PointF(grid.centerX(moves[it]), grid.centerY(moves[it])) }
            if (blocked(pts) != null) return TapResult.PAUSED
            if (!dispatch(pts, tapMs, gap)) {
                if (safetyCancel) { safetyCancel = false; return TapResult.PAUSED }
                return TapResult.FAILED
            }
            i = end
            if (i < moves.size) sleepMs(gap)
        }
        return TapResult.DONE
    }

    /** Sends the taps as one gesture (one stroke per tap, spaced in time) and waits for it. */
    private fun dispatch(points: List<PointF>, tapMs: Long, gapMs: Long): Boolean {
        val b = GestureDescription.Builder()
        for ((j, p) in points.withIndex()) {
            val path = Path().apply { moveTo(p.x, p.y) }
            b.addStroke(GestureDescription.StrokeDescription(path, j * (tapMs + gapMs), tapMs))
        }
        val latch = CountDownLatch(1)
        var ok = false
        safetyCancel = false
        inFlight = points
        try {
            val accepted = dispatchGesture(b.build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { ok = true; latch.countDown() }
                override fun onCancelled(g: GestureDescription) { latch.countDown() }
            }, main)
            if (!accepted) return false
            latch.await(15, TimeUnit.SECONDS)
        } finally {
            inFlight = null
        }
        return ok
    }

    // ------------------------------------------------------------------ screenshots

    private var lastShot = 0L
    private var shotError = 0
    private var argb: IntArray? = null
    private var luma: ByteArray? = null

    private fun capture(): GrayImage? {
        sleepMs(lastShot + SCREENSHOT_INTERVAL_MS - SystemClock.uptimeMillis())
        lastShot = SystemClock.uptimeMillis()
        val latch = CountDownLatch(1)
        var result: GrayImage? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                try {
                    val hb = shot.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(hb, shot.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    hb.close()
                    if (bmp != null) {
                        val w = bmp.width
                        val h = bmp.height
                        val px = argb?.takeIf { it.size == w * h } ?: IntArray(w * h).also { argb = it }
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        bmp.recycle()
                        result = GrayImage.fromArgb(px, w, h, luma).also { luma = it.px }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "screenshot", e)
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                shotError = errorCode
                latch.countDown()
            }
        })
        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    /** Saves the screen the bot couldn't read to Android/data/<app>/files/debug for troubleshooting. */
    private fun saveDebug(img: GrayImage, reason: String) {
        if (!settings.saveDebug) return
        try {
            val dir = File(getExternalFilesDir(null), "debug").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val px = IntArray(img.width * img.height) {
                val g = img.px[it].toInt() and 0xFF
                (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            val bmp = Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
            File(dir, "fail-$stamp.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            val sb = StringBuilder("reason: $reason\nscreen: ${img.width}x${img.height}\n")
            val (grid, why) = BoardReader.detectGrid(img, sb)
            sb.append("grid: $grid ($why)\n")
            if (grid != null) {
                val r = BoardReader.read(img, templates, maxCost = 1f)
                if (r is Reading.Ok) sb.append("best guess (worst cost ${r.worstCost}):\n${Puzzle.format(r.board, grid.n)}\n")
            }
            File(dir, "fail-$stamp.txt").writeText(sb.toString())
            dir.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "saveDebug", e)
        }
    }

    fun resetLearnedDigits() {
        templateFile.delete()
        templates = DigitTemplates.DEFAULT.copy()
    }
}
