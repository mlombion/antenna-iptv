package com.malick.antenna.tv

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.snapshotFlow
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.malick.antenna.tv.cast.CastAccount
import com.malick.antenna.tv.cast.CastBridge
import com.malick.antenna.tv.cast.CastService
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.malick.antenna.tv.ui.Palette
import com.malick.antenna.tv.ui.RootScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var isTv = true

    override fun onCreate(savedInstanceState: Bundle?) {
        isTv = DeviceKind.isTv(this)
        if (!isTv) enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            // Local test server for development (adb shell am start -e devStremio http://10.0.2.2:8790/).
            intent?.getStringExtra("devStremio")?.let {
                com.malick.antenna.tv.data.stremio.StremioApi.apiBase = it + "api/"
                com.malick.antenna.tv.data.stremio.StremioApi.linkBase = it + "link/"
            }
            com.malick.antenna.tv.torrent.TorrentEngine.devBudgetMB = intent?.getIntExtra("devTorrentBudgetMB", 0)?.toLong() ?: 0
            com.malick.antenna.tv.player.VodPlayer.softwareDecodingOnly = intent?.getBooleanExtra("devSoftwareDecoding", false) == true
            // A TV the phone can't discover by itself, e.g. the TV emulator: -e devTv 10.0.2.2:18781
            intent?.getStringExtra("devTv")?.takeIf { !isTv }?.let {
                vm.phone.tv.addManual("Antenna · Test TV", it.substringBefore(':'), it.substringAfter(':').toIntOrNull() ?: 8781)
            }
        }
        if (!isTv) { startPhone(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        CastService.start(this)
        CastBridge.handler = vm
        handleCast(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Palette.accent, onPrimary = Palette.accentInk,
                    background = Palette.bg, surface = Palette.surface, onSurface = Palette.fg, onBackground = Palette.fg,
                ),
            ) { RootScreen(vm) }
        }
    }

    /** The phone app: touch screens, Play on TV and the remote. The TV-only services stay off. */
    private fun startPhone() {
        // Create the view model here, not inside the read-only snapshot below.
        val phone = vm.phone
        DeviceKind.disableTvParts(this)
        setContent { com.malick.antenna.tv.phone.PhoneTheme { com.malick.antenna.tv.phone.PhoneRoot(vm) } }
        // Leaving the app while something plays shrinks it into picture in picture (by itself on Android 12+).
        lifecycleScope.launch {
            snapshotFlow { phone.playerOpen }.collect { open ->
                if (Build.VERSION.SDK_INT >= 31) runCatching { setPictureInPictureParams(pipParams(open)) }
            }
        }
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
        return b.build()
    }

    fun enterPip() {
        if (isTv || !vm.phone.playerOpen) return
        runCatching { enterPictureInPictureMode(pipParams(true)) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isTv && Build.VERSION.SDK_INT < 31 && vm.phone.playerOpen) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isTv) vm.phone.pip = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        if (!isTv) vm.phone.tv.foreground = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isTv) handleCast(intent)
    }

    override fun onDestroy() {
        if (CastBridge.handler === vm) CastBridge.handler = null
        super.onDestroy()
    }

    /** The app was opened by a phone sending a channel. */
    private fun handleCast(intent: Intent?) {
        val id = intent?.getIntExtra(CastService.EXTRA_STREAM, -1) ?: -1
        if (id < 0) return
        val account = intent?.getStringExtra(CastService.EXTRA_ACCOUNT)?.let { runCatching { JSONObject(it) }.getOrNull() }?.let {
            CastAccount(it.optString("name"), it.optString("server"), it.optString("username"), it.optString("password").ifEmpty { null })
        }
        intent?.removeExtra(CastService.EXTRA_STREAM)
        intent?.removeExtra(CastService.EXTRA_ACCOUNT)
        lifecycleScope.launch { vm.castPlay(id, account)?.let { vm.showToast(it) } }
    }

    override fun onStop() {
        super.onStop()
        if (!isTv) vm.phone.tv.foreground = false
        // Leaving the app (Home button) stops the stream so the subscription's one connection is free again.
        vm.stopPlaying()
        vm.stremio.stopPlayback()
    }
}
