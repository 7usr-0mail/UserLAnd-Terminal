package tech.ula

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Local authenticated bridge for non-root Android data exposed to proot. */
class AndroidCtlBridge(private val context: Context) {
    private val tokens = ConcurrentHashMap<String, Long>()
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        Thread {
            try {
                ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")).use { socket ->
                    server = socket
                    while (!socket.isClosed) {
                        // Accept exactly one connection on the listener thread,
                        // then hand only that accepted socket to a worker.
                        val client = try { socket.accept() } catch (_: java.net.SocketException) { break }
                        Thread { handle(client) }.apply { isDaemon = true }.start()
                    }
                }
            } catch (_: Exception) { } finally { server = null }
        }.apply { isDaemon = true; start() }
    }

    fun stop() { try { server?.close() } catch (_: Exception) {} }

    fun provision(filesystemId: Long, supportDir: File) {
        val token = ByteArray(24).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
        tokens[token] = filesystemId
        supportDir.mkdirs()
        File(supportDir, ".androidctl_token").writeText(token)
        val wrapper = """#!/bin/sh
TOKEN_FILE=/support/.androidctl_token
[ -r "${'$'}TOKEN_FILE" ] || { echo "android: bridge is available after a session starts" >&2; exit 1; }
printf '%s %s\n' "${'$'}(cat "${'$'}TOKEN_FILE")" "${'$'}*" | /support/busybox nc -w 8 127.0.0.1 $PORT
"""
        for (name in listOf("android", "androidctl")) {
            File(supportDir, name).apply { writeText(wrapper); setExecutable(true, false) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
        val line = it.getInputStream().bufferedReader().readLine() ?: return@use
        val parts = line.split(" ")
        if (parts.size < 2 || !tokens.containsKey(parts[0])) {
            it.getOutputStream().writer().apply { write("ERROR unauthorized\n"); flush() }; return@use
        }
        val result = when (parts.drop(1).joinToString(" ")) {
            "network status", "network local-ip", "ip", "ip addr" -> networkStatus()
            "ip route" -> networkRoute()
            "ip public" -> publicIp()
            "storage status" -> storageStatus()
            else -> "ERROR unsupported command"
        }
        it.getOutputStream().writer().apply { write("$result\n"); flush() }
        }
    }

    private fun networkStatus(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network)
        val type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "mobile"
            else -> "other"
        }
        val addresses = cm.getLinkProperties(network)?.linkAddresses?.joinToString(" ") { it.address.hostAddress } ?: ""
        return "network=$type local_ip=$addresses"
    }

    private fun networkRoute(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "offline"
        return cm.getLinkProperties(network)?.routes?.joinToString(" ") { it.toString() } ?: "route unavailable"
    }

    private fun publicIp(): String {
        return try {
            val value = java.net.URL("https://api.ipify.org").openConnection().apply {
                connectTimeout = 8000; readTimeout = 8000
            }.getInputStream().bufferedReader().use { it.readText().trim() }
            "public_ip=$value"
        } catch (err: Exception) { "ERROR public IP lookup failed: ${err.message}" }
    }

    private fun storageStatus(): String {
        val stat = StatFs(android.os.Environment.getExternalStorageDirectory().path)
        return "shared_available=${stat.availableBytes} shared_total=${stat.totalBytes}"
    }

    companion object { private const val PORT = 47223 }
}
