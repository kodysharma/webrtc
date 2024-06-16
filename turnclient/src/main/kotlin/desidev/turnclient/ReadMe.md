## Todos

- Solve Exceed Mtu size problem on sending msg in TurnClient
  java.io.IOException: sendto failed: EMSGSIZE (Message too long)

- Handle Allocation refresh failing with error code  
  java.io.IOException: Refresh failed with error code: ErrorValue(code=437, reason=Invalid
  allocation??)

## Code Structure

```kotlin
import java.net.InetSocketAddress


interface IncomingMsgObserver {
  fun addCallback(callback: MsgCallback)
  fun removeCallback(callback: MsgCallback)

  interface MsgCallback {
    fun onMsgReceived(msg: UdpMsg)
  }
}

interface UdpSocket(
  localIp: String,
  localPort: Int,
) : IncomingMsgObserver {
  fun send(msg: UdpMsg)
  fun close()
  fun isClosed()
}

// Builder function
fun UdpSocket(localIp: String, localPort: Int): UdpSocket


interface TurnSocket {
  fun addPeer(peer: InetSocketAddress): Either<TurnRequestFailure, Unit>
  fun removePeer(peer: InetSocketAddress): Either<TurnRequestFailure, Unit>
  fun allocate(): Either<TurnRequestFailure, Unit> 
  fun close(): Either<TurnRequestFailure, Unit>
  fun isAllocationExist(): Boolean
  fun addListener(listener: Listener)
  
  // Same as creating a Instance of TurnSocket
  fun reset()
  
  interface Listener { 
      fun onReceive(data: ByteArray, peer: InetSocketAddress)
      fun onBadNetworkConnection()
  }
}



```
