package com.malick.antenna.tv.cast

import org.json.JSONObject

/** Credentials a phone sends along so the TV can sign in with the same subscription. */
data class CastAccount(val name: String, val server: String, val username: String, val password: String?)

/** What the running app does with commands from a phone. Set by MainActivity while it exists. */
interface CastHandler {
    fun status(): JSONObject
    /** Returns null on success, or a short reason the phone can show. */
    suspend fun castPlay(streamId: Int, account: CastAccount?): String?
    fun command(action: String, args: JSONObject)
    /** A movie or episode from the phone's Stremio add-ons. Null on success, or a short reason. */
    suspend fun castStremio(body: JSONObject): String?
}

object CastBridge {
    @Volatile var handler: CastHandler? = null
}
