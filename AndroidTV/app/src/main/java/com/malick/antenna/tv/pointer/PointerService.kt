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
import android.widget.FrameLayout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The phone remote for the whole TV: draws a cursor over every app, and taps, long-presses, scrolls and presses
 * the remote's buttons where the phone says. It runs whenever it's switched on in the TV's accessibility settings,
 * whether or not Antenna is open, and listens on the home network (port 8782) for one simple line protocol:
 *
 *   m dx dy   move the cursor (phone points)      c / lc     click / long-press at the cursor
 *   s dx dy   scroll by a finger drag (points)     k <key>    back home up down left right ok recents notifications
 *   v up|down|mute   volume                        t <b64>    type text into the focused field   enter   center
 */
class PointerService : AccessibilityService() {
    companion object {
        const val PORT = 8782
        private const val TAG = "AntennaPointer"
        /** True while the service is on, so the cast status can tell phones the remote is available. */
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var overlay: FrameLayout? = null
    private var cursor: CursorView? = null
    private var server: ServerSocket? = null
    private var x = 0f
    private var y = 0f
    private var width = 1920f
    private var height = 1080f
    /** TV pixels per phone point: the cursor crosses the screen with a comfortable thumb movement. */
    private var scale = 2.4f
    private val hide = Runnable { cursor?.animate()?.alpha(0f)?.setDuration(400)?.start() }
    private var scrollAccum = 0f
    private var scrollAccumX = 0f
    private var lastMove = 0L
    /** The arrow's tip stops short of the bottom and right edges so part of it always stays on screen. */
    private var maxX = 1919f
    private var maxY = 1079f
    /** While the cursor rests at the top or bottom edge, the page keeps scrolling that way. */
    private val edgeScroll = object : Runnable {
        override fun run() {
            val zone = height * 0.035f
            val recent = System.currentTimeMillis() - lastMove < 5000
            when {
                !recent -> return
                y >= maxY - zone -> scrollStep(Dir.DOWN)
                y <= zone -> scrollStep(Dir.UP)
                else -> return
            }
            main.postDelayed(this, 650)
        }
    }
    private enum class Dir { UP, DOWN, LEFT, RIGHT }

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
        place()
        startServer()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
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
        out.write("ok ${width.toInt()} ${height.toInt()}\n".toByteArray())
        out.flush()
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        while (true) {
            val line = reader.readLine() ?: break
            main.post { command(line) }
        }
    }

    // ---- Commands

    private fun command(line: String) {
        val parts = line.trim().split(' ')
        when (parts.getOrNull(0)) {
            "m" -> move(parts.f(1), parts.f(2))
            "c" -> tap(long = false)
            "lc" -> tap(long = true)
            "s" -> scroll(parts.f(1), parts.f(2))
            "k" -> key(parts.getOrNull(1) ?: return)
            "v" -> volume(parts.getOrNull(1) ?: return)
            "t" -> type(String(Base64.decode(parts.getOrNull(1) ?: "", Base64.DEFAULT), Charsets.UTF_8))
            "enter" -> focusedInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            "show" -> place()
            "center" -> { x = width / 2; y = height / 2; place() }
            // Diagnostics: what a click would press here, without pressing it.
            "probe" -> Log.i(TAG, "at ${x.toInt()},${y.toInt()}: " + (actionableAt(x.toInt(), y.toInt(), AccessibilityNodeInfo.ACTION_CLICK)
                ?.let { "${it.className} '${it.text ?: it.contentDescription ?: ""}' clickable=${it.isClickable} focusable=${it.isFocusable}" } ?: "nothing"))
        }
    }

    private fun List<String>.f(i: Int) = getOrNull(i)?.toFloatOrNull() ?: 0f

    private fun move(dx: Float, dy: Float) {
        val wasInZone = inEdgeZone()
        x = (x + dx * scale).coerceIn(0f, maxX)
        y = (y + dy * scale).coerceIn(0f, maxY)
        lastMove = System.currentTimeMillis()
        place()
        if (inEdgeZone() && !wasInZone) { main.removeCallbacks(edgeScroll); main.postDelayed(edgeScroll, 350) }
    }

    private fun inEdgeZone() = y >= maxY - height * 0.035f || y <= height * 0.035f

    private fun place() {
        val c = cursor ?: return
        c.translationX = x
        c.translationY = y
        if (c.alpha < 1f) { c.animate().cancel(); c.alpha = 1f }
        main.removeCallbacks(hide)
        main.postDelayed(hide, 5000)
    }

    /**
     * TV boxes have no touchscreen and many ignore simulated taps, so a click presses whatever is under the cursor
     * directly (as TalkBack does): the button, card or link there. A simulated tap is the fallback for things
     * that don't describe themselves, like games and web pages.
     */
    private fun tap(long: Boolean) {
        cursor?.pulse()
        place()
        val target = actionableAt(x.toInt(), y.toInt(), if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
        val done = target?.let { node ->
            val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            node.performAction(action) || (node.isFocusable && node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) &&
                performGlobalAction(GLOBAL_ACTION_DPAD_CENTER))
        } ?: false
        if (done) return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, if (long) 800 else 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** The innermost visible item under the point, in the topmost window there (our own cursor layer excluded). */
    private fun nodeAt(px: Int, py: Int): AccessibilityNodeInfo? {
        val r = Rect()
        for (w in windows.sortedByDescending { it.layer }) {
            if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
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

    /** The item under the point, or the nearest one around it, that can do [action] (or at least take focus). */
    private fun actionableAt(px: Int, py: Int, action: Int): AccessibilityNodeInfo? {
        var n = nodeAt(px, py)
        var focusable: AccessibilityNodeInfo? = null
        var depth = 0
        while (n != null && depth < 12) {
            if (n.actionList.any { it.id == action } && n.isEnabled) return n
            // Lists and grids are focusable too, but "clicking" one would open whatever happens to be highlighted.
            if (focusable == null && n.isFocusable && !n.isScrollable && n.collectionInfo == null) focusable = n
            n = n.parent
            depth++
        }
        return focusable
    }

    /**
     * A two-finger drag scrolls like on a phone: content follows the fingers (drag up to go down a list).
     * Each step is fired once the phone has dragged far enough.
     */
    private fun scroll(dx: Float, dy: Float) {
        scrollAccum += dy * scale
        scrollAccumX += dx * scale
        lastMove = System.currentTimeMillis()
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
     * Scrolls the list under the cursor one step, the way TalkBack does; on screens without a scrollable list
     * there (like Google TV's home rows), the remote's arrow does it by moving the highlight.
     */
    private fun scrollStep(dir: Dir) {
        val wanted = when (dir) {
            Dir.DOWN -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            Dir.UP -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            Dir.RIGHT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            Dir.LEFT -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
        }
        val generic = if (dir == Dir.DOWN || dir == Dir.RIGHT) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        var n = nodeAt(x.toInt(), y.toInt())
        var depth = 0
        while (n != null && depth < 15) {
            val ids = n.actionList.map { it.id }
            if (wanted in ids && n.performAction(wanted)) return
            // Plain forward/backward only for lists that go the right way (vertical lists for up/down).
            if (generic in ids && n.isScrollable && matchesAxis(n, dir) && n.performAction(generic)) return
            n = n.parent
            depth++
        }
        performGlobalAction(when (dir) {
            Dir.DOWN -> GLOBAL_ACTION_DPAD_DOWN; Dir.UP -> GLOBAL_ACTION_DPAD_UP
            Dir.RIGHT -> GLOBAL_ACTION_DPAD_RIGHT; Dir.LEFT -> GLOBAL_ACTION_DPAD_LEFT
        })
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
