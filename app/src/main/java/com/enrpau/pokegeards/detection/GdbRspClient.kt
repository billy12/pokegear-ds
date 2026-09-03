package com.enrpau.pokegeards.detection

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal GDB Remote Serial Protocol client for Eden's debug stub
 * (design.md §2.2 Tier 3). No GDB binary — RSP is a tiny ASCII protocol:
 * `$<payload>#<checksum>`, plus a bare `0x03` byte to interrupt a running target.
 *
 * Eden halts the game at the loader until a client connects and continues, so
 * [connect] sends `c` to boot it, then the game runs until we [halt] it for a
 * batch of reads and [cont] it again. Everything here blocks; call off the main
 * thread.
 */
class GdbRspClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 6543,
) {
    data class Module(val name: String, val base: Long)

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var inp: BufferedInputStream? = null
    private var noAck = false

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** Connect, handshake, and send `c` so the game boots. Returns the loaded modules. */
    fun connect(timeoutMs: Int = 4000): List<Module> {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
        out = s.getOutputStream()
        inp = BufferedInputStream(s.getInputStream())
        noAck = false

        command("qSupported:multiprocess+;vContSupported+;hwbreak+")
        if (command("QStartNoAckMode") == "OK") noAck = true

        val modules = readModules()

        // target is stopped at the loader entry; let it run
        sendPacket("c")
        Log.d(TAG, "connected, ${modules.size} modules, game continued")
        return modules
    }

    fun close() {
        try { if (isConnected) sendPacket("D") } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null; out = null; inp = null
    }

    // ---- halted-window reads -------------------------------------------------

    /** Interrupt the running target, run [block], then continue it. */
    fun <T> withHalted(block: (GdbRspClient) -> T): T {
        halt()
        try {
            return block(this)
        } finally {
            cont()
        }
    }

    /** Read [len] bytes of guest memory at [addr]. Must be inside [withHalted]. */
    fun readMemory(addr: Long, len: Int): ByteArray {
        val reply = command("m${addr.toString(16)},${len.toString(16)}")
        if (reply.startsWith("E") || reply.isEmpty()) {
            throw RspException("read @0x${addr.toString(16)} len $len failed: '$reply'")
        }
        val n = reply.length / 2
        return ByteArray(n) { i -> reply.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun readU32(addr: Long): Long = le(readMemory(addr, 4), 4)
    fun readU64(addr: Long): Long = le(readMemory(addr, 8), 8)

    /** Walk a pointer chain: base, then add each offset dereferencing 8 bytes between hops. */
    fun followChain(base: Long, offsets: LongArray): Long {
        var p = base
        for ((i, off) in offsets.withIndex()) {
            p += off
            if (i < offsets.size - 1) p = readU64(p)
        }
        return p
    }

    // ---- internals ---------------------------------------------------------

    private fun halt() {
        out!!.write(0x03)      // bare interrupt byte, no framing
        out!!.flush()
        readPacket()           // consume the stop-reply (T05…)
    }

    private fun cont() {
        sendPacket("c")        // fire and forget; target is running again
    }

    private fun readModules(): List<Module> {
        val xml = qXfer("qXfer:libraries:read::")
        // <library name="SwitchPlayer.nss"><segment address="0x1a27e07000"/></library>
        val re = Regex("""<library name="([^"]+)">\s*<segment address="0x([0-9a-fA-F]+)"""")
        return re.findAll(xml).map { Module(it.groupValues[1], it.groupValues[2].toLong(16)) }.toList()
    }

    private fun qXfer(prefix: String): String {
        val sb = StringBuilder()
        var offset = 0
        while (true) {
            val chunk = command("$prefix$offset,fff")
            if (chunk.isEmpty()) break
            val more = chunk[0] == 'm'
            sb.append(chunk.substring(1))
            if (!more) break
            offset += chunk.length - 1
        }
        return sb.toString()
    }

    /** Send a packet and return the payload of the reply. */
    private fun command(payload: String): String {
        sendPacket(payload)
        return readPacket()
    }

    private fun sendPacket(payload: String) {
        val body = "$" + payload + "#" + checksum(payload)
        out!!.write(body.toByteArray(Charsets.ISO_8859_1))
        out!!.flush()
        if (!noAck) {
            val ack = inp!!.read()
            if (ack != '+'.code) Log.w(TAG, "expected +, got $ack after '$payload'")
        }
    }

    private fun readPacket(): String {
        val i = inp!!
        var c: Int
        // skip to '$', tolerate a leading '+' (or an out-of-band '\x03' echo)
        do { c = i.read(); if (c == -1) throw RspException("stream closed") } while (c != '$'.code)
        val sb = StringBuilder()
        while (true) {
            c = i.read()
            if (c == -1) throw RspException("stream closed mid-packet")
            if (c == '#'.code) break
            sb.append(c.toChar())
        }
        i.read(); i.read() // 2 checksum hex chars, not verified
        if (!noAck) { out!!.write('+'.code); out!!.flush() }
        return unescape(sb.toString())
    }

    private fun checksum(s: String): String {
        var sum = 0
        for (ch in s) sum = (sum + ch.code) and 0xff
        return sum.toString(16).padStart(2, '0')
    }

    /** RSP run-length + 0x7d escaping in packet payloads. */
    private fun unescape(s: String): String {
        if ('}' !in s && '*' !in s) return s
        val sb = StringBuilder()
        var idx = 0
        while (idx < s.length) {
            val ch = s[idx]
            when {
                ch == '}' -> { sb.append((s[idx + 1].code xor 0x20).toChar()); idx += 2 }
                ch == '*' && sb.isNotEmpty() -> {
                    val repeat = s[idx + 1].code - 29
                    val last = sb.last()
                    repeat(repeat) { sb.append(last) }
                    idx += 2
                }
                else -> { sb.append(ch); idx++ }
            }
        }
        return sb.toString()
    }

    private fun le(b: ByteArray, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = v or ((b[k].toLong() and 0xff) shl (8 * k))
        return v
    }

    class RspException(msg: String) : Exception(msg)

    companion object { private const val TAG = "GdbRsp" }
}
