package com.malick.antenna.tv

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (BuildConfig.DEBUG) {
            // Local test server for development (adb shell am start -e devStremio http://10.0.2.2:8790/).
            intent?.getStringExtra("devStremio")?.let {
                com.malick.antenna.tv.data.stremio.StremioApi.apiBase = it + "api/"
                com.malick.antenna.tv.data.stremio.StremioApi.linkBase = it + "link/"
            }
            com.malick.antenna.tv.torrent.TorrentEngine.devBudgetMB = intent?.getIntExtra("devTorrentBudgetMB", 0)?.toLong() ?: 0
            com.malick.antenna.tv.player.VodPlayer.softwareDecodingOnly = intent?.getBooleanExtra("devSoftwareDecoding", false) == true
        }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCast(intent)
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
        // Leaving the app (Home button) stops the stream so the subscription's one connection is free again.
        vm.stopPlaying()
        vm.stremio.stopPlayback()
    }
}
