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
                    while (!socket.isClosed) Thread { handle(socket.accept()) }.start()
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
    }

    private fun handle(socket: Socket) = socket.use {
        val line = it.getInputStream().bufferedReader().readLine() ?: return
        val parts = line.split(" ")
        if (parts.size < 2 || !tokens.containsKey(parts[0])) {
            it.getOutputStream().writer().apply { write("ERROR unauthorized\n"); flush() }; return
        }
        val result = when (parts.drop(1).joinToString(" ")) {
            "network status", "network local-ip" -> networkStatus()
            "storage status" -> storageStatus()
            else -> "ERROR unsupported command"
        }
        it.getOutputStream().writer().apply { write("$result\n"); flush() }
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

    private fun storageStatus(): String {
        val stat = StatFs(android.os.Environment.getExternalStorageDirectory().path)
        return "shared_available=${stat.availableBytes} shared_total=${stat.totalBytes}"
    }

    companion object { private const val PORT = 47223 }
}
