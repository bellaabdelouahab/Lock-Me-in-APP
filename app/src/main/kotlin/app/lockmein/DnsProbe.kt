package app.lockmein

import java.net.InetSocketAddress
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Asks the DNS host itself, the way Android will: a TLS connection to the secure DNS port, with a certificate for that name.
 * Returns null when the server answers properly, or a plain sentence about what went wrong. Blocks; call it off the main thread.
 */
object DnsProbe {
    fun problem(host: String): String? = runCatching {
        (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(host, 853), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) "$host answers, but its certificate is for another name." else null
        }
    }.getOrElse { "$host does not answer as a secure DNS server. Check the spelling, and that you are online." }

    /**
     * One probe for the DNS fallback (FEATURES 3.17), on the active network, around the private DNS (which is what may be down):
     * - networkOk: the network's own DNS servers (or 1.1.1.1) answer a plain query, so the network itself works;
     * - serverOk: [host] answers a real query (example.com) over TLS on 853 with a certificate for its name.
     * Blocks for up to about 15 s; call it off the main thread.
     */
    fun check(context: android.content.Context, host: String): Pair<Boolean, Boolean> {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false to false
        val servers = cm.getLinkProperties(network)?.dnsServers.orEmpty() + java.net.InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
        var address: java.net.InetAddress? = null
        var networkOk = false
        for (server in servers) {
            val reply = udp(network, server, host) ?: continue
            networkOk = true
            address = app.lockmein.core.DnsWire.firstA(reply)?.let(java.net.InetAddress::getByAddress)
            if (address != null) break
        }
        val ip = address ?: return false to networkOk
        val serverOk = runCatching {
            val raw = network.socketFactory.createSocket()
            raw.connect(InetSocketAddress(ip, 853), TIMEOUT_MS)
            raw.soTimeout = TIMEOUT_MS
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 853, true).use { socket ->
                socket as SSLSocket
                socket.startHandshake()
                check(HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.session)) { "certificate for another name" }
                val id = kotlin.random.Random.nextInt(0, 0xFFFF)
                socket.outputStream.apply { write(app.lockmein.core.DnsWire.framed(app.lockmein.core.DnsWire.query(id, PROBE_NAME))); flush() }
                val input = java.io.DataInputStream(socket.inputStream)
                val reply = ByteArray(input.readUnsignedShort()).also(input::readFully)
                app.lockmein.core.DnsWire.isGoodAnswer(reply, id)
            }
        }.getOrDefault(false)
        return serverOk to networkOk
    }

    private fun udp(network: android.net.Network, server: java.net.InetAddress, name: String): ByteArray? = runCatching {
        java.net.DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = UDP_TIMEOUT_MS
            val id = kotlin.random.Random.nextInt(0, 0xFFFF)
            val query = app.lockmein.core.DnsWire.query(id, name)
            socket.send(java.net.DatagramPacket(query, query.size, server, 53))
            val buffer = ByteArray(1500)
            val packet = java.net.DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            buffer.copyOf(packet.length).takeIf { (it[0].toInt() and 0xFF shl 8 or (it[1].toInt() and 0xFF)) == id }
        }
    }.getOrNull()

    private const val TIMEOUT_MS = 6_000
    private const val UDP_TIMEOUT_MS = 3_000
    private const val PROBE_NAME = "example.com"
}
