package tech.almira.support

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Just enough SMTP to receive what the live email adapter sends, on loopback.
 *
 * A fake rather than a mocked `JavaMailSender`, so the adapter's real session,
 * headers, encoding and error mapping are what is tested — and rather than a
 * library, because the conversation the adapter holds is eight commands long.
 * No TLS, no AUTH: the tests that use it set `start-tls: false`.
 *
 * [refuse] makes RCPT answer 550 for an address; [refuseSender] makes MAIL FROM
 * answer 553, as a relay that has not verified our from-address does.
 */
class FakeSmtpServer : AutoCloseable {

    data class Received(val from: String, val to: List<String>, val data: String) {
        val headers: String get() = data.substringBefore("\r\n\r\n")
        val body: String get() = data.substringAfter("\r\n\r\n")
    }

    private val socket = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    private val pool = Executors.newVirtualThreadPerTaskExecutor()
    val received = CopyOnWriteArrayList<Received>()
    val refused = CopyOnWriteArrayList<String>()
    @Volatile var refuseSender = false

    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "fake-smtp") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { converse(client) }
            }
        }
    }

    fun refuse(address: String) {
        refused += address.lowercase()
    }

    fun clear() {
        received.clear()
        refused.clear()
        refuseSender = false
    }

    private fun converse(client: Socket) = client.use {
        val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
        val out = PrintWriter(it.getOutputStream(), false, Charsets.UTF_8)
        fun say(line: String) {
            out.print("$line\r\n")
            out.flush()
        }
        say("220 fake.smtp ready")
        var from = ""
        val to = mutableListOf<String>()
        while (true) {
            val line = reader.readLine() ?: return
            val upper = line.uppercase()
            when {
                upper.startsWith("EHLO") || upper.startsWith("HELO") -> say("250 fake.smtp")
                upper.startsWith("MAIL FROM:") -> {
                    if (refuseSender) {
                        say("553 5.7.1 Sender address not verified")
                    } else {
                        from = address(line); to.clear(); say("250 OK")
                    }
                }
                upper.startsWith("RCPT TO:") -> {
                    val address = address(line)
                    if (address.lowercase() in refused) say("550 5.1.1 No such user") else { to += address; say("250 OK") }
                }
                upper == "DATA" -> {
                    say("354 go ahead")
                    val data = StringBuilder()
                    while (true) {
                        val d = reader.readLine() ?: return
                        if (d == ".") break
                        data.append(if (d.startsWith("..")) d.substring(1) else d).append("\r\n")
                    }
                    received += Received(from, to.toList(), data.toString())
                    say("250 OK queued")
                }
                upper == "RSET" -> { to.clear(); say("250 OK") }
                upper == "NOOP" -> say("250 OK")
                upper == "QUIT" -> { say("221 bye"); return }
                else -> say("502 not implemented")
            }
        }
    }

    private fun address(line: String) = line.substringAfter('<').substringBefore('>')

    override fun close() {
        socket.close()
        pool.shutdownNow()
    }
}
