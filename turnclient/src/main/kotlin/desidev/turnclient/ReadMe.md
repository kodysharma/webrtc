## Todos

- Solve Exceed Mtu size problem on sending msg in TurnClient
  java.io.IOException: sendto failed: EMSGSIZE (Message too long)

- Handle Allocation refresh failing with error code  
  java.io.IOException: Refresh failed with error code: ErrorValue(code=437, reason=Invalid
  allocation??)

## Code Structure

```kotlin


interface IncomingMsgObserver {
    fun addCallback(callback: MsgCallback)
    fun removeCallback(callback: MsgCallback)
    
    interface MsgCallback {
      fun onMsgReceived(msg: UdpMsg)
    }
}

interface UdpSocketHandler(
    localIp: String,  
    localPort: Int, 
): IncomingMsgObserver {
    fun send(msg: UdpMsg)
    fun close()
    fun isClosed()
}

// Builder function
fun UdpSockethandler(localIp: String, localPort: Int): UdpSocketHandler 

```

