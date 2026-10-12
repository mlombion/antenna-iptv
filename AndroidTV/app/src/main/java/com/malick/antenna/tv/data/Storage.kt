package com.malick.antenna.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File

data class SavedAccount(val name: String, val server: String, val username: String, val password: String)

/** Login in encrypted storage (backed by the Android Keystore); favorites and settings in plain preferences. */
class Storage(context: Context) {
    private val secure: SharedPreferences = EncryptedSharedPreferences.create(
        "antenna_secure",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val prefs: SharedPreferences = context.getSharedPreferences("antenna", Context.MODE_PRIVATE)
    private val lineupFile = File(context.filesDir, "lineup.json")
    private val stremioDir = File(context.filesDir, "stremio").apply { mkdirs() }

    var account: SavedAccount?
        get() {
            val server = secure.getString("server", null) ?: return null
            return SavedAccount(
                secure.getString("name", "My IPTV") ?: "My IPTV", server,
                secure.getString("username", "") ?: "", secure.getString("password", "") ?: "",
            )
        }
        set(value) {
            secure.edit().apply {
                if (value == null) { remove("name"); remove("server"); remove("username"); remove("password") } else {
                    putString("name", value.name); putString("server", value.server)
                    putString("username", value.username); putString("password", value.password)
                }
            }.apply()
        }

    var favorites: Set<Int>
        get() = prefs.getStringSet("favorites", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("favorites", v.map { it.toString() }.toSet()).apply()

    var recents: List<Int>
        get() = prefs.getString("recents", "")!!.split(',').mapNotNull { it.toIntOrNull() }
        set(v) = prefs.edit().putString("recents", v.joinToString(",")).apply()

    var format: String
        get() = prefs.getString("format", "ts")!!
        set(v) = prefs.edit().putString("format", v).apply()

    var hideAdult: Boolean
        get() = prefs.getBoolean("hideAdult", true)
        set(v) = prefs.edit().putBoolean("hideAdult", v).apply()

    var bufferMs: Int
        get() = prefs.getInt("bufferMs", 3000)
        set(v) = prefs.edit().putInt("bufferMs", v).apply()

    /** Minutes of the current channel kept on the box for rewinding. 0 = off. */
    var rewindMinutes: Int
        get() = prefs.getInt("rewindMinutes", 15)
        set(v) = prefs.edit().putInt("rewindMinutes", v).apply()

    // ---- Movies & Series (Stremio)

    /** The Stremio account key from signing in with a code. Never a password. */
    var stremioAuthKey: String?
        get() = secure.getString("stremioAuthKey", null)
        set(v) = secure.edit().apply { if (v == null) remove("stremioAuthKey") else putString("stremioAuthKey", v) }.apply()

    var stremioEmail: String?
        get() = prefs.getString("stremioEmail", null)
        set(v) = prefs.edit().putString("stremioEmail", v).apply()

    /** Language of the last add-on subtitle you picked, offered first next time. */
    var subtitleLang: String?
        get() = prefs.getString("subtitleLang", null)
        set(v) = prefs.edit().putString("subtitleLang", v).apply()

    /** Last source played per title, so Resume and the next episode can reuse it. */
    var lastStreams: String
        get() = prefs.getString("lastStreams", "{}")!!
        set(v) = prefs.edit().putString("lastStreams", v).apply()

    fun readStremio(name: String): String? = runCatching { File(stremioDir, name).readText() }.getOrNull()
    fun writeStremio(name: String, text: String) = runCatching { File(stremioDir, name).writeText(text) }
    fun clearStremio() { stremioDir.listFiles()?.forEach { it.delete() } }

    fun loadLineup(): Lineup? = runCatching { lineupFromJson(lineupFile.readText()) }.getOrNull()
    fun saveLineup(l: Lineup) = runCatching { lineupFile.writeText(l.toJson()) }
    fun clearLineup() = lineupFile.delete()
}
