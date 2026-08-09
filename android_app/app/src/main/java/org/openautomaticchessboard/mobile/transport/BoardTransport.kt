package org.openautomaticchessboard.mobile.transport

interface BoardTransport {
    val label: String
    val isConnected: Boolean
    fun start()
    fun send(line: String)
    fun close()

    interface Listener {
        fun onLine(line: String)
        fun onStatus(status: String, connected: Boolean)
    }
}

data class BleDevice(val name: String, val address: String, val rssi: Int)
