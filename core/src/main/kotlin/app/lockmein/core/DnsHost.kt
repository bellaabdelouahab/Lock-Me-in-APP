package app.lockmein.core

/** Checks and normalises the private DNS host a person types. */
object DnsHost {
    private val label = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val nextDnsId = Regex("^[a-f0-9]{6}$")

    /**
     * Accepts a full host (`poco-6b1bbb.dns.nextdns.io`, `dns.google`) or a bare NextDNS profile id (`6b1bbb`), which becomes
     * `6b1bbb.dns.nextdns.io`. Returns the host to store, or null when the text is not usable.
     */
    fun normalise(input: String): String? {
        val text = input.trim().lowercase().removePrefix("https://").removePrefix("tls://").trimEnd('/')
        if (nextDnsId.matches(text)) return "$text.dns.nextdns.io"
        return text.takeIf { problem(it) == null }
    }

    /** A plain sentence about what is wrong, or null when [host] is a valid host name. */
    fun problem(host: String): String? {
        if (host.isBlank()) return "Enter the private DNS host."
        if (host.length > 253) return "That host name is too long."
        if (host.any { it.isWhitespace() }) return "A host name has no spaces."
        if ('/' in host || ':' in host) return "Enter only the host name, without https:// or a port."
        val parts = host.split('.')
        if (parts.size < 2) return "A host name needs a dot, like dns.google."
        if (parts.any { !label.matches(it) }) return "That is not a valid host name."
        if (host.all { it.isDigit() || it == '.' }) return "Use a host name, not an IP address: Android checks the server's certificate for it."
        return null
    }
}
