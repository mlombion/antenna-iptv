package com.malick.antenna.tv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.snapshotFlow
import com.malick.antenna.tv.cast.CastAccount
import com.malick.antenna.tv.cast.CastHandler
import com.malick.antenna.tv.data.AccountInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import com.malick.antenna.tv.data.Channel
import com.malick.antenna.tv.data.EpgState
import com.malick.antenna.tv.data.LiveCategory
import com.malick.antenna.tv.data.Lineup
import com.malick.antenna.tv.data.Programme
import com.malick.antenna.tv.data.SavedAccount
import com.malick.antenna.tv.data.Storage
import com.malick.antenna.tv.data.XtreamClient
import com.malick.antenna.tv.pairing.PairingServer
import com.malick.antenna.tv.player.TvPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class Phase { Launching, SignedOut, Connecting, Ready }
enum class Section { Home, Live, Guide, Movies, Favorites, Settings }

data class Schedule(val current: Programme?, val upcoming: List<Programme>, val state: EpgState?) {
    val isLoading get() = state == null || state == EpgState.Loading
}

class AppViewModel(app: Application) : AndroidViewModel(app), CastHandler {
    val storage = Storage(app)
    val player = TvPlayer(app)

    var phase by mutableStateOf(Phase.Launching); private set
    var loginError by mutableStateOf<String?>(null)
    var connectStep by mutableStateOf<String?>(null); private set
    var account by mutableStateOf<SavedAccount?>(null); private set
    var accountInfo by mutableStateOf<AccountInfo?>(null); private set
    private var client: XtreamClient? = null

    var allCategories by mutableStateOf<List<LiveCategory>>(emptyList()); private set
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    private var byCategory: Map<String, List<Channel>> = emptyMap()
    private var byNum: Map<Int, Channel> = emptyMap()
    private var byId: Map<Int, Channel> = emptyMap()

    var section by mutableStateOf(Section.Live)
    var selectedCategory by mutableStateOf(LiveCategory.ALL)
    var selectedChannelId by mutableStateOf<Int?>(null)
    var guideCategory by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null); private set

    var favorites by mutableStateOf(storage.favorites); private set
    var recents by mutableStateOf(storage.recents); private set
    var hideAdult by mutableStateOf(storage.hideAdult)
        private set

    private val epg = mutableStateMapOf<Int, EpgState>()
    private val epgFetchedAt = HashMap<Int, Long>()
    private val epgInFlight = HashSet<Int>()
    private val epgLimit = Semaphore(4)
    var now by mutableStateOf(System.currentTimeMillis()); private set

    var playing by mutableStateOf<Channel?>(null); private set
    var zapList: List<Channel> = emptyList(); private set
    private var zapJob: Job? = null
    private var toastJob: Job? = null

    /** Movies & Series from the user's Stremio account and add-ons. */
    val stremio = StremioModel(app, viewModelScope, storage, ::showToast) { hideAdult }

    /** Lets a phone or computer on the same Wi-Fi send the login, so nobody types a password with the remote. */
    val pairing = PairingServer { name, server, user, pass -> viewModelScope.launch { login(name, server, user, pass) } }

    init {
        com.malick.antenna.tv.player.TimeshiftRecorder.removeLeftovers(app)
        viewModelScope.launch { while (true) { delay(20_000); now = System.currentTimeMillis() } }
        start()
    }

    private fun start() {
        val saved = storage.account
        val base = saved?.let { XtreamClient.normalize(it.server) }
        if (saved == null || base == null) { phase = Phase.SignedOut; pairing.start(); return }
        account = saved
        client = XtreamClient(base, saved.username, saved.password)
        val cached = storage.loadLineup()
        if (cached != null) {
            apply(cached)
            phase = Phase.Ready
            viewModelScope.launch { refresh(silent = true) }
        } else {
            phase = Phase.Connecting
            viewModelScope.launch {
                runCatching { load(client!!) }
                    .onSuccess { apply(it); storage.saveLineup(it); phase = Phase.Ready }
                    .onFailure { loginError = it.message; phase = Phase.SignedOut; pairing.start() }
            }
        }
    }

    suspend fun login(name: String, server: String, username: String, password: String) {
        loginError = null
        val base = XtreamClient.normalize(server) ?: run { loginError = "That server URL doesn't look right. It usually looks like http://example.com:8080."; return }
        if (username.isBlank() || password.isBlank()) { loginError = "Enter your username and password."; return }
        val newClient = XtreamClient(base, username.trim(), password.trim())
        phase = Phase.Connecting
        runCatching { load(newClient) }
            .onSuccess { lineup ->
                val acc = SavedAccount(name.ifBlank { "My IPTV" }, base.toString(), username.trim(), password.trim())
                storage.account = acc
                storage.saveLineup(lineup)
                account = acc
                client = newClient
                epg.clear(); epgFetchedAt.clear()
                apply(lineup)
                pairing.stop()
                phase = Phase.Ready
                section = Section.Live
                showToast("Connected · ${channels.size} channels")
            }
            .onFailure { loginError = it.message; phase = Phase.SignedOut }
    }

    fun signOut() {
        stopPlaying()
        storage.account = null
        storage.clearLineup()
        account = null; client = null; accountInfo = null
        allCategories = emptyList(); channels = emptyList(); reindex()
        epg.clear()
        phase = Phase.SignedOut
        pairing.start()
    }

    suspend fun refresh(silent: Boolean) {
        val c = client ?: return
        runCatching { load(c) }
            .onSuccess { apply(it); storage.saveLineup(it); if (!silent) showToast("Channels refreshed · ${channels.size} channels") }
            .onFailure { if (!silent) showToast("Couldn't refresh channels. ${it.message}") }
    }

    private suspend fun load(c: XtreamClient): Lineup {
        connectStep = "Connecting to the server"
        val info = c.authenticate()
        connectStep = "Loading channels"
        val (cats, chans) = viewModelScope.async { c.liveCategories() }.let { cats -> cats.await() to c.liveStreams() }
        connectStep = null
        return Lineup(info, cats, chans, System.currentTimeMillis())
    }

    private fun apply(l: Lineup) {
        accountInfo = l.account
        val known = l.categories.map { it.id }.toSet()
        val cats = l.categories.toMutableList()
        if (l.channels.any { it.categoryId !in known }) cats += LiveCategory("", "Other")
        allCategories = cats
        channels = l.channels.map { if (it.categoryId in known) it else it.copy(categoryId = "") }
        reindex()
        if (selectedChannelId?.let { byId[it] } == null) selectedChannelId = visibleChannels.firstOrNull()?.id
    }

    private fun reindex() {
        byCategory = channels.groupBy { it.categoryId }
        byNum = channels.associateBy { it.num }
        byId = channels.associateBy { it.id }
    }

    // ---- Queries

    val categories: List<LiveCategory> get() = if (hideAdult) allCategories.filterNot { it.isAdult } else allCategories

    fun channelsIn(categoryId: String): List<Channel> = when (categoryId) {
        LiveCategory.ALL -> {
            val hidden = if (hideAdult) allCategories.filter { it.isAdult }.map { it.id }.toSet() else emptySet()
            if (hidden.isEmpty()) channels else channels.filterNot { it.categoryId in hidden }
        }
        LiveCategory.FAVORITES -> channels.filter { it.id in favorites }
        else -> byCategory[categoryId].orEmpty()
    }

    fun categoryName(id: String) = when (id) {
        LiveCategory.ALL -> "All channels"
        LiveCategory.FAVORITES -> "Favorites"
        else -> allCategories.firstOrNull { it.id == id }?.name ?: "Other"
    }

    val visibleChannels: List<Channel> get() = channelsIn(selectedCategory)
    val selectedChannel: Channel? get() = selectedChannelId?.let { byId[it] }
    val favoriteChannels get() = channels.filter { it.id in favorites }
    val recentChannels get() = recents.mapNotNull { byId[it] }
    fun channel(number: Int) = byNum[number]

    fun selectCategory(id: String) {
        selectedCategory = id
        val list = visibleChannels
        if (list.none { it.id == selectedChannelId }) selectedChannelId = list.firstOrNull()?.id
    }

    // ---- Guide

    fun schedule(c: Channel): Schedule {
        val s = epg[c.id]
        if (s !is EpgState.Loaded) return Schedule(null, emptyList(), s)
        val cur = s.list.firstOrNull { it.isOn(now) }
        val after = cur?.end ?: now
        return Schedule(cur, s.list.filter { it.start >= after }, s)
    }

    fun requestEpg(c: Channel) {
        val client = client ?: return
        val id = c.id
        if (id in epgInFlight) return
        val fetched = epgFetchedAt[id]
        if (fetched != null) {
            val age = System.currentTimeMillis() - fetched
            when (val s = epg[id]) {
                is EpgState.Loaded -> if ((s.list.lastOrNull()?.end ?: 0) > now + 3_600_000 && age < 1_800_000) return
                EpgState.Unavailable -> if (age < 3_600_000) return
                EpgState.Failed -> if (age < 120_000) return
                else -> {}
            }
        }
        epgInFlight += id
        if (epg[id] == null) epg[id] = EpgState.Loading
        viewModelScope.launch {
            val result = epgLimit.withPermit {
                runCatching { client.shortEpg(id) }.fold(
                    onSuccess = { if (it.isEmpty()) EpgState.Unavailable else EpgState.Loaded(it) },
                    onFailure = { EpgState.Failed },
                )
            }
            epgInFlight -= id
            epgFetchedAt[id] = System.currentTimeMillis()
            if (result == EpgState.Failed && epg[id] is EpgState.Loaded) return@launch
            epg[id] = result
        }
    }

    // ---- Favorites

    fun isFavorite(c: Channel) = c.id in favorites

    fun toggleFavorite(c: Channel) {
        favorites = if (c.id in favorites) favorites - c.id else favorites + c.id
        storage.favorites = favorites
        showToast(if (c.id in favorites) "Added ${c.displayName} to Favorites" else "Removed ${c.displayName} from Favorites")
    }

    fun setHideAdultCategories(v: Boolean) { hideAdult = v; storage.hideAdult = v }

    // ---- Playback

    private val effectiveFormat: String
        get() {
            val allowed = accountInfo?.formats.orEmpty()
            val wanted = storage.format
            return if (allowed.isEmpty() || wanted in allowed) wanted else if ("m3u8" in allowed) "m3u8" else "ts"
        }

    fun play(c: Channel, list: List<Channel>? = null) {
        zapList = if (list != null && c in list) list else visibleChannels.takeIf { c in it } ?: channelsIn(LiveCategory.ALL)
        tune(c, debounce = false)
    }

    fun zap(delta: Int) {
        val cur = playing ?: return
        if (zapList.isEmpty()) return
        val i = zapList.indexOfFirst { it.id == cur.id }.coerceAtLeast(0)
        tune(zapList[(i + delta + zapList.size) % zapList.size], debounce = true)
    }

    fun tune(number: Int) {
        val c = channel(number) ?: run { showToast("No channel $number"); return }
        if (c !in zapList) zapList = channelsIn(LiveCategory.ALL)
        tune(c, debounce = false)
    }

    private fun tune(c: Channel, debounce: Boolean) {
        stremio.stopPlayback()
        playing = c
        selectedChannelId = c.id
        recents = (listOf(c.id) + recents.filter { it != c.id }).take(20)
        storage.recents = recents
        requestEpg(c)
        val client = client ?: return
        val url = client.streamUrl(c, effectiveFormat)
        val fallback = client.streamUrl(c, if (effectiveFormat == "ts") "m3u8" else "ts")
        zapJob?.cancel()
        player.prepareSwitch()
        if (debounce) {
            // Holding the button flips through channels; only open the one you stop on.
            zapJob = viewModelScope.launch { delay(260); player.load(url, fallback, storage.bufferMs, storage.rewindMinutes) }
        } else {
            player.load(url, fallback, storage.bufferMs, storage.rewindMinutes)
        }
    }

    // ---- Antenna Connect (commands from the phone)

    override fun status(): JSONObject {
        val ch = playing
        val state = when (player.state) {
            TvPlayer.State.Playing -> "playing"
            TvPlayer.State.Opening -> "opening"
            TvPlayer.State.Paused -> "paused"
            is TvPlayer.State.Failed -> "failed"
            TvPlayer.State.Idle -> "idle"
        }
        val name = Settings.Global.getString(getApplication<Application>().contentResolver, "device_name") ?: Build.MODEL
        return JSONObject().put("name", name).put("signedIn", account != null)
            .put("server", account?.server ?: JSONObject.NULL).put("username", account?.username ?: JSONObject.NULL)
            .put("state", state)
            .put("timeline", player.timeline?.let { t ->
                JSONObject().put("startMs", t.start).put("endMs", t.end).put("playheadMs", t.playhead)
                    .put("behindMs", t.behindMs).put("live", !player.isBehindLive)
            } ?: JSONObject.NULL)
            .put("vod", stremio.remoteStatus() ?: JSONObject.NULL)
            .put("stremio", stremio.signedIn)
            .put("playing", ch?.let {
                JSONObject().put("streamId", it.id).put("num", it.num).put("name", it.displayName)
                    .put("title", schedule(it).current?.title ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
    }

    override suspend fun castPlay(streamId: Int, account: CastAccount?): String? {
        // Wait for a cold start to finish loading the saved channel list.
        withTimeoutOrNull(20_000) { snapshotFlow { phase }.first { it == Phase.Ready || it == Phase.SignedOut } }
        val current = this.account
        val sameAccount = current != null && (account == null ||
            (XtreamClient.normalize(current.server)?.host == XtreamClient.normalize(account.server)?.host && current.username == account.username))
        if (!sameAccount) {
            val acc = account ?: return "Sign this TV in first."
            val password = acc.password ?: return "This TV is signed in to a different subscription."
            login(acc.name, acc.server, acc.username, password)
            if (phase != Phase.Ready) return loginError ?: "The TV couldn't sign in."
        }
        val ch = byId[streamId] ?: run {
            refresh(silent = true)
            byId[streamId]
        } ?: return "That channel isn't in the TV's channel list."
        play(ch)
        showToast("Playing from your phone")
        return null
    }

    override suspend fun castStremio(body: JSONObject): String? {
        withTimeoutOrNull(20_000) { snapshotFlow { phase }.first { it == Phase.Ready || it == Phase.SignedOut } }
        if (phase != Phase.Ready) return "Sign the TV in to your IPTV subscription first."
        stopPlaying()
        val error = stremio.playFromRemote(body)
        if (error == null) showToast("Playing from your phone")
        return error
    }

    override fun command(action: String, args: JSONObject) {
        if (action !in setOf("volumeUp", "volumeDown", "mute") && stremio.remoteCommand(action, args)) return
        val audio = getApplication<Application>().getSystemService(android.media.AudioManager::class.java)
        val stream = android.media.AudioManager.STREAM_MUSIC
        val show = android.media.AudioManager.FLAG_SHOW_UI
        when (action) {
            "stop" -> stopPlaying()
            "pause" -> player.togglePause()
            "next" -> zap(1)
            "previous" -> zap(-1)
            "back10" -> player.seekBy(-10_000)
            "fwd10" -> player.seekBy(10_000)
            "back30" -> player.seekBy(-30_000)
            "fwd30" -> player.seekBy(30_000)
            "live" -> player.goLive()
            "seekTo" -> args.optLong("time").takeIf { it > 0 }?.let(player::seekTo)
            "volumeUp" -> audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, show)
            "volumeDown" -> audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, show)
            "mute" -> audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_TOGGLE_MUTE, show)
        }
    }

    fun programmes(c: Channel): List<Programme> = (epg[c.id] as? EpgState.Loaded)?.list.orEmpty()

    fun stopPlaying() {
        zapJob?.cancel()
        player.stop()
        playing = null
    }

    fun showToast(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(2500); toast = null }
    }

    override fun onCleared() {
        stremio.release()
        player.release()
        pairing.stop()
    }
}
