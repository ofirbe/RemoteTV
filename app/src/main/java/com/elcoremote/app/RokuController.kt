package com.elcoremote.app

import java.net.HttpURLConnection
import java.net.URL

/** Roku External Control Protocol: plain HTTP POSTs to port 8060. */
class RokuController(private val ip: String) : TvController {

    override fun connect() {
        val conn = URL("http://$ip:8060/query/device-info").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        try {
            if (conn.responseCode !in 200..299) error("Roku device-info returned ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }

    override fun send(key: RemoteKey) {
        val conn = URL("http://$ip:8060/keypress/${key.rokuName}").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(0)
        try {
            conn.outputStream.close()
            if (conn.responseCode !in 200..299) error("Roku keypress returned ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }

    override fun close() {}
}
