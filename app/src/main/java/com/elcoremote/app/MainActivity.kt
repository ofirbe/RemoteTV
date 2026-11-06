package com.elcoremote.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private var controller: TvController? = null
    private var device: TvDevice? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }
        findViewById<Button>(R.id.btnManual).setOnClickListener { manualEntry() }
        findViewById<Button>(R.id.btnTools).setOnClickListener { TvTools.show(this) }

        mapOf(
            R.id.btnPower to RemoteKey.POWER,
            R.id.btnVolUp to RemoteKey.VOLUME_UP,
            R.id.btnVolDown to RemoteKey.VOLUME_DOWN,
            R.id.btnMute to RemoteKey.MUTE,
            R.id.btnUp to RemoteKey.UP,
            R.id.btnDown to RemoteKey.DOWN,
            R.id.btnLeft to RemoteKey.LEFT,
            R.id.btnRight to RemoteKey.RIGHT,
            R.id.btnEnter to RemoteKey.ENTER,
            R.id.btnBack to RemoteKey.BACK,
            R.id.btnHome to RemoteKey.HOME,
        ).forEach { (id, key) ->
            findViewById<View>(id).setOnClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                sendKey(key)
            }
        }

        // Reconnect to the last-used TV automatically.
        val prefs = getPreferences(MODE_PRIVATE)
        val ip = prefs.getString("ip", null)
        val proto = prefs.getString("protocol", null)?.let { runCatching { Protocol.valueOf(it) }.getOrNull() }
        if (ip != null && proto != null) select(TvDevice(ip, proto, prefs.getString("name", ip) ?: ip))
        else setStatus("Tap SCAN to find your TV")
    }

    private fun scan() {
        setStatus("Scanning Wi-Fi for TVs…")
        worker.execute {
            val found = runCatching { Discovery(this).scan() }.getOrDefault(emptyList())
            runOnUiThread {
                if (found.isEmpty()) {
                    setStatus("No TV found. Enable network/ADB debugging on the TV, or enter IP manually.")
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Select your TV")
                        .setItems(found.map { it.toString() }.toTypedArray()) { _, i -> select(found[i]) }
                        .show()
                    setStatus("Found ${found.size} device(s)")
                }
            }
        }
    }

    private fun manualEntry() {
        val input = EditText(this).apply {
            hint = "TV IP address, e.g. 192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(device?.ip ?: "")
        }
        val protos = Protocol.values()
        AlertDialog.Builder(this)
            .setTitle("Connect manually")
            .setView(input)
            .setPositiveButton("ADB (Android TV)") { _, _ -> select(TvDevice(input.text.toString().trim(), protos[0])) }
            .setNeutralButton("Roku") { _, _ -> select(TvDevice(input.text.toString().trim(), protos[1])) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun select(d: TvDevice) {
        device = d
        getPreferences(MODE_PRIVATE).edit()
            .putString("ip", d.ip).putString("protocol", d.protocol.name).putString("name", d.name)
            .apply()
        val old = controller
        val c = when (d.protocol) {
            Protocol.ADB -> AdbController(d.ip, filesDir)
            Protocol.ROKU -> RokuController(d.ip)
        }
        controller = c
        setStatus(
            if (d.protocol == Protocol.ADB) "Connecting to ${d.name}… accept the prompt on the TV if shown"
            else "Connecting to ${d.name}…"
        )
        worker.execute {
            old?.close()
            val result = runCatching { c.connect() }
            runOnUiThread {
                setStatus(result.fold({ "Connected: ${d.name} (${d.ip})" }, { "Connect failed: ${it.message}" }))
            }
        }
    }

    private fun sendKey(key: RemoteKey) {
        val c = controller ?: return setStatus("No TV selected — tap SCAN")
        worker.execute {
            val result = runCatching { c.send(key) }
            result.exceptionOrNull()?.let { e -> runOnUiThread { setStatus("Send failed: ${e.message}") } }
        }
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        val c = controller
        worker.execute { c?.close() }
        worker.shutdown()
    }
}
