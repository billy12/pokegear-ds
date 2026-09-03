package com.enrpau.pokegeards.bridge

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.enrpau.pokegeards.R
import com.enrpau.pokegeards.detection.GdbRspClient
import java.util.concurrent.Executors

/**
 * Developer tool for the Eden bridge (design.md §2.2 Tier 3). Connects to the
 * GDB stub, shows the loaded modules, and lets you peek guest memory — with a
 * 1s "watch" so you can walk around in-game and see which address tracks the
 * current route / encounter / battle state. This is how the RAM maps get found.
 */
class BridgeDebugActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var client: GdbRspClient? = null
    private var mainBase = 0L

    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var addr: EditText
    private lateinit var len: EditText
    private lateinit var watch: CheckBox
    private lateinit var status: TextView
    private lateinit var log: TextView

    private val watchRunnable = object : Runnable {
        override fun run() {
            if (watch.isChecked && client?.isConnected == true) {
                doRead(quiet = true)
                main.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bridge_debug)
        title = "Eden bridge"

        host = findViewById(R.id.brHost)
        port = findViewById(R.id.brPort)
        addr = findViewById(R.id.brAddr)
        len = findViewById(R.id.brLen)
        watch = findViewById(R.id.brWatch)
        status = findViewById(R.id.brStatus)
        log = findViewById(R.id.brLog)

        findViewById<Button>(R.id.brConnect).setOnClickListener { connect() }
        findViewById<Button>(R.id.brDisconnect).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.brRead).setOnClickListener { doRead(quiet = false) }
        watch.setOnCheckedChangeListener { _, on -> if (on) main.post(watchRunnable) }
    }

    override fun onDestroy() {
        io.execute { client?.close() }
        super.onDestroy()
    }

    private fun setStatus(s: String) = main.post { status.text = s }
    private fun append(s: String) = main.post {
        log.text = (s + "\n" + log.text).take(6000)
    }

    private fun connect() {
        val h = host.text.toString().ifBlank { "127.0.0.1" }
        val p = port.text.toString().toIntOrNull() ?: 6543
        setStatus("connecting to $h:$p …")
        io.execute {
            try {
                client?.close()
                val c = GdbRspClient(h, p)
                val modules = c.connect()
                client = c
                mainBase = (modules.firstOrNull { it.name.endsWith(".nss") } ?: modules.firstOrNull())?.base ?: 0L
                setStatus("connected · game continued · main = 0x${mainBase.toString(16)}")
                append("modules:\n" + modules.joinToString("\n") { "  ${it.name.padEnd(18)} 0x${it.base.toString(16)}" })
            } catch (e: Exception) {
                setStatus("connect failed: ${e.message}")
            }
        }
    }

    private fun disconnect() {
        watch.isChecked = false
        io.execute { client?.close(); client = null; setStatus("disconnected") }
    }

    private fun doRead(quiet: Boolean) {
        val c = client ?: run { if (!quiet) append("not connected"); return }
        val raw = addr.text.toString().trim()
        val n = len.text.toString().toIntOrNull() ?: 8
        val resolved = resolve(raw) ?: run { if (!quiet) append("bad address: $raw"); return }
        io.execute {
            try {
                val bytes = c.withHalted { it.readMemory(resolved, n) }
                append(format(raw, resolved, bytes))
            } catch (e: Exception) {
                if (!quiet) append("read error @0x${resolved.toString(16)}: ${e.message}")
            }
        }
    }

    /** "main+0x1234" / "nss+1234" / "0xABSOLUTE" / decimal. */
    private fun resolve(s: String): Long? {
        val t = s.replace(" ", "").lowercase()
        return try {
            when {
                t.startsWith("main+") || t.startsWith("nss+") -> {
                    val off = t.substringAfter("+")
                    mainBase + parseNum(off)
                }
                else -> parseNum(t)
            }
        } catch (e: Exception) { null }
    }

    private fun parseNum(s: String): Long =
        if (s.startsWith("0x")) s.substring(2).toLong(16)
        else if (s.any { it in 'a'..'f' }) s.toLong(16)
        else s.toLong()

    private fun format(label: String, address: Long, b: ByteArray): String {
        val hex = b.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
        fun le(n: Int) = (0 until minOf(n, b.size)).fold(0L) { a, k -> a or ((b[k].toLong() and 0xff) shl (8 * k)) }
        val u8 = if (b.isNotEmpty()) le(1) else 0
        val u16 = if (b.size >= 2) le(2) else 0
        val u32 = if (b.size >= 4) le(4) else 0
        val u64 = if (b.size >= 8) le(8) else 0
        val looksPtr = u64 in 0x0800_0000_0000L..0x0000_8000_0000_0000L
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        return "[$ts] $label = 0x${address.toString(16)}\n  $hex\n" +
            "  u8=$u8  u16=$u16  u32=$u32  u64=0x${u64.toString(16)}${if (looksPtr) " (ptr?)" else ""}"
    }
}
