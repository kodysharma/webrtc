## Todos

- Solve Exceed Mtu size problem on sending msg in TurnClient
  java.io.IOException: sendto failed: EMSGSIZE (Message too long)

- Handle Allocation refresh failing with error code  
  java.io.IOException: Refresh failed with error code: ErrorValue(code=437, reason=Invalid
  allocation??)

## Code Structure

```kotlin
import java.net.InetSocketAddress


interface IncomingMsgObserver
{
    fun addCallback(callback: MsgCallback)
    fun removeCallback(callback: MsgCallback)

    interface MsgCallback
    {
        fun onMsgReceived(msg: UdpMsg)
    }
}

interface UdpSocket(
    localIp: String,
    localPort: Int,
) : IncomingMsgObserver
{
    fun send(msg: UdpMsg)
    fun close()
    fun isClosed()
}

// Builder function
fun UdpSocket(localIp: String, localPort: Int): UdpSocket


interface TurnSocket
{
    fun addPeer(peer: InetSocketAddress)
    fun removePeer(peer: InetSocketAddress)
    fun allocate()
    fun close()
    fun isAllocationExist(): Boolean
    fun addListener(listener: Listener)
    fun getIce(): List<ICE>
    fun send(peer: InetSocketAddress, data: ByteArray)

    // Same as creating a Instance of TurnSocket
    fun reset()

    interface Listener
    {
        fun onReceive(data: ByteArray, peer: InetSocketAddress)
        fun onBadNetworkConnection()
    }
}

```

## P2PAgent

### p2pa is responsible for -

- knowing its ice candidate using stun/turn server etc.
- Sense peer connectivity using ping / pong. If a peer does not give pong response the connection
  may be considered
  as temporary closed connection and onPeerConnectionBreak callback will be called. You may remove
  that peer or P2PAgent will start waiting for the peer to comeback when that peer comeback you
  would be
  notify with onPeerConnectionRestore callback. If the peer does not comeback within a certain time
  span then p2pa will remove the connection with that peer and a notification is given to you with
  onPeerConnectionRemoved callback.
- Reconfigure network ice candidate on network reset.
- It sense the device network availability.

### Time values

- `ping-timeout`: The maximum time P2PA waits for a pong response from a peer. If the response is
  not received within this time, the connection is marked as temporarily closed.

- `peer-comeback-timeout`: The maximum time P2PA waits for a peer to reconnect after a connection
  break. If the peer does not reconnect within this period, the connection is permanently removed.

```kotlin

interface P2PAgent
{
    suspend fun createNetConfig()
    fun getNetConfig(): List<Ice>

    fun close()

    suspend fun createConnection(id: String, peerIce: List<Ice>)
    suspend fun closeConnection(id: String)

    fun send(data: ByteArray, id: String)

    fun setCallback(callback: Callback?)

    interface Callback
    {
        fun onNetworkConfigUpdate(ice: List<Ice>)
        fun onPeerConnectionBreak(id: String)
        fun onPeerConnectionRestore(id: String)
        fun onPeerConnectionRemoved(id: String)
        fun onReceive(data: ByteArray, id: String)
    }
}
```