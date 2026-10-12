package com.malick.antenna.tv.pointer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The phone remote for the whole TV: draws a cursor over every app and clicks, scrolls and presses the remote's
 * buttons where the phone says. It runs whenever it's switched on in the TV's accessibility settings, whether or
 * not Antenna is open, and listens on the home network (port 8782) for one simple line protocol:
 *
 *   m dx dy   move the cursor (phone points)      c / lc     click / long-press at the cursor
 *   s dx dy   scroll by a finger drag (points)     k <key>    back home up down left right ok recents notifications
 *   v up|down|mute   volume                        t <b64>    type text into the focused field   enter   center
 *
 * It tells the phone `mode pointer` or `mode arrows`: apps that draw their own screens (YouTube) don't say where
 * their buttons are, so there movement steps the highlight like the remote's arrows and a click presses OK.
 *
 * TV boxes have no touchscreen and ignore simulated taps, so clicks press items through accessibility, the way
 * TalkBack does, and items that can only be highlighted (Google TV's app icons) are reached with the arrows.
 */
class PointerService : AccessibilityService() {
    companion object {
        const val PORT = 8782
        private const val TAG = "AntennaPointer"
        /** True while the service is on, so the cast status can tell phones the remote is available. */
        @Volatile var running = false
    }

    private enum class Dir { UP, DOWN, LEFT, RIGHT }

    private val main = Handler(Looper.getMainLooper())
    private var overlay: FrameLayout? = null
    private var cursor: CursorView? = null
    private var server: ServerSocket? = null
    private val phones = CopyOnWriteArrayList<OutputStream>()
    private var x = 0f
    private var y = 0f
    private var width = 1920f
    private var height = 1080f
    /** TV pixels per phone point: the cursor crosses the screen with a comfortable thumb movement. */
    private var scale = 2.4f
    /** The arrow's tip stops short of the bottom and right edges so part of it always stays on screen. */
    private var maxX = 1919f
    private var maxY = 1079f
    private val hide = Runnable { cursor?.animate()?.alpha(0f)?.setDuration(400)?.start() }
    private var scrollAccum = 0f
    private var scrollAccumX = 0f
    private var lastMove = 0L
    /** In arrow mode, movement gathers here until it's worth one press of an arrow. */
    private var arrowX = 0f
    private var arrowY = 0f
    @Volatile private var arrowMode = false
    /** A click in progress that's walking the highlight to its target; a new click cancels it. */
    private var walkId = 0

    // ---- Lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds
        width = bounds.width().toFloat()
        height = bounds.height().toFloat()
        scale = width / 800f
        x = width / 2; y = height / 2
        val root = FrameLayout(this)
        val c = CursorView(this).apply { alpha = 0f }
        val size = (width / 48).toInt().coerceIn(28, 64)
        root.addView(c, FrameLayout.LayoutParams(size, (size * 1.4f).toInt()))
        maxX = width - size * 0.4f
        maxY = height - size * 0.6f
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(root, lp) }.onFailure { Log.w(TAG, "Couldn't add the cursor", it) }
        overlay = root
        cursor = c
        startServer()
        main.postDelayed(checkMode, 500)
        // Android brings this service back on its own (after updates too), so it also makes sure the TV is
        // findable by phones (the "Play on TV" service normally starts with the app or at boot).
        com.malick.antenna.tv.cast.CastService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // A new screen: decide again whether it can be pointed at (content may take a moment to appear).
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            main.removeCallbacks(checkMode)
            main.postDelayed(checkMode, 700)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        super.onDestroy()
    }

    // ---- Network: one connection per phone, lines of text

    private fun startServer() {
        val s = runCatching { ServerSocket(PORT) }.getOrElse { Log.w(TAG, "Port $PORT busy", it); return }
        server = s
        thread(name = "pointer-server", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }
    }

    private fun handle(client: Socket) {
        // Only phones on the home network.
        if (!client.inetAddress.isSiteLocalAddress && !client.inetAddress.isLoopbackAddress) return
        client.tcpNoDelay = true
        val out = client.getOutputStream()
        out.write("ok ${width.toInt()} ${height.toInt()}\nmode ${if (arrowMode) "arrows" else "pointer"}\n".toByteArray())
        out.flush()
        phones += out
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                main.post { command(line) }
            }
        } finally {
            phones -= out
        }
    }

    private fun tell(line: String) {
        val bytes = (line + "\n").toByteArray()
        thread(isDaemon = true) { phones.forEach { out -> runCatching { out.write(bytes); out.flush() } } }
    }

    // ---- Commands

    private fun command(line: String) {
        val parts = line.trim().split(' ')
        when (parts.getOrNull(0)) {
            "m" -> move(parts.f(1), parts.f(2))
            "c" -> click(long = false)
            "lc" -> click(long = true)
            "s" -> scroll(parts.f(1), parts.f(2))
            "k" -> key(parts.getOrNull(1) ?: return)
            "v" -> volume(parts.getOrNull(1) ?: return)
            "t" -> type(String(Base64.decode(parts.getOrNull(1) ?: "", Base64.DEFAULT), Charsets.UTF_8))
            "enter" -> focusedInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            "show" -> if (!arrowMode) place()
            "center" -> { x = width / 2; y = height / 2; if (!arrowMode) place() }
            // Diagnostics: what a click would press here, without pressing it (logcat AntennaPointer).
            "probe" -> Log.i(TAG, "at ${x.toInt()},${y.toInt()} arrows=$arrowMode: " + describe(nodeAt(x.toInt(), y.toInt())))
        }
    }

    private fun List<String>.f(i: Int) = getOrNull(i)?.toFloatOrNull() ?: 0f

    private fun describe(n: AccessibilityNodeInfo?): String =
        n?.let { "${it.className} '${it.text ?: it.contentDescription ?: ""}' click=${it.isClickable} focus=${it.isFocusable} focused=${it.isFocused}" } ?: "nothing"

    // ---- Moving

    private fun move(dx: Float, dy: Float) {
        lastMove = System.currentTimeMillis()
        if (arrowMode) { arrows(dx * scale, dy * scale); return }
        val wasInZone = inEdgeZone()
        x = (x + dx * scale).coerceIn(0f, maxX)
        y = (y + dy * scale).coerceIn(0f, maxY)
        place()
        if (inEdgeZone() && !wasInZone) { main.removeCallbacks(edgeScroll); main.postDelayed(edgeScroll, 350) }
    }

    /** Arrow mode: every so much movement is one press of an arrow, like swiping on a TV remote's touchpad. */
    private fun arrows(dx: Float, dy: Float) {
        val step = height / 7
        arrowX += dx; arrowY += dy
        // Only the main direction counts, so a slightly diagonal movement doesn't zig-zag.
        if (abs(arrowX) >= step && abs(arrowX) >= abs(arrowY)) {
            key(if (arrowX > 0) "right" else "left"); arrowX -= step * Math.signum(arrowX); arrowY = 0f
        } else if (abs(arrowY) >= step) {
            key(if (arrowY > 0) "down" else "up"); arrowY -= step * Math.signum(arrowY); arrowX = 0f
        }
    }

    private fun inEdgeZone() = y >= maxY - height * 0.035f || y <= height * 0.035f

    /** While the cursor rests at the top or bottom edge, the page keeps scrolling that way. */
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (arrowMode || System.currentTimeMillis() - lastMove > 5000) return
            when {
                y >= maxY - height * 0.035f -> scrollStep(Dir.DOWN)
                y <= height * 0.035f -> scrollStep(Dir.UP)
                else -> return
            }
            main.postDelayed(this, 650)
        }
    }

    private fun place() {
        val c = cursor ?: return
        c.translationX = x
        c.translationY = y
        if (c.alpha < 1f) { c.animate().cancel(); c.alpha = 1f }
        main.removeCallbacks(hide)
        main.postDelayed(hide, 5000)
    }

    // ---- Arrow mode

    /**
     * Some apps (YouTube) draw their whole screen themselves and only expose empty placeholders, so nothing can be
     * pointed at. There the cursor hides and the phone's movement steps the highlight instead.
     */
    private val checkMode = Runnable {
        val arrows = !pointable(rootInActiveWindow)
        if (arrows != arrowMode) {
            arrowMode = arrows
            arrowX = 0f; arrowY = 0f
            if (arrows) { main.removeCallbacks(hide); cursor?.animate()?.alpha(0f)?.setDuration(200)?.start() } else place()
            tell("mode ${if (arrows) "arrows" else "pointer"}")
            Log.i(TAG, "mode ${if (arrows) "arrows" else "pointer"} for ${rootInActiveWindow?.packageName}")
        }
    }

    /** True when the screen describes at least a few real things (buttons, text, cards) of a usable size. */
    private fun pointable(root: AccessibilityNodeInfo?): Boolean {
        root ?: return true
        var found = 0
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (found >= 4 || depth > 40) return
            n.getBoundsInScreen(r)
            val sized = r.width() >= 40 && r.height() >= 30 && !(r.width() >= width * 0.95f && r.height() >= height * 0.95f)
            val meaningful = n.isClickable || n.isFocusable || !n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()
            if (sized && meaningful && n.isVisibleToUser) found++
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return found >= 3
    }

    // ---- Clicking

    /**
     * Presses what's under the cursor: items that can be pressed directly are pressed (cards, buttons, links);
     * items that can only be highlighted (Google TV's app icons) get the highlight walked onto them with the
     * arrows, then OK, as if you'd done it with the remote. In arrow mode a click is simply OK.
     */
    private fun click(long: Boolean) {
        walkId++
        if (arrowMode) { key("ok"); return }
        cursor?.pulse()
        place()
        val px = x.toInt(); val py = y.toInt()
        val hit = nodeAt(px, py)
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val pressable = ancestor(hit) { it.isEnabled && it.isClickable && it.actionList.any { a -> a.id == action } }
        val focusable = ancestor(hit) { it.isFocusable && !it.isScrollable && it.collectionInfo == null && !isScreenSized(it) }
        Log.i(TAG, "click at $px,$py: hit=${describe(hit)} pressable=${describe(pressable)} focusable=${describe(focusable)}")
        when {
            // Something that takes focus is best reached the way the remote does it; the TV's own
            // apps often only react properly to OK on the highlighted item.
            focusable != null && (pressable == null || isAncestorOrSame(focusable, pressable)) -> highlightThenOk(focusable, pressable, action, walkId)
            pressable != null && pressable.performAction(action) -> {}
            else -> {
                // Nothing described there (games, video surfaces, web pages): try a tap, which works on TVs
                // with touch support.
                val path = Path().apply { moveTo(x, y) }
                val stroke = GestureDescription.StrokeDescription(path, 0, if (long) 800 else 60)
                dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            }
        }
    }

    private fun isScreenSized(n: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { n.getBoundsInScreen(it) }
        return r.width() >= width * 0.9f && r.height() >= height * 0.8f
    }

    /** True when [outer] is [inner] or contains it (by screen area, which survives node object identity). */
    private fun isAncestorOrSame(outer: AccessibilityNodeInfo, inner: AccessibilityNodeInfo): Boolean {
        val a = Rect().also { outer.getBoundsInScreen(it) }
        val b = Rect().also { inner.getBoundsInScreen(it) }
        return a.contains(b)
    }

    private fun ancestor(n: AccessibilityNodeInfo?, depthLimit: Int = 12, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var cur = n
        var depth = 0
        while (cur != null && depth < depthLimit) {
            if (test(cur)) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    /**
     * Moves the TV's highlight onto [target] and presses OK. Asking for focus directly works in most apps; where it
     * doesn't, the highlight is walked there one arrow at a time, re-checking where the target is after each step
     * (rows slide as the highlight moves).
     */
    private fun highlightThenOk(target: AccessibilityNodeInfo, pressable: AccessibilityNodeInfo?, action: Int, id: Int) {
        if (target.isFocused) { key("ok"); return }
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        main.postDelayed({ walk(target, pressable, action, id, 0, "", 0) }, 120)
    }

    private fun walk(target: AccessibilityNodeInfo, pressable: AccessibilityNodeInfo?, action: Int, id: Int, steps: Int, last: String, stuck: Int) {
        if (id != walkId) return
        target.refresh()
        if (target.isFocused) { key("ok"); return }
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val t = Rect().also { target.getBoundsInScreen(it) }
        if (focused == null) {
            // Nothing highlighted yet: the first arrow press wakes the highlight up.
            if (steps < 2) { key("down"); main.postDelayed({ walk(target, pressable, action, id, steps + 1, last, stuck) }, 160) }
            else pressable?.performAction(action)
            return
        }
        val f = Rect().also { focused.getBoundsInScreen(it) }
        if (f == t || (t.contains(f.centerX(), f.centerY()) && f.width() <= t.width() * 1.5f)) { key("ok"); return }
        // The highlight isn't getting there (or isn't moving): press the item directly instead.
        val here = f.toShortString()
        val nowStuck = if (here == last) stuck + 1 else 0
        if (steps >= 14 || nowStuck >= 2) { Log.i(TAG, "walk gave up after $steps"); pressable?.performAction(action); return }
        val dx = t.centerX() - f.centerX()
        val dy = t.centerY() - f.centerY()
        // Rows first (up/down) when the target is in another row, then along the row.
        val dir = if (abs(dy) > f.height() / 2 && abs(dy) >= abs(dx) / 3) (if (dy > 0) "down" else "up") else (if (dx > 0) "right" else "left")
        key(dir)
        main.postDelayed({ walk(target, pressable, action, id, steps + 1, here, nowStuck) }, 150)
    }

    /** The innermost visible item under the point, in the topmost window there (our own cursor layer excluded). */
    private fun nodeAt(px: Int, py: Int): AccessibilityNodeInfo? {
        val r = Rect()
        for (w in windows.sortedByDescending { it.layer }) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            w.getBoundsInScreen(r)
            if (!r.contains(px, py)) continue
            val root = w.root ?: continue
            deepest(root, px, py, r)?.let { return it }
        }
        return null
    }

    private fun deepest(n: AccessibilityNodeInfo, px: Int, py: Int, r: Rect): AccessibilityNodeInfo? {
        n.getBoundsInScreen(r)
        if (!r.contains(px, py) || !n.isVisibleToUser) return null
        for (i in n.childCount - 1 downTo 0) {
            val child = n.getChild(i) ?: continue
            deepest(child, px, py, r)?.let { return it }
        }
        return n
    }

    // ---- Scrolling

    /**
     * A two-finger drag scrolls like on a phone: content follows the fingers (drag up to go down a list).
     * Each step is fired once the phone has dragged far enough.
     */
    private fun scroll(dx: Float, dy: Float) {
        lastMove = System.currentTimeMillis()
        if (arrowMode) { arrows(-dx * scale, -dy * scale); return }
        scrollAccum += dy * scale
        scrollAccumX += dx * scale
        place()
        val threshold = height / 9
        when {
            scrollAccum <= -threshold -> { scrollAccum = 0f; scrollAccumX = 0f; scrollStep(Dir.DOWN) }
            scrollAccum >= threshold -> { scrollAccum = 0f; scrollAccumX = 0f; scrollStep(Dir.UP) }
            scrollAccumX <= -threshold -> { scrollAccum = 0f; scrollAccumX = 0f; scrollStep(Dir.RIGHT) }
            scrollAccumX >= threshold -> { scrollAccum = 0f; scrollAccumX = 0f; scrollStep(Dir.LEFT) }
        }
    }

    /**
     * Scrolls the list under the cursor one step, the way TalkBack does, then checks the list really moved: some
     * screens accept the request and ignore it (Google TV's home while a video banner plays). If nothing moved,
     * or there's no list there, the remote's arrow does it by moving the highlight.
     */
    private fun scrollStep(dir: Dir) {
        val wanted = when (dir) {
            Dir.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            Dir.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            Dir.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            Dir.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        }
        val generic = if (dir == Dir.DOWN || dir == Dir.RIGHT) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val list = ancestor(nodeAt(x.toInt(), y.toInt()), 15) { n ->
            val ids = n.actionList.map { it.id }
            wanted in ids || (generic in ids && n.isScrollable && matchesAxis(n, dir))
        }
        val arrow = when (dir) { Dir.DOWN -> "down"; Dir.UP -> "up"; Dir.RIGHT -> "right"; Dir.LEFT -> "left" }
        if (list == null) { key(arrow); return }
        val before = fingerprint(list)
        val asked = list.performAction(if (wanted in list.actionList.map { it.id }) wanted else generic)
        if (!asked) { key(arrow); return }
        main.postDelayed({
            list.refresh()
            if (fingerprint(list) == before) key(arrow)
        }, 280)
    }

    /** Where a list's first few items are: changes when it scrolls. */
    private fun fingerprint(n: AccessibilityNodeInfo): String {
        val r = Rect()
        return (0 until minOf(3, n.childCount)).joinToString("|") { i ->
            n.getChild(i)?.let { c -> c.getBoundsInScreen(r); "${c.text ?: c.contentDescription ?: ""}@${r.top},${r.left}" } ?: "-"
        } + "#${n.childCount}"
    }

    /** A list's direction: from its rows and columns when it says, else from its shape (rows are short and wide). */
    private fun matchesAxis(n: AccessibilityNodeInfo, dir: Dir): Boolean {
        val info = n.collectionInfo
        val vertical = when {
            info != null && info.rowCount > 1 && info.columnCount <= 1 -> true
            info != null && info.columnCount > 1 && info.rowCount <= 1 -> false
            else -> Rect().also { n.getBoundsInScreen(it) }.let { it.height() >= it.width() * 0.4f }
        }
        return if (dir == Dir.UP || dir == Dir.DOWN) vertical else !vertical
    }

    // ---- Buttons, volume, typing

    private fun key(name: String) {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "up" -> GLOBAL_ACTION_DPAD_UP
            "down" -> GLOBAL_ACTION_DPAD_DOWN
            "left" -> GLOBAL_ACTION_DPAD_LEFT
            "right" -> GLOBAL_ACTION_DPAD_RIGHT
            "ok" -> GLOBAL_ACTION_DPAD_CENTER
            else -> return
        }
        performGlobalAction(action)
    }

    private fun volume(dir: String) {
        val audio = getSystemService(AudioManager::class.java)
        val adjust = when (dir) {
            "up" -> AudioManager.ADJUST_RAISE
            "down" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
    }

    /** Types into the focused text field (search boxes in YouTube, the Play Store, Antenna…). */
    private fun type(text: String) {
        val node = focusedInput() ?: return
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: windows.firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }

    /** An arrow cursor with a dark outline, visible on any picture; it flashes on click. */
    private class CursorView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; setShadowLayer(6f, 0f, 2f, 0x99000000.toInt()) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF11151C.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f; strokeJoin = Paint.Join.ROUND }
        private val accent = 0xFFF2B544.toInt()

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val p = Path().apply {
                moveTo(2f, 2f); lineTo(2f, h * 0.78f); lineTo(w * 0.28f, h * 0.6f)
                lineTo(w * 0.46f, h * 0.96f); lineTo(w * 0.62f, h * 0.88f); lineTo(w * 0.45f, h * 0.53f)
                lineTo(w * 0.86f, h * 0.53f); close()
            }
            canvas.drawPath(p, fill)
            canvas.drawPath(p, edge)
        }

        fun pulse() {
            fill.color = accent; invalidate()
            postDelayed({ fill.color = Color.WHITE; invalidate() }, 160)
        }
    }
}
