package com.malick.antenna.tv.pointer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
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
    private var gestureBusy = false

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
        }
    }

    private fun List<String>.f(i: Int) = getOrNull(i)?.toFloatOrNull() ?: 0f

    private fun move(dx: Float, dy: Float) {
        x = (x + dx * scale).coerceIn(0f, width - 1)
        y = (y + dy * scale).coerceIn(0f, height - 1)
        place()
    }

    private fun place() {
        val c = cursor ?: return
        c.translationX = x
        c.translationY = y
        if (c.alpha < 1f) { c.animate().cancel(); c.alpha = 1f }
        main.removeCallbacks(hide)
        main.postDelayed(hide, 5000)
    }

    private fun tap(long: Boolean) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, if (long) 800 else 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        cursor?.pulse()
        place()
    }

    /**
     * Scrolling is a finger swipe on the TV, fired once the phone has dragged far enough; a swipe moves content the
     * same way the fingers move (drag up to go down a list, like a phone).
     */
    private fun scroll(dx: Float, dy: Float) {
        scrollAccum += dy * scale
        scrollAccumX += dx * scale
        place()
        if (gestureBusy) return
        val threshold = height / 18
        if (abs(scrollAccum) < threshold && abs(scrollAccumX) < threshold) return
        val sx = scrollAccumX.coerceIn(-width / 2, width / 2)
        val sy = scrollAccum.coerceIn(-height / 2, height / 2)
        scrollAccum = 0f; scrollAccumX = 0f
        // Start where the cursor is, but leave room for the swipe on screen.
        val startX = x.coerceIn(abs(sx) + 1, width - abs(sx) - 1)
        val startY = y.coerceIn(abs(sy) + 1, height - abs(sy) - 1)
        val path = Path().apply { moveTo(startX, startY); lineTo(startX + sx, startY + sy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 180)
        gestureBusy = true
        val done = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { gestureBusy = false }
            override fun onCancelled(gestureDescription: GestureDescription?) { gestureBusy = false }
        }
        if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), done, null)) gestureBusy = false
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
