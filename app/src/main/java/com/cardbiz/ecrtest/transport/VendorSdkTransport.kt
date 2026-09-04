package com.cardbiz.ecrtest.transport

import com.cardbiz.ecrtest.session.EcrTransport

/**
 * Integration seam for a CardBiz/Castles Android AAR.
 *
 * Add the vendor AAR under app/libs, declare it in app/build.gradle.kts, and translate the
 * vendor's connect/send/receive callbacks into this blocking test transport contract. Keep vendor
 * credentials outside source control and do not replace the protocol tests with opaque SDK calls.
 */
class VendorSdkTransport : EcrTransport {
    override val displayName = "CardBiz/Castles SDK (not installed)"
    override val isConnected = false
    override fun connect(): Unit = error("Add the CardBiz/Castles Android AAR and implement VendorSdkTransport")
    override fun disconnect() = Unit
    override fun write(bytes: ByteArray): Unit = error("Vendor SDK adapter is not implemented")
    override fun read(timeoutMs: Int): ByteArray? = error("Vendor SDK adapter is not implemented")
}
