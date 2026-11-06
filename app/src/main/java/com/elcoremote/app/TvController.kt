package com.elcoremote.app

/** Remote keys, with their Android KeyEvent code (used over ADB) and Roku ECP name. */
enum class RemoteKey(val androidKeyCode: Int, val rokuName: String) {
    POWER(26, "Power"),
    VOLUME_UP(24, "VolumeUp"),
    VOLUME_DOWN(25, "VolumeDown"),
    MUTE(164, "VolumeMute"),
    UP(19, "Up"),
    DOWN(20, "Down"),
    LEFT(21, "Left"),
    RIGHT(22, "Right"),
    ENTER(23, "Select"),
    BACK(4, "Back"),
    HOME(3, "Home"),
}

enum class Protocol(val port: Int, val label: String) {
    ADB(5555, "Android TV (ADB over Wi-Fi)"),
    ROKU(8060, "Roku ECP"),
}

data class TvDevice(val ip: String, val protocol: Protocol, val name: String = ip) {
    override fun toString() = "$name — $ip (${protocol.label})"
}

/** All calls are blocking; MainActivity runs them on a background executor. */
interface TvController {
    fun connect()
    fun send(key: RemoteKey)
    fun close()
}
