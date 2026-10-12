package com.malick.antenna.tv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.malick.antenna.tv.data.Storage
import com.malick.antenna.tv.data.stremio.Addon
import com.malick.antenna.tv.data.stremio.AddonClient
import com.malick.antenna.tv.data.stremio.CatalogDef
import com.malick.antenna.tv.data.stremio.LibraryItem
import com.malick.antenna.tv.data.stremio.MetaDetail
import com.malick.antenna.tv.data.stremio.MetaPreview
import com.malick.antenna.tv.data.stremio.Stream
import com.malick.antenna.tv.data.stremio.StremioApi
import com.malick.antenna.tv.data.stremio.StremioException
import com.malick.antenna.tv.data.stremio.Subtitle
import com.malick.antenna.tv.data.stremio.Video
import com.malick.antenna.tv.data.stremio.formatDate
import com.malick.antenna.tv.player.VodPlayer
import com.malick.antenna.tv.torrent.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Movies & Series: the user's Stremio account (library, Continue Watching, installed add-ons) and playback
 * of whatever the add-ons find. Progress is written back to the account so the official apps stay in sync.
 */
class StremioModel(
    app: Application,
    private val scope: CoroutineScope,
    private val storage: Storage,
    private val toast: (String) -> Unit,
    private val hideAdult: () -> Boolean,
) {
    val api = StremioApi()
    val player = VodPlayer(app)

    var authKey by mutableStateOf(storage.stremioAuthKey); private set
    var email by mutableStateOf(storage.stremioEmail); private set
    var addons by mutableStateOf(loadCachedAddons()); private set
    var library by mutableStateOf(loadCachedLibrary()); private set
    var isRefreshing by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set
    val signedIn get() = authKey != null

    init {
        TorrentEngine.init(app)
        if (signedIn) scope.launch { refresh() }
    }

    // ---- Sign-in with a code (Stremio's own page handles Facebook, Apple or email)

    var linkCode by mutableStateOf<StremioApi.LinkCode?>(null); private set
    var signInError by mutableStateOf<String?>(null); private set
    private var signInJob: Job? = null

    fun startSignIn() {
        if (signInJob?.isActive == true) return
        signInError = null
        signInJob = scope.launch {
            while (isActive) {
                val code = runCatching { api.createLink() }.getOrElse { signInError = it.message; linkCode = null; return@launch }
                linkCode = code
                // A code lasts a few minutes; make a new one if nobody used it.
                repeat(100) {
                    delay(3000)
                    val key = runCatching { api.readLink(code.code) }.getOrNull()
                    if (key != null) { finishSignIn(key); return@launch }
                }
            }
        }
    }

    fun cancelSignIn() { signInJob?.cancel(); linkCode = null }

    /** A key handed over from the phone or computer (it signed in with the same code flow). */
    fun signInWithKey(key: String) { scope.launch { finishSignIn(key) } }

    private suspend fun finishSignIn(key: String) {
        val user = runCatching { api.user(key) }.getOrElse { signInError = it.message; return }
        storage.stremioAuthKey = key
        storage.stremioEmail = user.email
        authKey = key
        email = user.email
        linkCode = null
        rows.clear()
        toast("Signed in to Stremio as ${user.email}")
        refresh()
    }

    fun signOut() {
        stopPlayback()
        cancelSignIn()
        storage.stremioAuthKey = null
        storage.stremioEmail = null
        storage.clearStremio()
        authKey = null; email = null
        addons = emptyList(); library = emptyList()
        rows.clear(); search.clear()
    }

    suspend fun refresh() {
        val key = authKey ?: return
        isRefreshing = true
        loadError = null
        try {
            val a = api.addons(key)
            val l = api.library(key)
            // Keep local changes that haven't reached the server yet.
            val unsent = library.filter { it.id in dirty }.associateBy { it.id }
            addons = a
            library = l.map { unsent[it.id]?.takeIf { u -> u.mtime > it.mtime } ?: it } + unsent.values.filter { u -> l.none { it.id == u.id } }
            storage.writeStremio("addons.json", JSONArray().apply { a.forEach { put(it.raw) } }.toString())
            saveLibrary()
        } catch (e: StremioException) {
            loadError = e.message
            if (e.signedOut) signOut()
        } finally {
            isRefreshing = false
        }
    }

    private fun loadCachedAddons(): List<Addon> {
        val arr = runCatching { JSONArray(storage.readStremio("addons.json")) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Addon::parse) }
    }

    private fun loadCachedLibrary(): List<LibraryItem> {
        val arr = runCatching { JSONArray(storage.readStremio("library.json")) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::LibraryItem) }
    }

    private fun saveLibrary() {
        val snapshot = library
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            storage.writeStremio("library.json", JSONArray().apply { snapshot.forEach { put(it.json) } }.toString())
        }
    }

    // ---- What the screens show

    val continueWatching: List<LibraryItem>
        get() = library.filter { it.inContinueWatching }.sortedByDescending { it.mtime }.take(40)

    val libraryItems: List<LibraryItem>
        get() = library.filter { it.inLibrary }.sortedByDescending { it.mtime }

    fun libraryItem(id: String) = library.firstOrNull { it.id == id }

    private val shownTypes = setOf("movie", "series", "anime")

    private val visibleAddons get() = addons.filter { !(hideAdult() && it.adult) }

    /** Catalog rows from the installed add-ons, in the order you installed them (as on Stremio's board). */
    val boardCatalogs: List<Pair<Addon, CatalogDef>>
        get() = visibleAddons.flatMap { a -> a.catalogs.filter { !it.needsInput && it.type in shownTypes }.map { a to it } }

    sealed interface RowState {
        data object Loading : RowState
        data class Loaded(val items: List<MetaPreview>) : RowState
        data object Failed : RowState
    }

    val rows = mutableStateMapOf<String, RowState>()
    private val rowLimit = Semaphore(4)

    fun loadRow(addon: Addon, catalog: CatalogDef) {
        if (rows[catalog.key] != null) return
        rows[catalog.key] = RowState.Loading
        scope.launch {
            rows[catalog.key] = rowLimit.withPermit {
                runCatching { AddonClient.catalog(addon, catalog) }
                    .fold({ if (it.isEmpty()) RowState.Failed else RowState.Loaded(it) }, { RowState.Failed })
            }
        }
    }

    // ---- Search across every add-on catalog that supports it

    var searchQuery by mutableStateOf(""); private set
    val search = mutableStateMapOf<String, RowState>()
    private var searchJob: Job? = null

    val searchCatalogs: List<Pair<Addon, CatalogDef>>
        get() = visibleAddons.flatMap { a -> a.catalogs.filter { it.supportsSearch && it.type in shownTypes && it.extras.none { e -> e.isRequired && e.name != "search" } }.map { a to it } }

    fun search(query: String) {
        searchQuery = query
        searchJob?.cancel()
        search.clear()
        val q = query.trim()
        if (q.length < 2) return
        searchJob = scope.launch {
            delay(450)
            searchCatalogs.forEach { (addon, catalog) ->
                search[catalog.key] = RowState.Loading
                launch {
                    search[catalog.key] = rowLimit.withPermit {
                        runCatching { AddonClient.catalog(addon, catalog, listOf("search" to q)) }
                            .fold({ if (it.isEmpty()) RowState.Failed else RowState.Loaded(it) }, { RowState.Failed })
                    }
                }
            }
        }
    }

    // ---- A title's page: details, episodes, and the sources add-ons find

    sealed interface SourcesState {
        data object Loading : SourcesState
        data class Loaded(val streams: List<Stream>) : SourcesState
        data object Failed : SourcesState
    }

    inner class Detail(val preview: MetaPreview) {
        var meta by mutableStateOf<MetaDetail?>(null)
        var metaFailed by mutableStateOf(false)
        var season by mutableStateOf<Int?>(null)
        /** The episode (or the movie itself) whose sources are showing. */
        var sourcesFor by mutableStateOf<Video?>(null)
        val sources = mutableStateMapOf<String, SourcesState>()
        var sourcesVideoId by mutableStateOf<String?>(null)
        /** Play the first source from this group as soon as it shows up (next episode, same quality and site). */
        var autoPick: String? = null
        val isSeries get() = (meta?.videos?.size ?: 0) > 1 || preview.type == "series" || preview.type == "anime"
        val sourcesPending get() = sources.values.count { it == SourcesState.Loading }
        val allSources: List<Stream> get() = sources.values.filterIsInstance<SourcesState.Loaded>().flatMap { it.streams }
    }

    var detail by mutableStateOf<Detail?>(null); private set
    private var sourcesJob: Job? = null

    fun openDetail(preview: MetaPreview) {
        val d = Detail(preview)
        detail = d
        // A movie's sources don't depend on its details, so ask for both at once.
        if (preview.type == "movie") loadSources(d, null)
        scope.launch {
            val providers = visibleAddons.filter { it.provides("meta", preview.type, preview.id) }
            var meta: MetaDetail? = null
            for (a in providers) {
                meta = runCatching { AddonClient.meta(a, preview.type, preview.id) }.getOrNull()
                if (meta != null) break
            }
            if (detail !== d) return@launch
            d.meta = meta
            d.metaFailed = meta == null
            val item = libraryItem(preview.id)
            if (d.isSeries && meta != null) {
                val current = item?.videoId?.let { id -> meta.videos.firstOrNull { it.id == id } }
                d.season = current?.season ?: meta.seasons.firstOrNull { it > 0 } ?: meta.seasons.firstOrNull()
            } else if (d.sourcesVideoId == null) {
                loadSources(d, null)
            }
        }
    }

    fun openDetail(item: LibraryItem) = openDetail(
        MetaPreview(item.id, item.type, item.name, item.poster, item.posterShape, null, null, null, null, null, emptyList(), null),
    )

    fun closeDetail() { sourcesJob?.cancel(); detail = null }

    /** Asks every add-on that has sources for this movie or episode, showing results as they come in. */
    fun loadSources(d: Detail, video: Video?) {
        val type = d.preview.type
        val videoId = video?.id ?: d.meta?.videos?.singleOrNull()?.id?.takeIf { !d.isSeries } ?: d.preview.id
        d.sourcesFor = video
        if (d.sourcesVideoId == videoId && d.sources.isNotEmpty()) return
        d.sourcesVideoId = videoId
        d.sources.clear()
        sourcesJob?.cancel()
        val providers = visibleAddons.filter { it.provides("stream", type, videoId) }
        sourcesJob = scope.launch {
            providers.forEach { a ->
                d.sources[a.transportUrl] = SourcesState.Loading
                launch {
                    val result = runCatching { AddonClient.streams(a, type, videoId) }
                        .fold({ if (it.isEmpty()) SourcesState.Failed else SourcesState.Loaded(it) }, { SourcesState.Failed })
                    if (d.sourcesVideoId != videoId) return@launch
                    d.sources[a.transportUrl] = result
                    val group = d.autoPick
                    if (group != null && result is SourcesState.Loaded) {
                        result.streams.firstOrNull { it.bingeGroup == group && it.playable }?.let { s ->
                            d.autoPick = null
                            play(d, video, s)
                        }
                    }
                }
            }
        }
    }

    fun closeSources(d: Detail) {
        sourcesJob?.cancel()
        d.sourcesFor = null
        d.sourcesVideoId = null
        d.sources.clear()
    }

    fun addonName(transportUrl: String) = addons.firstOrNull { it.transportUrl == transportUrl }?.name ?: "Add-on"

    fun toggleLibrary(d: Detail) {
        val existing = libraryItem(d.preview.id)
        val meta = d.meta?.preview ?: d.preview
        val add = existing?.inLibrary != true
        val item = (existing ?: LibraryItem.create(meta, inLibrary = true)).copy { o, _ ->
            o.put("removed", !add); o.put("temp", false)
            if (add) { o.put("name", meta.name); meta.poster?.let { o.put("poster", it) } }
        }
        upsert(item)
        push(force = true)
        toast(if (add) "Added ${meta.name} to your library" else "Removed ${meta.name} from your library")
    }

    // ---- Playback

    inner class Playback(
        val detail: Detail,
        val video: Video?,
        val videoId: String,
        val stream: Stream,
    ) {
        val title: String get() = detail.meta?.preview?.name ?: detail.preview.name
        /** The episode as the add-on describes it, once the details arrive (a phone may send just the id). */
        private val episode: Video? get() = detail.meta?.videos?.firstOrNull { it.id == videoId } ?: video
        val subtitle: String? get() = episode?.let { v -> if (v.season != null) listOf(v.label, v.title).filter { it.isNotBlank() }.joinToString(" · ") else v.title }
        var step by mutableStateOf<String?>("Starting")
        var error by mutableStateOf<String?>(null)
        var subtitles by mutableStateOf<List<Subtitle>>(emptyList())
        var torrentStats by mutableStateOf<TorrentEngine.Stats?>(null)
        var fileName: String? = stream.filename
        var fileSize: Long? = stream.videoSize
        val next: Video? get() = detail.meta?.next(videoId)
    }

    var playback by mutableStateOf<Playback?>(null); private set
    private var playJob: Job? = null
    private var lastPush = 0L
    private val dirty = HashSet<String>()
    private val subtitlePrefs get() = storage.subtitleLang

    fun play(d: Detail, video: Video?, stream: Stream, startAt: Long? = null) {
        when {
            stream.ytId != null && stream.url == null -> { toast("YouTube sources can't play here yet. Pick another one."); return }
            !stream.playable -> { toast("This source only opens on its own website. Pick another one."); return }
        }
        finishPlayback(stopTorrent = true)
        val videoId = video?.id ?: d.sourcesVideoId ?: d.preview.id
        val p = Playback(d, video, videoId, stream)
        playback = p
        rememberStream(d.preview.id, videoId, stream)
        val item = libraryItem(d.preview.id)
        val resume = startAt ?: item?.takeIf { it.videoId == videoId && it.timeOffset > 5_000 && (it.duration == 0L || it.timeOffset < it.duration * 0.95) }?.timeOffset ?: 0L

        playJob = scope.launch {
            if (stream.infoHash != null) launch {
                while (isActive && playback === p && p.step != null) { p.torrentStats = TorrentEngine.stats(); delay(1000) }
            }
            val url = if (stream.infoHash != null) {
                try {
                    val opened = TorrentEngine.open(stream.infoHash, stream.fileIdx, stream.sources, video?.season, video?.episode) { p.step = it }
                    p.fileName = p.fileName ?: opened.fileName
                    p.fileSize = p.fileSize ?: opened.size
                    p.step = "Downloading the start of the video"
                    opened.url
                } catch (e: Exception) {
                    if (playback === p) { p.error = e.message ?: "This torrent didn't start. Try another source."; p.step = null }
                    return@launch
                }
            } else stream.url!!
            if (playback !== p) return@launch
            player.open(url, resume, stream.headers["User-Agent"], stream.headers["Referer"], torrent = stream.infoHash != null)
            launch { loadSubtitles(p) }
            // Torrent progress for the loading screen, then progress saving while watching.
            while (isActive && playback === p) {
                if (stream.infoHash != null) p.torrentStats = TorrentEngine.stats()
                if (player.state == VodPlayer.State.Playing) {
                    p.step = null
                    recordProgress(p, final = false)
                }
                if (player.state is VodPlayer.State.Failed && p.error == null) {
                    p.error = (player.state as VodPlayer.State.Failed).message
                }
                delay(if (player.state == VodPlayer.State.Playing) 5000 else 1000)
            }
        }
    }

    private suspend fun loadSubtitles(p: Playback) {
        val d = p.detail
        val extra = buildList {
            p.fileSize?.let { add("videoSize" to it.toString()) }
            p.fileName?.let { add("filename" to it) }
        }
        val found = visibleAddons.filter { it.provides("subtitles", d.preview.type, p.videoId) }.flatMap { a ->
            runCatching { AddonClient.subtitles(a, d.preview.type, p.videoId, extra) }.getOrDefault(emptyList())
        }
        if (playback !== p) return
        // One per language is plenty on a TV; the add-on lists the best first.
        val preferred = listOfNotNull(subtitlePrefs, java.util.Locale.getDefault().isO3Language, "eng", "fre", "fra")
            .map { languageName(it) }.distinct()
        p.subtitles = found.distinctBy { languageName(it.lang) }.sortedWith(compareBy(
            { preferred.indexOf(languageName(it.lang)).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { languageName(it.lang) }))
        val wanted = subtitlePrefs ?: return
        val match = p.subtitles.firstOrNull { it.lang == wanted } ?: return
        // Only when the file has no subtitles of its own in that language.
        repeat(20) { if (player.state != VodPlayer.State.Playing) delay(500) }
        if (playback === p && player.subtitleTracks.none { it.name.contains(languageName(wanted), ignoreCase = true) }) player.addSubtitle(match.url)
    }

    fun chooseSubtitle(sub: Subtitle) {
        player.addSubtitle(sub.url)
        storage.subtitleLang = sub.lang
    }

    fun subtitlesOff() {
        player.selectSubtitle(-1)
        storage.subtitleLang = null
    }

    /** The next episode: reuses the same kind of source (same add-on and quality) when it can. */
    fun playNext() {
        val p = playback ?: return
        val next = p.next ?: return
        val d = p.detail
        finishPlayback(stopTorrent = true)
        d.season = next.season ?: d.season
        d.autoPick = p.stream.bingeGroup
        loadSources(d, next)
        if (p.stream.bingeGroup == null) toast("Pick a source for ${next.label}")
    }

    /** Resume where you left off with the source you used last time, if it's remembered. */
    fun resume(d: Detail): Boolean {
        val item = libraryItem(d.preview.id) ?: return false
        val videoId = item.videoId ?: d.preview.id
        val saved = runCatching { JSONObject(storage.lastStreams).optJSONObject(d.preview.id) }.getOrNull()
        val video = d.meta?.videos?.firstOrNull { it.id == videoId }
        if (saved != null && saved.optString("videoId") == videoId) {
            val stream = saved.optJSONObject("stream")?.let { Stream.parse(it, saved.optString("addon")) }
            if (stream != null) {
                // Have the other sources ready in case this one stopped working since last time.
                video?.season?.let { d.season = it }
                loadSources(d, video)
                play(d, video, stream)
                return true
            }
        }
        video?.season?.let { d.season = it }
        loadSources(d, video)
        return false
    }

    private fun rememberStream(metaId: String, videoId: String, stream: Stream) {
        val all = runCatching { JSONObject(storage.lastStreams) }.getOrDefault(JSONObject())
        all.put(metaId, JSONObject().put("videoId", videoId).put("addon", stream.addonName).put("stream", stream.json))
        // Keep the 50 most recent.
        val keys = all.keys().asSequence().toList()
        if (keys.size > 50) keys.take(keys.size - 50).forEach { all.remove(it) }
        storage.lastStreams = all.toString()
    }

    /**
     * A movie or episode sent from the phone: what to play and which source, as the phone's add-ons found it.
     * The phone's Stremio key comes along so a TV that isn't signed in can save progress to the same account.
     */
    suspend fun playFromRemote(body: JSONObject): String? {
        if (!signedIn) {
            val key = body.optString("authKey").ifEmpty { null } ?: return "Sign in to Stremio on the TV first."
            finishSignIn(key)
            if (!signedIn) return signInError ?: "The TV couldn't sign in to Stremio."
        }
        val type = body.optString("type").ifEmpty { return "Missing title" }
        val id = body.optString("id").ifEmpty { return "Missing title" }
        val videoId = body.optString("videoId").ifEmpty { id }
        val stream = body.optJSONObject("stream")?.let { Stream.parse(it, body.optString("addon", "Add-on")) }
            ?: return "That source can't play on the TV."
        val preview = MetaPreview(id, type, body.optString("name", id), body.optString("poster").ifEmpty { null },
            "poster", body.optString("background").ifEmpty { null }, null, null, null, null, emptyList(), null)
        if (detail?.preview?.id != id) openDetail(preview)
        val d = detail!!
        val parts = videoId.split(':')
        val video = if (parts.size >= 3) Video(videoId, body.optString("episodeTitle", ""), parts[parts.size - 2].toIntOrNull(),
            parts.last().toIntOrNull(), null, null, null) else null
        d.sourcesVideoId = videoId
        play(d, d.meta?.videos?.firstOrNull { it.id == videoId } ?: video, stream, body.optLong("startAt", -1).takeIf { it >= 0 })
        return null
    }

    /** What the phone's remote shows while a movie or episode plays. */
    fun remoteStatus(): JSONObject? {
        val p = playback ?: return null
        val state = when {
            p.error != null -> "failed"
            p.step != null -> "opening"
            else -> when (player.state) {
                VodPlayer.State.Playing -> "playing"; VodPlayer.State.Paused -> "paused"; VodPlayer.State.Ended -> "ended"
                VodPlayer.State.Opening -> "opening"; is VodPlayer.State.Failed -> "failed"; VodPlayer.State.Idle -> "idle"
            }
        }
        val meta = p.detail.meta?.preview ?: p.detail.preview
        return JSONObject().put("id", meta.id).put("type", meta.type).put("title", p.title).put("subtitle", p.subtitle ?: JSONObject.NULL)
            .put("poster", meta.poster ?: JSONObject.NULL).put("state", state).put("step", p.step ?: JSONObject.NULL)
            .put("error", p.error ?: JSONObject.NULL).put("positionMs", player.time).put("durationMs", player.length)
            .put("hasNext", p.next != null)
            .put("subtitles", JSONArray().apply {
                put(JSONObject().put("key", "off").put("label", "Off").put("on", player.subtitleTrack < 0))
                player.subtitleTracks.forEach { put(JSONObject().put("key", "t${it.id}").put("label", it.name).put("on", it.id == player.subtitleTrack)) }
                p.subtitles.forEach { put(JSONObject().put("key", "a${it.id}").put("label", languageName(it.lang) + " · from add-on").put("on", false)) }
            })
            .put("audio", JSONArray().apply {
                player.audioTracks.forEach { put(JSONObject().put("key", "t${it.id}").put("label", it.name).put("on", it.id == player.audioTrack)) }
            })
    }

    /** Remote buttons while a movie or episode plays. Returns false when there's nothing playing to control. */
    fun remoteCommand(action: String, args: JSONObject): Boolean {
        val p = playback ?: return false
        when (action) {
            "stop" -> stopPlayback()
            "pause" -> player.togglePause()
            "back10" -> player.seekBy(-10_000)
            "fwd10" -> player.seekBy(10_000)
            "back30" -> player.seekBy(-30_000)
            "fwd30" -> player.seekBy(30_000)
            "seekTo" -> args.optLong("position", -1).takeIf { it >= 0 }?.let(player::seekTo)
            "next" -> if (p.next != null) playNext()
            "subtitle" -> args.optString("key").let { key ->
                when {
                    key == "off" -> subtitlesOff()
                    key.startsWith("t") -> key.drop(1).toIntOrNull()?.let(player::selectSubtitle)
                    key.startsWith("a") -> p.subtitles.firstOrNull { "a${it.id}" == key }?.let(::chooseSubtitle)
                }
            }
            "audio" -> args.optString("key").removePrefix("t").toIntOrNull()?.let(player::selectAudio)
            else -> return false
        }
        return true
    }

    fun stopPlayback() = finishPlayback(stopTorrent = true)

    private fun finishPlayback(stopTorrent: Boolean) {
        val p = playback ?: return
        recordProgress(p, final = true)
        playJob?.cancel()
        player.stop()
        playback = null
        if (stopTorrent && p.stream.infoHash != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) { TorrentEngine.stop() }
    }

    /** Stremio's own progress rules, so Continue Watching and "watched" match the official apps. */
    private fun recordProgress(p: Playback, final: Boolean) {
        val time = player.time
        val duration = player.length
        if (duration <= 0 || time <= 0) return
        val meta = p.detail.meta?.preview ?: p.detail.preview
        val existing = libraryItem(meta.id) ?: LibraryItem.create(meta, inLibrary = false)
        val updated = existing.copy { _, st ->
            st.put("lastWatched", formatDate(System.currentTimeMillis()))
            if (st.optString("video_id") != p.videoId || st.isNull("video_id")) {
                st.put("overallTimeWatched", st.optLong("overallTimeWatched") + st.optLong("timeWatched"))
                st.put("timeWatched", 0); st.put("flaggedWatched", 0); st.put("timeOffset", 0)
                st.put("video_id", p.videoId)
            }
            val delta = time - st.optLong("timeOffset")
            if (delta in 1..60_000) {
                st.put("timeWatched", st.optLong("timeWatched") + delta)
                st.put("overallTimeWatched", st.optLong("overallTimeWatched") + delta)
            }
            st.put("timeOffset", time)
            st.put("duration", duration)
            if (st.optLong("timeWatched") > duration * 0.7 && st.optInt("flaggedWatched") == 0) {
                st.put("flaggedWatched", 1)
                st.put("timesWatched", st.optInt("timesWatched") + 1)
            }
            if (final && time > duration * 0.9) {
                // Finished: a series moves on to the next episode; a movie leaves Continue Watching.
                val next = p.next
                if (next != null) {
                    st.put("overallTimeWatched", st.optLong("overallTimeWatched") + st.optLong("timeWatched"))
                    st.put("video_id", next.id); st.put("timeOffset", 1); st.put("timeWatched", 0); st.put("flaggedWatched", 0)
                } else st.put("timeOffset", 0)
            }
        }
        upsert(updated)
        push(force = final)
    }

    private fun upsert(item: LibraryItem) {
        library = library.filterNot { it.id == item.id } + item
        dirty += item.id
        saveLibrary()
    }

    /** Sends changed entries to the account at most every 90 seconds while watching (as Stremio does). */
    private fun push(force: Boolean) {
        val key = authKey ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPush < 90_000) return
        lastPush = now
        val items = library.filter { it.id in dirty }
        if (items.isEmpty()) return
        scope.launch {
            runCatching { api.putLibrary(key, items) }.onSuccess { dirty -= items.map { it.id }.toSet() }
        }
    }

    fun release() {
        stopPlayback()
        player.release()
    }

    companion object {
        /** "eng" / "en" / "English" → "English", for subtitle and audio menus. */
        fun languageName(code: String): String {
            val c = code.trim()
            if (c.length == 3 || c.length == 2) {
                val name = runCatching { java.util.Locale(c).getDisplayLanguage(java.util.Locale.ENGLISH) }.getOrNull()
                if (!name.isNullOrBlank() && !name.equals(c, true)) return name
            }
            return c.replaceFirstChar { it.uppercase() }
        }
    }
}
