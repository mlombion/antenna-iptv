package com.malick.antenna.tv.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny web page served on the home network while the TV is signed out. Open the address on a phone or
 * computer, enter the code shown on the TV plus the provider details, and the TV signs in.
 * The code stops anyone else on the network from sending a login.
 */
class PairingServer(private val onLogin: (name: String, server: String, user: String, pass: String) -> Unit) {
    var address by mutableStateOf<String?>(null); private set
    var code by mutableStateOf(newCode()); private set
    private var socket: ServerSocket? = null

    fun start() {
        if (socket != null) return
        code = newCode()
        val ip = localAddress() ?: return
        val server = runCatching { ServerSocket(8780) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() } ?: return
        socket = server
        address = "http://$ip:${server.localPort}"
        thread(name = "pairing", isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) }; runCatching { client.close() } }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        address = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        var length = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val (status, body) = if (requestLine.startsWith("POST")) {
            val chars = CharArray(length.coerceAtMost(8192))
            var read = 0
            while (read < chars.size) { val n = reader.read(chars, read, chars.size - read); if (n < 0) break; read += n }
            val form = String(chars, 0, read).split('&').associate {
                val k = it.substringBefore('='); val v = it.substringAfter('=', "")
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }
            if (form["code"]?.trim() != code) {
                "403 Forbidden" to page("That code doesn't match the one on your TV. Check the TV screen and try again.", error = true)
            } else {
                onLogin(form["name"].orEmpty(), form["server"].orEmpty(), form["username"].orEmpty(), form["password"].orEmpty())
                "200 OK" to page("Sent. Look at your TV — it's signing in now.", done = true)
            }
        } else {
            // The QR code on the TV carries the code, so scanning it fills that field in.
            val prefill = Regex("[?&]c=(\\d{4})").find(requestLine)?.groupValues?.get(1)
            "200 OK" to page(null, prefill = prefill)
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    /** Address with the code built in, for the QR code on the TV. */
    val qrTarget: String? get() = address?.let { "$it/?c=$code" }

    private fun page(message: String?, error: Boolean = false, done: Boolean = false, prefill: String? = null): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Antenna IPTV · Sign in your TV</title>
        <style>
          :root{color-scheme:dark}*{box-sizing:border-box}
          body{margin:0;background:#0B0E13;color:#E9EDF3;font:16px -apple-system,system-ui,sans-serif;padding:32px 20px}
          main{max-width:420px;margin:0 auto}
          h1{font-size:26px;margin:8px 0 6px}h1 b{color:#F2B544}p{color:#8C96A8;margin:0 0 20px;line-height:1.45}
          label{display:block;font-size:13px;color:#8C96A8;margin:14px 0 6px}
          input{width:100%;height:46px;border-radius:10px;border:1px solid rgba(150,170,205,.22);background:#181D26;color:#E9EDF3;padding:0 14px;font-size:16px}
          input:focus{outline:none;border-color:#F2B544}
          #code{font:600 22px ui-monospace,monospace;letter-spacing:.3em;text-align:center}
          button{width:100%;height:50px;margin-top:22px;border:0;border-radius:12px;background:#F2B544;color:#1B1406;font-weight:600;font-size:16px}
          .msg{padding:12px 14px;border-radius:10px;margin-bottom:18px;background:${if (error) "rgba(255,81,81,.15)" else "rgba(70,194,138,.15)"}}
          small{display:block;color:#5F6879;margin-top:16px;font-size:12px}
        </style></head><body><main>
        <h1>Antenna <b>IPTV</b></h1>
        ${if (message != null) "<div class=\"msg\">$message</div>" else ""}
        ${if (done) "" else """
        <p>Sign in your TV with the Xtream Codes details your provider sent you.</p>
        <form method="post">
          <label for="code">Code shown on your TV</label><input id="code" name="code" inputmode="numeric" maxlength="4" required ${if (prefill != null) "value=\"$prefill\"" else "autofocus"}>
          <label for="name">Name</label><input id="name" name="name" value="My IPTV">
          <label for="server">Server URL</label><input id="server" name="server" placeholder="http://provider.example:8080" autocapitalize="off" autocorrect="off" required>
          <label for="username">Username</label><input id="username" name="username" autocapitalize="off" autocorrect="off" required>
          <label for="password">Password</label><input id="password" name="password" type="password" required>
          <button type="submit">Send to TV</button>
        </form>
        <small>This page only works on your home network while the TV shows the sign-in screen. Your details go straight to the TV.</small>
        """}
        </main></body></html>
    """.trimIndent()

    private fun newCode() = (1000..9999).random().toString()

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
