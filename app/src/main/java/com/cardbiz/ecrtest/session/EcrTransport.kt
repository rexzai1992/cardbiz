package com.cardbiz.ecrtest.session

interface EcrTransport {
    val displayName: String
    val isConnected: Boolean

    fun connect()
    fun disconnect()
    fun write(bytes: ByteArray)

    /** Returns received bytes, or null when no data arrived before timeoutMs. */
    fun read(timeoutMs: Int): ByteArray?
}
