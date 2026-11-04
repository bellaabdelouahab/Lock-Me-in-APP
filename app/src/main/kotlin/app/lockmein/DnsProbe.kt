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

    private const val TIMEOUT_MS = 6_000
}
