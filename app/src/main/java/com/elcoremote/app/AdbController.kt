package com.elcoremote.app

import android.util.Base64
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Minimal ADB-over-TCP client (legacy "network debugging" on port 5555).
 * Sends `shell:input keyevent N` to an Android-based TV. On first connect the TV
 * shows an "Allow debugging?" prompt that must be accepted with the physical remote.
 */
class AdbController(private val ip: String, private val keyDir: File) : TvController {

    private companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val VERSION = 0x01000000
        const val MAX_DATA = 4096
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        // PKCS#1 v1.5 type-1 padding + SHA-1 DigestInfo prefix for a 2048-bit key (ADB signs the raw 20-byte token).
        val SIGNATURE_PADDING: ByteArray = ByteArray(256 - 20).also { p ->
            p[0] = 0x00; p[1] = 0x01
            for (i in 2 until 220) p[i] = 0xff.toByte()
            p[220] = 0x00
            val digestInfo = intArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
            digestInfo.forEachIndexed { i, b -> p[221 + i] = b.toByte() }
        }
    }

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null
    private var nextLocalId = 1

    override fun connect() {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(ip, 5555), 3000)
        s.tcpNoDelay = true
        s.soTimeout = 5000
        socket = s
        input = DataInputStream(s.getInputStream())
        output = s.getOutputStream()

        val keys = loadOrCreateKeys()
        write(CNXN, VERSION, MAX_DATA, "host::elco-remote\u0000".toByteArray())

        var sentSignature = false
        while (true) {
            val msg = read()
            when (msg.command) {
                CNXN -> return
                AUTH -> {
                    if (msg.arg0 != AUTH_TOKEN) error("Unexpected AUTH type ${msg.arg0}")
                    if (!sentSignature) {
                        write(AUTH, AUTH_SIGNATURE, 0, sign(keys, msg.data))
                        sentSignature = true
                    } else {
                        // Key not yet trusted: send public key; TV shows the approval prompt.
                        write(AUTH, AUTH_RSAPUBLICKEY, 0, adbPublicKey(keys.public as RSAPublicKey))
                        s.soTimeout = 60_000 // give the user time to press "Allow" on the TV
                        val reply = read()
                        s.soTimeout = 5000
                        if (reply.command == CNXN) return
                        error("TV rejected the connection")
                    }
                }
                else -> error("Unexpected ADB command 0x${msg.command.toString(16)}")
            }
        }
    }

    override fun send(key: RemoteKey) {
        if (socket?.isConnected != true || socket?.isClosed == true) connect()
        try {
            shell("input keyevent ${key.androidKeyCode}")
        } catch (e: Exception) {
            // Connection may have gone stale (TV slept, Wi-Fi blip): reconnect once and retry.
            connect()
            shell("input keyevent ${key.androidKeyCode}")
        }
    }

    private fun shell(cmd: String) {
        val localId = nextLocalId++
        write(OPEN, localId, 0, "shell:$cmd\u0000".toByteArray())
        var remoteId = 0
        while (true) {
            val msg = read()
            when (msg.command) {
                OKAY -> remoteId = msg.arg0
                WRTE -> write(OKAY, localId, msg.arg0, ByteArray(0))
                CLSE -> {
                    write(CLSE, localId, if (remoteId != 0) remoteId else msg.arg0, ByteArray(0))
                    return
                }
                else -> {}
            }
        }
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; input = null; output = null
    }

    // ---- wire format ----

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
        header.putInt(data.fold(0) { acc, b -> acc + (b.toInt() and 0xff) })
        header.putInt(command.inv())
        val out = output ?: error("Not connected")
        out.write(header.array())
        out.write(data)
        out.flush()
    }

    private fun read(): Message {
        val inp = input ?: error("Not connected")
        val raw = ByteArray(24)
        inp.readFully(raw)
        val h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val command = h.int; val arg0 = h.int; val arg1 = h.int; val len = h.int
        val data = ByteArray(len)
        if (len > 0) inp.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    // ---- crypto ----

    private fun loadOrCreateKeys(): KeyPair {
        val priv = File(keyDir, "adbkey")
        val pub = File(keyDir, "adbkey.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (priv.exists() && pub.exists()) {
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())),
            )
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp
    }

    private fun sign(keys: KeyPair, token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.private)
        cipher.update(SIGNATURE_PADDING)
        return cipher.doFinal(token)
    }

    /** Encodes the key in Android's mincrypt RSAPublicKey struct, base64'd, as adbd expects. */
    private fun adbPublicKey(key: RSAPublicKey): ByteArray {
        val words = 2048 / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n = key.modulus
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val n0inv = n.remainder(r32).modInverse(r32).negate().mod(r32)

        val buf = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        var t = n
        repeat(words) { buf.putInt(t.remainder(r32).toInt()); t = t.shiftRight(32) }
        t = rr
        repeat(words) { buf.putInt(t.remainder(r32).toInt()); t = t.shiftRight(32) }
        buf.putInt(key.publicExponent.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 elco-remote@phone\u0000".toByteArray()
    }
}
