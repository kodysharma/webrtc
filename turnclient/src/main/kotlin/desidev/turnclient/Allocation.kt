package desidev.turnclient

import desidev.turnclient.attribute.AddressValue
import desidev.turnclient.attribute.TransportProtocol

data class Allocation(
    val relayedAddress: AddressValue,  // allocated ip:port by the turn server
    val mappedAddress: AddressValue,   // public ip:port address
    val protocol: TransportProtocol    // transport protocol between the relayed transport address and peers.
)